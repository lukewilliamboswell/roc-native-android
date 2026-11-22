#!/usr/bin/env roc-stable
app [main!] {
	cli: platform "https://github.com/roc-lang/basic-cli/releases/download/0.28.1/7brgwR8YUqfeGrkdfF1V4GPPN7DnP7ftqub9KcrTEZtr.tar.zst",
	roc: "nightly-2026-11-14-a87808d",
}

import cli.Cmd
import cli.Duration
import cli.Monotonic
import cli.OsStr
import cli.Path
import cli.Stderr
import cli.Stdout
import src/Repo

## The `check` task, the pull-request gate: `blueprint run check` runs every
## stage, and `blueprint run check -- STAGE...` the named ones, in order.
##
## - `pins`: only the scripts pin a compiler, and they pin `roc-stable`.
## - `engine`: the engine modules match their recorded roc-signals digests.
## - `fmt`: `roc fmt --check` over the platform and examples, and
##   `roc-stable fmt --check` over the scripts.
## - `scripts`: the scripts' `expect` tests.
## - `build`: `scripts/build.roc`: the host, then every example for both
##   targets.
## - `test`: `roc test` on the platform root and on every example.
## - `smoke`: every example's macOS library on the desktop JVM, driven
##   through JNI by the app's own Kotlin `Tree`/`Host` code.
## - `android`: every example's Android library needs no other library,
##   leaves no symbol undefined, has no TLS and exports the JNI functions the
##   Kotlin side declares.
## - `bundle`: the platform bundle from the host `build` made, and the
##   counter example built and run against it as an app author would
##   (`scripts/bundle.roc --prebuilt --test`).
##
## The package compiler is the `roc` on PATH: the latest nightly in CI.
main! : List(OsStr) => Try({}, _)
main! = |raw_args| {
	chosen = match Repo.selection(raw_args.map(OsStr.display)) {
		Ok(stages) => stages
		Err(Unknown(name)) => {
			warn!("unknown stage ${name}; the stages are ${Str.join_with(Repo.stages, ", ")}")
			return Err(Exit(2))
		}
	}
	var $problems = []
	for stage in chosen {
		note!("== ${stage}")
		start = Monotonic.now!()
		found = run_stage!(stage)?
		note!("== ${stage}: ${if (found.is_empty()) "passed" else "failed"} in ${Repo.millis(Monotonic.elapsed!(start).to_millis())}")
		$problems = $problems.concat(found.map(|problem| "${stage}: ${problem}"))
	}
	if $problems.is_empty() {
		note!("check: passed (${Str.join_with(chosen, ", ")})")
		Ok({})
	} else {
		warn!("\nFailed")
		for problem in $problems {
			warn!("  ${problem}")
		}
		warn!("check: ${$problems.len().to_str()} problems")
		Err(Exit(1))
	}
}

run_stage! : Str => Try(List(Str), _)
run_stage! = |stage|
	match stage {
		"pins" => pins!()
		"engine" => engine!()
		"fmt" => fmt!()
		"scripts" => scripts!()
		"build" => run!("roc-stable", ["scripts/build.roc"])
		"test" => test!()
		"smoke" => smoke!()
		"android" => android!()
		"bundle" => run!("roc-stable", ["scripts/bundle.roc", "--", "--prebuilt", "--test"])
		_ => Ok(["no such stage"])
	}

## The files git tracks or would track (untracked files .gitignore does not
## exclude), under the given paths.
tracked! : List(Str) => Try(List(Str), _)
tracked! = |paths| {
	listed = Cmd.new("git").args(["ls-files", "--cached", "--others", "--exclude-standard", "--"].concat(paths).map(OsStr.from_str)).capture!()?
	Ok(listed.split_on("\n").keep_if(|line| !line.is_empty()))
}

pins! : () => Try(List(Str), _)
pins! = || {
	tag = match Repo.stable_tag(Path.read_utf8!(Path.utf8("Blueprint.roc"))?) {
		Ok(found) => found
		Err(NoTag) => return Ok(["Blueprint.roc names no roc-stable nightly"])
	}
	var $files = []
	for path in tracked!(["*.roc"])? {
		$files = $files.append({ path, text: Path.read_utf8!(Path.utf8(path))? })
	}
	var $problems = Repo.pin_problems(tag, $files)
	for path in [".roc-version", ".github/roc-nightly.json"] {
		if Path.exists!(Path.utf8(path))? {
			$problems = $problems.append("${path} pins a compiler; only the scripts under scripts/ may")
		}
	}
	note!("${$files.len().to_str()} Roc files; the scripts pin ${tag}")
	Ok($problems)
}

## The engine modules are the roc-signals copies, unchanged: their digests
## are the ones `tests/engine-modules.sha256` records.
engine! : () => Try(List(Str), _)
engine! = || {
	recorded = Path.read_utf8!(Path.utf8("tests/engine-modules.sha256"))?
	missing = Repo.engine_modules.keep_if(|path| !recorded.contains("  ${path}\n"))
	digests = run!("shasum", ["-a", "256", "--quiet", "--check", "tests/engine-modules.sha256"])?
	Ok(missing.map(|path| "tests/engine-modules.sha256 records no digest for ${path}").concat(digests))
}

fmt! : () => Try(List(Str), _)
fmt! = || {
	sources = tracked!(["platform", "examples"])?.keep_if(|path| path.ends_with(".roc"))
	sources_fmt = run!("roc", ["fmt", "--check"].concat(sources))?
	scripts_fmt = run!("roc-stable", ["fmt", "--check"].concat(tracked!(["scripts"])?.keep_if(|path| path.ends_with(".roc"))))?
	note!("${sources.len().to_str()} platform and example sources checked")
	Ok(sources_fmt.concat(scripts_fmt))
}

scripts! : () => Try(List(Str), _)
scripts! = || {
	var $problems = []
	for script in ["scripts/check_all.roc", "scripts/build.roc", "scripts/bundle.roc"] {
		$problems = $problems.concat(run!("roc-stable", ["test", script])?)
	}
	Ok($problems)
}

test! : () => Try(List(Str), _)
test! = || {
	var $problems = run!("roc", ["test", "platform/main.roc"])?
	for example in examples!()? {
		$problems = $problems.concat(run!("roc", ["test", "examples/${example}/main.roc"])?)
	}
	Ok($problems)
}

## The examples: every directory under `examples/` with a `main.roc`.
examples! : () => Try(List(Str), _)
examples! = || {
	roots = tracked!(["examples/*/main.roc"])?
	Ok(roots.keep_oks(|path| path.drop_prefix("examples/").split_on("/").first()).sort_with(Repo.compare))
}

## The app's Kotlin directory: the host code the smoke test shares.
kotlin_dir : Str
kotlin_dir = "android/app/src/main/java/dev/roc/android"

## Compile the smoke test (the app's Kotlin files that use no Android API,
## with `android/desktop/`) into `build/smoke.jar`, then run every example's
## scripted interactions on its macOS library.
smoke! : () => Try(List(Str), _)
smoke! = || {
	var $app = []
	for path in tracked!(["${kotlin_dir}/*.kt"])? {
		$app = $app.append({ path, text: Path.read_utf8!(Path.utf8(path))? })
	}
	sources = Repo.smoke_sources($app).concat(tracked!(["android/desktop/*.kt"])?)
	note!("RUN kotlinc (${sources.len().to_str()} files) -d build/smoke.jar")
	compiled = quiet!("kotlinc", sources.concat(["-include-runtime", "-d", "build/smoke.jar"]), Duration.seconds(600))?
	match compiled {
		Ok(_) => {}
		Err(problem) => return Ok(["kotlinc: ${problem}"])
	}
	cases = examples!()?
	var $problems = []
	var $index = 0
	for example in cases {
		$index = $index + 1
		start = Monotonic.now!()
		library = "build/lib${example}.dylib"
		outcome =
			if Path.exists!(Path.utf8(library))? {
				quiet!("java", ["-jar", "build/smoke.jar", library, example], Duration.seconds(120))?
			} else {
				Err("${library} is not built: run `blueprint run build` first")
			}
		ms = Monotonic.elapsed!(start).to_millis()
		note!(Repo.progress({ index: $index, total: cases.len(), name: example, outcome: if (outcome.is_ok()) "PASS" else "FAIL", ms }))
		match outcome {
			Ok(_) => {}
			Err(problem) => {
				$problems = $problems.append("${example}: ${problem}")
			}
		}
	}
	Ok($problems)
}

## Every example's Android library, audited for what it links and exports.
android! : () => Try(List(Str), _)
android! = || {
	var $natives = []
	for path in tracked!(["${kotlin_dir}/*.kt"])? {
		$natives = $natives.concat(Repo.jni_natives(Path.read_utf8!(Path.utf8(path))?))
	}
	note!("${$natives.len().to_str()} JNI functions declared in Kotlin")
	var $problems = []
	for example in examples!()? {
		library = "build/android/lib${example}.so"
		if Path.exists!(Path.utf8(library))? {
			headers = Cmd.new("objdump").args(["-p", library].map(OsStr.from_str)).capture!()?
			undefined = Cmd.new("nm").args(["-D", "--undefined-only", library].map(OsStr.from_str)).capture!()?
			defined = Cmd.new("nm").args(["-D", "--defined-only", library].map(OsStr.from_str)).capture!()?
			found = Repo.library_problems({ headers, undefined, defined, natives: $natives })
			note!("${library}: ${if (found.is_empty()) "ok" else "${found.len().to_str()} problems"}")
			$problems = $problems.concat(found.map(|problem| "${library} ${problem}"))
		} else {
			$problems = $problems.append("${library} is not built: run `blueprint run build` first")
		}
	}
	Ok($problems)
}

## Run a program with its output captured, shown only if it fails.
quiet! : Str, List(Str), Duration => Try(Try({}, Str), _)
quiet! = |program, args, limit| {
	result = Cmd.new(OsStr.from_str(program))
		.args(args.map(OsStr.from_str))
		.stdin(Null)
		.stderr(MergeWithStdout)
		.timeout(limit)
		.run!()
	output = match result {
		Ok(done) => done
		Err(CmdErr(error)) => return Ok(Err("${program} did not finish: ${Str.inspect(error)}"))
		Err(Interrupted) => return Ok(Err("interrupted"))
	}
	printed = Str.from_utf8_lossy(output.stdout_bytes)
	match output.status {
		Exited(0) => Ok(Ok({}))
		Exited(code) => Ok(Err("exited with ${code.to_str()}; it printed:\n${printed}"))
		Signaled(signal) => Ok(Err("ended by signal ${signal.to_str()}; it printed:\n${printed}"))
	}
}

## Run a program with the terminal inherited; a non-zero exit is a problem.
run! : Str, List(Str) => Try(List(Str), _)
run! = |program, args| {
	shown = Str.join_with([program].concat(args.take_first(4)), " ")
	note!("RUN ${shown}")
	output = Cmd.new(OsStr.from_str(program)).args(args.map(OsStr.from_str)).inherit_stdio().run!()?
	match output.status {
		Exited(0) => Ok([])
		Exited(code) => Ok(["${shown} exited with ${code.to_str()}"])
		Signaled(signal) => Ok(["${shown} ended by signal ${signal.to_str()}"])
	}
}

note! : Str => {}
note! = |line| Stdout.line!(line) ?? {}

warn! : Str => {}
warn! = |line| Stderr.line!(line) ?? {}
