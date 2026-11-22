package dev.roc.android

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Port messages as Kotlin services see them: the platform's `PortJson`
 * encoding (roc-signals `PortJson.roc`) read into plain values and written
 * back, as the browser runtime's `portValue` and `portData` do.
 *
 * Values are `null`, `Boolean`, `Long` (a JSON integer that fits) or
 * `Double`, `String`, `List<Any?>`, `Map<String, Any?>` (insertion order) and
 * `ByteArray` (a `{"$bytes":N}` marker, whose bytes travel beside the text).
 * An object key that begins with `$` is written with one more `$`, so no key
 * is mistaken for a marker; reading removes it again. Writing also takes
 * `Int`, `Short`, `Byte`, `Float`, `BigInteger` and `BigDecimal` (as decimal
 * text, like a JavaScript `BigInt`), `Char`, `Unit`, arrays, any `Iterable`
 * and any `Map` with `String` keys. Anything else, a NaN or an infinity, or
 * containers nested 512 deep, is not JSON data (`PortDataError`).
 *
 * No Android dependency: the desktop smoke test runs it too.
 */
object PortJson {
    class PortDataError(message: String) : Exception(message)

    const val MAX_DEPTH = 512

    /** JSON text and its byte blocks, as a message carries them. */
    class Encoded(val json: String, val blobs: List<ByteArray>) {
        /** JSON text and bytes together, as `maxPortBytes` counts them. */
        val size: Long get() = json.toByteArray(Charsets.UTF_8).size.toLong() + blobs.sumOf { it.size.toLong() }
    }

    // ---- reading --------------------------------------------------------

    /** A message's value, every bytes marker revived as its `ByteArray`. */
    fun read(json: String, blobs: List<ByteArray> = emptyList()): Any? {
        val p = Parser(json)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.at != json.length) p.fail("trailing text")
        return revive(v, blobs)
    }

    private fun revive(v: Any?, blobs: List<ByteArray>): Any? = when (v) {
        is List<*> -> v.map { revive(it, blobs) }
        is Map<*, *> -> {
            val marker = v["\$bytes"]
            if (v.size == 1 && marker != null) {
                val n = (marker as? Long)?.toInt() ?: throw PortDataError("a bytes marker that is not an index")
                blobs.getOrNull(n) ?: throw PortDataError("a bytes marker $n beyond the ${blobs.size} blocks")
            } else if (v.size == 1 && v.containsKey("\$file")) {
                throw PortDataError("a file marker: files do not cross this host's ports")
            } else {
                val out = LinkedHashMap<String, Any?>()
                for ((k, x) in v) {
                    val key = k as String
                    out[if (key.startsWith("$")) key.substring(1) else key] = revive(x, blobs)
                }
                out
            }
        }
        else -> v
    }

    private class Parser(val s: String) {
        var at = 0
        fun fail(why: String): Nothing = throw PortDataError("JSON: $why at $at")
        fun ws() { while (at < s.length && s[at] in " \t\r\n") at++ }
        fun value(depth: Int): Any? {
            if (at >= s.length) fail("unexpected end")
            return when (val c = s[at]) {
                '{' -> {
                    if (depth >= MAX_DEPTH) fail("containers nested $MAX_DEPTH deep")
                    at++; ws()
                    val out = LinkedHashMap<String, Any?>()
                    if (peek('}')) return out.also { at++ }
                    while (true) {
                        ws(); if (!peek('"')) fail("expected a key")
                        val k = string(); ws(); expect(':'); ws()
                        out[k] = value(depth + 1); ws()
                        if (peek(',')) { at++; continue }
                        expect('}'); return out
                    }
                    @Suppress("UNREACHABLE_CODE") out
                }
                '[' -> {
                    if (depth >= MAX_DEPTH) fail("containers nested $MAX_DEPTH deep")
                    at++; ws()
                    val out = ArrayList<Any?>()
                    if (peek(']')) return out.also { at++ }
                    while (true) {
                        ws(); out += value(depth + 1); ws()
                        if (peek(',')) { at++; continue }
                        expect(']'); return out
                    }
                    @Suppress("UNREACHABLE_CODE") out
                }
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }
        fun peek(c: Char) = at < s.length && s[at] == c
        fun expect(c: Char) { if (!peek(c)) fail("expected '$c'"); at++ }
        fun word(w: String, v: Any?): Any? { if (!s.startsWith(w, at)) fail("expected $w"); at += w.length; return v }
        fun number(): Any {
            val start = at
            if (peek('-')) at++
            if (peek('0')) at++ else { if (at >= s.length || s[at] !in '1'..'9') fail("a number"); while (at < s.length && s[at] in '0'..'9') at++ }
            var whole = true
            if (peek('.')) { whole = false; at++; val d = at; while (at < s.length && s[at] in '0'..'9') at++; if (at == d) fail("a fraction") }
            if (peek('e') || peek('E')) { whole = false; at++; if (peek('+') || peek('-')) at++; val d = at; while (at < s.length && s[at] in '0'..'9') at++; if (at == d) fail("an exponent") }
            val text = s.substring(start, at)
            return if (whole) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }
        fun string(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (at >= s.length) fail("unterminated string")
                val c = s[at++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (at >= s.length) fail("unterminated escape")
                        when (val e = s[at++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (at + 4 > s.length) fail("a \\u escape")
                                b.append(s.substring(at, at + 4).toIntOrNull(16)?.toChar() ?: fail("a \\u escape"))
                                at += 4
                            }
                            else -> fail("escape \\$e")
                        }
                    }
                    c < ' ' -> fail("a control character in a string")
                    else -> b.append(c)
                }
            }
        }
    }

    // ---- writing --------------------------------------------------------

    /** A service's value as a message: strict JSON text and its byte blocks. */
    fun write(value: Any?): Encoded {
        val blobs = ArrayList<ByteArray>()
        val out = StringBuilder()
        val open = java.util.IdentityHashMap<Any, Unit>()
        fun where(path: String) = if (path.isEmpty()) "the top" else path
        fun enc(v: Any?, path: String, depth: Int) {
            when (v) {
                null, Unit -> { out.append("null"); return }
                is Boolean -> { out.append(v); return }
                is Byte, is Short, is Int, is Long -> { out.append((v as Number).toLong()); return }
                is Float, is Double -> { number((v as Number).toDouble(), out) ?: throw PortDataError("$v at ${where(path)}"); return }
                is BigInteger, is BigDecimal -> { string(v.toString(), out); return }
                is String -> { string(v, out); return }
                is Char -> { string(v.toString(), out); return }
                else -> {}
            }
            if (depth >= MAX_DEPTH) throw PortDataError("containers nested more than $MAX_DEPTH deep at ${where(path)}")
            if (v is ByteArray) { out.append("{\"\$bytes\":${blobs.size}}"); blobs += v; return }
            if (open.containsKey(v)) throw PortDataError("a cycle at ${where(path)}")
            open[v] = Unit
            when (v) {
                is Map<*, *> -> {
                    out.append('{')
                    var first = true
                    for ((k, x) in v) {
                        if (k !is String) throw PortDataError("a key that is not a string at ${where(path)}")
                        if (x == Unit) continue
                        if (!first) out.append(',')
                        first = false
                        string(if (k.startsWith("$")) "$$k" else k, out)
                        out.append(':')
                        enc(x, "$path.$k", depth + 1)
                    }
                    out.append('}')
                }
                is Iterable<*>, is Array<*>, is IntArray, is LongArray, is DoubleArray, is BooleanArray -> {
                    val items: Iterable<Any?> = when (v) {
                        is Iterable<*> -> v
                        is Array<*> -> v.asIterable()
                        is IntArray -> v.asIterable()
                        is LongArray -> v.asIterable()
                        is DoubleArray -> v.asIterable()
                        else -> (v as BooleanArray).asIterable()
                    }
                    out.append('[')
                    items.forEachIndexed { i, x -> if (i > 0) out.append(','); enc(x, "$path[$i]", depth + 1) }
                    out.append(']')
                }
                else -> throw PortDataError("a ${v::class.java.simpleName} at ${where(path)}")
            }
            open.remove(v)
        }
        enc(value, "", 0)
        return Encoded(out.toString(), blobs)
    }

    /** A finite number as JavaScript's `JSON.stringify` writes it; null otherwise. */
    private fun number(d: Double, out: StringBuilder): Unit? {
        if (d.isNaN() || d.isInfinite()) return null
        if (d == Math.rint(d) && Math.abs(d) < 1e21) out.append(BigDecimal(d).toBigInteger())
        else out.append(d.toString().replace("E", "e"))
        return Unit
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { out.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> out.append("\\u%04x".format(c.code)) // a lone surrogate, as JSON.stringify writes it
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }
}
