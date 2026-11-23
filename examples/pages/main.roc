app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Click, Elem, In, Root, State]
import pf.Ui

## Pages: entry routes pick the first page, links push history entries, and
## the system back button traverses them (a popstate subscription hears it).
## The location is local state; every page is drawn from it.
Model : { url : Str, nav : Engine.Outbox(Str) }

Ctx : In(Root, Model)

## The pages a cold start can open at. A path the catalogue does not hold
## (an item page, say, from a deep link) opens the fallback, home.
Entry : [Home, About, Items]

entries : Engine.Entries(Entry)
entries = Engine.entries({ fallback: Some("/") }, [{ path: "/", route: Home }, { path: "/about", route: About }, { path: "/items", route: Items }])

main : Engine.Program
main = Engine.program_startup(
	{},
	entries,
	|entry| {
		url = (entries.path(entry) ?? crash "internal: every entry is declared").text()
		Engine.state({ url, nav: Engine.outbox({}) }, view)
	},
)

## What a location shows. An item page takes its id from the path.
Page : [Home, About, Items, Item, NotFound]

all_pages : List(Page)
all_pages = [Home, About, Items, Item, NotFound]

## The path segments of a location, without its query or fragment.
segments : Str -> List(Str)
segments = |url| {
	path = match url.split_on("#") {
		[before, ..] => before
		[] => url
	}
	bare = match path.split_on("?") {
		[before, ..] => before
		[] => path
	}
	bare.split_on("/").keep_if(|s| !s.is_empty())
}

page_of : Str -> Page
page_of = |url| match segments(url) {
	[] => Home
	["about"] => About
	["items"] => Items
	["items", _] => Item
	_ => NotFound
}

item_of : Str -> Str
item_of = |url| match segments(url) {
	["items", id] => id
	_ => ""
}

title_of : Str -> Str
title_of = |url| match page_of(url) {
	Home => "Home"
	About => "About"
	Items => "Items"
	Item => "Item ${item_of(url)}"
	NotFound => "Not found"
}

## Follow a link: move to its href and post it for the history push.
follow : Model, Click -> Model
follow = |m, click| match click.link {
	Link({ href, target: _, download: _ }) => { ..m, url: href, nav: m.nav.post(href) }
	NoLink => m
}

view : State(Ctx, Model) -> Elem(Ctx)
view = |model| {
	url = model.signal().map(|m| m.url)
	link = |label, to| Ui.link(label, to, model.set_value(follow))
	Ui.column([
		## Navigation: push what a link posts, hear back, title the page.
		Engine.on_post(model.signal().map(|m| m.nav), |to| [Engine.command(Push(to))], Engine.ignore({})),
		Engine.watch(
			Engine.const({}),
			|_| [Engine.popstate({})],
			model.on_result(
				|m, _, res| match res {
					Ok(to) => { ..m, url: to }
					Err(_) => m
				},
			),
		),
		Engine.watch(url.map(title_of), |t| [Engine.command(Title(t))], Engine.ignore({})),
		Ui.row([link("Home", "/"), link("About", "/about"), link("Items", "/items")]),
		Ui.text_s(url.map(|u| "At ${u}")),
		Engine.switch(
			url.map(page_of),
			all_pages,
			|page| match page {
				Home => Ui.column([Ui.title("Home"), Ui.text("A multi-page app. Follow a link, then press back.")])
				About => Ui.column([Ui.title("About"), Ui.text("Each page is a history entry; back pops it."), link("See the items", "/items")])
				Items => Ui.column([Ui.title("Items"), Ui.list(["1", "2", "3"].map(|id| link("Item ${id}", "/items/${id}")))])
				Item => Ui.column([
					Ui.title("Item"),
					Ui.text_s(url.map(|u| "Item ${item_of(u)}")),
					link("All items", "/items"),
				])
				NotFound => Ui.column([Ui.title("Not found"), Ui.text_s(url.map(|u| "Nothing at ${u}"))])
			},
		),
	])
}
