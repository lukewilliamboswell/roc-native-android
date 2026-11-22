// Desktop smoke test of examples/contacts: a windowed table view driven by
// paced scroll and size reports, a search field with focus, keymaps and
// controlled-value deferral, and REF commands. It also checks the pure input
// models (Keymap, TextControl) the Android renderer relies on.

import dev.roc.android.*

fun inputModels() {
    val ctrlK = Keymap.parse("poC---1:kkaC---1:ka*")
    expect("keymap: Ctrl+K heard and prevented outside text", ctrlK.match(KeyInput("k", "KeyK", ctrl = true), textEntry = false)?.prevent == true)
    expect("keymap: in text the kept entry hears it", ctrlK.match(KeyInput("K", "KeyK", ctrl = true), textEntry = true)?.prevent == false)
    expect("keymap: Cyrillic Ctrl+K by its physical key", ctrlK.match(KeyInput("л", "KeyK", ctrl = true), textEntry = true)?.key == "k")
    expect("keymap: every hears the rest, unprevented", ctrlK.match(KeyInput("x", "KeyX"), textEntry = true)?.let { it.key == "x" && !it.prevent } == true)
    val esc = Keymap.parse("pa----6:Escape")
    expect("keymap: Escape, not Shift+Escape", esc.match(KeyInput("Escape", "Escape"), true) != null && esc.match(KeyInput("Escape", "Escape", shift = true), true) == null)
    expect("keymap: Shift does not count for a symbol", Keymap.parse("ka----1:?").match(KeyInput("?", "Slash", shift = true), true) != null)
    expect("keymap: the empty map hears nothing", Keymap.of("").match(KeyInput("a", "KeyA"), false) == null)

    val c = TextControl("draft")
    c.focus()
    c.render("server")
    expect("control: a differing value waits while focused", c.shown == "draft" && c.deferred == "server")
    c.typed("server")
    expect("control: typing that matches it clears the wait", c.deferred == null)
    c.render("again")
    c.blur()
    expect("control: blur applies the deferred value", c.shown == "again")
    c.focus(); c.render("now"); c.applyRendered()
    expect("control: ApplyValue shows the rendered value while focused", c.shown == "now")
}

fun contacts(host: Host, platform: ManualPlatform, renderer: CountingRenderer) {
    inputModels()
    val text = { host.tree.root.textContent() }
    val firstRow = { host.rows().firstOrNull()?.textContent() ?: "" }
    val list = host.find { it.tag == "ui-list" }
    val scroll = host.find { it.tag == "ui-scroll" }
    val input = host.find { it.tag == "input" && it.attr("type") == "text" }

    expect("10000 contacts, a window of 9 rows drawn", text().contains("10000 contacts") && host.rows().size == 9)
    expect("the list is as tall as every row", list.attr("extent") == "800000" && list.attr("offset") == "0")
    expect("first row", firstRow().startsWith("Ashby, Alba"))

    // Size and scroll reports wait for the frame; only the latest scroll counts.
    host.report(scroll, "size", Layout.size(400, 720))
    expect("a report waits for its frame", host.rows().size == 9)
    platform.advance(16)
    expect("size report: 11 rows for a 720 dp viewport", host.rows().size == 11)
    val before = renderer.renders
    host.report(scroll, "scroll", Layout.scroll(100, 0))
    host.report(scroll, "scroll", Layout.scroll(5000, 0))
    host.report(scroll, "scroll", Layout.scroll(80000, 0))
    platform.advance(16)
    expect("three scroll reports, one turn", renderer.renders == before + 1)
    expect("the window follows the scroll: row 1000 first", firstRow().startsWith("Belby, Alba") && list.attr("offset") == "80000" && host.rows().size == 11)
    host.report(scroll, "scroll", Layout.scroll(799280, 0))
    host.flushReports()
    expect("scrolled to the end: the last row is drawn", host.rows().last().textContent().startsWith("Kinwood, Jox") && host.rows().size == 11)

    // Focus and typing; a report waiting before an occurrence is delivered first.
    renderer.focus(input)
    expect("the focus handler ran: Done shown", host.find { it.tag == "ui-button" && it.textContent() in setOf("Done", "Search") }.textContent() == "Done")
    host.report(scroll, "scroll", Layout.scroll(7200, 0))
    host.typed(input, "alice")
    expect("typing filters: 100 matches", text().contains("100 contacts") && firstRow().startsWith("Ashby, Alice"))
    expect("the report came before the typing (back at the top)", list.attr("offset") == "0")
    expect("typing asked to scroll the list to the top", renderer.refs.any { it.op == 7 && it.top.tag == 1 })
    expect("the field shows what was typed", host.shown(input) == "alice" && input.value == "alice")

    // Keymaps: Escape clears with ApplyValue though the field has focus.
    expect("a key no keymap hears costs no turn", host.key(input, "keydown", KeyInput("a", "KeyA")) == null)
    val heard = host.key(input, "keydown", KeyInput("Escape", "Escape"))
    expect("Escape heard and prevented", heard?.prevent == true)
    expect("Escape cleared the search, applied while focused", host.shown(input) == "" && text().contains("10000 contacts") && renderer.refs.any { it.op == 4 })
    host.key(input, "keydown", KeyInput("ArrowDown", "ArrowDown"))
    host.key(input, "keydown", KeyInput("ArrowDown", "ArrowDown"))
    expect("the arrows select the second contact", text().contains("Alden Ashby") && host.rows()[1].attrs.containsKey("selected"))
    host.key(input, "keydown", KeyInput("ArrowUp", "ArrowUp"))
    expect("ArrowUp moves back", text().contains("Alba Ashby") && host.rows()[0].attrs.containsKey("selected"))

    // Tapping a row selects it; the switch writes that row.
    val tapped = host.rows()[3]
    host.dispatch(tapped, "click")
    expect("a row tap shows the contact", text().contains("Allia Ashby") && tapped.attrs.containsKey("selected"))
    val favourite = host.find { it.tag == "input" && it.attr("variant") == "switch" }
    host.dispatch(favourite, "change", "true")
    expect("the switch marks the row a favourite", tapped.textContent().contains("★") && favourite.checked)

    // The keyboard's action key blurs; the Search button focuses again.
    host.dispatch(input, "done", host.shown(input))
    expect("done blurred the field", renderer.focused == null && renderer.refs.any { it.op == 2 })
    host.dispatch(host.button("Search"), "click")
    expect("Search focused the field", renderer.focused === input && host.button("Done").tag == "ui-button")
}
