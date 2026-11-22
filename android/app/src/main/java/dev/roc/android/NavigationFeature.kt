package dev.roc.android

/**
 * The session history of one engine, as the browser keeps it for a page:
 * a stack of URLs (`pathname + search + hash`), the last one current. Push
 * and replace resolve the engine's URL against the current one; back pops.
 * Plain Kotlin, so the desktop smoke test drives it too.
 */
class History(initial: String = "/") {
    private val entries = mutableListOf(initial)

    val current: String get() = entries.last()
    val depth: Int get() = entries.size

    /** `history.pushState(null, "", url)`; false (and nothing pushed) for a URL leaving the app. */
    fun push(url: String): Boolean {
        entries += resolve(url) ?: return false
        return true
    }

    /** `history.replaceState(null, "", url)`. */
    fun replace(url: String): Boolean {
        entries[entries.lastIndex] = resolve(url) ?: return false
        return true
    }

    /** Traverse back one entry: the new current URL, or null at the first entry. */
    fun back(): String? {
        if (entries.size <= 1) return null
        entries.removeAt(entries.lastIndex)
        return current
    }

    /**
     * Resolve `url` against the current entry as the URL parser does for a
     * same-origin reference: tabs and newlines removed, C0 controls and
     * spaces trimmed, dot segments removed. A URL with a scheme or an
     * authority would leave the app's origin (pushState throws there): null.
     * Percent-encoding is not applied; the app sees what it wrote.
     */
    fun resolve(url: String): String? {
        val u = url.filter { it != '\t' && it != '\n' && it != '\r' }.trim { it <= ' ' }
        if (u.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(u)) return null
        val cur = current
        val curNoHash = cur.substringBefore('#')
        val curPath = curNoHash.substringBefore('?')
        return when {
            u.isEmpty() -> curNoHash
            u.startsWith("#") -> curNoHash + u
            u.startsWith("?") -> curPath + u
            u.startsWith("/") -> normalize(u)
            else -> normalize(curPath.substringBeforeLast('/') + "/" + u)
        }
    }

    private fun normalize(ref: String): String {
        val cut = ref.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) ref.length else it }
        val segments = ref.substring(0, cut).split('/').drop(1)
        val out = mutableListOf<String>()
        segments.forEachIndexed { i, s ->
            val last = i == segments.lastIndex
            when (s) {
                "." -> if (last) out += ""
                ".." -> { out.removeLastOrNull(); if (last) out += "" }
                else -> out += s
            }
        }
        return "/" + out.joinToString("/") + ref.substring(cut)
    }
}

/**
 * Navigation: `NAV` (op 15, `mode:u8 url:str`, 1 push / 2 replace) moves
 * the history, and `Popstate` subscriptions (FX 5) hear a traversal with
 * result tag 6 (`url:rest`), as the browser fires `popstate` on back. Boot
 * section 1 is the current entry, from which a deterministic program's
 * startup entry is selected.
 *
 * A push or replace fires nothing (as in the browser); only `back` does.
 * The feature lives in the runtime, so the history survives the activity
 * being recreated.
 */
class NavigationFeature(initial: String = "/") : Feature {
    val history = History(initial)
    private val subscribers = LinkedHashSet<Int>()

    /** Called after the history's depth may have changed (the activity arms its back callback). */
    var changed: (() -> Unit)? = null

    class Nav(val mode: Int, val url: String)

    override val opDecoders: Map<Int, (Reader) -> Any> = mapOf(15 to { r -> Nav(r.u8(), r.str()) })
    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(5 to { _ -> Unit })

    override fun boot(facts: BootFacts, decl: Declarations) {
        facts.url = history.current
    }

    override fun perform(host: Host, fx: Fx): (() -> Unit)? {
        subscribers += fx.id
        return { subscribers.remove(fx.id) }
    }

    override fun command(host: Host, op: Int, value: Any) {
        val nav = value as Nav
        val ok = when (nav.mode) {
            1 -> history.push(nav.url)
            2 -> history.replace(nav.url)
            else -> error("protocol: NAV mode ${nav.mode}")
        }
        if (!ok) host.log("navigation to ${nav.url} leaves the app: ignored")
        changed?.invoke()
    }

    /**
     * System back: pop the history and tell every popstate subscriber the
     * new URL. False when the history is at its first entry, so the caller
     * lets the activity go back (finish) instead.
     */
    fun back(host: Host): Boolean {
        val url = history.back() ?: return false
        val payload = bytesOf { u8(6); bytes(url.toByteArray(Charsets.UTF_8)) }
        for (id in subscribers.toList()) host.answer(id, payload, done = false)
        changed?.invoke()
        return true
    }

    override fun stopped(host: Host) {
        subscribers.clear()
    }
}

/**
 * The click payload (kind 10): modifier flags, then the nearest link at or
 * above `target` below the mount root, as the browser runtime finds it: an
 * `a` or `ui-link` element with an `href`. `flags 1 href:str target:str
 * download:u8`, or `flags 0` when no link encloses the target.
 */
fun clickPayload(target: Node, root: Node): ByteArray {
    var n: Node? = target
    while (n != null && n !== root) {
        if (n is Element && (n.tag == "a" || n.tag == "ui-link")) {
            val link: Element = n
            val href = link.attr("href")
            if (href != null) return bytesOf {
                u8(0); u8(1); str(href); str(link.attr("target") ?: ""); flag(link.attrs.containsKey("download"))
            }
        }
        n = n.parent
    }
    return Host.CLICK
}
