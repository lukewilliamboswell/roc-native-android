app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Elem, In, Root, State]
import pf.Ui

## Fetch a JSON document over HTTPS (FX kind 1), showing loading, progress,
## the status, the failure (network, timeout) and a retry. A `watch_latest`
## site issues one request per attempt; Retry starts a new attempt and Cancel
## is a pure cancel, which the engine sends to the host as CANCEL.
Phase : [Loading, Loaded(Engine.Response), Failed(Engine.HttpFailure), Cancelled]

Model : { attempt : U64, live : Bool, phase : Phase, received : Str }

Ctx : In(Root, Model)

todo_url : Str
todo_url = "https://jsonplaceholder.typicode.com/todos/1"

initial : Model
initial = { attempt: 0, live: Bool.True, phase: Loading, received: "" }

main : Engine.Program
main = Engine.program_startup({}, Engine.entries({ fallback: Some("/") }, [{ path: "/", route: 0.U8 }]), |_route| Engine.state(initial, view))

## The `"name": "value"` string field of a JSON body, crudely.
field : Str, Str -> Str
field = |body, name| {
	after = Str.split_on(body, "\"${name}\": \"").get(1) ?? ""
	Str.split_on(after, "\"").get(0) ?? ""
}

header : Engine.Response, Str -> Str
header = |response, name| {
	found = response.headers.keep_if(|(k, _)| k == name)
	match found.first() {
		Ok((_, v)) => v
		Err(_) => ""
	}
}

ok : Engine.Response -> Bool
ok = |r| r.status >= 200 and r.status < 300

status_line : Model -> Str
status_line = |m| match m.phase {
	Loading => "Loading…"
	Loaded(r) => if (ok(r)) "HTTP ${r.status.to_str()} OK" else "HTTP ${r.status.to_str()}: the server refused (try again)"
	Failed(Network(message)) => "Network error: ${message}"
	Failed(Timeout) => "Timed out after 10 s"
	Failed(Refused) => "Refused: too many requests in flight"
	Cancelled => "Cancelled"
}

detail : Model -> Str
detail = |m| match m.phase {
	Loaded(r) =>
		if ok(r) {
			title = field(r.body, "title")
			shown = if (title.is_empty()) "(no title)" else title
			"Todo: ${shown}\nType: ${header(r, "content-type")}"
		} else {
			""
		}
	_ => ""
}

progress_text : Engine.Transfer -> Str
progress_text = |t| match t.total {
	Known(total) => "Received ${t.done.to_str()} of ${total.to_str()} bytes"
	Unknown => "Received ${t.done.to_str()} bytes"
}

view : State(Ctx, Model) -> Elem(Ctx)
view = |model| {
	m = model.signal()
	reaction = model.on_result(
		|s, _key, res|
			match res {
				Ok(response) => { ..s, live: Bool.False, phase: Loaded(response) }
				Err(failure) => { ..s, live: Bool.False, phase: Failed(failure) }
			},
	).with_progress({}, model.on_progress(|s, _key, t| { ..s, received: progress_text(t) }))
	Ui.column([
		Ui.title("HTTP"),
		Ui.text("GET ${todo_url}"),
		Ui.text_s(m.map(status_line)),
		Ui.text_s(m.map(|s| s.received)),
		Ui.text_s(m.map(detail)),
		Ui.row([
			Ui.button("Retry", model.update(|s| { ..s, attempt: s.attempt + 1, live: Bool.True, phase: Loading, received: "" })),
			Ui.button("Cancel", model.update(|s| if (s.live) { ..s, live: Bool.False, phase: Cancelled } else s)),
		]),
		Engine.watch_latest(
			m.map(|s| { attempt: s.attempt, live: s.live }),
			|key| if (key.live) [Engine.http({ url: todo_url, timeout: Ms(10000) })] else [],
			reaction,
		),
	])
}

expect field("{\n  \"id\": 1,\n  \"title\": \"delectus aut autem\"\n}", "title") == "delectus aut autem"
expect field("{}", "title") == ""
