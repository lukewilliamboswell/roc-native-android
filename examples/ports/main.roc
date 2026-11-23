app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Elem, In, Root, State]
import engine.Port
import pf.Ui

## Native Android services reached from Roc through ports, with no change to
## the engine or the platform: the Kotlin services behind these names are
## registered by this example's Activity (`PortsActivity`). A call answers
## once, a command expects nothing back, and a subscription streams messages
## until its site is disposed.
Demo := [].{

	Device : { model : Str, manufacturer : Str, release : Str, sdk : U32 }

	Battery : { percent : U32, charging : Bool }

	## Metres per second squared, rounded to hundredths by the service.
	Motion : { x : Dec, y : Dec, z : Dec }

	Ports : {
		## The device's model and Android version (`android.os.Build`).
		device : Port.Call({}, Device),

		## Show `text` in a Toast.
		toast : Port.Command({ text : Str }),

		## The battery level whenever it changes; each change matters, so a
		## few may wait in order.
		battery : Port.Subscription({}, Battery),

		## The accelerometer, sampled about every `every_ms`; only the newest
		## sample matters.
		motion : Port.Subscription({ every_ms : U32 }, Motion),
	}

	ports : Ports
	ports = {
		device: Port.call({ name: "dev.roc.android/device", timeout: Ms(2000) }),
		toast: Port.command({ name: "dev.roc.android/toast" }),
		battery: Port.subscription({ name: "dev.roc.android/battery", delivery: Every(4) }),
		motion: Port.subscription({ name: "dev.roc.android/motion", delivery: Latest }),
	}

	S : { device : Str, asking : Engine.Outbox({}), draft : Str, toasts : Engine.Outbox(Str), sent : U64, battery : Str, following : Bool, motion : Str, samples : U64 }

	initial : S
	initial = { device: "Device: not asked yet", asking: Engine.outbox({}), draft: "Hello from Roc", toasts: Engine.outbox({}), sent: 0, battery: "Battery: waiting", following: Bool.False, motion: "Motion: off", samples: 0 }

	failed : Port.Error -> Str
	failed = |e| match e {
		Timeout => "no answer in time"
		Threw(message) => "failed: ${message}"
		Closed => "ended"
		_ => Str.inspect(e)
	}

	show_device : Device -> Str
	show_device = |d| "${d.manufacturer} ${d.model}, Android ${d.release} (API ${d.sdk.to_str()})"

	show_battery : Battery -> Str
	show_battery = |b| "Battery: ${b.percent.to_str()}%${if (b.charging) ", charging" else ""}"

	show_motion : Motion -> Str
	show_motion = |m| "x ${m.x.to_str()}  y ${m.y.to_str()}  z ${m.z.to_str()}"

	toasted : S -> S
	toasted = |s| { ..s, toasts: s.toasts.post(s.draft), sent: s.sent + 1 }

	view : State(In(Root, S), S) -> Elem(In(Root, S))
	view = |st| {
		v = st.signal()
		Ui.column([
			Ui.title("Ports"),
			Ui.text("Kotlin services, typed in Roc"),
			# A call: one answer per press.
			Ui.text_s(v.map(|s| s.device)),
			Ui.button("Read device info", st.update(|s| { ..s, asking: s.asking.post({}) })),
			Engine.on_post(
				v.map(|s| s.asking),
				|_| [Demo.ports.device.ask({})],
				st.on_result(
					|s, _, res| match res {
						Ok(d) => { ..s, device: Demo.show_device(d) }
						Err(e) => { ..s, device: "Device: ${Demo.failed(e)}" }
					},
				),
			),
			# A command: sent after the turn, nothing comes back.
			Ui.text_field({ hint: "Toast text", value: v.map(|s| s.draft), on_change: st.set_value(|s, t| { ..s, draft: t }), on_done: st.update(Demo.toasted) }),
			Ui.button("Show toast", st.update(Demo.toasted)),
			Ui.text_s(v.map(|s| "Toasts sent: ${s.sent.to_str()}")),
			Engine.on_post(v.map(|s| s.toasts), |text| [Engine.command(Demo.ports.toast.send({ text: text }))], Engine.ignore({})),
			# A subscription held for the screen's life.
			Ui.text_s(v.map(|s| s.battery)),
			Engine.watch(
				Engine.const({}),
				|_| [Demo.ports.battery.ask({})],
				st.on_result(
					|s, _, res| match res {
						Ok(b) => { ..s, battery: Demo.show_battery(b) }
						Err(e) => { ..s, battery: "Battery: ${Demo.failed(e)}" }
					},
				),
			),
			# A subscription held while the switch is on: off disposes it,
			# and the service unregisters its sensor listener.
			Ui.row([
				Ui.checkbox(v.map(|s| s.following), [], st.set_value(|s, on| { ..s, following: on, motion: if (on) "Motion: starting" else "Motion: off" })),
				Ui.text("Follow the accelerometer"),
			]),
			Ui.text_s(v.map(|s| s.motion)),
			Ui.text_s(v.map(|s| "Samples: ${s.samples.to_str()}")),
			Engine.when(
				v.map(|s| s.following),
				|| Engine.watch(
					Engine.const({ every_ms: 100 }),
					|arg| [Demo.ports.motion.ask(arg)],
					st.on_result(
						|s, _, res| match res {
							Ok(m) => { ..s, motion: Demo.show_motion(m), samples: s.samples + 1 }
							Err(e) => { ..s, motion: "Motion: ${Demo.failed(e)}", following: Bool.False }
						},
					),
				),
				|| Engine.text(""),
			),
		])
	}

	program : Engine.Program
	program =
		Engine.program_boot({}, |_boot| Engine.state(Demo.initial, Demo.view))
			.with_ports(Port.registrations(Demo.ports))
}

main : Engine.Program
main = Demo.program

expect Demo.show_battery({ percent: 80, charging: Bool.True }) == "Battery: 80%, charging"
