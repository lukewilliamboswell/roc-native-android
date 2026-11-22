app [main] { pf: platform "../../platform/main.roc" }

import pf.Engine exposing [Elem, In, Root, State]
import pf.Ui

## A counter: one piece of local state and three buttons that change it.
Ctx : In(Root, I64)

main : Engine.Program
main = Engine.program_startup({}, Engine.entries({ fallback: Some("/") }, [{ path: "/", route: 0.U8 }]), |_route| Engine.state(0, view))

view : State(Ctx, I64) -> Elem(Ctx)
view = |count|
	Ui.column([
		Ui.title("Counter"),
		Ui.text_s(count.signal().map(|n| "Count: ${n.to_str()}")),
		Ui.row([
			Ui.button("-", count.update(|n| n - 1)),
			Ui.button("+", count.update(|n| n + 1)),
			Ui.button("Reset", count.update(|_| 0)),
		]),
		Engine.when(count.signal().map(|n| n < 0), || Ui.warning("Below zero!"), || Engine.text("")),
	])
