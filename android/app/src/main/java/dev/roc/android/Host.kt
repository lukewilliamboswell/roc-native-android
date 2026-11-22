package dev.roc.android

/** What the host needs from its environment: time, deferred work, output. */
interface Platform {
    fun nowMs(): Long
    /** Run `task` after `ms`; the returned function cancels it. */
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
 * starts while another batch is being applied.
 */
class Host(private val platform: Platform, private val renderer: Renderer) {
    val tree = Tree()
    private val queue = ArrayDeque<Triple<Int, Int, ByteArray>>()
    private var busy = false
    private val live = HashMap<Int, () -> Unit>()
    private var opportunity: (() -> Unit)? = null

    companion object {
        const val FX_NODE = -1 // 0xffffffff
        val CLICK = byteArrayOf(0, 0) // no modifier keys, not a link
    }

    fun start() {
        check(RocEngine.nativeVersion() == 23) { "engine speaks protocol ${RocEngine.nativeVersion()}, host 23" }
        applyBatch(RocEngine.nativeInit())
        drain()
    }

    fun stop() {
        live.values.forEach { it() }
        live.clear()
        RocEngine.nativeDrop()
    }

    /**
     * A view event on `target`, routed like a bubbling DOM event. `value` is
     * the control's value as the DOM gives it (an input's text, a checkbox's
     * "true"/"false"); the payload is encoded by the handler's kind.
     */
    fun dispatch(target: Node, type: String, value: String = ""): Boolean {
        val (node, handler) = tree.route(target, type) ?: return false
        val bytes = when (handler.kind) {
            10 -> CLICK // CLICK: modifier flags, not a link
            5 -> byteArrayOf(if (value == "true") 1 else 0) // OPEN_STATE: checked
            1 -> value.toByteArray(Charsets.UTF_8) // TEXT
            else -> return false // pointer, key, scroll, files: not in this spike
        }
        send(node, handler.number, bytes)
        return true
    }

    fun send(node: Int, handler: Int, payload: ByteArray) {
        queue.add(Triple(node, handler, payload))
        drain()
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
        renderer.render(tree)
        val effects = tree.effects.toList()
        val ack = tree.startupAck
        for (e in effects) perform(e)
        // The cold batch is applied and its effects started: acknowledge it.
        if (ack) queue.add(Triple(FX_NODE, 0, byteArrayOf(254.toByte())))
    }

    private fun result(id: Int, payload: ByteArray) {
        if (live.containsKey(id)) send(FX_NODE, id, payload)
    }

    private fun fired(): ByteArray {
        val at = platform.nowMs()
        return ByteArray(9) { i -> if (i == 0) 5 else (at shr (8 * (i - 1))).toByte() }
    }

    private fun perform(e: Effect) {
        when (e) {
            is Effect.Timer -> live[e.id] = platform.schedule(e.ms) { val p = fired(); result(e.id, p); live.remove(e.id) }
            is Effect.Interval -> {
                fun tick() {
                    live[e.id] = platform.schedule(e.ms) { tick(); result(e.id, fired()) }
                }
                tick()
            }
            is Effect.StorageRead -> live[e.id] = platform.schedule(0) { result(e.id, byteArrayOf(4)); live.remove(e.id) }
            is Effect.Cancel -> live.remove(e.id)?.invoke()
            is Effect.Opportunity -> {
                opportunity?.invoke(); opportunity = null
                if (e.request) {
                    val payload = ByteArray(9) { i -> if (i == 0) 255.toByte() else (e.generation shr (8 * (i - 1))).toByte() }
                    opportunity = platform.schedule(0) { opportunity = null; send(FX_NODE, 0, payload) }
                }
            }
            is Effect.Title -> platform.title(e.text)
            is Effect.Focus -> platform.focus(e.node)
        }
    }
}
