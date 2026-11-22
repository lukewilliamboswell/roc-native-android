package dev.roc.android

import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneRules

// Environment facts (design.md, "The environment at boot", "Time, entropy
// and locale", "Connectivity, visibility and media"): boot sections 3, 4 and
// 6–10, the connectivity (FX 6), visibility (FX 7) and media (FX 16)
// subscriptions, and environment snapshots (FX 22). Plain Kotlin: the device
// is behind `EnvironmentSource`, so the desktop smoke test runs it on fakes.

/** The device facts a media query is evaluated against. */
class MediaFacts(val dark: Boolean, val reducedMotion: Boolean)

/** What the host reads from the device. Watchers call back on the engine's thread. */
interface EnvironmentSource {
    fun online(): Boolean
    fun visible(): Boolean
    fun media(): MediaFacts
    fun nowMs(): Long
    /** Sixteen bytes of entropy. */
    fun seed(): ByteArray
    /** Preferred languages as BCP 47 tags, most preferred first. */
    fun languages(): List<String>
    fun zone(): ZoneId
    /** Call `changed` when connectivity may have changed; returns how to stop. */
    fun watchOnline(changed: () -> Unit): () -> Unit
    fun watchVisible(changed: () -> Unit): () -> Unit
    /** Call `changed` when any media fact may have changed. */
    fun watchMedia(changed: () -> Unit): () -> Unit
}

/** A value that tells its watchers when it changes (to an unequal value). */
class Watchable<T>(initial: T) {
    var value: T = initial
        private set
    private val watchers = linkedSetOf<() -> Unit>()

    fun set(v: T) {
        if (v == value) return
        value = v
        for (w in watchers.toList()) w()
    }

    fun watch(changed: () -> Unit): () -> Unit {
        val w = { changed() } // a distinct identity per watch
        watchers += w
        return { watchers -= w }
    }
}

/**
 * Whether `query` matches. The supported features are
 * `prefers-color-scheme` (dark/light) and `prefers-reduced-motion`
 * (reduce/no-preference); any other query is false.
 */
fun mediaMatches(query: String, facts: MediaFacts): Boolean =
    when (query.lowercase().filter { !it.isWhitespace() }) {
        "(prefers-color-scheme:dark)" -> facts.dark
        "(prefers-color-scheme:light)" -> !facts.dark
        "(prefers-reduced-motion:reduce)" -> facts.reducedMotion
        "(prefers-reduced-motion:no-preference)" -> !facts.reducedMotion
        else -> false
    }

/** The first sixteen tags that fit 64 UTF-8 bytes; `en-US` when none do. */
fun fitLanguages(tags: List<String>): List<String> {
    val fitting = tags.filter { it.isNotEmpty() && readableText(it) && it.toByteArray(Charsets.UTF_8).size <= 64 }.take(16)
    return fitting.ifEmpty { listOf("en-US") }
}

/** A zone table as boot section 10 carries it. */
class ZoneTable(val name: String, val fromMs: Long, val untilMs: Long, val offsets: List<ZoneOffset>)

/** UTC over the whole I64 range. */
val UTC_TABLE = ZoneTable("UTC", Long.MIN_VALUE, Long.MAX_VALUE, listOf(ZoneOffset(Long.MIN_VALUE, 0)))

private const val DAY_MS = 86_400_000L

/**
 * The offsets of `zone` for `days` each side of `atMs`, from its rules'
 * transitions (as the browser's probe finds them, without probing). The
 * table meets `Core.zone_from`: 1–128 strictly ascending offsets inside the
 * inclusive span, the first at its start. With more transitions than fit,
 * both ends are shortened until they do, still containing `atMs`. A name
 * over 64 bytes is the empty string.
 */
fun zoneTable(zone: ZoneId, days: Int, atMs: Long): ZoneTable = zoneTable(zone.id, zone.rules, days, atMs)

fun zoneTable(id: String, rules: ZoneRules, days: Int, atMs: Long): ZoneTable {
    require(days in 0..7305) { "time-zone days must be from 0 to 7305" }
    fun minutesAt(ms: Long) = rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds / 60
    val name = id.takeIf { readableText(it) && it.toByteArray(Charsets.UTF_8).size <= 64 } ?: ""
    var from = atMs - days * DAY_MS
    var until = atMs + days * DAY_MS
    // Transitions in (from, until], where the offset in minutes changes.
    var changes = mutableListOf<ZoneOffset>()
    var last = minutesAt(from)
    var t = rules.nextTransition(Instant.ofEpochMilli(from))
    while (t != null) {
        val ms = t.instant.toEpochMilli()
        if (ms > until) break
        val minutes = t.offsetAfter.totalSeconds / 60
        if (ms > from && minutes != last) changes += ZoneOffset(ms, minutes)
        last = minutes
        t = rules.nextTransition(t.instant)
    }
    if (changes.size > 127) {
        var radius = days * DAY_MS
        while (changes.size > 127) {
            radius = changes.maxOf { if (it.fromMs < atMs) atMs - it.fromMs else it.fromMs - atMs } - 1
            changes = changes.filter { it.fromMs > atMs - radius && it.fromMs <= atMs + radius }.toMutableList()
        }
        from = atMs - radius
        until = atMs + radius
    }
    return ZoneTable(name, from, until, listOf(ZoneOffset(from, minutesAt(from))) + changes)
}

class EnvironmentFeature(private val source: EnvironmentSource) : Feature {
    companion object {
        const val ONLINE = 8
        const val VISIBLE = 9
        const val MATCHES = 22
        const val ENVIRONMENT = 31
    }

    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(
        6 to { _ -> Unit },     // OnlineChanges
        7 to { _ -> Unit },     // VisibilityChanges
        16 to { r -> r.str() }, // MediaChanges(query)
        22 to { _ -> Unit },    // EnvironmentSnapshot
    )

    override fun perform(host: Host, fx: Fx): (() -> Unit)? = when (fx.kind) {
        6 -> follow(host, fx.id, ONLINE, source::watchOnline) { source.online() }
        7 -> follow(host, fx.id, VISIBLE, source::watchVisible) { source.visible() }
        16 -> {
            val query = fx.request as String
            follow(host, fx.id, MATCHES, source::watchMedia) { mediaMatches(query, source.media()) }
        }
        22 -> host.schedule(0) {
            // Sampled in the next task, by every feature, as at boot.
            host.answer(fx.id, byteArrayOf(ENVIRONMENT.toByte()) + encodeEnvironment(host.environment()))
        }
        else -> null
    }

    /**
     * A subscription to a Boolean fact: report `tag value:u8` each time it
     * differs from the last value the app has (from boot or a report).
     */
    private fun follow(host: Host, id: Int, tag: Int, watch: (() -> Unit) -> () -> Unit, read: () -> Boolean): () -> Unit {
        var last = read()
        return watch {
            val now = read()
            if (now != last) {
                last = now
                host.answer(id, byteArrayOf(tag.toByte(), if (now) 1 else 0), done = false)
            }
        }
    }

    override fun boot(facts: BootFacts, decl: Declarations) {
        facts.online = source.online()
        facts.visible = source.visible()
        val media = source.media()
        decl.mediaQueries.forEachIndexed { i, q -> facts.media[i] = mediaMatches(q, media) }
        val now = source.nowMs()
        facts.nowMs = now
        facts.seed = source.seed().copyOf(16)
        facts.languages = fitLanguages(source.languages())
        val zone = try { zoneTable(source.zone(), decl.zoneDays, now) } catch (e: Exception) { UTC_TABLE }
        facts.zoneName = zone.name
        facts.zoneFromMs = zone.fromMs
        facts.zoneUntilMs = zone.untilMs
        facts.zoneOffsets = zone.offsets
    }
}
