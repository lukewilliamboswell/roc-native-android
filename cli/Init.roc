import cli.Path
import cli.Stdout
import Bundle

## `init [DIR]`: create an app, a counter, in `DIR` (by default, this
## directory). It writes only files that are not there yet, and copies this
## directory's `android.roc` into a new directory so the app can run the
## tool from its own.
Init :: [].{

	## What `init` writes: a file's path in the app, and its text or the
	## file it is copied from.
	Step : { path : Str, contents : [Text(Str), CopyOf(Str)] }

	## Every file `init` writes into `dir`. `main.roc` names the release's
	## platform.
	plan : Str -> List(Step)
	plan = |dir| {
		tool = if (dir == ".") [] else [{ path: "android.roc", contents: CopyOf("android.roc") }]
		tool
			.append({ path: "main.roc", contents: Text(Init.with_platform(Bundle.scaffold_main, Bundle.platform_url)) })
			.append({ path: ".gitignore", contents: Text(gitignore) })
	}

	## Create the app, after checking that no file it writes already exists
	## and, for a new directory, that there is an `android.roc` here to copy.
	run! : Str => Try({}, _)
	run! = |dir| {
		steps = Init.plan(dir)
		for step in steps {
			target = Init.place(dir, step.path)
			if Path.exists!(Path.utf8(target))? {
				return Err(InitWouldOverwrite(target))
			}
		}
		if dir != "." and !(Path.exists!(Path.utf8("android.roc"))?) {
			return Err(NoToolToCopy(dir))
		}
		if dir != "." {
			Path.create_all!(Path.utf8(dir))?
		}
		for step in steps {
			target = Init.place(dir, step.path)
			match step.contents {
				Text(text) => Path.write_utf8!(Path.utf8(target), text)?
				CopyOf(source) => Path.copy!(Path.utf8(source), Path.utf8(target))?
			}
		}
		Stdout.line!(Init.summary(dir))?
		Ok({})
	}

	## Where a file of the app goes when the app is created in `dir`.
	place : Str, Str -> Str
	place = |dir, path| if (dir == ".") path else "${dir.drop_suffix("/")}/${path}"

	## A `main.roc` naming `url` as its platform in place of whatever its
	## header named.
	with_platform : Str, Str -> Str
	with_platform = |source, url|
		match source.split_on("platform \"") {
			[before, rest, ..] =>
				match rest.split_on("\"") {
					[_, .. as after] => "${before}platform \"${url}\"${Str.join_with(after, "\"")}"
					_ => source
				}
			_ => source
		}

	## What `init` prints: what it made, and what to do next.
	summary : Str -> Str
	summary = |dir| {
		next = if (dir == ".") "  roc android.roc run" else "  cd ${dir}\n  roc android.roc run"
		"Created a counter app on roc-native-android ${Bundle.version}:\n\n  main.roc     the app: its state, view and program\n  android.roc  the tool, which builds and runs it\n  .gitignore   keeps build output out of version control\n\nNext, start an arm64 emulator (or connect a phone) and run the app:\n\n${next}"
	}
}

gitignore =
	\\# Written by `roc android.roc`: the build directory and the APK.
	\\/.android/
	\\/*.apk
	\\

## The scaffold names the release's platform, not a path.
expect {
	source = "app [main] { pf: platform \"../../platform/main.roc\" }\n\nimport pf.Engine\n"
	Init.with_platform(source, "https://x/0.0.1/h.tar.zst") == "app [main] { pf: platform \"https://x/0.0.1/h.tar.zst\" }\n\nimport pf.Engine\n"
}

## A new directory gets a copy of the tool; this directory already has it.
expect [Init.plan("my-app").map(|step| step.path), Init.plan(".").map(|step| step.path)] == [["android.roc", "main.roc", ".gitignore"], ["main.roc", ".gitignore"]]

expect [Init.place("my-app/", "main.roc"), Init.place(".", "main.roc")] == ["my-app/main.roc", "main.roc"]
