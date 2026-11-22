package dev.roc.android

/**
 * Ports (roc-signals design.md, "Ports"; Appendix B: op 19 `PORT`, op 27
 * `UNSENT`, FX 9 call, FX 10 subscription, result tags 14 and 15,
 * declaration section 5), served by Kotlin `PortService`s as the browser
 * runtime (`runtime.mjs`) serves them with the page's JavaScript:
 *
 * - At boot the catalogue is checked like the runtime's: at most 64 ports,
 *   names of 1 to 128 bytes, each once, a valid shape and delivery; then
 *   every declared port must have a service of its shape and every service
 *   must name a declared port, or the host refuses to mount (it throws).
 * - A command (op 19) is performed after its batch; a throw is a port fault,
 *   reported to the log and `onFault`, never to the app. `UNSENT` (op 27),
 *   a command the engine did not send because its message nests past 512
 *   containers, is reported the same way and calls no service.
 * - A call (FX 9) is answered once: tag 14 with the answer, tag 15 `Threw`
 *   (the service threw, failed or answered something that is not JSON data)
 *   or `TooLarge` (over `maxPortBytes`), or tag 7 `Timeout` at its
 *   `timeout_ms` (capped at 600 s), which also aborts the service.
 * - A subscription (FX 10) delivers each message as its own turn, one at a
 *   time. `Latest` keeps one waiting message, replaced by a newer one;
 *   `Every(n)` keeps up to n in order and `emit` refuses more. `close` ends
 *   it with `Closed` after the waiting messages, `fail` or a throw with
 *   `Threw`; a message over the bound is `TooLarge` and it goes on.
 *
 * Files are not supported: an answer or message never carries one (nfiles
 * is 0), and a request whose message carries files is refused (a call or a
 * subscription answers `Threw`, a command is a fault). No Android
 * dependency: the desktop smoke test runs it with fake services.
 *
 * `services` is read once, at boot.
 */
class PortsFeature(
    private val maxPortBytes: Int = DEFAULT_PORT_BYTES,
    /** A failure the app cannot be told of: `(port, kind, message)`. */
    private val onFault: (String, String, String) -> Unit = { _, _, _ -> },
    private val services: () -> Map<String, PortService>,
) : Feature {
    companion object {
        const val DEFAULT_PORT_BYTES = 4 * 1024 * 1024
        const val MAX_PORT_BYTES = 64 * 1024 * 1024
        const val MAX_TIMEOUT_MS = 600_000L
        const val RES_TIMEOUT = 7
        const val RES_PORT_ANSWER = 14
        const val RES_PORT_FAILED = 15
        const val THREW = 1
        const val TOO_LARGE = 2
        const val CLOSED = 3
        private val SHAPES = listOf("", "command", "call", "subscription")
    }

    init {
        require(maxPortBytes in 1..MAX_PORT_BYTES) { "maxPortBytes: $maxPortBytes is not from 1 to $MAX_PORT_BYTES" }
    }

    /** A message as the engine sent it: JSON text, byte blocks, file count. */
    class Message(val json: String, val blobs: List<ByteArray>, val files: Int)

    class PortCommand(val port: String, val message: Message)
    class Unsent(val port: String)
    class PortCall(val port: String, val message: Message, val timeoutMs: Long)
    class PortSubscribe(val port: String, val message: Message)

    private fun message(r: Reader): Message {
        val json = r.str()
        val blobs = List(r.u32()) { r.bytes(r.u32()) }
        val files = r.u32()
        repeat(files) { r.u64(); r.u64(); r.u64() } // token start len
        return Message(json, blobs, files)
    }

    override val opDecoders: Map<Int, (Reader) -> Any> = mapOf(
        19 to { r -> PortCommand(r.str(), message(r)) },
        27 to { r ->
            val port = r.str()
            val reason = r.u8()
            check(reason == 1) { "protocol: UNSENT reason $reason is not 1" }
            Unsent(port)
        },
    )

    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(
        9 to { r -> PortCall(r.str(), message(r), r.u32().toLong() and 0xffffffffL) },
        10 to { r -> PortSubscribe(r.str(), message(r)) },
    )

    private val declared = HashMap<String, Declarations.Port>()
    private var handlers: Map<String, PortService> = emptyMap()
    private val subs = HashSet<Sub>()

    // ---- boot -----------------------------------------------------------

    override fun boot(facts: BootFacts, decl: Declarations) {
        declared.clear()
        check(decl.ports.size <= 64) { "more than 64 ports" }
        for (p in decl.ports) {
            val n = p.name.toByteArray(Charsets.UTF_8).size
            check(n in 1..128 && !declared.containsKey(p.name)) { "invalid or duplicate port name \"${p.name}\"" }
            val ok = when (p.kind) {
                1, 2 -> p.delivery == 0 && p.bound == 0
                3 -> if (p.delivery == 1) p.bound in 1..1024 else p.delivery == 2 && p.bound == 1
                else -> false
            }
            check(ok) { "port \"${p.name}\": invalid port shape or delivery" }
            declared[p.name] = p
        }
        val given = services()
        for ((name, s) in given) {
            val d = declared[name] ?: error("a service is given for port \"$name\", which the app does not declare in with_ports")
            val has = when (s) { is PortService.Command -> 1; is PortService.Call -> 2; is PortService.Subscription -> 3 }
            check(has == d.kind) { "port \"$name\" is declared by the app as a ${SHAPES[d.kind]}, but its service is a ${SHAPES[has]}" }
        }
        for (d in decl.ports) check(given.containsKey(d.name)) { "port \"${d.name}\" is declared by the app as a ${SHAPES[d.kind]}, but no service is given for it" }
        handlers = given
    }

    /** The declared port `name` of shape `kind`; anything else is the engine's contract broken. */
    private fun service(name: String, kind: Int): PortService {
        val d = declared[name]
        check(d != null && d.kind == kind) { "protocol: a ${SHAPES[kind]} request for port \"$name\", which is not declared as one" }
        return handlers.getValue(name)
    }

    private fun fault(host: Host, port: String, kind: String, message: String) {
        host.log("roc port fault [$kind] \"$port\": $message")
        try { onFault(port, kind, message) } catch (_: Throwable) {} // an observer that throws changes nothing
    }

    private fun text(e: Throwable) = e.message ?: e.toString()

    // ---- payloads -------------------------------------------------------

    private fun failed(klass: Int, text: String = ""): ByteArray = bytesOf {
        u8(RES_PORT_FAILED); u8(klass)
        if (klass == THREW) bytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun answered(e: PortJson.Encoded): ByteArray = bytesOf {
        u8(RES_PORT_ANSWER); str(e.json); u32(e.blobs.size)
        for (b in e.blobs) { u32(b.size); bytes(b) }
        u32(0) // nfiles: files do not cross this host's ports
    }

    /** A service's answer as its result payload, or the failure it is. */
    private fun answerPayload(value: Any?): ByteArray {
        val e = try {
            PortJson.write(value)
        } catch (x: PortJson.PortDataError) {
            return failed(THREW, "the answer is not JSON data: ${x.message}")
        } catch (x: Throwable) {
            return failed(THREW, "reading the answer threw: ${text(x)}")
        }
        return if (e.size > maxPortBytes) failed(TOO_LARGE) else answered(e)
    }

    private fun value(m: Message): Any? {
        check(m.files == 0) { "the message carries ${m.files} file(s); files do not cross this host's ports" }
        return PortJson.read(m.json, m.blobs)
    }

    // ---- commands -------------------------------------------------------

    override fun command(host: Host, op: Int, value: Any) {
        when (value) {
            is Unsent -> {
                service(value.port, 1)
                fault(host, value.port, "command", "the message nests containers more than 512 deep, so it was not sent")
            }
            is PortCommand -> {
                val s = service(value.port, 1) as PortService.Command
                try {
                    s.command(value(value.message))
                } catch (e: Throwable) {
                    fault(host, value.port, "command", text(e))
                }
            }
        }
    }

    // ---- calls and subscriptions ----------------------------------------

    override fun perform(host: Host, fx: Fx): (() -> Unit)? = when (val r = fx.request) {
        is PortCall -> call(host, fx.id, r)
        is PortSubscribe -> subscribe(host, fx.id, r)
        else -> null
    }

    private fun call(host: Host, id: Int, req: PortCall): () -> Unit {
        val s = service(req.port, 2) as PortService.Call
        val lock = Any()
        var settled = false
        var abort: (() -> Unit)? = null
        var timer: (() -> Unit)? = null
        // Claim the call once: an answer, a failure, the timeout or a cancel.
        fun claim(): Boolean = synchronized(lock) { if (settled) false else { settled = true; true } }
        fun settle(payload: () -> ByteArray) {
            if (!claim()) return
            // Delivered as its own turn on the engine's thread, never inside the caller.
            host.schedule(0) { timer?.invoke(); host.answer(id, payload()) }
        }
        val reply = object : PortService.Reply {
            override fun answer(value: Any?) {
                // Encoded now: the value is a snapshot at answer.
                val payload = answerPayload(value)
                settle { payload }
            }
            override fun fail(message: String) = settle { failed(THREW, message) }
        }
        if (req.timeoutMs > 0) {
            timer = host.schedule(minOf(req.timeoutMs, MAX_TIMEOUT_MS)) {
                if (claim()) {
                    abortQuietly(host, req.port, abort)
                    host.answer(id, byteArrayOf(RES_TIMEOUT.toByte()))
                }
            }
        }
        try {
            abort = s.call(value(req.message), reply)
        } catch (e: Throwable) {
            reply.fail(text(e))
        }
        return {
            if (claim()) {
                timer?.invoke()
                abortQuietly(host, req.port, abort)
            }
        }
    }

    private fun abortQuietly(host: Host, port: String, abort: (() -> Unit)?) {
        try { abort?.invoke() } catch (e: Throwable) { fault(host, port, "abort", text(e)) }
    }

    /** One live subscription: its waiting messages and how they are kept. */
    private inner class Sub(val host: Host, val id: Int, val port: String, val latest: Boolean, val bound: Int) : PortService.Sink {
        /** Waiting payloads; a terminal one is last. Guarded by `this`. */
        val queue = ArrayDeque<Pair<ByteArray, Boolean>>()
        var ended = false
        var closing = false
        var pumping = false
        var unsubscribe: (() -> Unit)? = null

        override fun emit(value: Any?): Boolean {
            val e = PortJson.write(value) // not JSON data: the emitter's own error, thrown back
            val payload = if (e.size > maxPortBytes) failed(TOO_LARGE) else answered(e)
            synchronized(this) {
                if (ended || closing) return false
                if (latest) {
                    if (queue.isNotEmpty()) queue.removeLast()
                } else if (queue.size >= bound) return false
                queue.addLast(payload to false)
            }
            pump()
            return true
        }

        override fun close() = end(failed(CLOSED))
        override fun fail(message: String) = end(failed(THREW, message))

        fun end(payload: ByteArray) {
            synchronized(this) {
                if (ended || closing) return
                closing = true
                queue.addLast(payload to true)
            }
            pump()
        }

        /** Deliver the next waiting message as its own turn; the next waits for it. */
        fun pump() {
            synchronized(this) {
                if (pumping || ended || queue.isEmpty()) return
                pumping = true
            }
            host.schedule(0) {
                val next = synchronized(this) {
                    pumping = false
                    if (ended) null else queue.removeFirstOrNull()?.also { if (it.second) ended = true }
                }
                if (next != null) {
                    if (next.second) subs.remove(this)
                    host.answer(id, next.first, done = next.second)
                    pump()
                }
            }
        }

        /** The engine cancelled it, or the host stopped. */
        fun cancel() {
            val u = synchronized(this) {
                if (ended) return
                ended = true
                queue.clear()
                unsubscribe
            }
            subs.remove(this)
            try { u?.invoke() } catch (e: Throwable) { fault(host, port, "unsubscribe", text(e)) }
        }
    }

    private fun subscribe(host: Host, id: Int, req: PortSubscribe): () -> Unit {
        val s = service(req.port, 3) as PortService.Subscription
        val d = declared.getValue(req.port)
        val sub = Sub(host, id, req.port, latest = d.delivery == 2, bound = d.bound)
        subs += sub
        try {
            val u = s.subscribe(value(req.message), sub)
            val late = synchronized(sub) { if (sub.ended) true else { sub.unsubscribe = u; false } }
            if (late) u?.invoke() // ended (cancelled) while subscribing
        } catch (e: Throwable) {
            sub.fail(text(e))
        }
        return { sub.cancel() }
    }

    override fun stopped(host: Host) {
        // The host has cancelled every live request; anything left is ended here.
        for (s in subs.toList()) s.cancel()
        subs.clear()
    }
}
