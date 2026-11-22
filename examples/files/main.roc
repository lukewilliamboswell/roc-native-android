app [main] { pf: platform "../../platform/main.roc" }

import pf.Engine exposing [File]
import pf.Ui

## Files: choose a file with the system chooser, see its name, size and
## type, a preview of its text and its SHA-256, and save a copy.
##
## The chosen file is a handle in the store, never its bytes. Two resource
## sites follow it: one reads the first bytes into the page as text (FX 11),
## the other asks for the digest (FX 13), which the host computes without
## bringing the bytes into Roc. "Save a copy" posts the file to an outbox, and
## an `on_post` site issues the SAVE command in the click's turn. Choosing
## another file drops the store's hold on the old one: Roc releases its
## handle, and the host lets the file go.
Picked := [Picked].{

	## What the user has chosen.
	Choice : [Nothing, Chosen(File)]

	nothing : Choice
	nothing = Nothing

	store = Engine.store(
		Picked.Picked,
		{
			chosen: Engine.cell(Picked.nothing),
			saves: Engine.cell(Engine.outbox({})),
		}.Engine.Cells,
	)

	cells = Picked.store.cells()

	## The chooser answered: the first file becomes the chosen one.
	chose : Engine.Received -> List(Engine.Write(_))
	chose = |{ files, refused: _ }| match files {
		[first, ..] => [Picked.cells.chosen.set(Chosen(first))]
		[] => []
	}

	## "Save a copy": post the chosen file for the save site to send.
	save : Picked.Choice -> List(Engine.Write(_))
	save = |choice| match choice {
		Chosen(file) => [Picked.cells.saves.update(|box| box.post(file))]
		Nothing => []
	}
}

## How many bytes the preview reads.
preview_bytes : U64
preview_bytes = 512

about : Picked.Choice -> Str
about = |choice| match choice {
	Nothing => "No file chosen yet."
	Chosen(file) => {
		kind = if file.mime() == "" "unknown type" else file.mime()
		"${file.name()}\n${file.size().to_str()} bytes, ${kind}"
	}
}

failure : Engine.FileError -> Str
failure = |error| match error {
	Unreadable => "the file could not be read"
	TooLarge => "the file is too large"
	InvalidUtf8 => "not UTF-8 text"
	Unavailable => "not available here"
	Refused => "too many requests in flight"
	OutsideFile => "outside the file"
}

hex_digit : U32 -> Str
hex_digit = |d| ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"].get(d.to_u64()) ?? "?"

## Two lower-case hex digits per byte.
hex : List(U8) -> Str
hex = |bytes| bytes.fold("", |acc, b| Str.concat(Str.concat(acc, hex_digit(b.to_u32().shr_zf_wrap(4))), hex_digit(b.to_u32().bitwise_and(15))))

## The file's first bytes, read into the page as text.
preview : {} -> Engine.Elem(ctx)
	where [ctx.store : () -> Engine.Reach(ctx, _)]
preview = |{}| Engine.resource(
	Picked.cells.chosen.signal(),
	{
		init: "",
		pending: |_, choice| match choice {
			Nothing => ""
			Chosen(_) => "Reading…"
		},
		plan: |choice| match choice {
			Chosen(file) =>
				match file.slice({ start: 0, len: file.size().min(preview_bytes) }) {
					Ok(head) => [head.read_utf8({})]
					Err(_) => []
				}
			Nothing => []
		},
		resolve: |_, _, res| match res {
			Ok(text) => if text == "" "(empty file)" else text
			Err(e) => "(no preview: ${failure(e)})"
		},
	},
	|text| Ui.text_s(text),
)

## The file's SHA-256, computed by the host.
digest : {} -> Engine.Elem(ctx)
	where [ctx.store : () -> Engine.Reach(ctx, _)]
digest = |{}| Engine.resource(
	Picked.cells.chosen.signal(),
	{
		init: "",
		pending: |_, choice| match choice {
			Nothing => ""
			Chosen(_) => "SHA-256: …"
		},
		plan: |choice| match choice {
			Chosen(file) => [file.sha256()]
			Nothing => []
		},
		resolve: |_, _, res| match res {
			Ok(bytes) => "SHA-256: ${hex(bytes)}"
			Err(e) => "SHA-256: ${failure(e)}"
		},
	},
	|text| Ui.text_s(text),
)

## A URL for the file (on Android its content:// URI).
link : {} -> Engine.Elem(ctx)
	where [ctx.store : () -> Engine.Reach(ctx, _)]
link = |{}| Engine.resource(
	Picked.cells.chosen.signal(),
	{
		init: "",
		pending: |_, _| "",
		plan: |choice| match choice {
			Chosen(file) => [file.object_url()]
			Nothing => []
		},
		resolve: |_, _, res| match res {
			Ok(url) => "URL: ${url}"
			Err(e) => "URL: ${failure(e)}"
		},
	},
	|text| Ui.text_s(text),
)

view : Engine.Boot -> Engine.Elem(Engine.StoreRoot(_))
view = |_boot| {
	chosen = Picked.cells.chosen.signal()
	Ui.column(
		[
			Ui.title("Files"),
			Ui.row(
				[
					Ui.file_picker("Choose a file", Engine.handle(Picked.chose)),
					Ui.button("Save a copy", Engine.handle_by(chosen, |choice, _| Picked.save(choice))),
				],
			),
			Ui.text_s(chosen.map(about)),
			digest({}),
			link({}),
			preview({}),
			Engine.on_post(
				Picked.cells.saves.signal(),
				|file| [Engine.command(File.save(file, { name: Named("Copy of ${file.name()}") }))],
				Engine.ignore({}),
			),
		],
	)
}

main : Engine.Program
main = Engine.program_store({}, |_boot| Picked.store, view)

expect hex([0, 15, 16, 255]) == "000f10ff"
expect about(Picked.nothing) == "No file chosen yet."
