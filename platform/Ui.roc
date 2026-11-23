import engine.Engine exposing [Attr, Click, Elem, FilesHandler, Handler, HandlerOf, Signal, Window]

## The native vocabulary apps build Android screens from. Each builder is one
## engine element that the Kotlin host (`ViewRenderer.kt`) maps to one View:
##
## | Builder | Element | View |
## |---|---|---|
## | `column`, `column_with`, `list` | `ui-column`, `ui-list` | vertical `LinearLayout` |
## | `row`, `row_with` | `ui-row` | horizontal `LinearLayout` |
## | `card`, `card_with` | `ui-card` | vertical `LinearLayout` on a rounded surface |
## | `scroll` | `ui-scroll` | vertical `ScrollView`; reports scroll and size |
## | `rows` | `ui-list row-height=…` | fixed-height rows, a window of a longer list |
## | `spacer` | `ui-spacer` | empty `View` taking the spare space |
## | `divider` | `ui-divider` | hairline `View` |
## | `title`, `text`, `text_s`, `warning`, `label` | `ui-title`, `ui-text` | `TextView` |
## | `button`, `button_s` | `ui-button` | `Button` |
## | `text_field`, `text_field_with` | `input` | `EditText` |
## | `checkbox` | `input type=checkbox` | `CheckBox` |
## | `link` | `ui-link href=…` | link-styled `TextView` |
## | `switch` | `input type=checkbox variant=switch` | `Switch` |
## | `slider` | `input type=range` | `SeekBar` |
## | `file_picker`, `file_picker_with` | `input type=file` | `Button` opening the system file chooser |
##
## Attributes the renderer reads: `fill` (take the spare width in a row, or
## height in a column), `pad` (padding in dp), `selected` (a highlighted
## surface), `tone`, `size` and `bold` on text, `hint`, `action` and `type`
## on a field, `description` (`contentDescription`), `hidden`, `disabled`.
## An element with a click handler (`Engine.on_click`) is tappable.
##
## Sizes are dp, Android's CSS pixel, and so are the `Layout.Scroll` and
## `Layout.Size` reports a `scroll` sends (`Engine.on_scroll`,
## `Engine.on_size`). The page scrolls as a whole until a `scroll` is on
## screen; then the `scroll` owns scrolling and takes its column's spare
## height (wrap it only in columns marked `fill`).
##
## The engine's controlled value and checked properties belong to `input`,
## so the controls keep that element name; nothing else is HTML.
Ui := [].{

	# ---- layout ------------------------------------------------------------

	## Children stacked vertically.
	column : List(Elem(ctx)) -> Elem(ctx)
	column = |kids| Engine.element("ui-column", [], kids)

	## A column with attributes (`Ui.fill`, `Ui.pad`, `Engine.on_click`, …).
	column_with : List(Attr(ctx)), List(Elem(ctx)) -> Elem(ctx)
	column_with = |attrs, kids| Engine.element("ui-column", attrs, kids)

	## Children side by side; plain text in a row takes the spare width.
	row : List(Elem(ctx)) -> Elem(ctx)
	row = |kids| Engine.element("ui-row", [], kids)

	## A row with attributes: a tappable row is `row_with([Engine.on_click(h)], …)`.
	row_with : List(Attr(ctx)), List(Elem(ctx)) -> Elem(ctx)
	row_with = |attrs, kids| Engine.element("ui-row", attrs, kids)

	## A vertical list of rows with a hairline between them, typically an
	## `Engine.table_view` or `Engine.each` drawing every row.
	list : List(Elem(ctx)) -> Elem(ctx)
	list = |kids| Engine.element("ui-list", [], kids)

	## A group of children on a rounded surface, padded.
	card : List(Elem(ctx)) -> Elem(ctx)
	card = |kids| Engine.element("ui-card", [], kids)

	## A card with attributes.
	card_with : List(Attr(ctx)), List(Elem(ctx)) -> Elem(ctx)
	card_with = |attrs, kids| Engine.element("ui-card", attrs, kids)

	## A vertically scrolling column. With `Engine.on_scroll` it reports
	## its position (`{ top, left }`, dp) and with `Engine.on_size` its
	## viewport (`{ width, height }`, dp), each at most once per frame.
	## Name it with `Engine.ref` for `ScrollTo` and `scroll_into_view`.
	scroll : List(Attr(ctx)), List(Elem(ctx)) -> Elem(ctx)
	scroll = |attrs, kids| Engine.element("ui-scroll", attrs, kids)

	## A window of a long list of rows `row_height` dp tall: `total` rows
	## in all, of which the children draw the ones from `window.start` on
	## (an `Engine.table_view` over the same `window`). The list is as
	## tall as every row, so a `scroll` around it scrolls the whole list.
	rows : { row_height : U32, total : Signal(ctx, U64), window : Signal(ctx, Window) }, List(Elem(ctx)) -> Elem(ctx)
	rows = |{ row_height, total, window: drawn }, kids| {
		h = row_height.to_u64()
		Engine.element(
			"ui-list",
			[
				Engine.attr("row-height", row_height.to_str()),
				Engine.attr_s("extent", total.map(|n| (n * h).to_str())),
				Engine.attr_s("offset", drawn.map2(total, |w, n| (w.start.min(n) * h).to_str())),
			],
			kids,
		)
	}

	## The window of fixed-height rows a viewport shows: from the row at
	## scroll position `top` through the viewport's `height`, plus a row
	## either side, kept within `total` rows.
	window : { top : U32, height : U32, row_height : U32, total : U64 } -> Window
	window = |{ top, height, row_height, total }| {
		h = row_height.max(1).to_u64()
		count = height.to_u64() // h + 2
		first = top.to_u64() // h
		last_start = if (total > count) total - count else 0
		{ start: first.min(last_start), count }
	}

	## Empty space that takes the spare room of its row or column.
	spacer : {} -> Elem(ctx)
	spacer = |_| Engine.element("ui-spacer", [], [])

	## A hairline across a column (or down a row).
	divider : {} -> Elem(ctx)
	divider = |_| Engine.element("ui-divider", [], [])

	## Take the spare width in a row, or the spare height in a column.
	fill : {} -> Attr(ctx)
	fill = |_| Engine.attr("fill", "")

	## Padding on every side, in dp.
	pad : U32 -> Attr(ctx)
	pad = |dp| Engine.attr("pad", dp.to_str())

	## Highlight a row, column or card while `on` is true.
	selected : Signal(ctx, Bool) -> Attr(ctx)
	selected = |on| Engine.attr_presence("selected", on.map(|is| if (is) Present("") else Absent))

	# ---- text --------------------------------------------------------------

	## A screen title.
	title : Str -> Elem(ctx)
	title = |label_text| Engine.element("ui-title", [], [Engine.text(label_text)])

	## Body text.
	text : Str -> Elem(ctx)
	text = |label_text| Engine.element("ui-text", [], [Engine.text(label_text)])

	## Body text that follows a signal.
	text_s : Signal(ctx, Str) -> Elem(ctx)
	text_s = |label_text| Engine.element("ui-text", [], [Engine.text_s(label_text)])

	## Text drawn in the warning colour.
	warning : Str -> Elem(ctx)
	warning = |label_text| Engine.element("ui-text", [Engine.attr("tone", "warning")], [Engine.text(label_text)])

	## Text that follows a signal, styled by `tone`, `size` and `bold`.
	label : List(Attr(ctx)), Signal(ctx, Str) -> Elem(ctx)
	label = |attrs, label_text| Engine.element("ui-text", attrs, [Engine.text_s(label_text)])

	## The colour of text.
	Tone : [Muted, Accent, Warning, Danger]

	tone : Tone -> Attr(ctx)
	tone = |t| Engine.attr(
		"tone",
		match t {
			Muted => "muted"
			Accent => "accent"
			Warning => "warning"
			Danger => "danger"
		},
	)

	## The size of text: 14, 18 (body), 22 and 28 sp.
	Size : [Small, Body, Large, Headline]

	size : Size -> Attr(ctx)
	size = |s| Engine.attr(
		"size",
		match s {
			Small => "small"
			Body => "body"
			Large => "large"
			Headline => "headline"
		},
	)

	## Bold text.
	bold : {} -> Attr(ctx)
	bold = |_| Engine.attr("bold", "")

	## The accessible description of an element (`contentDescription`).
	describe : Signal(ctx, Str) -> Attr(ctx)
	describe = |label_text| Engine.attr_s("description", label_text)

	# ---- controls ------------------------------------------------------------

	## A button with a fixed label.
	button : Str, HandlerOf(ctx, Click) -> Elem(ctx)
	button = |label_text, h| Engine.element("ui-button", [Engine.on_click(h)], [Engine.text(label_text)])

	## A button whose label follows a signal.
	button_s : Signal(ctx, Str), HandlerOf(ctx, Click) -> Elem(ctx)
	button_s = |label_text, h| Engine.element("ui-button", [Engine.on_click(h)], [Engine.text_s(label_text)])

	## A one-line text field whose text follows `value`. `on_change` gets the
	## text on every edit; `on_done` runs when the keyboard's action key is
	## pressed (the event `done`).
	text_field : { hint : Str, value : Signal(ctx, Str), on_change : Handler(ctx), on_done : Handler(ctx) } -> Elem(ctx)
	text_field = |{ hint, value, on_change, on_done: done }| Engine.element(
		"input",
		[Engine.attr("type", "text"), Engine.attr("hint", hint), Engine.prop_s(Engine.Prop.value, value), Engine.on("input", on_change), Engine.on("done", done)],
		[],
	)

	## What a field holds, which picks the keyboard.
	Field : [Text, Number, Email, Phone, Password]

	## A one-line text field with attributes: `Ui.on_done`, `Ui.on_focus`,
	## `Ui.on_blur`, `Ui.action`, `Engine.ref` (for `Focus`, `Blur`,
	## `SelectText` and `ApplyValue`) and `Engine.on_key` (Enter, Escape and
	## arrows from a hardware keyboard; the keyboard's action key is Enter).
	## While the field has focus a changed `value` waits until it loses it,
	## unless the app asks for `ApplyValue`.
	text_field_with : List(Attr(ctx)), { hint : Str, kind : Field, value : Signal(ctx, Str), on_change : Handler(ctx) } -> Elem(ctx)
	text_field_with = |attrs, { hint, kind, value, on_change }| {
		input_type = match kind {
			Text => "text"
			Number => "number"
			Email => "email"
			Phone => "tel"
			Password => "password"
		}
		Engine.element(
			"input",
			[Engine.attr("type", input_type), Engine.attr("hint", hint), Engine.prop_s(Engine.Prop.value, value), Engine.on("input", on_change)].concat(attrs),
			[],
		)
	}

	## Runs when the keyboard's action key is pressed in a field; the payload is its text.
	on_done : Handler(ctx) -> Attr(ctx)
	on_done = |h| Engine.on("done", h)

	## Runs when the element or a descendant takes focus; the payload is the control's text.
	on_focus : Handler(ctx) -> Attr(ctx)
	on_focus = |h| Engine.on("focus", h)

	## Runs when the element or a descendant loses focus; the payload is the control's text.
	on_blur : Handler(ctx) -> Attr(ctx)
	on_blur = |h| Engine.on("blur", h)

	## The keyboard's action key.
	Action : [Done, Search, Go, Next, Send]

	action : Action -> Attr(ctx)
	action = |a| Engine.attr(
		"action",
		match a {
			Done => "done"
			Search => "search"
			Go => "go"
			Next => "next"
			Send => "send"
		},
	)

	## A checkbox whose state follows `checked`; `h` gets the new state.
	checkbox : Signal(ctx, Bool), List(Attr(ctx)), HandlerOf(ctx, Bool) -> Elem(ctx)
	checkbox = |checked, attrs, h| Engine.element("input", [Engine.attr("type", "checkbox"), Engine.prop_s(Engine.Prop.checked, checked), Engine.on_checked(h)].concat(attrs), [])

	## A link to `url` (`ui-link`, link-styled text). A tap sends `h` the
	## click with the link's facts, `link: Link({ href: url, .. })`, as the
	## browser does for an `a`; navigating is the app's decision: push the URL
	## (`Engine.command(Push(url))`) and show its page. Nothing happens by
	## default.
	link : Str, Str, HandlerOf(ctx, Click) -> Elem(ctx)
	link = |link_text, url, h| Engine.element("ui-link", [Engine.attr("href", url), Engine.on_click(h)], [Engine.text(link_text)])

	## A labelled on/off switch whose state follows `on`; `h` gets the new state.
	switch : Str, Signal(ctx, Bool), HandlerOf(ctx, Bool) -> Elem(ctx)
	switch = |label_text, on, h| Engine.element("input", [Engine.attr("type", "checkbox"), Engine.attr("variant", "switch"), Engine.attr("label", label_text), Engine.prop_s(Engine.Prop.checked, on), Engine.on_checked(h)], [])

	## A slider over the whole numbers `min` to `max`, following `value`.
	## `on_change` gets the number as text while it is dragged.
	slider : { min : I64, max : I64, value : Signal(ctx, I64), on_change : Handler(ctx) } -> Elem(ctx)
	slider = |{ min, max, value, on_change }| Engine.element(
		"input",
		[Engine.attr("type", "range"), Engine.attr("min", min.to_str()), Engine.attr("max", max.to_str()), Engine.prop_s(Engine.Prop.value, value.map(|n| n.to_str())), Engine.on("input", on_change)],
		[],
	)

	## A button labelled `label` that opens the system's file chooser
	## (`ACTION_OPEN_DOCUMENT`) for one file of any type. `h` receives the
	## chosen file as a handle (`Engine.Received`, as from a file input's
	## `change`), never its bytes; a cancelled chooser delivers nothing.
	file_picker : Str, FilesHandler(ctx) -> Elem(ctx)
	file_picker = |label_text, h| Ui.file_picker_with({ label: label_text, types: "", multiple: Bool.False }, h)

	## As `file_picker`, offering the media types in `types` first (a comma
	## separated list such as `"text/*,application/pdf"`, `""` for any) and,
	## when `multiple` is true, letting the user choose several files. The
	## types are a hint to the chooser, as `accept` is to a file input.
	file_picker_with : { label : Str, types : Str, multiple : Bool }, FilesHandler(ctx) -> Elem(ctx)
	file_picker_with = |{ label: label_text, types, multiple }, h| {
		base = [Engine.attr("type", "file"), Engine.attr("label", label_text), Engine.on_files(Change, h)]
		with_types = if (types == "") base else base.append(Engine.attr("accept", types))
		Engine.element("input", if (multiple) with_types.append(Engine.attr("multiple", "")) else with_types, [])
	}
}

expect Ui.window({ top: 0, height: 560, row_height: 56, total: 10000 }) == { start: 0, count: 12 }
expect Ui.window({ top: 5600, height: 560, row_height: 56, total: 10000 }) == { start: 100, count: 12 }
expect Ui.window({ top: 559440, height: 560, row_height: 56, total: 10000 }) == { start: 9988, count: 12 }
expect Ui.window({ top: 4000, height: 560, row_height: 56, total: 5 }) == { start: 0, count: 12 }
