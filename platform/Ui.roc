import Engine exposing [Attr, Click, Elem, Handler, HandlerOf, Signal]

## The native vocabulary apps build Android screens from. Each builder is one
## engine element that the Kotlin host maps to one View:
##
## | Builder | Element | View |
## |---|---|---|
## | `column`, `list` | `ui-column`, `ui-list` | vertical `LinearLayout` |
## | `row` | `ui-row` | horizontal `LinearLayout` |
## | `title`, `text`, `text_s`, `warning` | `ui-title`, `ui-text` | `TextView` |
## | `button`, `button_s` | `ui-button` | `Button` |
## | `text_field` | `input` | `EditText` |
## | `checkbox` | `input type=checkbox` | `CheckBox` |
##
## The engine's controlled value and checked properties belong to `input`,
## so the two controls keep that element name; nothing else is HTML.
Ui := [].{

	## Children stacked vertically.
	column : List(Elem(ctx)) -> Elem(ctx)
	column = |kids| Engine.element("ui-column", [], kids)

	## Children side by side; plain text in a row takes the spare width.
	row : List(Elem(ctx)) -> Elem(ctx)
	row = |kids| Engine.element("ui-row", [], kids)

	## A vertical list of rows, typically an `Engine.table_view` or `Engine.each`.
	list : List(Elem(ctx)) -> Elem(ctx)
	list = |kids| Engine.element("ui-list", [], kids)

	## A screen title.
	title : Str -> Elem(ctx)
	title = |label| Engine.element("ui-title", [], [Engine.text(label)])

	## Body text.
	text : Str -> Elem(ctx)
	text = |label| Engine.element("ui-text", [], [Engine.text(label)])

	## Body text that follows a signal.
	text_s : Signal(ctx, Str) -> Elem(ctx)
	text_s = |label| Engine.element("ui-text", [], [Engine.text_s(label)])

	## Text drawn in the warning colour.
	warning : Str -> Elem(ctx)
	warning = |label| Engine.element("ui-text", [Engine.attr("tone", "warning")], [Engine.text(label)])

	## A button with a fixed label.
	button : Str, HandlerOf(ctx, Click) -> Elem(ctx)
	button = |label, h| Engine.element("ui-button", [Engine.on_click(h)], [Engine.text(label)])

	## A button whose label follows a signal.
	button_s : Signal(ctx, Str), HandlerOf(ctx, Click) -> Elem(ctx)
	button_s = |label, h| Engine.element("ui-button", [Engine.on_click(h)], [Engine.text_s(label)])

	## The accessible description of an element (`contentDescription`).
	describe : Signal(ctx, Str) -> Attr(ctx)
	describe = |label| Engine.attr_s("description", label)

	## A one-line text field whose text follows `value`. `on_change` gets the
	## text on every edit; `on_done` runs when the keyboard's action key is
	## pressed (the event `done`).
	text_field : { hint : Str, value : Signal(ctx, Str), on_change : Handler(ctx), on_done : Handler(ctx) } -> Elem(ctx)
	text_field = |{ hint, value, on_change, on_done }| Engine.element(
		"input",
		[Engine.attr("type", "text"), Engine.attr("hint", hint), Engine.prop_s(Engine.Prop.value, value), Engine.on("input", on_change), Engine.on("done", on_done)],
		[],
	)

	## A checkbox whose state follows `checked`; `h` gets the new state.
	checkbox : Signal(ctx, Bool), List(Attr(ctx)), HandlerOf(ctx, Bool) -> Elem(ctx)
	checkbox = |checked, attrs, h| Engine.element("input", [Engine.attr("type", "checkbox"), Engine.prop_s(Engine.Prop.checked, checked), Engine.on_checked(h)].concat(attrs), [])
}
