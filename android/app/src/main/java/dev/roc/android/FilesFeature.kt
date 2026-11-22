package dev.roc.android

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// Files (roc-signals design.md, "Files"): the files the user gives the app
// reach it as handles. Kotlin keeps each file (`HostFile`) under the token of
// the handle the host minted for it (`RocFiles.nativeStage`); Roc's
// reference count decides when the app is done with it, and after every
// batch whose effects were performed `RocFiles.nativeReleased` lists the
// tokens Roc let go, whose files are then let go here. Operations on a file
// (FX kinds 11–15, the SAVE command) act on it where it lives, through a
// `FileAccess`: content Uris on Android (`AndroidFiles`), plain files in the
// desktop smoke test. Nothing in this file depends on Android.

/**
 * A file the host holds for the app. `location` is what the `FileAccess`
 * reads it by (an Android content `Uri`, or a `java.io.File`); an `owned`
 * file is the host's own temporary copy, deleted when it is let go.
 */
class HostFile(val name: String, val size: Long, val type: String, val location: Any, val owned: Boolean = false) {
    /** Requests and commands in progress that hold this file. */
    internal var uses = 0
    /** Its handle has been released (or the engine stopped). */
    internal var released = false
}

/** Where files come from and go to: the platform half of `FilesFeature`. */
interface FileAccess {
    /**
     * Run `work` away from the engine's thread (or on it, on the desktop).
     * `FilesFeature` posts every answer back through `Host.schedule`.
     */
    fun background(work: () -> Unit)

    /**
     * Let the user choose files (`multiple` or one, offering `types` first,
     * media types, empty for any); `done` runs on the engine's thread with
     * them, empty when the user cancelled.
     */
    fun choose(multiple: Boolean, types: List<String>, done: (List<HostFile>) -> Unit)

    /** The file's bytes from its start, or null when it cannot be read. */
    fun open(location: Any): InputStream?

    /**
     * Ask where to save a file called `name` of media type `type`; `done`
     * runs on the engine's thread with a location to write, or null.
     */
    fun saveTarget(name: String, type: String, done: (Any?) -> Unit)

    /** A stream that writes the location `saveTarget` gave, or null. */
    fun create(location: Any): OutputStream?

    /** A new temporary file of the host's own (a download, a range shown by URL). */
    fun tempFile(): java.io.File

    /** A URL that shows the whole file at `location`. */
    fun url(location: Any): String

    /** A `java.io.File` location, as `url` gives it. */
    fun url(file: java.io.File): String = url(file as Any)

    fun log(text: String) {}
}

/** The JNI surface of the host's blob heap (`host/host.zig`, "File handles"). */
object RocFiles {
    /** Mint up to `n` handles for the next files-kind event; their tokens. */
    @JvmStatic external fun nativeStage(n: Int): LongArray
    /** A files-kind event with the staged handles; returns its batch. */
    @JvmStatic external fun nativeEvent(node: Int, handler: Int, record: ByteArray): ByteArray
    /** The tokens of the handles Roc released since the last call. */
    @JvmStatic external fun nativeReleased(): LongArray
    /** Live handles, retired ones included until drained. */
    @JvmStatic external fun nativeLive(): Int
}

class FilesFeature(
    private val access: FileAccess,
    /** The most bytes any operation reads (roc-signals' `maxFileBytes`). */
    private val maxFileBytes: Long = 256L * 1024 * 1024,
) : Feature {
    /** The file of each live handle, by token. */
    val files = HashMap<Long, HostFile>()
    /** Per token: the URL answered for each range, at most 16 ranges. */
    private val urls = HashMap<Long, LinkedHashMap<Pair<Long, Long>, String>>()
    /** Per token: the range copies made for those URLs, deleted on release. */
    private val copies = HashMap<Long, MutableList<java.io.File>>()

    class Range(val token: Long, val start: Long, val len: Long)
    class Read(val mode: Int, val range: Range, val maxBytes: Long)
    class Digest(val algorithm: Int, val range: Range)
    class Url(val range: Range)
    class Save(val range: Range, val name: String)
    class HttpProps(val method: String, val url: String, val headers: List<Pair<String, String>>, val timeoutMs: Int)
    class Upload(val http: HttpProps, val range: Range)
    class Fetch(val http: HttpProps)

    companion object {
        const val UNREADABLE = 1
        const val TOO_LARGE = 2
        const val UNAVAILABLE = 3
        const val MAX_URLS_PER_FILE = 16

        private fun range(r: Reader) = Range(r.u64(), r.u64(), r.u64())

        private fun http(r: Reader, upload: Boolean): Pair<HttpProps, Range?> {
            val method = r.str(); val url = r.str()
            val headers = List(r.u32()) { r.str() to r.str() }
            val body = if (upload) range(r) else null
            val timeout = r.u32()
            r.u8(); r.u8(); r.u32() // cache, credentials, progress_ms: not served here
            return HttpProps(method, url, headers, timeout) to body
        }

        fun failed(reason: Int) = byteArrayOf(13, reason.toByte())
    }

    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(
        11 to { r -> val mode = r.u8(); Read(mode, range(r), r.u64()) },
        12 to { r -> val (h, b) = http(r, true); Upload(h, b!!) },
        13 to { r -> Digest(r.u8(), range(r)) },
        14 to { r -> Url(range(r)) },
        15 to { r -> Fetch(http(r, false).first) },
    )

    override val opDecoders: Map<Int, (Reader) -> Any> = mapOf(
        20 to { r -> Save(range(r), r.str()) }, // SAVE
    )

    // ---- the files of live handles ---------------------------------------

    private fun resolve(token: Long): HostFile =
        files[token] ?: error("protocol: file token $token is not held")

    private fun hold(f: HostFile) { f.uses++ }

    private fun letGo(f: HostFile) {
        f.uses--
        if (f.uses <= 0 && f.released && f.owned) (f.location as? java.io.File)?.delete()
    }

    private fun release(token: Long) {
        val f = files.remove(token) ?: return
        urls.remove(token)
        copies.remove(token)?.forEach { it.delete() }
        f.released = true
        if (f.uses <= 0 && f.owned) (f.location as? java.io.File)?.delete()
    }

    override fun applied(host: Host) {
        for (token in RocFiles.nativeReleased()) release(token)
    }

    override fun stopped(host: Host) {
        for (token in files.keys.toList()) release(token)
    }

    /**
     * Mint handles for `chosen` and build the file record, `minted:u32
     * refused:u32 (size:u64 name:str type:str)*minted`; the files beyond the
     * free slots are refused. Call only inside a turn.
     */
    private fun stage(chosen: List<HostFile>, header: Writer.(minted: Int) -> Unit): ByteArray {
        val tokens = RocFiles.nativeStage(chosen.size)
        return bytesOf {
            header(tokens.size)
            for (i in tokens.indices) {
                val f = chosen[i]
                files[tokens[i]] = f
                u64(f.size); str(f.name); str(f.type)
            }
        }
    }

    /** Deliver `chosen` to the files-kind handler for `type` at or above `target`. */
    fun deliver(host: Host, target: Node, type: String, chosen: List<HostFile>): Boolean {
        if (chosen.isEmpty()) return false // no files means no dispatch
        val (node, handler) = host.tree.route(target, type) ?: return false
        if (handler.kind != 2) return false
        host.turn {
            val record = stage(chosen) { minted -> u32(minted); u32(chosen.size - minted) }
            RocFiles.nativeEvent(node, handler.number, record)
        }
        return true
    }

    /** The user activated a file picker (`Ui.file_picker`): choose, then deliver. */
    fun pick(host: Host, picker: Element) {
        val types = (picker.attr("accept") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        access.choose(picker.attrs.containsKey("multiple"), types) { chosen -> deliver(host, picker, "change", chosen) }
    }

    // ---- operations ------------------------------------------------------

    /** Answer `id` in a turn of its own, after the batch that asked. */
    private fun later(host: Host, id: Int, payload: ByteArray) {
        host.schedule(0) { host.answer(id, payload) }
    }

    /** Run `work` on `f` in the background, holding it until answered. */
    private fun withFile(host: Host, id: Int, f: HostFile, work: () -> ByteArray) {
        hold(f)
        access.background {
            val payload = try { work() } catch (e: Exception) { failed(UNREADABLE) }
            host.schedule(0) { letGo(f); host.answer(id, payload) }
        }
    }

    /** `len` bytes of `f` from `start`, or null when they cannot all be read. */
    private fun readRange(f: HostFile, start: Long, len: Long): ByteArray? {
        val input = access.open(f.location) ?: return null
        input.use {
            if (!skipFully(it, start)) return null
            val out = ByteArray(len.toInt())
            var got = 0
            while (got < out.size) {
                val n = it.read(out, got, out.size - got)
                if (n < 0) return null
                got += n
            }
            return out
        }
    }

    private fun skipFully(input: InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) return false else left-- } else left -= s
        }
        return true
    }

    /** Copy the range to `out` in chunks; false when the file runs short. */
    private fun copyRange(f: HostFile, start: Long, len: Long, out: OutputStream): Boolean {
        val input = access.open(f.location) ?: return false
        input.use {
            if (!skipFully(it, start)) return false
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return false
                out.write(buf, 0, n)
                left -= n
            }
            return true
        }
    }

    override fun perform(host: Host, fx: Fx): (() -> Unit)? {
        when (val req = fx.request) {
            is Read -> {
                val f = resolve(req.range.token)
                val r = req.range
                if (r.len > req.maxBytes || r.len > maxFileBytes || r.len > Int.MAX_VALUE - 8) {
                    later(host, fx.id, failed(TOO_LARGE))
                } else withFile(host, fx.id, f) {
                    val bytes = readRange(f, r.start, r.len) ?: return@withFile failed(UNREADABLE)
                    // 16 FileBytes, 17 FileText (the engine checks the UTF-8)
                    bytesOf { u8(if (req.mode == 1) 16 else 17); bytes(bytes) }
                }
            }
            is Digest -> {
                val f = resolve(req.range.token)
                val r = req.range
                if (req.algorithm != 1) error("protocol: digest algorithm ${req.algorithm}")
                if (r.len > maxFileBytes) later(host, fx.id, failed(TOO_LARGE))
                else withFile(host, fx.id, f) {
                    val md = MessageDigest.getInstance("SHA-256")
                    val sink = object : OutputStream() {
                        override fun write(b: Int) = md.update(b.toByte())
                        override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
                    }
                    if (!copyRange(f, r.start, r.len, sink)) failed(UNREADABLE)
                    else bytesOf { u8(18); bytes(md.digest()) }
                }
            }
            is Url -> {
                val token = req.range.token
                val f = resolve(token)
                val r = req.range
                val key = r.start to r.len
                val known = urls.getOrPut(token) { LinkedHashMap() }
                val url = known[key]
                when {
                    url != null -> later(host, fx.id, urlResult(url))
                    known.size >= MAX_URLS_PER_FILE -> later(host, fx.id, failed(TOO_LARGE))
                    r.start == 0L && r.len == f.size -> {
                        val whole = access.url(f.location)
                        known[key] = whole
                        later(host, fx.id, urlResult(whole))
                    }
                    else -> {
                        // A range has no URL of its own: show a copy of it,
                        // kept (and deleted) with the handle.
                        val copy = access.tempFile()
                        withFile(host, fx.id, f) {
                            val ok = copy.outputStream().use { copyRange(f, r.start, r.len, it) }
                            if (!ok) { copy.delete(); return@withFile failed(UNREADABLE) }
                            val u = access.url(copy)
                            host.schedule(0) {
                                if (files[token] === f) {
                                    known[key] = u
                                    copies.getOrPut(token) { mutableListOf() }.add(copy)
                                } else copy.delete()
                            }
                            urlResult(u)
                        }
                    }
                }
            }
            is Upload -> {
                val f = resolve(req.range.token)
                val r = req.range
                withFile(host, fx.id, f) {
                    Http.exchange(req.http, r.len, writeBody = { out ->
                        if (!copyRange(f, r.start, r.len, out)) throw java.io.IOException("the file could not be read")
                    }).textResult()
                }
            }
            is Fetch -> {
                access.background {
                    val temp = access.tempFile()
                    val got = try {
                        temp.outputStream().use { out -> Http.exchange(req.http, -1, null, out, maxFileBytes) }
                    } catch (e: Exception) { Http.Answer.Failed(byteArrayOf(2) + (e.message ?: e.javaClass.simpleName).toByteArray()) }
                    host.schedule(0) { fetched(host, fx.id, req.http.url, temp, got) }
                }
            }
            else -> error("unreachable")
        }
        return null
    }

    private fun urlResult(url: String) = bytesOf { u8(19); bytes(url.toByteArray(Charsets.UTF_8)) }

    /** A fetch's answer: tag 21 with its body as a new file, or its failure. */
    private fun fetched(host: Host, id: Int, url: String, temp: java.io.File, got: Http.Answer) {
        if (got !is Http.Answer.Response || !host.isLive(id)) {
            temp.delete()
            host.answer(id, (got as? Http.Answer.Failed)?.payload ?: failed(TOO_LARGE))
            return
        }
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { "download" }
        val type = got.headers.firstOrNull { it.first.equals("content-type", true) }?.second ?: ""
        val file = HostFile(name, temp.length(), type, temp, owned = true)
        host.answerTurn(id) {
            val record = stage(listOf(file)) { _ ->
                u8(21); u16(got.status); u32(got.headers.size)
                for ((k, v) in got.headers) { str(k); str(v) }
            }
            if (files.values.none { it === file }) {
                // No free slot: all or nothing, so the body is TooLarge.
                temp.delete()
                RocEngine.nativeEvent(Host.FX_NODE, id, failed(TOO_LARGE))
            } else RocFiles.nativeEvent(Host.FX_NODE, id, record)
        }
    }

    override fun command(host: Host, op: Int, value: Any) {
        val save = value as Save
        val f = resolve(save.range.token)
        val r = save.range
        hold(f)
        access.saveTarget(save.name, f.type) { target ->
            if (target == null) { letGo(f); return@saveTarget }
            access.background {
                val ok = try {
                    access.create(target)?.use { copyRange(f, r.start, r.len, it) } ?: false
                } catch (e: Exception) { false }
                host.schedule(0) {
                    letGo(f)
                    // A save has no result: the app learns nothing of it.
                    if (!ok) host.log("save of ${save.name} failed")
                }
            }
        }
    }
}

/**
 * A small HTTP client for the file transfers (FX 12 upload, FX 15 download
 * into a file). The HTTP feature owns FX kind 1; this one is separate so the
 * files feature stands alone.
 */
object Http {
    sealed class Answer {
        class Response(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) : Answer()
        /** A ready result payload: tag 2 `Network`, 7 `Timeout`, 13 `FileFailed`. */
        class Failed(val payload: ByteArray) : Answer()

        /** As a kind-1 result: tag 1 `Http` with the body as text, or the failure. */
        fun textResult(): ByteArray = when (this) {
            is Response -> bytesOf {
                u8(1); u16(status); u32(headers.size)
                for ((k, v) in headers) { str(k); str(v) }
                bytes(body)
            }
            is Failed -> payload
        }
    }

    /**
     * One request: `writeBody` (when given) writes `bodyLen` bytes; the
     * response body goes to `sink` (bounded by `maxBytes`, else `TooLarge`)
     * or is kept in the answer.
     */
    fun exchange(
        props: FilesFeature.HttpProps,
        bodyLen: Long,
        writeBody: ((OutputStream) -> Unit)? = null,
        sink: OutputStream? = null,
        maxBytes: Long = Long.MAX_VALUE,
    ): Answer {
        val conn = try {
            java.net.URI(props.url).toURL().openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            return Answer.Failed(byteArrayOf(2) + (e.message ?: "bad URL").toByteArray())
        }
        try {
            conn.requestMethod = props.method
            if (props.timeoutMs > 0) { conn.connectTimeout = props.timeoutMs; conn.readTimeout = props.timeoutMs }
            for ((k, v) in props.headers) conn.addRequestProperty(k, v)
            if (writeBody != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bodyLen)
                conn.outputStream.use(writeBody)
            }
            val status = conn.responseCode
            val headers = conn.headerFields.entries.filter { it.key != null }
                .flatMap { (k, vs) -> vs.map { k.lowercase() to it } }
            val input = (if (status >= 400) conn.errorStream else conn.inputStream)
            val body = java.io.ByteArrayOutputStream()
            val out = sink ?: body
            var total = 0L
            input?.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return Answer.Failed(FilesFeature.failed(FilesFeature.TOO_LARGE))
                    out.write(buf, 0, n)
                }
            }
            return Answer.Response(status, headers, body.toByteArray())
        } catch (e: java.net.SocketTimeoutException) {
            return Answer.Failed(byteArrayOf(7))
        } catch (e: Exception) {
            return Answer.Failed(byteArrayOf(2) + (e.message ?: e.javaClass.simpleName).toByteArray())
        } finally {
            conn.disconnect()
        }
    }
}
