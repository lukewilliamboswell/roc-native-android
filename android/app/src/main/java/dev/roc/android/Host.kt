package dev.roc.android

/** What the host needs from its environment: time, deferred work, output. */
interface Platform {
    fun nowMs(): Long
    /** Run `task` after `ms` on the engine's thread; the returned function cancels it. */
    fun schedule(ms: Long, task: () -> Unit): () -> Unit
    fun log(text: String)
    fun title(text: String) {}
    fun focus(node: Node) {}
}

/** Applies a tree's changes to some view system. */
interface Renderer {
    fun render(tree: Tree)
}

/**
 * The thin driver around the engine: every input (a view event, an effect
 * result, the startup acknowledgement) is one turn, queued so a turn never
 * starts while another batch is being applied. Effects and commands go to
 * the feature that owns their kind or opcode (see `Feature`).
 *
 * The host outlives any one view system: `renderer` can be replaced (an
 * activity recreated on rotation attaches a new one) without remounting.
 */
class Host(private val platform: Platform, private val features: List<Feature> = emptyList()) {
    private val fxOwners = HashMap<Int, Feature>()
    private val opOwners = HashMap<Int, Feature>()

    init {
        for (f in features) {
            for (k in f.fxDecoders.keys) check(fxOwners.put(k, f) == null) { "FX kind $k claimed twice" }
            for (o in f.opDecoders.keys) check(opOwners.put(o, f) == null) { "opcode $o claimed twice" }
        }
    }

    val tree = Tree(
        features.flatMap { it.fxDecoders.entries }.associate { it.key to it.value },
        features.flatMap { it.opDecoders.entries }.associate { it.key to it.value },
    )

    /** Where changes are drawn; null while no view system is attached. */
    var renderer: Renderer? = null
        set(value) { field = value; value?.render(tree) }

    lateinit var declarations: Declarations
        private set
    var started = false
        private set

    private val queue = ArrayDeque<Triple<Int, Int, ByteArray>>()
    private var busy = false
    private val live = HashMap<Int, () -> Unit>()
    private var opportunity: (() -> Unit)? = null

    companion object {
        const val FX_NODE = -1 // 0xffffffff
        const val PROTOCOL = 23
        val CLICK = byteArrayOf(0, 0) // no modifier keys, not a link
    }

    val now: Long get() = platform.nowMs()
    fun schedule(ms: Long, task: () -> Unit) = platform.schedule(ms, task)
    fun log(text: String) = platform.log(text)

    fun start() {
        check(RocEngine.nativeVersion() == PROTOCOL) { "engine speaks protocol ${RocEngine.nativeVersion()}, host $PROTOCOL" }
        declarations = Declarations(RocEngine.nativeKeys())
        // A deterministic startup program mounts on neutral facts (as the
        // browser does) and samples the live ones with FX 22; only the
        // location stays, since the engine selects the entry from it.
        val live = environment()
        val facts = if (declarations.startupProfile == 1) BootFacts(declarations).also { it.url = live.url } else live
        applyBatch(RocEngine.nativeInit(encodeBoot(declarations, facts)))
        started = true
        for (f in features) f.started(this)
        drain()
    }

    /**
     * A fresh sample of the environment, every feature filling its facts:
     * the boot facts at mount, and an environment snapshot (FX 22) later.
     */
    fun environment(): BootFacts {
        val facts = BootFacts(declarations)
        for (f in features) f.boot(facts, declarations)
        return facts
    }

    fun stop() {
        live.values.forEach { it() }
        live.clear()
        opportunity?.invoke()
        for (f in features) f.stopped(this)
        RocEngine.nativeDrop()
        started = false
    }

    /**
     * A view event on `target`, routed like a bubbling DOM event. `value` is
     * the control's value as the DOM gives it (an input's text, a checkbox's
     * "true"/"false"); the payload is encoded by the handler's kind.
     */
    fun dispatch(target: Node, type: String, value: String = ""): Boolean {
        val (node, handler) = tree.route(target, type) ?: return false
        val bytes = when (handler.kind) {
            10 -> clickPayload(target, tree.root) // CLICK: modifier flags and the nearest link (NavigationFeature.kt)
            5 -> byteArrayOf(if (value == "true") 1 else 0) // OPEN_STATE: checked
            1 -> value.toByteArray(Charsets.UTF_8) // TEXT
            else -> return false // pointer, key, scroll, files: not in this host yet
        }
        send(node, handler.number, bytes)
        return true
    }

    /** A view event whose payload the caller has already encoded. */
    fun dispatchRaw(target: Node, type: String, payload: (Handler) -> ByteArray?): Boolean {
        val (node, handler) = tree.route(target, type) ?: return false
        send(node, handler.number, payload(handler) ?: return false)
        return true
    }

    fun send(node: Int, handler: Int, payload: ByteArray) {
        queue.add(Triple(node, handler, payload))
        drain()
    }

    /** Whether request `id` is still live (not answered for good, not cancelled). */
    fun isLive(id: Int) = live.containsKey(id)

    /**
     * Answer request `id` with a result payload (Appendix B, "Effect
     * results"). `done` ends the request; a subscription or progress report
     * passes false. An answer to a request no longer live is dropped.
     */
    fun answer(id: Int, payload: ByteArray, done: Boolean = true) {
        if (!live.containsKey(id)) return
        if (done) live.remove(id)
        send(FX_NODE, id, payload)
    }

    private fun drain() {
        if (busy) return
        busy = true
        try {
            while (queue.isNotEmpty()) {
                val (node, handler, payload) = queue.removeFirst()
                applyBatch(RocEngine.nativeEvent(node, handler, payload))
            }
        } finally {
            busy = false
        }
    }

    private fun applyBatch(batch: ByteArray) {
        val dbg = RocEngine.nativeDbg()
        if (dbg.isNotEmpty()) platform.log(String(dbg, Charsets.UTF_8).trimEnd())
        tree.clearMarks()
        tree.apply(batch)
        renderer?.render(tree)
        val effects = tree.effects.toList()
        val ack = tree.startupAck
        for (e in effects) perform(e)
        // The cold batch is applied and its effects started: acknowledge it.
        if (ack) queue.add(Triple(FX_NODE, 0, byteArrayOf(254.toByte())))
    }

    private fun fired(): ByteArray = bytesOf { u8(5); u64(platform.nowMs()) }

    private fun perform(e: Effect) {
        when (e) {
            is Effect.Timer -> {
                live[e.id] = {}
                val cancel = platform.schedule(e.ms) { answer(e.id, fired()) }
                live[e.id] = cancel
            }
            is Effect.Interval -> {
                fun tick() {
                    live[e.id] = platform.schedule(e.ms) { tick(); answer(e.id, fired(), done = false) }
                }
                tick()
            }
            is Effect.Request -> {
                // Live before perform, so a synchronous answer is delivered.
                live[e.id] = {}
                val cancel = fxOwners.getValue(e.fx.kind).perform(this, e.fx)
                if (cancel != null && live.containsKey(e.id)) live[e.id] = cancel
            }
            is Effect.Command -> opOwners.getValue(e.op).command(this, e.op, e.value)
            is Effect.Cancel -> live.remove(e.id)?.invoke()
            is Effect.Opportunity -> {
                opportunity?.invoke(); opportunity = null
                if (e.request) {
                    val payload = bytesOf { u8(255); u64(e.generation) }
                    opportunity = platform.schedule(0) { opportunity = null; send(FX_NODE, 0, payload) }
                }
            }
            is Effect.Title -> platform.title(e.text)
            is Effect.Focus -> platform.focus(e.node)
        }
    }
}
