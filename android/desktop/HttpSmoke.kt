// The http example against a local HTTP server: success with progress, a
// 404, a dropped connection, a timeout and a cancel. HttpFeature's rewrite
// points the example's public URL at this server.

import com.sun.net.httpserver.HttpServer
import dev.roc.android.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object HttpSmoke {
    const val PUBLIC = "https://jsonplaceholder.typicode.com"
    @Volatile var mode = "404"
    private val received = Semaphore(0)
    private val release = CountDownLatch(1)
    private var server: HttpServer? = null
    @Volatile var base = ""

    val body = "{\n  \"userId\": 1,\n  \"id\": 1,\n  \"title\": \"delectus aut autem\",\n  \"completed\": false\n}"

    /** The feature, its URLs rewritten to the local server (started on first use). */
    fun feature() = HttpFeature { url -> if (url.startsWith(PUBLIC)) start() + url.removePrefix(PUBLIC) else url }

    @Synchronized
    fun start(): String {
        if (server == null) {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            s.createContext("/todos/1") { ex ->
                received.release()
                try {
                    when (mode) {
                        "404" -> {
                            val b = "{}".toByteArray()
                            ex.sendResponseHeaders(404, b.size.toLong()); ex.responseBody.use { it.write(b) }
                        }
                        "ok" -> {
                            val b = body.toByteArray()
                            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                            ex.sendResponseHeaders(200, b.size.toLong())
                            ex.responseBody.use { out ->
                                // In four chunks, slowly enough for a 100 ms progress sample.
                                for (part in b.toList().chunked((b.size + 3) / 4)) {
                                    out.write(part.toByteArray()); out.flush(); Thread.sleep(80)
                                }
                            }
                        }
                        "drop" -> {} // close without a response
                        "hang" -> {
                            release.await(20, TimeUnit.SECONDS)
                            val b = "late".toByteArray()
                            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
                        }
                    }
                } catch (_: Exception) {
                    // the client went away (timeout, cancel)
                } finally {
                    ex.close()
                }
            }
            s.start()
            server = s
            base = "http://127.0.0.1:${s.address.port}"
        }
        return base
    }

    fun run(host: Host, platform: ManualPlatform) {
        val text = { host.tree.root.textContent() }
        expect("mount issues the request: 404", platform.pump { text().contains("HTTP 404") })

        mode = "ok"
        host.dispatch(host.button("Retry"), "click")
        expect("retry shows loading", text().contains("Loading"))
        expect("200 with the JSON body", platform.pump { text().contains("HTTP 200 OK") })
        expect("body field and content type", text().contains("Todo: delectus aut autem") && text().contains("Type: application/json; charset=utf-8"))
        expect("progress reported with the known total", text().contains(Regex("Received \\d+ of ${body.length} bytes")))

        mode = "drop"
        host.dispatch(host.button("Retry"), "click")
        expect("a dropped connection is a network error", platform.pump { text().contains("Network error") })

        mode = "hang"
        received.drainPermits() // a dropped keep-alive GET may be retried once
        host.dispatch(host.button("Retry"), "click")
        expect("hanging request reached the server", received.tryAcquire(1, 5, TimeUnit.SECONDS))
        platform.advance(10_000)
        expect("timeout after 10 s", text().contains("Timed out after 10 s"))

        host.dispatch(host.button("Retry"), "click")
        expect("second hanging request reached the server", received.tryAcquire(1, 5, TimeUnit.SECONDS))
        host.dispatch(host.button("Cancel"), "click")
        expect("cancel shows cancelled", text().contains("Cancelled"))
        val before = platform.posted.get()
        release.countDown() // the server now answers both hung requests
        Thread.sleep(500)
        platform.pump(300) { false }
        expect("nothing answered after timeout or cancel", platform.posted.get() == before && text().contains("Cancelled"))
        platform.advance(20_000)
        expect("no timer left behind", text().contains("Cancelled"))
        server?.stop(0)
    }
}
