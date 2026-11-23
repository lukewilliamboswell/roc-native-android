app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Signal]
import pf.Ui

## The device's facts as the app sees them: connectivity, visibility and
## two media queries start from boot and follow their subscriptions; the
## languages and time zone are fixed at boot; a clock reads the zone; and the
## clock's format is a preference saved in local storage. "Sample
## environment" asks for a fresh snapshot of every fact (FX 22).
Device := [Device].{
	store = Engine.store(
		Device.Device,
		{
			online: Engine.cell(Bool.True),
			dark: Engine.cell(Bool.False),
			calm: Engine.cell(Bool.False),
			clock: Engine.cell(0.I64),
			hours24: Engine.cell(Bool.False),
			returns: Engine.cell(0.U64),
			sampled: Engine.cell("Not sampled yet"),
			samples: Engine.cell(Engine.outbox({})),
		}.Engine.Cells,
	)

	cells = Device.store.cells()

	dark_query = "(prefers-color-scheme: dark)"
	calm_query = "(prefers-reduced-motion: reduce)"

	## The store a visit starts with: boot's facts, and the saved preference.
	start : Engine.Boot -> Engine.Store(_, _)
	start = |boot|
		Device.store
			.with_initial(Device.cells.online, boot.online)
			.with_initial(Device.cells.dark, Engine.boot_media(boot, Device.dark_query) ?? Bool.False)
			.with_initial(Device.cells.calm, Engine.boot_media(boot, Device.calm_query) ?? Bool.False)
			.with_initial(Device.cells.clock, boot.now_ms)
			.with_initial(Device.cells.hours24, Engine.boot_storage(boot, "clock") == Ok("24"))

	## One line about a fresh environment snapshot.
	summary : Engine.Boot -> Str
	summary = |boot| {
		dark = Engine.boot_media(boot, Device.dark_query) ?? Bool.False
		"Sampled ${if (boot.online) "online" else "offline"}, ${if (dark) "dark" else "light"}, ${boot.languages.first() ?? "?"}, ${Device.zone_name(boot.zone)}, saved clock ${Engine.boot_storage(boot, "clock") ?? "unset"}"
	}

	zone_name : Engine.Zone -> Str
	zone_name = |zone| if (zone.name().is_empty()) "unknown zone" else zone.name()

	program : Engine.Program
	program = Engine.program_store({ storage_keys: ["clock"], media: [Device.dark_query, Device.calm_query] }, Device.start, view)
}

## Two digits.
pad : I64 -> Str
pad = |n| if (n < 10) "0${n.to_str()}" else n.to_str()

## The wall-clock time at `at_ms` in `zone`, which says nothing outside the
## span boot measured rather than guess an offset.
local_time : Engine.Zone, I64, Bool -> Str
local_time = |zone, at_ms, hours24|
	match zone.offset_at(at_ms) {
		Err(OutsideZone) => "Outside the measured time zone"
		Ok(minutes) => {
			local = at_ms + minutes.to_i64() * 60_000
			of_day = ((local % 86_400_000) + 86_400_000) % 86_400_000 // 1000
			hour = of_day // 3600
			rest = ":${pad((of_day // 60) % 60)}:${pad(of_day % 60)}"
			if hours24 {
				"${pad(hour)}${rest}"
			} else {
				twelve = if (hour % 12 == 0) 12 else hour % 12
				"${twelve.to_str()}${rest} ${if (hour < 12) "AM" else "PM"}"
			}
		}
	}

## Follow one Boolean subscription into a cell.
follow : Engine.Ask(Bool, [Refused]), Engine.Cell(_, Bool) -> Engine.Elem(ctx)
	where [ctx.store : () -> Engine.Reach(ctx, _)]
follow = |ask, cell| Engine.watch(
	Engine.const({}),
	|_| [ask],
	Engine.react(
		|_, result| match result {
			Ok(value) => [cell.set(value)]
			_ => []
		},
	),
)

view : Engine.Boot -> Engine.Elem(Engine.StoreRoot(_))
view = |boot| {
	cells = Device.cells
	hours24 = cells.hours24.signal()
	Ui.column([
		Ui.title("Device"),
		Ui.text_s(cells.online.signal().map(|on| if (on) "Online" else "Offline")),
		Ui.text_s(cells.dark.signal().map(|on| "Dark mode: ${if (on) "on" else "off"}")),
		Ui.text_s(cells.calm.signal().map(|on| "Reduced motion: ${if (on) "on" else "off"}")),
		Ui.text("Languages: ${Str.join_with(boot.languages, ", ")}"),
		Ui.text("Time zone: ${Device.zone_name(boot.zone)}"),
		Ui.text_s({ at: cells.clock.signal(), h24: hours24 }.Signal.map(|{ at, h24 }| "Local time: ${local_time(boot.zone, at, h24)}")),
		Ui.button_s(hours24.map(|on| if (on) "Use 12-hour clock" else "Use 24-hour clock"), Engine.handle_by(hours24, |on, _| [cells.hours24.set(!on)])),
		Ui.text_s(cells.returns.signal().map(|n| "Returned to the foreground ${n.to_str()} times")),
		Ui.button("Sample environment", Engine.handle(|_| [cells.samples.update(|outbox| outbox.post({}))])),
		Ui.text_s(cells.sampled.signal()),
		follow(Engine.online_changes({}), cells.online),
		follow(Engine.media_changes(Device.dark_query), cells.dark),
		follow(Engine.media_changes(Device.calm_query), cells.calm),
		Engine.watch(
			Engine.const({}),
			|_| [Engine.visibility_changes({})],
			Engine.react(
				|_, result| match result {
					Ok(Bool.True) => [cells.returns.update(|n| n + 1)]
					_ => []
				},
			),
		),
		Engine.watch(
			Engine.const({}),
			|_| [Engine.interval(1000)],
			Engine.react(
				|_, result| match result {
					Ok(at_ms) => [cells.clock.set(at_ms)]
					_ => []
				},
			),
		),
		Engine.watch_changes(hours24.map(|on| if (on) "24" else "12"), |text| [Engine.command(StorageSet("clock", text))], Engine.ignore({})),
		Engine.on_post(
			cells.samples.signal(),
			|_| [Engine.environment({})],
			Engine.react(
				|_, result| match result {
					Ok(sample) => [cells.sampled.set(Device.summary(sample)), cells.online.set(sample.online)]
					_ => []
				},
			),
		),
	])
}

main : Engine.Program
main = Device.program

expect local_time(Engine.utc, 0, Bool.True) == "00:00:00"
expect local_time(Engine.utc, 13 * 3_600_000 + 5 * 60_000 + 9_000, Bool.False) == "1:05:09 PM"
expect local_time(Engine.utc, -1000, Bool.True) == "23:59:59"
