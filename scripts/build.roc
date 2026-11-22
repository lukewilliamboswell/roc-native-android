#!/usr/bin/env roc-stable
app [main!] {
	cli: platform "https://github.com/roc-lang/basic-cli/releases/download/0.28.1/7brgwR8YUqfeGrkdfF1V4GPPN7DnP7ftqub9KcrTEZtr.tar.zst",
	roc: "nightly-2026-11-14-a87808d",
}

import cli.Cmd
import cli.Env
import cli.OsStr
import cli.Path
import cli.Stderr
import cli.Stdout
import src/Repo

## The `build` task, the contributor build:
##
##     blueprint run build                  # the host, then every example
##     blueprint run build -- counter todos # the host, then the examples named
##     blueprint run build -- --host        # the host only
##
## The host is `zig build` in host/ (host/build.zig, which Gradle's `rocHost`
## task also runs): it installs `platform/targets/<target>/libhost.a` for
## `arm64musl` and `arm64mac`. Each example is then built twice with the
## `roc` on PATH: `build/android/lib<example>.so` for Android, to audit (the
## APK's own copies are built by Gradle), and `build/lib<example>.dylib` for
## the desktop smoke test.
main! : List(OsStr) => Try({}, _)
main! = |raw_args| {
	args = raw_args.map(OsStr.display).drop_if(|arg| arg == "--")
	root = Env.cwd!()?.display()
	step!(
		Cmd.new("zig").args(["build", "--prefix", "${root}/platform/targets", "--cache-dir", "${root}/build/zig-cache"].map(OsStr.from_str)).cwd(Path.utf8("host")),
		"zig build (host/build.zig)",
	)?
	if args.contains("--host") {
		return Ok({})
	}
	present = examples!()?
	chosen = if (args.is_empty()) present else args
	match chosen.find_first(|name| !present.contains(name)) {
		Ok(unknown) => {
			warn!("build: there is no examples/${unknown}/main.roc; the examples are ${Str.join_with(present, ", ")}")
			return Err(Exit(2))
		}
		Err(_) => {}
	}
	Path.create_all!(Path.utf8("build/android"))?
	for example in chosen {
		source = "examples/${example}/main.roc"
		step!(Cmd.new("roc").args(["build", "--target=arm64musl", "--output=build/android/lib${example}.so", source].map(OsStr.from_str)), "roc build --target=arm64musl ${source}")?
		step!(Cmd.new("roc").args(["build", "--target=arm64mac", "--output=build/lib${example}.dylib", source].map(OsStr.from_str)), "roc build --target=arm64mac ${source}")?
	}
	Ok({})
}

## The examples: every directory under `examples/` with a `main.roc`.
examples! : () => Try(List(Str), _)
examples! = || {
	listed = Cmd.new("git").args(["ls-files", "--cached", "--others", "--exclude-standard", "--", "examples/*/main.roc"].map(OsStr.from_str)).capture!()?
	Ok(listed.split_on("\n").keep_oks(|path| path.drop_prefix("examples/").split_on("/").first()).drop_if(Str.is_empty).sort_with(Repo.compare))
}

## Run a program with the terminal inherited; it must exit 0.
step! : Cmd.Command, Str => Try({}, _)
step! = |command, shown| {
	note!("RUN ${shown}")
	output = command.inherit_stdio().run!()?
	match output.status {
		Exited(0) => Ok({})
		_ => {
			warn!("build: ${shown} failed: ${Str.inspect(output.status)}")
			Err(Exit(1))
		}
	}
}

note! : Str => {}
note! = |line| Stdout.line!(line) ?? {}

warn! : Str => {}
warn! = |line| Stderr.line!(line) ?? {}
