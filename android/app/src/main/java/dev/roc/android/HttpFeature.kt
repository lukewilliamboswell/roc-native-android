package dev.roc.android

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP (FX kind 1, design.md Appendix B), performed with `HttpURLConnection`
 * on a background executor, mirroring the browser runtime's `startTransfer`:
 *
 * - Answers (tag 1 `Http`, tag 2 `Network`, tag 7 `Timeout`) and progress
 *   reports (tag 26) are delivered on the engine thread through
 *   `host.schedule(0)`, so they keep the order they were produced in: every
 *   progress report reaches the engine before the final answer.
 * - The timeout is one engine-thread timer (`host.schedule`), cleared on
 *   completion, failure or cancel; at expiry it answers `Timeout` and
 *   disconnects. Whichever of the timer and the transfer finishes first
 *   wins (`over`, read and written only on the engine thread).
 * - Cancel (the engine's CANCEL, through the function `perform` returns)
 *   disconnects and never answers afterwards.
 * - Progress (`progress_ms > 0`): one latest sample and at most one armed
 *   timer per request, so reports come no more often than the interval.
 *   Only receiving is reported (a request body is a string, sent at once).
 *   The total is known only when the response has a `Content-Length` and
 *   no `Content-Encoding`, as in the runtime.
 * - Response headers are given as `fetch` iterates them: lower-case names,
 *   sorted, repeated names joined with ", ". The body is decoded as UTF-8
 *   (malformed bytes replaced), a leading BOM dropped, as `Response.text()`.
 * - A non-empty body is sent for methods other than GET and HEAD, with
 *   `Content-Type: text/plain;charset=UTF-8` unless the app gives one.
 *
 * Cache and credentials are best effort; see `applyCache`. `rewrite` maps a
 * request URL before it is opened (the desktop smoke test points the
 * example's public URL at a local server); it is the identity otherwise.
 */
class HttpFeature(private val rewrite: (String) -> String = { it }) : Feature {
    class Request(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String,
        val timeoutMs: Long,
        val cache: Int,
        val credentials: Int,
        val progressMs: Long,
    )

    companion object {
        const val KIND = 1
        const val RES_HTTP = 1
        const val RES_NETWORK = 2
        const val RES_TIMEOUT = 7
        const val RES_PROGRESS = 26
        /** design.md "Bounds": an HTTP timeout is capped at 600 s. */
        const val MAX_TIMEOUT_MS = 600_000L
        private const val CHUNK = 16 * 1024

        fun progress(done: Long, total: Long?): ByteArray = bytesOf {
            u8(RES_PROGRESS); u8(2) // Receiving
            u64(done); flag(total != null); u64(total ?: 0)
        }

        fun network(message: String): ByteArray = bytesOf { u8(RES_NETWORK); bytes(message.toByteArray(Charsets.UTF_8)) }

        fun http(status: Int, headers: List<Pair<String, String>>, body: ByteArray): ByteArray = bytesOf {
            u8(RES_HTTP); u16(status); u32(headers.size)
            for ((k, v) in headers) { str(k); str(v) }
            bytes(body)
        }
    }

    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(KIND to { r ->
        val method = r.str()
        val url = r.str()
        val headers = List(r.u32()) { r.str() to r.str() }
        val body = r.str()
        val timeout = r.u32().toLong() and 0xffffffffL
        val cache = r.u8()
        val credentials = r.u8()
        val progress = r.u32().toLong() and 0xffffffffL
        // Contract faults, as the runtime's decoder raises them.
        require(cache in 1..5 && credentials in 1..2) { "contract: invalid HTTP cache or credentials" }
        require(progress == 0L || progress >= 16) { "contract: progress interval must be zero or at least 16 ms" }
        Request(method, url, headers, body, minOf(timeout, MAX_TIMEOUT_MS), cache, credentials, progress)
    })

    private var executor: ExecutorService? = null

    private fun pool(): ExecutorService = executor ?: Executors.newCachedThreadPool { r ->
        Thread(r, "roc-http").apply { isDaemon = true }
    }.also { executor = it }

    override fun stopped(host: Host) {
        // Live requests were cancelled by the host already.
        executor?.shutdownNow()
        executor = null
    }

    override fun perform(host: Host, fx: Fx): (() -> Unit)? {
        val req = fx.request as Request
        val transfer = Transfer(host, fx.id, req)
        val pool = pool()
        transfer.disconnect = { pool.execute { transfer.closeConnection() } }
        if (req.timeoutMs > 0) {
            transfer.timer = host.schedule(req.timeoutMs) {
                transfer.timer = null
                if (transfer.finish()) {
                    transfer.disconnect()
                    host.answer(fx.id, byteArrayOf(RES_TIMEOUT.toByte()))
                }
            }
        }
        pool.execute { transfer.run() }
        return {
            if (transfer.finish()) transfer.disconnect()
        }
    }

    private inner class Transfer(val host: Host, val id: Int, val req: Request) {
        /** The request is over (answered, timed out or cancelled). Engine thread only. */
        private var over = false
        /** Set once `over` is: the background thread stops reading and posts nothing more. */
        val stopped = AtomicBoolean(false)
        var timer: (() -> Unit)? = null
        var progressTimer: (() -> Unit)? = null
        lateinit var disconnect: () -> Unit

        private val lock = Any()
        private var conn: HttpURLConnection? = null
        private val latest = AtomicReference<ByteArray?>(null)
        private val armed = AtomicBoolean(false)

        /** Mark the request over (engine thread); false when it already was. */
        fun finish(): Boolean {
            if (over) return false
            over = true
            stopped.set(true)
            timer?.invoke(); timer = null
            progressTimer?.invoke(); progressTimer = null
            latest.set(null)
            return true
        }

        fun closeConnection() {
            val c = synchronized(lock) { conn.also { conn = null } } ?: return
            try { c.disconnect() } catch (_: Exception) {}
        }

        /** Post a final answer from the background thread. */
        private fun answer(payload: ByteArray) {
            if (stopped.get()) return
            host.schedule(0) { if (finish()) host.answer(id, payload) }
        }

        /** Record a sample; arm the one sampling timer if it is not armed. */
        private fun sample(done: Long, total: Long?) {
            if (req.progressMs == 0L || stopped.get()) return
            latest.set(progress(done, total))
            if (!armed.compareAndSet(false, true)) return
            host.schedule(0) {
                if (over) return@schedule
                progressTimer = host.schedule(req.progressMs) {
                    progressTimer = null
                    armed.set(false)
                    val payload = latest.getAndSet(null)
                    if (!over && payload != null) host.answer(id, payload, done = false)
                }
            }
        }

        fun run() {
            if (stopped.get()) return
            try {
                val c = URI(rewrite(req.url)).toURL().openConnection() as HttpURLConnection
                synchronized(lock) {
                    if (stopped.get()) return
                    conn = c
                }
                try {
                    c.requestMethod = req.method
                } catch (e: java.net.ProtocolException) {
                    // The desktop JVM refuses PATCH; Android's implementation accepts it.
                    answer(network("this host cannot send method ${req.method}"))
                    closeConnection()
                    return
                }
                c.instanceFollowRedirects = true
                applyCache(c, req.cache)
                // Credentials: no cookie store is installed (CookieHandler is
                // null) and there is no page origin, so SameOrigin and Include
                // behave alike: no ambient cookies; app headers always go.
                for ((k, v) in req.headers) c.addRequestProperty(k, v)
                val bytes = req.body.toByteArray(Charsets.UTF_8)
                val sendBody = req.method != "GET" && req.method != "HEAD" && req.body.isNotEmpty()
                if (sendBody) {
                    if (req.headers.none { it.first.equals("content-type", ignoreCase = true) }) {
                        c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                    }
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val status = c.responseCode
                if (status < 0) throw IOException("invalid HTTP response")
                val headers = responseHeaders(c)
                val stream: InputStream? = if (status >= 400) c.errorStream else try { c.inputStream } catch (e: IOException) { c.errorStream }
                val length = c.getHeaderField("Content-Length")
                val total = if (c.getHeaderField("Content-Encoding") == null && length != null && length.matches(Regex("0|[1-9][0-9]*"))) length.toLongOrNull() else null
                val body = java.io.ByteArrayOutputStream()
                if (stream != null && req.method != "HEAD") {
                    stream.use { s ->
                        val buf = ByteArray(CHUNK)
                        var done = 0L
                        while (true) {
                            if (stopped.get()) return
                            val n = s.read(buf)
                            if (n < 0) break
                            body.write(buf, 0, n)
                            done += n
                            sample(done, total)
                        }
                    }
                }
                answer(http(status, headers, text(body.toByteArray())))
            } catch (e: Exception) {
                if (!stopped.get()) answer(network(e.toString()))
            } finally {
                closeConnection()
            }
        }
    }

    /**
     * The fetch cache modes, best effort. No `HttpResponseCache` is installed,
     * so nothing is cached locally and Default and ForceCache only allow a
     * cache if an embedder installs one. NoStore and Reload send
     * `Cache-Control: no-cache` and `Pragma: no-cache`, NoCache sends
     * `Cache-Control: max-age=0`, as fetch does, unless the app set them.
     */
    private fun applyCache(c: HttpURLConnection, cache: Int) {
        fun put(name: String, value: String) { if (c.getRequestProperty(name) == null) c.setRequestProperty(name, value) }
        when (cache) {
            2, 3 -> { c.useCaches = false; put("Cache-Control", "no-cache"); put("Pragma", "no-cache") }
            4 -> { c.useCaches = false; put("Cache-Control", "max-age=0") }
            else -> c.useCaches = true
        }
    }

    /** Headers as fetch iterates them: lower-case names, sorted, values joined. */
    private fun responseHeaders(c: HttpURLConnection): List<Pair<String, String>> {
        val merged = sortedMapOf<String, MutableList<String>>()
        for ((name, values) in c.headerFields) {
            if (name == null) continue // the status line
            // Android's implementation adds its own bookkeeping headers.
            if (name.startsWith("X-Android-", ignoreCase = true)) continue
            merged.getOrPut(name.lowercase()) { mutableListOf() }.addAll(values)
        }
        return merged.map { (k, v) -> k to v.joinToString(", ") }
    }

    /** UTF-8 with replacement and no leading BOM, as `Response.text()`. */
    private fun text(bytes: ByteArray): ByteArray {
        val s = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        return s.toByteArray(Charsets.UTF_8)
    }
}
