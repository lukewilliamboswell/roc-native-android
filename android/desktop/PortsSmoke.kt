// The ports example on the desktop: PortsFeature with fake services in place
// of DevicePorts.kt's Android ones (see Smoke.kt).

import dev.roc.android.*

class PortFakes {
    /** How the device call behaves on each press, in turn. */
    val deviceModes = ArrayDeque(listOf("answer", "hang", "throw", "nan"))
    var deviceAborts = 0
    val toasts = mutableListOf<String>()
    val faults = mutableListOf<String>()
    var battery: PortService.Sink? = null
    var motion: PortService.Sink? = null
    var motionArg: Any? = null
    var motionUnsubscribed = 0

    val services: Map<String, PortService> = mapOf(
        "dev.roc.android/device" to PortService.Call { _, reply ->
            when (deviceModes.removeFirstOrNull() ?: "answer") {
                "answer" -> reply.answer(mapOf("model" to "Smoke", "manufacturer" to "JVM", "release" to "17", "sdk" to 37))
                "hang" -> {}
                "throw" -> error("boom")
                "nan" -> reply.answer(mapOf("model" to Double.NaN))
            }
            { deviceAborts++ }
        },
        "dev.roc.android/toast" to PortService.Command { v ->
            val text = (v as Map<*, *>)["text"] as String
            if (text == "explode") error("no toast today")
            toasts += text
        },
        "dev.roc.android/battery" to PortService.Subscription { _, sink -> battery = sink; null },
        "dev.roc.android/motion" to PortService.Subscription { arg, sink ->
            motion = sink; motionArg = arg
            { motionUnsubscribed++ }
        },
    )

    fun feature() = PortsFeature(maxPortBytes = 4096, onFault = { port, kind, m -> faults += "$kind $port: $m" }) { services }
}

/** The host refuses to mount when a declared port has no service of its shape. */
fun refusesBadServices(platform: Platform) {
    fun refused(services: Map<String, PortService>): String? = try {
        Host(platform, listOf(PortsFeature { services })).start()
        null
    } catch (e: IllegalStateException) {
        e.message
    }
    val full = PortFakes().services
    val missing = refused(full - "dev.roc.android/toast")
    expect("refuses a missing service ($missing)", missing?.contains("no service is given") == true)
    val wrong = refused(full + ("dev.roc.android/toast" to PortService.Call { _, _ -> null }))
    expect("refuses a service of another shape ($wrong)", wrong?.contains("declared by the app as a command") == true)
    val extra = refused(full + ("dev.roc.android/nope" to PortService.Command {}))
    expect("refuses an undeclared service ($extra)", extra?.contains("does not declare") == true)
}

fun portsCase(host: Host, platform: ManualPlatform, fakes: PortFakes) {
    val text = { host.tree.root.textContent() }
    expect("declares four ports", host.declarations.ports.map { it.name to it.kind } == listOf(
        "dev.roc.android/battery" to 3, "dev.roc.android/device" to 2, "dev.roc.android/motion" to 3, "dev.roc.android/toast" to 1,
    ))
    expect("battery is Every(4), motion is Latest", host.declarations.ports.map { it.delivery to it.bound } == listOf(1 to 4, 0 to 0, 2 to 1, 0 to 0))

    // Call: an answer, a timeout (which aborts the service), a throw, an answer that is not JSON data.
    val device = host.button("Read device info")
    host.dispatch(device, "click"); platform.advance(0)
    expect("call answered", text().contains("JVM Smoke, Android 17 (API 37)"))
    host.dispatch(device, "click"); platform.advance(1999)
    expect("call still waiting before its timeout", text().contains("JVM Smoke") && fakes.deviceAborts == 0)
    platform.advance(1)
    expect("call timed out and aborted", text().contains("Device: no answer in time") && fakes.deviceAborts == 1)
    host.dispatch(device, "click"); platform.advance(0)
    expect("a throw answers Threw", text().contains("Device: failed: boom"))
    host.dispatch(device, "click"); platform.advance(0)
    expect("NaN answers Threw", text().contains("Device: failed: the answer is not JSON data"))

    // Command: performed after the turn; a throw is a fault, not the app's.
    val field = host.find { it.tag == "input" && it.attr("type") == "text" }
    host.dispatch(field, "input", "Hi \"there\" \$5")
    host.dispatch(host.button("Show toast"), "click"); platform.advance(0)
    expect("command sent", fakes.toasts == listOf("Hi \"there\" \$5") && text().contains("Toasts sent: 1"))
    host.dispatch(field, "input", "explode")
    host.dispatch(field, "done", "explode"); platform.advance(0)
    expect("command fault reported, app unaffected", fakes.faults.size == 1 && fakes.faults[0].contains("no toast today") && text().contains("Toasts sent: 2"))

    // Every(4): four wait, the fifth is refused; each arrives as its own turn.
    val battery = fakes.battery!!
    val taken = (1..5).map { battery.emit(mapOf("percent" to 90 + it, "charging" to (it % 2 == 0))) }
    expect("Every(4) takes four, refuses the fifth", taken == listOf(true, true, true, true, false))
    platform.advance(0)
    expect("all four delivered in order", text().contains("Battery: 94%, charging"))

    // Latest: subscribing on the switch; waiting messages coalesce to the newest.
    val box = host.find { it.tag == "input" && it.attr("type") == "checkbox" }
    host.dispatch(box, "change", "true"); platform.advance(0)
    expect("motion subscribed with its argument", fakes.motionArg == mapOf("every_ms" to 100L))
    val motion = fakes.motion!!
    for (i in 1..3) expect("Latest takes message $i", motion.emit(mapOf("x" to i * 1.5, "y" to -0.25, "z" to 9.81)))
    platform.advance(0)
    expect("Latest delivers only the newest", text().contains("x 4.5  y -0.25  z 9.81") && text().contains("Samples: 1"))
    expect("emit of something that is not JSON data throws back", try { motion.emit(Any()); false } catch (e: PortJson.PortDataError) { true })
    host.dispatch(box, "change", "false"); platform.advance(0)
    expect("off disposes the subscription", fakes.motionUnsubscribed == 1 && text().contains("Motion: off"))
    expect("a cancelled subscription takes nothing", !motion.emit(mapOf("x" to 1, "y" to 2, "z" to 3)))

    // A second subscription, closed by the service.
    host.dispatch(box, "change", "true"); platform.advance(0)
    val again = fakes.motion!!
    expect("a new subscription", again !== motion)
    again.emit(mapOf("x" to 0, "y" to 0, "z" to 1)); again.close(); platform.advance(0)
    expect("close ends it after the waiting message", text().contains("Motion: ended") && text().contains("Samples: 2"))
    battery.emit(mapOf("percent" to 1, "charging" to "x".repeat(5000))); platform.advance(0)
    expect("a message over maxPortBytes is TooLarge", text().contains("Battery: TooLarge"))
    battery.emit(mapOf("percent" to 7, "charging" to false)); platform.advance(0)
    expect("and the subscription goes on", text().contains("Battery: 7%"))
    battery.emit(mapOf("percent" to 50, "charging" to false)); battery.fail("unplugged sensor"); platform.advance(0)
    expect("fail ends with Threw after the waiting message", text().contains("Battery: failed: unplugged sensor"))
    expect("an ended subscription takes nothing", !battery.emit(mapOf("percent" to 1, "charging" to false)))
}

/** PortJson as the browser runtime's portData/portValue: markers, `$` keys, numbers. */
fun portJsonCase() {
    val e = PortJson.write(mapOf("\$k" to listOf(1, 2.5, 3.0, -0.0, null, true), "b" to byteArrayOf(1, 2), "s" to "a\"\n\u0001\ud800"))
    val want = "{\"\$\$k\":[1,2.5,3,0,null,true],\"b\":{\"\$bytes\":0},\"s\":\"a\\\"\\n\\u0001\\ud800\"}"
    expect("writes strictly (${e.json})", e.json == want && e.blobs.size == 1)
    val v = PortJson.read(e.json, e.blobs) as Map<*, *>
    expect("reads back", v.keys == setOf("\$k", "b", "s") && (v["b"] as ByteArray).toList() == listOf<Byte>(1, 2) && v["\$k"] == listOf(1L, 2.5, 3L, 0L, null, true))
    expect("refuses NaN", try { PortJson.write(Double.NaN); false } catch (_: PortJson.PortDataError) { true })
    expect("refuses a cycle", try { val l = mutableListOf<Any>(); l.add(l); PortJson.write(l); false } catch (_: PortJson.PortDataError) { true })
}
