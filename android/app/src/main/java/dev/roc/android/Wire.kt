package dev.roc.android

import java.io.ByteArrayOutputStream

// Little-endian framing of the roc-signals wire protocol (design.md,
// Appendix B): u32 ids, `str` = u32 byte length + UTF-8, `rest` = the
// remaining bytes.

class Reader(val bytes: ByteArray, var at: Int = 0, val end: Int = bytes.size) {
    fun more() = at < end
    fun u8(): Int = bytes[at++].toInt() and 0xff
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Int { var v = 0; for (i in 0 until 4) v = v or (u8() shl (8 * i)); return v }
    fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); return v }
    fun bytes(n: Int): ByteArray { val out = bytes.copyOfRange(at, at + n); at += n; return out }
    fun str(): String { val n = u32(); val s = String(bytes, at, n, Charsets.UTF_8); at += n; return s }
    /** The rest of the frame as bytes. */
    fun rest(): ByteArray = bytes(end - at)
    /** A sub-reader over the next `n` bytes, which this reader skips. */
    fun sub(n: Int): Reader { val r = Reader(bytes, at, at + n); at += n; return r }
}

class Writer {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Int) = apply { for (i in 0 until 4) out.write((v shr (8 * i)) and 0xff) }
    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xff).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun str(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); u32(b.size); bytes(b) }
    fun flag(b: Boolean) = u8(if (b) 1 else 0)
    /** `tag:u8 len:u32 body:len`, the boot and declaration framing. */
    fun section(tag: Int, body: Writer.() -> Unit) = apply {
        val b = Writer().apply(body).toBytes()
        u8(tag); u32(b.size); bytes(b)
    }
    fun toBytes(): ByteArray = out.toByteArray()
}

fun bytesOf(build: Writer.() -> Unit): ByteArray = Writer().apply(build).toBytes()
