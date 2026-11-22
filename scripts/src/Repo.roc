## The pure decisions of the scripts: which compiler pins are allowed, which
## Kotlin files the smoke test compiles, what an Android library may link,
## how stages are chosen and how results print.
Repo := [].{

	## The check's stages, in the order a full run takes them.
	stages : List(Str)
	stages = ["pins", "engine", "fmt", "scripts", "build", "test", "smoke", "android", "bundle"]

	## The stages named on the command line, or every stage when none is.
	selection : List(Str) -> Try(List(Str), [Unknown(Str)])
	selection = |args| {
		named = args.drop_if(|arg| arg == "--")
		match named.find_first(|arg| !Repo.stages.contains(arg)) {
			Ok(unknown) => Err(Unknown(unknown))
			Err(_) => Ok(if (named.is_empty()) Repo.stages else Repo.stages.keep_if(|stage| named.contains(stage)))
		}
	}

	## The engine modules, byte-identical copies of roc-signals. They are
	## never edited or reformatted here; `tests/engine-modules.sha256` holds
	## their digests.
	engine_modules : List(Str)
	engine_modules = [
		"Core",
		"Engine",
		"SignalCore",
		"TableCore",
		"View",
		"Seq",
		"Layout",
		"EntryRoutes",
		"ReadyIndex",
		"Url",
		"Spelling",
		"MarkupAdvice",
		"Web",
		"Port",
		"PortJson",
		"PortJsonBody",
		"PortRegistry",
		"Wire",
		"WorkerCodec",
	].map(|name| "platform/${name}.roc")

	## The tag of the `roc-stable` release Blueprint.roc fetches.
	stable_tag : Str -> Try(Str, [NoTag])
	stable_tag = |blueprint|
		match blueprint.split_on("Recipes.roc_nightly(\"") {
			[_, rest, ..] =>
				match rest.split_on("\"").first() {
					Ok(tag) if !tag.is_empty() => Ok(tag)
					_ => Err(NoTag)
				}
			_ => Err(NoTag)
		}

	## The compiler a Roc file's header pins with a `roc: "..."` field.
	header_pin : Str -> Try(Str, [NoPin])
	header_pin = |text| {
		header = text.split_on("\n").take_first(10)
		match header.find_first(|line| line.trim().starts_with("roc: \"")) {
			Ok(line) =>
				match line.split_on("\"") {
					[_, tag, ..] => Ok(tag)
					_ => Err(NoPin)
				}
			Err(_) => Err(NoPin)
		}
	}

	## Whether a Roc file is an application (a script entry point), rather
	## than a module it imports.
	is_app : Str -> Bool
	is_app = |text| text.split_on("\n").any(|line| line.starts_with("app ["))

	## What is wrong with the compiler pins (roc-automation's model): every
	## script under `scripts/` names the tooling compiler, and nothing else
	## names a compiler at all, since the platform and its examples float on
	## the latest nightly.
	pin_problems : Str, List({ path : Str, text : Str }) -> List(Str)
	pin_problems = |tag, files|
		files.keep_oks(
			|{ path, text }| {
				pin = Repo.header_pin(text)
				if path.starts_with("scripts/") and Repo.is_app(text) {
					match pin {
						Ok(found) if found == tag => Err(Fine)
						Ok(found) => Ok("${path} pins roc: \"${found}\"; Blueprint.roc's roc-stable is ${tag}")
						Err(NoPin) => Ok("${path} does not pin roc: \"${tag}\", the roc-stable release Blueprint.roc names")
					}
				} else {
					match pin {
						Ok(found) => Ok("${path} pins roc: \"${found}\" in its header; only the scripts under scripts/ may pin a compiler")
						Err(NoPin) => Err(Fine)
					}
				}
			},
		)

	## The app's Kotlin files the desktop smoke test compiles: those that use
	## no Android API (no `import android...`).
	smoke_sources : List({ path : Str, text : Str }) -> List(Str)
	smoke_sources = |files|
		files
			.drop_if(|{ text, .. }| text.split_on("\n").any(|line| line.starts_with("import android")))
			.map(|{ path, .. }| path)

	## The JNI symbols a Kotlin file's `external fun`s need the host to
	## export: `Java_dev_roc_android_<object>_<function>`.
	jni_natives : Str -> List(Str)
	jni_natives = |text| {
		var $owner = ""
		var $found = []
		for line in text.split_on("\n") {
			trimmed = line.trim()
			match trimmed.split_on("object ") {
				[before, rest, ..] if before == "" or before == "internal " => {
					$owner = rest.split_on(" ").first() ?? ""
				}
				_ => {}
			}
			match trimmed.split_on("external fun ") {
				[_, rest, ..] => {
					name = rest.split_on("(").first() ?? ""
					$found = $found.append("Java_dev_roc_android_${$owner}_${name}")
				}
				_ => {}
			}
		}
		$found
	}

	## What is wrong with an Android library, from `objdump -p` (its program
	## headers and dynamic section) and `nm -D` (undefined, then defined
	## dynamic symbols). The library must load on any Android: it needs no
	## other library, leaves no symbol unresolved, has no thread-local
	## storage, and exports every JNI function the Kotlin side declares.
	library_problems : { headers : Str, undefined : Str, defined : Str, natives : List(Str) } -> List(Str)
	library_problems = |{ headers, undefined, defined, natives }| {
		lines = headers.split_on("\n").map(Str.trim)
		needed = lines.keep_if(|line| line.starts_with("NEEDED")).map(|line| "needs another library: ${line}")
		tls = if (lines.any(|line| line.starts_with("TLS "))) ["has a TLS segment"] else []
		unresolved = undefined.split_on("\n").map(Str.trim).keep_if(|line| !line.is_empty()).map(|line| "leaves a symbol undefined: ${line}")
		exported = defined.split_on("\n").keep_oks(|line| line.trim().split_on(" ").last())
		missing = natives.drop_if(|name| exported.contains(name)).map(|name| "does not export ${name}")
		needed.concat(tls).concat(unresolved).concat(missing)
	}

	## A duration as whole milliseconds with digit separators: `1_204ms`.
	millis : U64 -> Str
	millis = |ms| "${Repo.separated(ms)}ms"

	separated : U64 -> Str
	separated = |n|
		if n < 1000 {
			n.to_str()
		} else {
			rest = n % 1000
			pad = if (rest < 10) "00" else if (rest < 100) "0" else ""
			"${Repo.separated(n // 1000)}_${pad}${rest.to_str()}"
		}

	## One line of a run's progress: its position, its name, its result and
	## its time, right-aligned.
	progress : { index : U64, total : U64, name : Str, outcome : Str, ms : U64 } -> Str
	progress = |{ index, total, name, outcome, ms }| {
		width = total.to_str().count_utf8_bytes()
		position = Repo.left_pad(index.to_str(), width)
		dots = Str.repeat(".", if (name.count_utf8_bytes() < 36) (38 - name.count_utf8_bytes()) else 2)
		" [${position}/${total.to_str()}] ${name} ${dots} ${outcome}${Repo.left_pad(Repo.millis(ms), 10)}"
	}

	left_pad : Str, U64 -> Str
	left_pad = |text, width| {
		len = text.count_utf8_bytes()
		if (len >= width) text else "${Str.repeat(" ", width - len)}${text}"
	}

	compare : Str, Str -> [Before, Same, After]
	compare = |a, b| Repo.compare_bytes(a.to_utf8(), b.to_utf8())

	compare_bytes : List(U8), List(U8) -> [Before, Same, After]
	compare_bytes = |a, b|
		match (a, b) {
			([], []) => Same
			([], _) => Before
			(_, []) => After
			([x, .. as xs], [y, .. as ys]) => if (x < y) Before else if (x > y) After else Repo.compare_bytes(xs, ys)
		}
}

expect Repo.selection([]) == Ok(Repo.stages)
expect Repo.selection(["--", "smoke", "build"]) == Ok(["build", "smoke"])
expect Repo.selection(["bogus"]) == Err(Unknown("bogus"))

expect Repo.engine_modules.len() == 19

expect Repo.stable_tag("x = Fetch(\"roc-stable\", Recipes.roc_nightly(\"nightly-2026-11-14-a87808d\"))") == Ok("nightly-2026-11-14-a87808d")
expect Repo.stable_tag("config = []") == Err(NoTag)

script_text : Str
script_text = "#!/usr/bin/env roc-stable\napp [main!] {\n\tcli: platform \"x\",\n\troc: \"nightly-a\",\n}\n"

expect Repo.header_pin(script_text) == Ok("nightly-a")
expect Repo.header_pin("app [main] { pf: platform \"../../platform/main.roc\" }\n") == Err(NoPin)

## A script pinning the tooling compiler, a module it imports and an
## unpinned example are fine.
expect
	Repo.pin_problems("nightly-a", [{ path: "scripts/check_all.roc", text: script_text }, { path: "scripts/src/Repo.roc", text: "Repo := [].{}\n" }, { path: "examples/counter/main.roc", text: "app [main] { pf: platform \"../../platform/main.roc\" }\n" }])
		== []

## A script on another compiler, and an example that pins one, are not.
expect
	Repo.pin_problems("nightly-b", [{ path: "scripts/check_all.roc", text: script_text }, { path: "examples/counter/main.roc", text: script_text }])
		== [
			"scripts/check_all.roc pins roc: \"nightly-a\"; Blueprint.roc's roc-stable is nightly-b",
			"examples/counter/main.roc pins roc: \"nightly-a\" in its header; only the scripts under scripts/ may pin a compiler",
		]

expect
	Repo.smoke_sources([{ path: "Tree.kt", text: "package dev.roc.android\n\nimport java.util.ArrayDeque\n" }, { path: "ViewRenderer.kt", text: "package dev.roc.android\n\nimport android.view.View\n" }])
		== ["Tree.kt"]

expect
	Repo.jni_natives("package dev.roc.android\n\nobject RocFiles {\n    @JvmStatic external fun nativeStage(n: Int): LongArray\n}\n\ninternal object RocEngine {\n    @JvmStatic external fun nativeDrop()\n}\n")
		== ["Java_dev_roc_android_RocFiles_nativeStage", "Java_dev_roc_android_RocEngine_nativeDrop"]

clean_headers : Str
clean_headers = "Program Header:\n    LOAD off    0x0 vaddr 0x0\n DYNAMIC off    0x162c80\n\nDynamic Section:\n  RELA      0x0000000000000ab0\n"

expect
	Repo.library_problems({ headers: clean_headers, undefined: "", defined: "0000000000019a2c T Java_dev_roc_android_RocEngine_nativeInit\n", natives: ["Java_dev_roc_android_RocEngine_nativeInit"] })
		== []

expect
	Repo.library_problems({ headers: "     TLS off    0x0\nDynamic Section:\n  NEEDED       libc.so\n", undefined: "                 U malloc\n", defined: "", natives: ["Java_dev_roc_android_RocEngine_nativeInit"] })
		== [
			"needs another library: NEEDED       libc.so",
			"has a TLS segment",
			"leaves a symbol undefined: U malloc",
			"does not export Java_dev_roc_android_RocEngine_nativeInit",
		]

expect (Repo.millis(12), Repo.millis(1204), Repo.millis(1002003)) == ("12ms", "1_204ms", "1_002_003ms")

expect Repo.progress({ index: 3, total: 20, name: "counter", outcome: "PASS", ms: 1204 }) == " [ 3/20] counter ............................... PASS   1_204ms"

expect Repo.compare("a", "b") == Before
