app [main] { pf: platform "../../platform/main.roc" }

import pf.Engine
import pf.Ui

## A stopwatch: while `running` is true a watch holds a 100 ms interval
## (FX kind 4); stopping disposes it, and the engine cancels it (CANCEL).
Watch := [Watch].{
	store = Engine.store(
		Watch.Watch,
		{
			running: Engine.cell(Bool.False),
			tenths: Engine.cell(0.U64),
			laps: Engine.cell([]),
		}.Engine.Cells,
	)

	cells = Watch.store.cells()
}

format : U64 -> Str
format = |tenths| {
	seconds = tenths // 10
	minutes = seconds // 60
	secs = seconds % 60
	pad = if secs < 10 "0" else ""
	"${minutes.to_str()}:${pad}${secs.to_str()}.${(tenths % 10).to_str()}"
}

ticker : {} -> Engine.Elem(ctx)
	where [ctx.store : () -> Engine.Reach(ctx, _)]
ticker = |{}| Engine.watch(
	Engine.const({}),
	|_| [Engine.interval(100)],
	Engine.react(
		|_, result| match result {
			Ok(_) => [Watch.cells.tenths.update(|t| t + 1)]
			_ => []
		},
	),
)

view : Engine.Boot -> Engine.Elem(Engine.StoreRoot(_))
view = |_boot| {
	running = Watch.cells.running.signal()
	tenths = Watch.cells.tenths.signal()
	Ui.column(
		[
			Ui.title("Stopwatch"),
			Ui.text_s(tenths.map(format)),
			Ui.row(
				[
					Ui.button_s(running.map(|on| if on "Stop" else "Start"), Engine.handle_by(running, |on, _| [Watch.cells.running.set(!on)])),
					Ui.button("Lap", Engine.handle_by(tenths, |t, _| [Watch.cells.laps.update(|laps| laps.append(t))])),
					Ui.button("Reset", Engine.handle(|_| [Watch.cells.running.set(Bool.False), Watch.cells.tenths.set(0), Watch.cells.laps.set([])])),
				],
			),
			Ui.text_s(Watch.cells.laps.signal().map(|laps| Str.join_with(laps.map_with_index(|t, i| "Lap ${(i + 1).to_str()}: ${format(t)}"), "\n"))),
			Engine.when(running, || ticker({}), || Engine.text("")),
		],
	)
}

main : Engine.Program
main = Engine.program_store({}, |_boot| Watch.store, view)

expect format(0) == "0:00.0"
expect format(634) == "1:03.4"
