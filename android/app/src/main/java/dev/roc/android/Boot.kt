package dev.roc.android

/**
 * The program's declarations (`roc_engine_keys`), read before mount: which
 * storage keys, ports, media queries, time-zone span, session keys and
 * startup catalogue it declared. Sections a program omits read as empty.
 */
class Declarations(bytes: ByteArray) {
    val localKeys = mutableListOf<String>()
    val sessionKeys = mutableListOf<String>()
    val mediaQueries = mutableListOf<String>()
    /** Section 5: `(name, kind, delivery, bound)` per declared port. */
    val ports = mutableListOf<Port>()
    var zoneDays = 0
    /** Section 14 body, profile 0 (legacy) or 1 (deterministic catalogue). */
    var startupProfile = 0
    /** The declared mount root's expanded-name bytes (profile 1). */
    var mountRoot: ByteArray = DEFAULT_ROOT
    val entryPaths = mutableListOf<String>()

    class Port(val name: String, val kind: Int, val delivery: Int, val bound: Int)

    companion object {
        /** An HTML `div`: namespace HTML, no prefix, local "div". */
        val DEFAULT_ROOT = bytesOf { u8(0); str(""); str("div") }
    }

    init {
        val r = Reader(bytes)
        while (r.more()) {
            val tag = r.u8()
            val body = r.sub(r.u32())
            when (tag) {
                2 -> repeat(body.u32()) { localKeys += body.str() }
                5 -> repeat(body.u32()) { ports += Port(body.str(), body.u8(), body.u8(), body.u32()) }
                6 -> repeat(body.u32()) { mediaQueries += body.str() }
                10 -> zoneDays = body.u16()
                11 -> repeat(body.u32()) { sessionKeys += body.str() }
                14 -> {
                    startupProfile = body.u8()
                    if (startupProfile == 1) {
                        val start = body.at
                        // expanded name: namespace code (4 = explicit URI), prefix, local
                        if (body.u8() == 4) body.str()
                        body.str(); body.str()
                        mountRoot = body.bytes.copyOfRange(start, body.at)
                        repeat(body.u32()) { entryPaths += body.str() }
                        body.u32() // fallback entry
                    }
                }
                else -> {} // sections this host does not read
            }
        }
    }
}

/** One time-zone offset from `fromMs`, in minutes east of UTC. */
class ZoneOffset(val fromMs: Long, val minutes: Int)

/**
 * The app-visible facts at mount (Core's `Boot`), which features fill in
 * before the host encodes them. The defaults are those of an empty boot.
 */
class BootFacts(decl: Declarations) {
    var url = "/"
    var storageAvailable = true
    /** Values of declared local-storage keys that have one. */
    val storage = linkedMapOf<String, String>()
    var online = true
    var visible = true
    /** One flag per declared media query, in declaration order. */
    val media = decl.mediaQueries.map { false }.toMutableList()
    var nowMs = 0L
    /** Sixteen bytes of seed, little-endian. */
    var seed = ByteArray(16)
    var languages = listOf("en-US")
    var zoneName = "UTC"
    var zoneFromMs = Long.MIN_VALUE
    var zoneUntilMs = Long.MAX_VALUE
    var zoneOffsets = listOf(ZoneOffset(Long.MIN_VALUE, 0))
    var sessionAvailable = true
    val session = linkedMapOf<String, String>()
}

/**
 * Encode the boot bytes: the environment (sections 1–4 and 6–11), then the
 * mount root (13) and startup (14). A deterministic program's entry is left
 * to the engine to select from the URL (`0xffffffff`).
 */
fun encodeBoot(decl: Declarations, f: BootFacts): ByteArray = bytesOf {
    bytes(encodeEnvironment(f))
    section(13) { bytes(decl.mountRoot) }
    section(14) {
        if (decl.startupProfile == 1) { u8(1); u8(0); u32(-1) } else u8(0)
    }
}

/**
 * The app-visible environment, sections 1–4 and 6–11 in boot framing: the
 * start of the boot bytes, and the body of an `Environment` answer (result
 * tag 31, to FX kind 22).
 */
fun encodeEnvironment(f: BootFacts): ByteArray = bytesOf {
    section(1) { str(f.url) }
    section(2) {
        flag(f.storageAvailable); u32(f.storage.size)
        for ((k, v) in f.storage) { str(k); str(v) }
    }
    section(3) { flag(f.online) }
    section(4) { flag(f.visible) }
    section(6) { for (m in f.media) flag(m) }
    section(7) { u64(f.nowMs) }
    section(8) { bytes(f.seed.copyOf(16)) }
    section(9) { u32(f.languages.size); for (l in f.languages) str(l) }
    section(10) {
        str(f.zoneName); u64(f.zoneFromMs); u64(f.zoneUntilMs); u32(f.zoneOffsets.size)
        for (o in f.zoneOffsets) { u64(o.fromMs); u32(o.minutes) }
    }
    section(11) {
        flag(f.sessionAvailable); u32(f.session.size)
        for ((k, v) in f.session) { str(k); str(v) }
    }
}
