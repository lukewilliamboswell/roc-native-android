app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Elem, Signal, State, Table]
import pf.Ui

## Contacts: 10 000 generated people in a table, drawn as a window of
## fixed-height rows inside a scroll container. The window follows the
## scroll position and the viewport the container reports (`on_scroll`,
## `on_size`), so a dozen rows are drawn however far the list scrolls. A
## search field filters the list as you type; tapping a row shows that
## contact in the card above the list.
##
## The search field shows the text-input fidelity of the platform: focus
## and blur handlers, a keymap (Escape clears the search, the arrows move
## the selection), and commands on named references (`Focus`, `Blur`,
## `ApplyValue` to replace the text while the field has focus, `ScrollTo`
## to keep the selection in view).
Contacts := [Book].{
	Contact : { given : Str, surname : Str, phone : Str, email : Str, city : Str, search : Str, favourite : Bool }

	row_height : U32
	row_height = 80

	store = Engine.store(
		Contacts.Book,
		{
			contacts: Engine.cell(Table.empty({})),
			query: Engine.cell(""),
			top: Engine.cell(0.U32),
			viewport: Engine.cell(600.U32),
			selected: Engine.cell(None),
			searching: Engine.cell(Bool.False),
			commands: Engine.cell(Engine.outbox({})),
		}.Engine.Cells,
	)

	cells = Contacts.store.cells()

	# ---- the generated book ------------------------------------------------

	count : U64
	count = 10000

	surname_heads = ["Ash", "Bel", "Cor", "Dun", "Ell", "Fen", "Gar", "Hal", "Ive", "Kin"]
	surname_tails = ["by", "croft", "den", "field", "ford", "ham", "ley", "more", "ton", "wood"]
	given_heads = ["Al", "Be", "Ca", "Da", "El", "Fi", "Gu", "Ha", "Iv", "Jo"]
	given_tails = ["ba", "den", "ice", "lia", "na", "ra", "ron", "sa", "tor", "x"]
	cities = ["Auckland", "Bergen", "Cork", "Dunedin", "Exeter", "Fresno", "Galway", "Hobart"]

	part : List(Str), U64 -> Str
	part = |names, index| names.get(index) ?? ""

	lower : Str -> Str
	lower = |text| Str.from_utf8(text.to_utf8().map(|byte| if (byte >= 'A' and byte <= 'Z') byte + 32 else byte)) ?? text

	pad4 : U64 -> Str
	pad4 = |n| {
		text = n.to_str()
		if (n < 10) "000${text}" else if (n < 100) "00${text}" else if (n < 1000) "0${text}" else text
	}

	## The contact under `key`. Keys run in surname-then-given-name order,
	## so the view sorts by key alone.
	contact : U64 -> Contact
	contact = |key| {
		s = key // 100
		g = key % 100
		surname = "${Contacts.part(Contacts.surname_heads, s // 10)}${Contacts.part(Contacts.surname_tails, s % 10)}"
		given = "${Contacts.part(Contacts.given_heads, g // 10)}${Contacts.part(Contacts.given_tails, g % 10)}"
		{
			given,
			surname,
			phone: "+64 21 ${Contacts.pad4(key)}",
			email: Contacts.lower("${given}.${surname}@example.com"),
			city: Contacts.part(Contacts.cities, (key * 7) % 8),
			search: Contacts.lower("${given} ${surname}"),
			favourite: Bool.False,
		}
	}

	start : Engine.Boot -> Engine.Store(_, _)
	start = |_boot| {
		var $rows = List.with_capacity(Contacts.count)
		for key in 0..<Contacts.count {
			$rows = $rows.append({ key, row: Contacts.contact(key) })
		}
		match Table.from_list($rows) {
			Ok(table) => Contacts.store.with_initial(Contacts.cells.contacts, table)
			Err(DuplicateKey(key)) => crash "Contacts.start: key ${key.to_str()} repeats"
		}
	}

	# ---- search ------------------------------------------------------------

	## What the search matches: the typed text, trimmed and folded.
	needle : Str -> Str
	needle = |query| Contacts.lower(query.trim())

	keeps : Contact, Str -> Bool
	keeps = |c, n| n.is_empty() or c.search.contains(n)

	plan : Str -> Engine.QueryPlan(U64, Contact, (U64, U64))
	plan = |n| Engine.sort_by({ keep: |_, c| Contacts.keeps(c, n), sort: |key, _| key })

	matching : Table(U64, Contact), Str -> U64
	matching = |table, n| table.fold(0, |found, _, c| if (Contacts.keeps(c, n)) found + 1 else found)

	## Where `key` stands among the matches: the matches before it.
	place : Table(U64, Contact), Str, U64 -> U64
	place = |table, n, key| table.fold(0, |found, k, c| if (k < key and Contacts.keeps(c, n)) found + 1 else found)

	## The next match after (`Down`) or before (`Up`) `from`.
	step : Table(U64, Contact), Str, [None, Some(U64)], [Up, Down] -> [None, Some(U64)]
	step = |table, n, from, direction| {
		var $at = match (from, direction) {
			(Some(k), Down) => k + 1
			(Some(k), Up) => if (k == 0) Contacts.count else k - 1
			(None, Down) => 0
			(None, Up) => Contacts.count - 1
		}
		var $found = None
		while $at < Contacts.count and $found == None {
			match table.get($at) {
				Ok(c) if Contacts.keeps(c, n) => {
					$found = Some($at)
				}
				_ => {}
			}
			$at = match direction {
				Down => $at + 1
				Up => if ($at == 0) Contacts.count else $at - 1
			}
		}
		match $found {
			None => from
			found => found
		}
	}

	## Typing filters the list and returns it to the top.
	searched : Str -> List(Engine.Write(_))
	searched = |typed| [
		Contacts.cells.query.set(typed),
		Contacts.cells.top.set(0),
		Contacts.cells.commands.update(|box| box.post([ScrollTo("list", { top: Start, left: Keep, behavior: Instant })])),
	]

	Reading : { table : Table(U64, Contact), query : Str, selected : [None, Some(U64)], top : U32, viewport : U32 }

	reading : {} -> Signal(ctx, Reading)
		where [ctx.store : () -> Engine.Reach(ctx, _)]
	reading = |{}|
		{
			table: Contacts.cells.contacts.signal(),
			query: Contacts.cells.query.signal(),
			selected: Contacts.cells.selected.signal(),
			top: Contacts.cells.top.signal(),
			viewport: Contacts.cells.viewport.signal(),
		}.Signal

	## A key the search field's keymap heard: Escape clears the search
	## (replacing the focused field's text at once), and the arrows move the
	## selection through the matches, scrolling it into view.
	keyed : Reading, Engine.Key -> List(Engine.Write(_))
	keyed = |r, key|
		if key.heard == Engine.Chord.plain("Escape") {
			[
				Contacts.cells.query.set(""),
				Contacts.cells.top.set(0),
				Contacts.cells.commands.update(|box| box.post([ApplyValue("search"), ScrollTo("list", { top: Start, left: Keep, behavior: Instant })])),
			]
		} else {
			n = Contacts.needle(r.query)
			direction = if (key.heard == Engine.Chord.plain("ArrowUp")) Up else Down
			next = Contacts.step(r.table, n, r.selected, direction)
			match next {
				None => []
				Some(k) => {
					h = Contacts.row_height
					y = Contacts.place(r.table, n, k).to_u32_wrap() * h
					scroll =
						if y < r.top {
							[ScrollTo("list", { top: Px(y), left: Keep, behavior: Smooth })]
						} else if y + h > r.top + r.viewport {
							[ScrollTo("list", { top: Px(y + h - r.viewport), left: Keep, behavior: Smooth })]
						} else {
							[]
						}
					[Contacts.cells.selected.set(next), Contacts.cells.commands.update(|box| box.post(scroll))]
				}
			}
		}

	post : List(Engine.Command) -> List(Engine.Write(_))
	post = |commands| [Contacts.cells.commands.update(|box| box.post(commands))]

	search_bar : {} -> Elem(ctx)
		where [ctx.store : () -> Engine.Reach(ctx, _)]
	search_bar = |{}| {
		searching = Contacts.cells.searching.signal()
		Ui.row([
			Ui.text_field_with(
				[
					Ui.fill({}),
					Engine.ref("search"),
					Ui.action(Search),
					Ui.on_focus(Engine.does([Contacts.cells.searching.set(Bool.True)])),
					Ui.on_blur(Engine.does([Contacts.cells.searching.set(Bool.False)])),
					Ui.on_done(Engine.does(Contacts.post([Blur("search")]))),
					Engine.on_key(
						{ keymap: Engine.const(Engine.Keymap.prevent([Engine.Chord.plain("Escape"), Engine.Chord.plain("ArrowDown"), Engine.Chord.plain("ArrowUp")])) },
						Engine.handle_by(Contacts.reading({}), Contacts.keyed),
					),
				],
				{ hint: "Search 10,000 contacts", kind: Text, value: Contacts.cells.query.signal(), on_change: Engine.handle(Contacts.searched) },
			),
			Engine.when(
				searching,
				|| Ui.button("Done", Engine.does(Contacts.post([Blur("search")]))),
				|| Ui.button("Search", Engine.does(Contacts.post([Focus("search")]))),
			),
		])
	}

	# ---- the selected contact ------------------------------------------------

	Shown : [Nobody, Shown(U64, Contact)]

	shown : {} -> Signal(ctx, Shown)
		where [ctx.store : () -> Engine.Reach(ctx, _)]
	shown = |{}|
		{ table: Contacts.cells.contacts.signal(), selected: Contacts.cells.selected.signal() }.Signal.map(
			|{ table, selected }|
				match selected {
					Some(key) =>
						match table.get(key) {
							Ok(c) => Shown(key, c)
							Err(_) => Nobody
						}
					None => Nobody
				},
		)

	field : Shown, (Contact -> Str) -> Str
	field = |s, get|
		match s {
			Shown(_, c) => get(c)
			Nobody => ""
		}

	detail : {} -> Elem(ctx)
		where [ctx.store : () -> Engine.Reach(ctx, _)]
	detail = |{}| {
		s = Contacts.shown({})
		Ui.card([
			Engine.when(
				s.map(|it| it == Nobody),
				|| Ui.label([Ui.tone(Muted)], Engine.const("Tap a contact to see it here.")),
				|| Ui.column([
					Ui.label([Ui.size(Large), Ui.bold({})], s.map(|it| Contacts.field(it, |c| "${c.given} ${c.surname}"))),
					Ui.label([], s.map(|it| Contacts.field(it, |c| c.phone))),
					Ui.label([Ui.tone(Accent)], s.map(|it| Contacts.field(it, |c| c.email))),
					Ui.label([Ui.tone(Muted)], s.map(|it| Contacts.field(it, |c| c.city))),
					Ui.divider({}),
					Ui.switch(
						"Favourite",
						s.map(
							|it| match it {
								Shown(_, c) => c.favourite
								Nobody => Bool.False
							},
						),
						Engine.handle_by(
							Contacts.cells.selected.signal(),
							|selected, on| match selected {
								Some(key) => [Contacts.cells.contacts.update(|table| table.update(key, |c| { ..c, favourite: on }))]
								None => []
							},
						),
					),
				]),
			),
		])
	}

	# ---- the list ------------------------------------------------------------

	row : Signal(r, U64), State(r, Contact) -> Elem(r)
		where [r.store : () -> Engine.Reach(r, _)]
	row = |key, held| {
		c = held.signal()
		Ui.row_with(
			[
				Ui.pad(4),
				Engine.on_click(Engine.handle_by(key, |k, _| [Contacts.cells.selected.set(Some(k))])),
				Ui.selected(key.map2(Contacts.cells.selected.signal(), |k, chosen| chosen == Some(k))),
				Ui.describe(c.map(|x| "${x.given} ${x.surname}")),
			],
			[
				Ui.column_with(
					[Ui.fill({})],
					[
						Ui.label([Ui.size(Body)], c.map(|x| "${x.surname}, ${x.given}")),
						Ui.label([Ui.size(Small), Ui.tone(Muted)], c.map(|x| "${x.phone} · ${x.city}")),
					],
				),
				Ui.label([Ui.tone(Accent), Ui.size(Large)], c.map(|x| if (x.favourite) "★" else "")),
			],
		)
	}

	list : {} -> Elem(ctx)
		where [ctx.store : () -> Engine.Reach(ctx, _)]
	list = |{}| {
		query = Contacts.cells.query.signal().map(Contacts.needle)
		total_of = { table: Contacts.cells.contacts.signal(), n: query }.Signal.map(|{ table, n }| Contacts.matching(table, n))
		Engine.derive(
			total_of,
			|total| {
				window = { top: Contacts.cells.top.signal(), height: Contacts.cells.viewport.signal(), total }.Signal.map(
					|{ top, height, total: t }| Ui.window({ top, height, row_height: Contacts.row_height, total: t }),
				)
				Ui.column_with(
					[Ui.fill({})],
					[
						Ui.label(
							[Ui.size(Small), Ui.tone(Muted)],
							total.map2(window, |t, w| if (t == 0) "No contacts match." else "${t.to_str()} contacts · drawing ${(w.start + 1).to_str()}–${(w.start + w.count).min(t).to_str()}"),
						),
						Ui.scroll(
							[
								Engine.ref("list"),
								Engine.on_scroll(Engine.handle(|at| [Contacts.cells.top.set(at.top)])),
								Engine.on_size(Engine.handle(|size| [Contacts.cells.viewport.set(size.height)])),
							],
							[
								Ui.rows(
									{ row_height: Contacts.row_height, total, window },
									[Engine.table_view(Contacts.cells.contacts, { query: Contacts.cells.query.signal().map(Contacts.needle), plan: Contacts.plan, window }, Contacts.row)],
								),
							],
						),
					],
				)
			},
		)
	}

	view : {} -> Elem(Engine.StoreRoot(_))
	view = |{}|
		Ui.column([
			Ui.title("Contacts"),
			Contacts.search_bar({}),
			Contacts.detail({}),
			Contacts.list({}),
			Engine.on_post(Contacts.cells.commands.signal(), |commands| commands.map(Engine.command), Engine.ignore({})),
		])

	program : Engine.Program
	program = Engine.program_store({}, Contacts.start, |_boot| Contacts.view({}))
}

main : Engine.Program
main = Contacts.program

expect Contacts.contact(0).surname == "Ashby" and Contacts.contact(0).given == "Alba"
expect Contacts.contact(9999).surname == "Kinwood" and Contacts.contact(9999).given == "Jox"
expect Contacts.keeps(Contacts.contact(2), Contacts.needle("  ALICE "))
