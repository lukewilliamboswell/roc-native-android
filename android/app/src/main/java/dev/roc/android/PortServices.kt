package dev.roc.android

/**
 * A service behind one declared port (roc-signals design.md, "Ports"): what
 * the page's JavaScript supplies in the browser, supplied here by Kotlin. The
 * app declares each port with `Port.command`, `Port.call` or
 * `Port.subscription` and `with_ports`; the host is given one service per
 * declared port, of the same shape, by name (see `PortsFeature`).
 *
 * Messages are plain values (`PortJson`): a `Map` for a record, a `List`,
 * `String`, `Long`/`Double`, `Boolean`, `null`, and a `ByteArray` for a
 * `Wire.Bytes`. `U64`/`I64` fields cross as decimal strings. Files do not
 * cross this host's ports yet.
 *
 * `Reply` and `Sink` may be used from any thread: what they deliver reaches
 * the engine as its own turn on the engine's thread.
 */
sealed interface PortService {
    /** A command: a message out, nothing back. A throw is a port fault. */
    fun interface Command : PortService {
        fun command(value: Any?)
    }

    /**
     * A call: answer `value` once through `reply`, now or later. Returns how
     * to abort it (the engine cancelled it, or its `timeout` passed), or null.
     * A throw answers `Threw`.
     */
    fun interface Call : PortService {
        fun call(value: Any?, reply: Reply): (() -> Unit)?
    }

    /**
     * A subscription: emit any number of messages through `sink` until it is
     * closed or the returned unsubscribe function is called (the app's site
     * was disposed). A throw ends it with `Threw`.
     */
    fun interface Subscription : PortService {
        fun subscribe(arg: Any?, sink: Sink): (() -> Unit)?
    }

    interface Reply {
        /** Answer with a value; one that is not JSON data answers `Threw`. */
        fun answer(value: Any?)
        /** Answer `Threw(message)`. */
        fun fail(message: String)
    }

    interface Sink {
        /**
         * Send one message. False when it was not taken: the subscription
         * has ended, or it is declared `Every(n)` and n messages already
         * wait. A value that is not JSON data throws `PortDataError` back
         * to the emitter.
         */
        fun emit(value: Any?): Boolean
        /** End the subscription with `Closed`, after the waiting messages. */
        fun close()
        /** End the subscription with `Threw(message)`. */
        fun fail(message: String)
    }
}
