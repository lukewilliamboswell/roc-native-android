// Desktop smoke test: loads an example's macOS .dylib (the same Roc app and
// Zig JNI host as the Android .so) into a plain JVM and drives it through
// the same Tree/Host code the Android app uses, with a manual clock.
//
//   blueprint run smoke  (scripts/check_all.roc builds build/smoke.jar)
//   java -jar build/smoke.jar build/libcounter.dylib counter

import dev.roc.android.*
import java.io.File

class ManualPlatform : Platform {
    var now = 0L
    private var seq = 0
    private val pending = sortedMapOf<Pair<Long, Int>, () -> Unit>(compareBy({ it.first }, { it.second }))
    // Work posted from other threads (a feature's executor) waits here until
    // the test thread drains it (`advance`, `pump`); `posted` counts it.
    private val owner = Thread.currentThread()
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    val posted = java.util.concurrent.atomic.AtomicInteger()
    override fun nowMs() = now
    override fun schedule(ms: Long, task: () -> Unit): () -> Unit {
        if (Thread.currentThread() !== owner) {
            posted.incrementAndGet()
            inbox.add { schedule(ms, task) }
            return {}
        }
        val key = (now + ms) to seq++
        pending[key] = task
        return { pending.remove(key) }
    }
    override fun log(text: String) = println("dbg: $text")
    override fun title(text: String) = println("title: $text")

    fun advance(ms: Long) {
        while (true) inbox.poll()?.invoke() ?: break
        val end = now + ms
        while (true) {
            val next = pending.keys.firstOrNull()?.takeIf { it.first <= end } ?: break
            now = next.first
            pending.remove(next)!!()
        }
        now = end
    }

    /**
     * Run posted work, the manual clock following real time, until `done`
     * holds or `timeoutMs` of real time passes; whether it held.
     */
    fun pump(timeoutMs: Long = 5000, done: () -> Boolean): Boolean {
        val start = System.nanoTime()
        var last = start
        while (true) {
            val t = System.nanoTime()
            val ms = (t - last) / 1_000_000
            advance(ms)
            last += ms * 1_000_000
            if (done()) return true
            if (t - start > timeoutMs * 1_000_000) return false
            Thread.sleep(5)
        }
    }
}

class CountingRenderer : Renderer {
    var renders = 0
    var lastDirty = 0
    override fun render(tree: Tree) { renders++; lastDirty = tree.dirty.size }

    // REF commands, with focus kept as a view system keeps it.
    var host: Host? = null
    var focused: Element? = null
    val refs = mutableListOf<Ref>()
    override fun ref(ref: Ref) {
        refs += ref
        val e = ref.node as? Element ?: return
        when (ref.op) {
            1, 3, 5 -> focus(e)
            2 -> if (focused === e) blur()
        }
    }
    fun focus(e: Element) {
        if (focused === e) return
        blur()
        focused = e
        host?.focused(e)
    }
    fun blur() {
        val e = focused ?: return
        focused = null
        host?.blurred(e)
    }
}

fun show(n: Node, indent: String = ""): String = when (n) {
    is TextNode -> "$indent\"${n.text}\"\n"
    is Comment -> ""
    is Element -> {
        val attrs = n.attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" } +
            (if (n.tag == "input") " .value=\"${n.value}\" .checked=${n.checked}" else "") +
            (if (n.handlers.isNotEmpty()) " on=${n.handlers.keys}" else "")
        "$indent<${n.tag}$attrs>\n" + n.children.joinToString("") { show(it, "$indent  ") }
    }
}

fun all(n: Node): Sequence<Node> = sequence {
    yield(n)
    if (n is Element) for (c in n.children.toList()) yieldAll(all(c))
}

fun Host.find(pred: (Element) -> Boolean): Element =
    all(tree.root).filterIsInstance<Element>().first(pred)

fun Host.button(text: String) = find { it.tag == "ui-button" && it.textContent() == text }
fun Host.link(text: String) = find { it.tag == "ui-link" && it.textContent() == text }
fun Host.heading() = find { it.tag == "ui-title" }.textContent()
fun Host.rows() = all(tree.root).filterIsInstance<Element>().filter { it.tag == "ui-row" && it.parent?.tag == "ui-list" }.toList()

var failures = 0
fun expect(label: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + label)
    if (!ok) failures++
}

fun main(args: Array<String>) {
    System.load(File(args[0]).absolutePath)
    val platform = ManualPlatform()
    val renderer = CountingRenderer()
    val navigation = NavigationFeature() // history and back (pages)
    val fakes = PortFakes()
    if (args[1] == "ports") { portJsonCase(); refusesBadServices(platform) }
    // Port services must match the app's declared ports, so only `ports` gets them.
    val ports = if (args[1] == "ports") listOf(fakes.feature()) else emptyList()
    val host = Host(platform, Fakes.features(platform) + HttpSmoke.feature() + navigation + ports + filesSmokeFeatures(args[1]))
    renderer.host = host
    host.renderer = renderer
    host.start()
    platform.advance(0)
    println("mounted:\n" + show(host.tree.root))
    val text = { host.tree.root.textContent() }

    when (args[1]) {
        "counter" -> {
            expect("starts at 0", text().contains("Count: 0"))
            host.dispatch(host.button("+"), "click")
            host.dispatch(host.button("+"), "click")
            expect("two increments", text().contains("Count: 2"))
            expect("one text changed, one element dirty", renderer.lastDirty == 1)
            repeat(3) { host.dispatch(host.button("-"), "click") }
            expect("below zero shows warning", text().contains("Count: -1") && text().contains("Below zero!"))
            host.dispatch(host.button("Reset"), "click")
            expect("reset hides warning", text().contains("Count: 0") && !text().contains("Below zero!"))
        }
        "todos" -> {
            val input = host.find { it.tag == "input" && it.attr("type") == "text" }
            host.dispatch(input, "input", "Buy milk")
            host.dispatch(host.button("Add"), "click")
            host.dispatch(input, "input", "Water plants")
            host.dispatch(input, "done", "Water plants") // the keyboard's action key
            println(show(host.tree.root))
            expect("two rows", host.rows().size == 2)
            expect("draft cleared", input.value == "")
            expect("2 items left", text().contains("2 items left"))
            val box = host.find { it.tag == "input" && it.attr("type") == "checkbox" }
            host.dispatch(box, "change", "true")
            expect("checkbox checked", box.checked)
            expect("1 item left", text().contains("1 item left"))
            host.dispatch(host.button("Show open"), "click")
            expect("open filter hides the done row", !text().contains("Buy milk") && text().contains("Water plants"))
            host.dispatch(host.button("Show all"), "click")
            val remove = host.find { it.tag == "ui-button" && it.attr("description") == "Remove Buy milk" }
            host.dispatch(remove, "click")
            expect("removed one row", host.rows().size == 1 && !text().contains("Buy milk"))
        }
        "contacts" -> contacts(host, platform, renderer) // SmokeContacts.kt
        "stopwatch" -> {
            expect("starts at 0:00.0", text().contains("0:00.0"))
            host.dispatch(host.button("Start"), "click")
            platform.advance(1250)
            expect("12 ticks after 1.25 s", text().contains("0:01.2"))
            host.dispatch(host.button("Lap"), "click")
            platform.advance(500)
            host.dispatch(host.button("Stop"), "click")
            val stopped = text()
            platform.advance(5000)
            expect("stop cancels the interval", text() == stopped && stopped.contains("0:01.7"))
            expect("lap recorded", stopped.contains("Lap 1: 0:01.2"))
            host.dispatch(host.button("Reset"), "click")
            expect("reset", text().contains("0:00.0") && !text().contains("Lap 1"))
        }
        "http" -> HttpSmoke.run(host, platform)
        "device" -> { deviceCase(host, platform); zoneCases() }
        "pages" -> {
            expect("starts at home", host.heading() == "Home" && navigation.history.current == "/")
            host.dispatch(host.link("About"), "click")
            expect("link pushes /about", host.heading() == "About" && navigation.history.depth == 2 && navigation.history.current == "/about")
            host.dispatch(host.link("See the items"), "click")
            host.dispatch(host.link("Item 2"), "click")
            expect("parameter page", host.heading() == "Item" && text().contains("Item 2") && navigation.history.current == "/items/2")
            expect("four entries", navigation.history.depth == 4)
            expect("back to items", navigation.back(host) && host.heading() == "Items" && text().contains("At /items"))
            host.dispatch(host.link("Item 3"), "click")
            expect("item 3 after back", text().contains("Item 3") && navigation.history.depth == 4)
            expect("back to items again", navigation.back(host) && host.heading() == "Items")
            expect("back to about", navigation.back(host) && host.heading() == "About")
            expect("back to home", navigation.back(host) && host.heading() == "Home" && text().contains("At /"))
            expect("back at the root finishes", !navigation.back(host))
            val h = History("/items/2?x=1#f")
            expect("resolve relative", h.resolve("3") == "/items/3" && h.resolve("../about") == "/about" && h.resolve("#g") == "/items/2?x=1#g" && h.resolve("?y") == "/items/2?y")
            expect("refuse other origins", h.resolve("https://example.com/") == null && h.resolve("//x/") == null)
            // A deep link: the first history entry selects the startup entry.
            host.stop()
            val deep = Host(platform, listOf(NavigationFeature("/about")))
            deep.start(); platform.advance(0)
            expect("boot at /about opens About", deep.heading() == "About")
            deep.stop()
            host.start(); platform.advance(0)
        }
        "ports" -> portsCase(host, platform, fakes)
        "files" -> filesSmoke(host, platform) // FilesSmoke.kt
    }
    println("final:\n" + show(host.tree.root))
    host.stop()
    when (args[1]) {
        "todos" -> todosRestart(platform)
        "device" -> deviceRestart(platform)
    }
    println(if (failures == 0) "PASS ${args[1]}" else "FAILED ${args[1]}: $failures")
    if (failures != 0) kotlin.system.exitProcess(1)
}
