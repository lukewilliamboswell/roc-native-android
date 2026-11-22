package dev.roc.android

// The host's model of what the engine drew: the same tree the browser
// runtime keeps as DOM, as plain Kotlin objects. `Tree.apply` decodes one
// command batch (roc-signals design.md, Appendix B, protocol 23) and
// records which elements changed; a renderer then refreshes only those.
// Nothing here depends on Android, so the desktop smoke test runs it too.

sealed class Node {
    var parent: Element? = null
    /** The engine id that routes events (set by LISTEN and INST). */
    var routing: Int? = null
    /** For a row root: its `each` site and resolved hole targets. */
    var site: Int? = null
    var holes: List<Node> = emptyList()
    /** The engine ids that name this node (its own, and hole aliases). */
    val ids = mutableListOf<Int>()

    abstract fun copy(): Node

    fun textContent(): String = when (this) {
        is TextNode -> text
        is Comment -> ""
        is Element -> children.joinToString("") { it.textContent() }
    }
}

class TextNode(var text: String) : Node() {
    override fun copy() = TextNode(text)
}

class Comment(val text: String) : Node() {
    override fun copy() = Comment(text)
}

class Handler(val number: Int, val type: String, val kind: Int, val policies: Int)

class Element(val tag: String) : Node() {
    val children = mutableListOf<Node>()
    val attrs = linkedMapOf<String, String>()
    val handlers = mutableMapOf<String, Handler>()
    var value: String = ""
    var checked: Boolean = false

    fun attr(name: String): String? = attrs[name]
    val classes: List<String> get() = (attrs["class"] ?: "").split(' ').filter { it.isNotEmpty() }

    override fun copy(): Element {
        val e = Element(tag)
        e.attrs.putAll(attrs)
        e.handlers.putAll(handlers)
        e.value = value
        e.checked = checked
        for (c in children) e.append(c.copy())
        return e
    }

    fun append(child: Node) = insert(child, null)

    fun insert(child: Node, before: Node?) {
        child.parent?.children?.remove(child)
        child.parent = this
        val at = if (before == null) -1 else children.indexOf(before)
        if (at < 0) children.add(child) else children.add(at, child)
    }
}

/** Work the host performs after a batch is applied, in stream order. */
sealed class Effect(val id: Int) {
    class Timer(id: Int, val ms: Long) : Effect(id)
    class Interval(id: Int, val ms: Long) : Effect(id)
    /** An FX kind owned by a feature. */
    class Request(val fx: Fx) : Effect(fx.id)
    /** A command (opcode) owned by a feature. */
    class Command(val op: Int, val value: Any) : Effect(0)
    class Cancel(id: Int) : Effect(id)
    class Opportunity(val request: Boolean, val generation: Long) : Effect(0)
    class Title(val text: String) : Effect(0)
    class Focus(val node: Node) : Effect(0)
}

class Template(val root: Node, val holes: List<Hole>)

class Hole(val kind: Int, val path: List<Int>, val name: String = "", val handler: Handler? = null, val prop: Int = 0)

class Tree(
    /** FX kind decoders and opcode decoders, from the host's features. */
    private val fxDecoders: Map<Int, (Reader) -> Any> = emptyMap(),
    private val opDecoders: Map<Int, (Reader) -> Any> = emptyMap(),
) {
    val root = Element("div")
    private val nodes = HashMap<Int, Node>()
    private val templates = HashMap<Int, Template>()
    private val sites = HashMap<Int, MutableSet<Int>>()

    /** Elements whose children, text, attributes or properties changed. */
    val dirty = LinkedHashSet<Element>()
    /** Nodes removed from the tree this batch (their views can be dropped). */
    val removed = mutableListOf<Node>()
    /** Effects collected in stream order, performed after the batch. */
    val effects = mutableListOf<Effect>()
    /** The batch ended a cold startup plan: acknowledge it. */
    var startupAck = false

    init {
        nodes[0] = root
        root.routing = null
    }

    fun lookup(id: Int): Node = nodes[id] ?: error("protocol: unknown node $id")

    private fun touch(n: Node?) {
        var e: Node? = n
        while (e != null && e !is Element) e = e.parent
        if (e is Element) dirty.add(e)
    }

    private fun place(node: Node, parent: Int, before: Int) {
        val (owner, anchor) = if (before == 0) (lookup(parent) as Element) to null
        else {
            val b = lookup(before)
            b.parent!! to b
        }
        owner.insert(node, anchor)
        touch(owner)
    }

    private fun forget(n: Node) {
        val stack = ArrayDeque<Node>()
        stack.add(n)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x is Element) stack.addAll(x.children)
            removed.add(x)
            for (id in x.ids) if (nodes[id] === x) nodes.remove(id)
        }
    }

    private fun register(id: Int, n: Node) {
        nodes[id] = n
        n.ids.add(id)
    }

    // ---- decoding -------------------------------------------------------

    /** An expanded name: namespace code (4 = explicit URI), prefix, local. */
    private fun name(r: Reader): String {
        val ns = r.u8()
        if (ns == 4) r.str()
        val prefix = r.str()
        val local = r.str()
        return if (prefix.isEmpty()) local else "$prefix:$local"
    }

    private fun attribute(r: Reader, e: Element, name: String) {
        when (val present = r.u8()) {
            0 -> e.attrs.remove(name)
            1 -> e.attrs[name] = r.str()
            else -> error("protocol: attribute presence $present")
        }
        touch(e)
    }

    private fun templateNode(r: Reader): Node = when (val kind = r.u8()) {
        1 -> {
            val e = Element(name(r))
            r.u8() // child container: CHILDREN or TEMPLATE_CONTENT (both children here)
            repeat(r.u8()) { val n = name(r); e.attrs[n] = r.str() }
            repeat(r.u8()) { e.append(templateNode(r)) }
            e
        }
        2 -> TextNode(r.str())
        3 -> Comment(r.str())
        else -> error("protocol: template node kind $kind")
    }

    private fun walk(root: Node, path: List<Int>): Node {
        var n = root
        for (i in path.indices step 2) n = (n as Element).children[path[i + 1]]
        return n
    }

    private fun template(r: Reader, end: Int): Template {
        val root = templateNode(r)
        val holes = mutableListOf<Hole>()
        repeat(r.u8()) {
            val kind = r.u8()
            val depth = r.u8()
            val path = List(depth * 2) { r.u8() }
            holes += when (kind) {
                2 -> Hole(kind, path, name = name(r))
                3 -> {
                    val h = r.u8(); val type = r.str(); val payloadKind = r.u8(); val policies = r.u32()
                    Hole(kind, path, name = type, handler = Handler(h, type, payloadKind, policies))
                }
                6 -> Hole(kind, path, prop = r.u8())
                1, 4, 5 -> Hole(kind, path)
                else -> error("protocol: hole kind $kind")
            }
        }
        check(r.at == end) { "protocol: template length" }
        return Template(root, holes)
    }

    private fun setProp(target: Node, prop: Int, value: String) {
        val e = target as Element
        when (prop) {
            1 -> e.value = value
            2 -> e.checked = value == "true"
            else -> {} // keymaps, pointer zones, dialogs, inner_html: not in this spike
        }
        touch(e)
    }

    private fun fx(r: Reader) {
        val id = r.u32()
        val kind = r.u8()
        val decode = fxDecoders[kind]
        when {
            decode != null -> effects += Effect.Request(Fx(id, kind, decode(r)))
            kind == 3 -> effects += Effect.Timer(id, r.u32().toLong() and 0xffffffffL)
            kind == 4 -> effects += Effect.Interval(id, r.u32().toLong() and 0xffffffffL)
            // Subscriptions no feature serves never fire: accepted and idle.
            kind == 5 || kind == 6 || kind == 7 || kind == 20 -> {}
            kind == 16 || kind == 18 -> r.str()
            else -> error("unsupported effect kind $kind: no feature of this host serves it")
        }
    }

    /** A command a feature owns; false when none does. */
    private fun command(op: Int, r: Reader): Boolean {
        val decode = opDecoders[op] ?: return false
        effects += Effect.Command(op, decode(r))
        return true
    }

    fun apply(batch: ByteArray) {
        if (batch.isEmpty()) return
        val r = Reader(batch)
        r.u8() // flags: 0 plain, 1 view transition (applied plainly here)
        while (r.more()) {
            val op = r.u8()
            if (command(op, r)) continue
            when (op) {
                1 -> { // INST
                    val id = r.u32(); val parent = r.u32(); val before = r.u32(); val site = r.u32()
                    val tpl = templates[site] ?: error("protocol: no template $site")
                    val el = tpl.root.copy()
                    val targets = tpl.holes.map { walk(el, it.path) }
                    el.routing = id; el.site = site; el.holes = targets
                    tpl.holes.forEachIndexed { i, hole ->
                        val t = targets[i]
                        when (hole.kind) {
                            1 -> (t as TextNode).text = r.str()
                            2 -> attribute(r, t as Element, hole.name)
                            6 -> setProp(t, hole.prop, r.str())
                            3 -> (t as Element).handlers[hole.name] = hole.handler!!
                            4, 5 -> register(r.u32(), t)
                        }
                    }
                    place(el, parent, before)
                    register(id, el)
                    sites.getOrPut(site) { mutableSetOf() }.add(id)
                }
                2 -> { // TEXT
                    val row = lookup(r.u32()); val hole = r.u8()
                    val t = row.holes[hole] as TextNode
                    t.text = r.str(); touch(t)
                }
                3 -> { // HATTR
                    val row = lookup(r.u32()); val hole = r.u8()
                    val tpl = templates[row.site!!]!!
                    attribute(r, row.holes[hole] as Element, tpl.holes[hole].name)
                }
                4 -> { // MOVE
                    val n = lookup(r.u32()); val before = r.u32()
                    val p = n.parent!!
                    p.insert(n, if (before == 0) null else lookup(before))
                    touch(p)
                }
                5 -> { // REMOVE
                    val n = lookup(r.u32())
                    val p = n.parent
                    p?.children?.remove(n); n.parent = null
                    touch(p); forget(n)
                }
                6 -> { // CLEAR
                    val p = lookup(r.u32()) as Element; val site = r.u32()
                    for (c in p.children.toList()) { c.parent = null; forget(c) }
                    p.children.clear(); sites[site] = mutableSetOf(); touch(p)
                }
                7 -> { // TEMPLATE
                    val tpl = r.u32(); val n = r.u32()
                    val start = r.at
                    templates[tpl] = template(r, start + n)
                    sites[tpl] = mutableSetOf()
                }
                8 -> { // ELEM
                    val id = r.u32(); val parent = r.u32(); val before = r.u32()
                    val e = Element(name(r)); r.u8()
                    place(e, parent, before); register(id, e)
                }
                9 -> { val e = lookup(r.u32()) as Element; attribute(r, e, name(r)) } // ATTR
                10, 26 -> { // TEXTNODE, COMMENT
                    val id = r.u32(); val parent = r.u32(); val before = r.u32(); val text = r.str()
                    val n = if (op == 10) TextNode(text) else Comment(text)
                    place(n, parent, before); register(id, n)
                }
                11 -> { // LISTEN
                    val id = r.u32(); val h = r.u32(); val type = r.str(); val kind = r.u8(); val policies = r.u32()
                    val e = lookup(id) as Element
                    e.routing = id
                    e.handlers[type] = Handler(h, type, kind, policies)
                    touch(e)
                }
                12 -> { val n = lookup(r.u32()); (n as TextNode).text = r.str(); touch(n) } // SETTEXT
                13 -> fx(r)
                14 -> effects += Effect.Cancel(r.u32())
                15 -> { r.u8(); r.str() } // NAV, when no feature owns it: ignored
                16 -> { val o = r.u8(); r.str(); if (o == 1 || o == 3) r.str() } // STORAGE, likewise
                17 -> effects += Effect.Title(r.str())
                18 -> { // REF
                    val o = r.u8(); val n = lookup(r.u32())
                    when (o) {
                        1, 3, 5 -> effects += Effect.Focus(n)
                        6 -> { r.u8(); r.u8(); r.u8() }
                        7 -> { repeat(2) { if (r.u8() == 3) r.u32() }; r.u8() }
                    }
                }
                21 -> { val n = lookup(r.u32()); val p = r.u8(); setProp(n, p, r.str()) } // PROP
                22 -> { // HPROP
                    val row = lookup(r.u32()); val hole = r.u8()
                    setProp(row.holes[hole], templates[row.site!!]!!.holes[hole].prop, r.str())
                }
                23 -> r.u32() // RELEASE
                24 -> repeat(r.u32()) { r.str() } // TRANSITION
                25 -> r.u8() // GUARD
                28 -> { r.u8(); r.u32() } // STARTUP
                29 -> startupAck = true // STARTUP_ACK
                30 -> {} // STARTUP_COMPLETE
                31 -> { val a = r.u8(); effects += Effect.Opportunity(a == 1, r.u64()) }
                else -> error("unsupported command $op in this spike host")
            }
        }
    }

    // ---- events ---------------------------------------------------------

    /**
     * Route an event as the browser runtime does: the nearest handler for
     * `type` at or above `target`, then the nearest routing id at or above
     * the element that declared it. Null when nothing listens.
     */
    fun route(target: Node, type: String): Pair<Int, Handler>? {
        var n: Node? = target
        var handler: Handler? = null
        while (n != null) {
            if (handler == null && n is Element) handler = n.handlers[type]
            if (handler != null && n.routing != null) return n.routing!! to handler
            n = n.parent
        }
        return null
    }

    fun clearMarks() {
        dirty.clear(); removed.clear(); effects.clear(); startupAck = false
    }
}
