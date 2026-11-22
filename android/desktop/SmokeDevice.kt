// Smoke cases for storage and environment facts: fake stores and device
// facts behind the same StorageFeature and EnvironmentFeature the app uses.
// The stores outlive a host, so a "restart" (stop, then a new Host on the
// same fakes) shows what persists.

import dev.roc.android.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset as JZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules

class FakeEnvironment(private val clock: () -> Long) : EnvironmentSource {
    val online = Watchable(true)
    val visible = Watchable(true)
    val dark = Watchable(false)
    var languages = listOf("en-AU", "fr-FR")
    var zone: ZoneId = ZoneId.of("Australia/Sydney")
    override fun online() = online.value
    override fun visible() = visible.value
    override fun media() = MediaFacts(dark = dark.value, reducedMotion = false)
    override fun nowMs() = clock()
    override fun seed() = ByteArray(16) { it.toByte() }
    override fun languages() = languages
    override fun zone() = zone
    override fun watchOnline(changed: () -> Unit) = online.watch(changed)
    override fun watchVisible(changed: () -> Unit) = visible.watch(changed)
    override fun watchMedia(changed: () -> Unit) = dark.watch(changed)
}

/** The fakes the smoke features share across restarts of one run. */
object Fakes {
    val local = MemoryStore()
    val session = MemoryStore()
    lateinit var env: FakeEnvironment
    fun features(platform: ManualPlatform): List<Feature> {
        if (!::env.isInitialized) env = FakeEnvironment { platform.now }
        return listOf(StorageFeature(local, session), EnvironmentFeature(env))
    }
}

/** Stop, then mount the same example again on the same fakes. */
fun restart(platform: ManualPlatform): Host {
    val host = Host(platform, Fakes.features(platform))
    host.start()
    platform.advance(0)
    return host
}

fun deviceCase(host: Host, platform: ManualPlatform) {
    val text = { host.tree.root.textContent() }
    val env = Fakes.env
    expect("boot: online", text().contains("Online"))
    expect("boot: dark mode off", text().contains("Dark mode: off"))
    expect("boot: languages", text().contains("Languages: en-AU, fr-FR"))
    expect("boot: zone name", text().contains("Time zone: Australia/Sydney"))
    expect("local time through the zone (1970 Sydney is +10)", text().contains("Local time: 10:00:00 AM"))
    platform.advance(1000)
    expect("interval moves the clock", text().contains("Local time: 10:00:01 AM"))
    host.dispatch(host.button("Use 24-hour clock"), "click")
    expect("24-hour clock", text().contains("Local time: 10:00:01") && !text().contains("AM"))
    expect("preference written to local storage", Fakes.local.values["clock"] == "24")
    env.online.set(false)
    expect("connectivity subscription", text().contains("Offline"))
    env.dark.set(true)
    expect("media subscription", text().contains("Dark mode: on"))
    env.visible.set(false); env.visible.set(true)
    expect("visibility subscription", text().contains("Returned to the foreground 1 times"))
    env.languages = listOf("de-DE")
    host.dispatch(host.button("Sample environment"), "click")
    expect("snapshot waits a task", text().contains("Not sampled yet"))
    platform.advance(0)
    expect("environment snapshot", text().contains("Sampled offline, dark, de-DE, Australia/Sydney, saved clock 24"))
}

fun deviceRestart(platform: ManualPlatform) {
    val host = restart(platform)
    val text = host.tree.root.textContent()
    expect("restart: preference kept", text.contains("Use 12-hour clock") && text.contains("Local time: 10:00:01"))
    expect("restart: boot reads the current facts", text.contains("Offline") && text.contains("Dark mode: on") && text.contains("Languages: de-DE"))
    host.stop()
}

fun todosRestart(platform: ManualPlatform) {
    expect("list saved to local storage", Fakes.local.values["todos"] == "-Water plants")
    val host = restart(platform)
    val text = host.tree.root.textContent()
    expect("restart: saved row restored", host.rows().size == 1 && text.contains("Water plants") && text.contains("1 item left"))
    val input = host.find { it.tag == "input" && it.attr("type") == "text" }
    host.dispatch(input, "input", "Call home")
    host.dispatch(host.button("Add"), "click")
    expect("restart: next key follows the saved rows", host.rows().size == 2 && Fakes.local.values["todos"] == "-Water plants\n-Call home")
    host.stop()
}

/** The zone table meets Core.zone_from's rules; dense zones are trimmed. */
fun zoneCases() {
    val at = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    val sydney = zoneTable(ZoneId.of("Australia/Sydney"), 366, at)
    expect("zone: Sydney name and span", sydney.name == "Australia/Sydney" && sydney.fromMs == at - 366 * 86_400_000L && sydney.untilMs == at + 366 * 86_400_000L)
    expect("zone: Sydney DST offsets", sydney.offsets.size == 5 && sydney.offsets.map { it.minutes } == listOf(660, 600, 660, 600, 660))
    expect("zone: valid", validZone(sydney))
    val none = zoneTable(ZoneId.of("Australia/Sydney"), 0, at)
    expect("zone: zero days is the offset at mount", none.fromMs == at && none.untilMs == at && none.offsets.size == 1 && none.offsets[0].minutes == 660)
    val utc = zoneTable(ZoneId.of("UTC"), 7305, at)
    expect("zone: UTC has one offset", utc.offsets.size == 1 && utc.offsets[0].minutes == 0 && validZone(utc))
    // A synthetic zone changing every day: 7305 days each side would hold
    // thousands of changes, so both ends are shortened to fit 128 offsets.
    val day = 86_400_000L
    val plus0 = JZoneOffset.ofHours(0); val plus1 = JZoneOffset.ofHours(1)
    val changes = (-400..400).map { i ->
        val (before, after) = if (i % 2 == 0) plus0 to plus1 else plus1 to plus0
        ZoneOffsetTransition.of(java.time.LocalDateTime.ofEpochSecond((at + i * day + 3_600_000) / 1000, 0, before), before, after)
    }
    val dense = ZoneRules.of(plus0, plus0, emptyList(), changes, emptyList())
    val trimmed = zoneTable("Dense", dense, 7305, at)
    expect("zone: dense zone trimmed to 128 offsets around mount", trimmed.offsets.size <= 128 && trimmed.fromMs <= at && at <= trimmed.untilMs && validZone(trimmed))
}

fun validZone(z: ZoneTable): Boolean =
    z.offsets.size in 1..128 && z.offsets[0].fromMs == z.fromMs && z.fromMs <= z.untilMs &&
        z.offsets.zipWithNext().all { (a, b) -> a.fromMs < b.fromMs } && z.offsets.all { it.fromMs in z.fromMs..z.untilMs }
