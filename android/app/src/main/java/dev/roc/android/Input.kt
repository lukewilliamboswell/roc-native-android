package dev.roc.android

// Input fidelity in plain Kotlin, so the desktop smoke test runs it too:
// rendered keymaps and key payloads (design.md "Keyboard input", Appendix B
// key kind 4 and PROP 4/5), controlled text values (design.md "Controlled
// Inputs"; runtime.mjs `setValue`), and layout report payloads (kinds 6–8).

/** Payload kinds (Appendix B) this host encodes. */
object Kind {
    const val TEXT = 1
    const val KEY = 4
    const val OPEN_STATE = 5
    const val SCROLL = 6
    const val SIZE = 7
    const val SEEN = 8
    const val CLICK = 10
}

/** Policy bits (Appendix B). */
object Policy {
    const val PREVENT = 1
    const val STOP = 2
    const val PACED = 8
    const val DOCUMENT = 16
}

/** Layout report payloads: `top:u32 left:u32`, `width:u32 height:u32`, `intersecting:u8 ratio:u16`. */
object Layout {
    fun scroll(top: Int, left: Int) = bytesOf { u32(top.coerceAtLeast(0)); u32(left.coerceAtLeast(0)) }
    fun size(width: Int, height: Int) = bytesOf { u32(width.coerceAtLeast(0)); u32(height.coerceAtLeast(0)) }
    fun seen(intersecting: Boolean, permille: Int) = bytesOf { flag(intersecting); u16(permille.coerceIn(0, 1000)) }
}

/** A key as the view system reported it, in DOM terms (`key`, `code`, modifiers). */
class KeyInput(
    val key: String,
    val code: String = "",
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val repeat: Boolean = false,
    val composing: Boolean = false,
) {
    val modifiers: Int get() = (if (ctrl) 1 else 0) or (if (alt) 2 else 0) or (if (shift) 4 else 0) or (if (meta) 8 else 0)

    /** The key kind's payload: `flags:u8 key:str code:str heard:u8 heard_key:str`. */
    fun payload(heard: Keymap.Entry): ByteArray = bytesOf {
        u8(modifiers or (if (repeat) 16 else 0) or (if (composing) 32 else 0))
        str(key); str(code); u8(heard.modifiers); str(heard.key)
    }
}

/**
 * A rendered keymap (PROP 4 `keymap_down`, 5 `keymap_up`), parsed as
 * runtime.mjs `parseKeymap` does and matched as its `matchKey`: exact
 * chords in listed order, then physical fallbacks, then `every`.
 */
class Keymap private constructor(
    private val exact: Map<String, List<Entry>>,
    private val physical: Map<String, List<Entry>>,
    private val every: Entry?,
) {
    class Entry(val key: String, val modifiers: Int, val outside: Boolean, val prevent: Boolean)

    companion object {
        val EMPTY = Keymap(emptyMap(), emptyMap(), null)
        private val cache = HashMap<String, Keymap>()

        fun of(text: String?): Keymap = if (text.isNullOrEmpty()) EMPTY else cache.getOrPut(text) { parse(text) }

        private fun asciiLetter(k: String) = k.length == 1 && (k[0] in 'a'..'z' || k[0] in 'A'..'Z')
        private fun asciiAlnum(k: String) = asciiLetter(k) || (k.length == 1 && k[0] in '0'..'9')

        /** One Unicode scalar of general category P* or S*: Shift does not count. */
        private fun symbolKey(k: String): Boolean {
            if (k.isEmpty() || k.codePointCount(0, k.length) != 1) return false
            return when (Character.getType(k.codePointAt(0)).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
                else -> false
            }
        }

        private fun index(key: String, modifiers: Int) =
            "${if (symbolKey(key)) modifiers and 4.inv() else modifiers}:${if (asciiLetter(key)) key.lowercase() else key}"

        fun parse(text: String): Keymap {
            val b = text.toByteArray(Charsets.UTF_8)
            val exact = HashMap<String, MutableList<Entry>>()
            val physical = HashMap<String, MutableList<Entry>>()
            var every: Entry? = null
            var at = 0
            fun bad(what: String): Nothing = error("executor: invalid keymap ($what)")
            while (at < b.size) {
                val policy = b[at++].toInt()
                if ((policy == 'a'.code || policy == 'o'.code) && at < b.size && b[at].toInt() == '*'.code) {
                    at++
                    if (at != b.size || every != null) bad("every must occur once at the end")
                    every = Entry("", 0, policy == 'o'.code, false)
                    continue
                }
                if (policy != 'p'.code && policy != 'k'.code) bad("policy")
                val place = b[at++].toInt()
                if (place != 'a'.code && place != 'o'.code) bad("place")
                var modifiers = 0
                for (bit in 0 until 4) {
                    val f = b[at++].toInt()
                    if (f == "CASM"[bit].code) modifiers = modifiers or (1 shl bit) else if (f != '-'.code) bad("modifier")
                }
                val start = at
                while (at < b.size && b[at] >= '0'.code.toByte() && b[at] <= '9'.code.toByte()) at++
                if (at == start || at >= b.size || b[at].toInt() != ':'.code || (at - start > 1 && b[start].toInt() == '0'.code)) bad("key length")
                val length = String(b, start, at - start, Charsets.US_ASCII).toInt()
                at++
                if (length > b.size - at) bad("key length")
                val key = String(b, at, length, Charsets.UTF_8)
                at += length
                val entry = Entry(key, modifiers, place == 'o'.code, policy == 'p'.code)
                exact.getOrPut(index(key, modifiers)) { mutableListOf() }.add(entry)
                if (asciiAlnum(key)) {
                    val code = if (asciiLetter(key)) "Key${key.uppercase()}" else "Digit$key"
                    physical.getOrPut("$modifiers:$code") { mutableListOf() }.add(entry)
                }
            }
            return Keymap(exact, physical, every)
        }
    }

    /**
     * The entry that hears `k`, or null. `textEntry` says the target is a
     * text-entry control, which entries placed outside text skip.
     */
    fun match(k: KeyInput, textEntry: Boolean): Entry? {
        if (k.key.isEmpty()) return null
        val outside = !textEntry
        fun first(entries: List<Entry>?) = entries?.firstOrNull { !it.outside || outside }
        if (!k.composing) {
            first(exact[index(k.key, k.modifiers)])?.let { return it }
            if (!asciiAlnum(k.key)) first(physical["${k.modifiers}:${k.code}"])?.let { return it }
        }
        val e = every ?: return null
        return if (!e.outside || outside) Entry(k.key, k.modifiers, e.outside, false) else null
    }
}

/**
 * One text control's controlled value (design.md "Controlled Inputs";
 * runtime.mjs `setValue` and its focusout listener). `shown` is the text
 * the native widget should hold. A rendered value equal to it is a no-op; a
 * different one is deferred while the control is focused (an IME composes
 * only in the focused view, so focus covers composition) and applied on
 * blur, unless the user's own typing has meanwhile matched it.
 */
class TextControl(var shown: String) {
    var focused = false
        private set
    /** The last rendered `Prop.value`. */
    var rendered: String = shown
        private set
    /** A rendered value waiting for blur. */
    var deferred: String? = null
        private set

    fun render(value: String) {
        rendered = value
        if (shown == value) { deferred = null; return }
        if (focused) { deferred = value; return }
        deferred = null
        shown = value
    }

    /** The user edited the text: an echo that equals a deferred value clears it. */
    fun typed(text: String) {
        shown = text
        if (deferred == text) deferred = null
    }

    fun focus() { focused = true }

    /** Losing focus applies a deferred value. */
    fun blur() {
        focused = false
        deferred?.let { shown = it }
        deferred = null
    }

    /**
     * REF 4, `ApplyValue`: show the last rendered value now, even while
     * focused. (The browser waits for `compositionend`; on Android writing
     * the text restarts the IME's input, which ends its composition.)
     */
    fun applyRendered() {
        shown = rendered
        deferred = null
    }
}

/** An edge of a REF 7 scroll: `0` Keep, `1` Start, `2` End, `3` pixels. */
class Edge(val tag: Int, val px: Int = 0) {
    companion object { val KEEP = Edge(0) }
}

/**
 * A REF command (Appendix B, opcode 18): `1` focus, `2` blur, `3` focus and
 * select, `4` apply the rendered value, `5` focus without scrolling, `6`
 * scroll into view (`block`/`inline` alignment 0 Start, 1 Center, 2 End,
 * 3 Nearest), `7` scroll to (`top`/`left` edges); behavior 0 Auto, 1
 * Instant, 2 Smooth.
 */
class Ref(
    val op: Int,
    val node: Node,
    val block: Int = 3,
    val inline: Int = 3,
    val behavior: Int = 0,
    val top: Edge = Edge.KEEP,
    val left: Edge = Edge.KEEP,
)
