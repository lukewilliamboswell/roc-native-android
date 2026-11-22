package dev.roc.android

/** What the host needs from its environment: time, deferred work, output. */
interface Platform {
    fun nowMs(): Long
    /** Run `task` after `ms` on the engine's thread; the returned function cancels it. */
    fun schedule(ms: Long, task: () -> Unit): () -> Unit
    fun log(text: String)
    fun title(text: String) {}
    /** Told of REF focus commands (1, 3, 5); the renderer performs them (`Renderer.ref`). */
    fun focus(node: Node) {}
}

/** Applies a tree's changes to some view system. */
interface Renderer {
    fun render(tree: Tree)
    /** Perform a REF command (focus, blur, select, apply value, scroll) after its batch. */
    fun ref(ref: Ref) {}
}

/**
 * The thin driver around the engine: every input (a view event, an effect
 * result, the startup acknowledgement) is one turn, queued so a turn never
 * starts while another batch is being applied. Effects and commands go to
 * the feature that owns their kind or opcode (see `Feature`).
 *
 * Layout reports (`report`) are paced: they wait for the next frame and a
 * newer report from the same source replaces a waiting one, while any other
 * input (an occurrence) first delivers the reports that arrived before it,
 * as runtime.mjs's report queue does.
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
        set(value) {
            val replaced = value !== field
            field = value
            // The old views' focus went with them: blur what they held.
            if (replaced) for (e in controls.filterValues { it.focused }.keys.toList()) blurred(e)
            value?.render(tree)
        }

    lateinit var declarations: Declarations
        private set
    var started = false
        private set

    /**
     * One input waiting for its turn. A report (`source` set) waits for
     * its frame and is replaced by a newer report of its source while it
     * waits in the current epoch; an occurrence starts a new epoch, so the
     * reports queued before it are delivered first, in order.
     */
    private class Entry(val node: Int, val handler: Int, var payload: ByteArray, val source: Source?, val epoch: Int)

    /** A report source: one declaring element's handler for one type. */
    private data class Source(val element: Element, val type: String, val handler: Handler)

    private val queue = ArrayDeque<Entry>()
    private val waitingReports = HashMap<Source, Entry>()
    private var epoch = 0
    private var reportDrain: (() -> Unit)? = null
    private var reportDue = false
    private var busy = false
    private val live = HashMap<Int, () -> Unit>()
    private var opportunity: (() -> Unit)? = null

    /** Controlled text inputs, by element (see `TextControl`). */
    private val controls = HashMap<Element, TextControl>()

    companion object {
        const val FX_NODE = -1 // 0xffffffff
        const val PROTOCOL = 23
        val CLICK = byteArrayOf(0, 0) // no modifier keys, not a link
        /** The longest a report waits for its frame. */
        const val FRAME_MS = 16L
        private val NOT_TEXT = setOf("checkbox", "radio", "range", "button", "submit", "reset", "file", "hidden", "color", "image")
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
        reportDrain?.invoke()
        reportDrain = null
        queue.clear(); waitingReports.clear(); controls.clear()
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
            Kind.CLICK -> clickPayload(target, tree.root) // modifier flags and the nearest link (NavigationFeature.kt)
            Kind.OPEN_STATE -> byteArrayOf(if (value == "true") 1 else 0) // checked
            Kind.TEXT -> value.toByteArray(Charsets.UTF_8)
            else -> return false // keys and layout reports: `key`, `report`; pointer and files: not here
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
        occurrence(node, handler, payload)
        drain()
    }

    /** Queue an occurrence: it starts a new epoch, behind every waiting report. */
    private fun occurrence(node: Int, handler: Int, payload: ByteArray) {
        epoch++
        queue.add(Entry(node, handler, payload, null, epoch))
    }

    // ---- text controls ------------------------------------------------------

    private fun control(e: Element) = controls.getOrPut(e) { TextControl(e.value) }

    /** The text a text input's widget should show: its controlled value. */
    fun shown(e: Element): String = controls[e]?.shown ?: e.value

    /** The user changed a text input's text: an `input` event carrying it. */
    fun typed(e: Element, text: String): Boolean {
        if (!tree.attached(e)) return false
        control(e).typed(text)
        return dispatch(e, "input", text)
    }

    /** A view took focus: `focus` (focusin) carries the target's value. */
    fun focused(e: Element): Boolean {
        if (!tree.attached(e)) return false
        if (e.tag == "input") control(e).focus()
        return dispatch(e, "focus", shown(e))
    }

    /**
     * A view lost focus. A deferred value is applied first (the renderer then
     * shows `shown(e)`), and `blur` (focusout) carries the result.
     */
    fun blurred(e: Element): Boolean {
        controls[e]?.blur()
        if (!tree.attached(e)) return false
        return dispatch(e, "blur", shown(e))
    }

    // ---- keys -----------------------------------------------------------------

    /** A text-entry target, which keymap entries placed outside text skip. */
    private fun textEntry(n: Node): Boolean {
        val e = n as? Element ?: n.parent ?: return false
        return e.tag == "input" && (e.attr("type") ?: "text") !in NOT_TEXT
    }

    /**
     * A key on `target` (`keydown` or `keyup`), delivered to the nearest key
     * handler whose rendered keymap hears it; one that does not hear it is
     * passed over. Returns the entry that heard it (its `prevent` decides
     * whether the view keeps its default), or null: a key nobody hears costs
     * no turn.
     */
    fun key(target: Node, type: String, k: KeyInput): Keymap.Entry? {
        if (!tree.attached(target)) return null
        val text = textEntry(target)
        var heard: Keymap.Entry? = null
        val routed = tree.routeAt(target, type) { e, h ->
            if (h.kind != Kind.KEY || (h.policies and Policy.DOCUMENT) != 0) false
            else {
                heard = Keymap.of(e.props[if (type == "keyup") 5 else 4]).match(k, text)
                heard != null
            }
        } ?: return null
        val entry = heard!!
        send(routed.node, routed.handler.number, k.payload(entry))
        return entry
    }

    // ---- layout reports -------------------------------------------------------

    /**
     * A layout report (`scroll`, `size` or `seen`) from `target`, already
     * encoded (`Layout`). It waits for the next frame, and a newer report
     * from the same source replaces it meanwhile, so the engine sees the
     * latest position once per frame rather than every native event. False
     * when nothing listens.
     */
    fun report(target: Node, type: String, payload: ByteArray): Boolean {
        val routed = tree.routeAt(target, type) { _, h -> h.kind == Kind.SCROLL || h.kind == Kind.SIZE || h.kind == Kind.SEEN }
            ?: return false
        val source = Source(routed.declaring, type, routed.handler)
        val waiting = waitingReports[source]
        if (waiting != null && waiting.epoch == epoch) waiting.payload = payload
        else {
            val entry = Entry(routed.node, routed.handler.number, payload, source, epoch)
            queue.add(entry)
            waitingReports[source] = entry
        }
        scheduleReports()
        return true
    }

    /** Deliver the waiting reports now, as a frame does. */
    fun flushReports() = drain(reports = true)

    private fun scheduleReports() {
        if (reportDrain != null) return
        reportDrain = platform.schedule(FRAME_MS) { reportDrain = null; drain(reports = true) }
    }

    /** A report is stale once its element left or no longer declares that handler. */
    private fun Source.live() = element.handlers[type] === handler && tree.attached(element)

    // ---- effects ----------------------------------------------------------------

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

    private fun drain(reports: Boolean = false) {
        if (busy) { if (reports) reportDue = true; return }
        var frame = reports || reportDue
        reportDue = false
        busy = true
        try {
            while (queue.isNotEmpty()) {
                val entry = queue.first()
                // Reports of the current epoch wait for their frame.
                if (entry.source != null && entry.epoch == epoch && !frame) break
                queue.removeFirst()
                val source = entry.source
                if (source != null) {
                    if (waitingReports[source] === entry) waitingReports.remove(source)
                    if (!source.live()) continue
                }
                applyBatch(RocEngine.nativeEvent(entry.node, entry.handler, entry.payload))
                if (reportDue) { frame = true; reportDue = false }
            }
        } finally {
            busy = false
        }
        if (queue.isNotEmpty()) scheduleReports()
    }

    private fun applyBatch(batch: ByteArray) {
        val dbg = RocEngine.nativeDbg()
        if (dbg.isNotEmpty()) platform.log(String(dbg, Charsets.UTF_8).trimEnd())
        tree.clearMarks()
        tree.apply(batch)
        for (n in tree.removed) if (n is Element) controls.remove(n)
        for (e in tree.dirty) controls[e]?.render(e.value)
        renderer?.render(tree)
        val effects = tree.effects.toList()
        val ack = tree.startupAck
        for (e in effects) perform(e)
        // The cold batch is applied and its effects started: acknowledge it.
        if (ack) occurrence(FX_NODE, 0, byteArrayOf(254.toByte()))
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
            is Effect.Reference -> {
                val ref = e.ref
                // A node removed since the command was collected is stale.
                if (!tree.attached(ref.node)) return
                if (ref.op == 4 && ref.node is Element) controls[ref.node]?.applyRendered()
                if (ref.op == 1 || ref.op == 3 || ref.op == 5) platform.focus(ref.node)
                renderer?.ref(ref)
            }
        }
    }
}
