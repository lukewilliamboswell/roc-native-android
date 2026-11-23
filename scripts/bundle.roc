#!/usr/bin/env roc-stable
app [main!] {
	cli: platform "https://github.com/roc-lang/basic-cli/releases/download/0.28.1/7brgwR8YUqfeGrkdfF1V4GPPN7DnP7ftqub9KcrTEZtr.tar.zst",
	roc: "nightly-2026-11-14-a87808d",
}

import cli.Cmd
import cli.Duration
import cli.Env
import cli.OsStr
import cli.Path
import cli.Stderr
import cli.Stdout
import src/Bundle
import src/Repo

## The `bundle` task: the platform bundle an app author's header names.
##
##     blueprint run bundle                              # build/platform/<hash>.tar.zst
##     blueprint run bundle -- --output-dir DIR          # somewhere else
##     blueprint run bundle -- --test                    # then build and run an example against it
##     blueprint run bundle -- --prebuilt                # bundle the host already built
##
## It builds the host (`scripts/build.roc --host`) unless `--prebuilt`,
## stages the tracked Android platform modules, each target's `libhost.a` and
## the licence notices under `build/`, and runs `roc bundle` there. `roc
## bundle` packs only the files it is given, so the bundle holds exactly
## these: no external linker input and no runtime library. The output
## directory then holds exactly one content-addressed `<hash>.tar.zst`.
##
## `--test` checks the bundle as an author would use it: it serves the
## output directory on localhost (the compiler accepts `http` only for
## localhost) and, with an empty Roc package cache, builds `examples/counter`
## from a directory outside the platform, its header naming the served
## bundle. The macOS build runs under the smoke test (`build/smoke.jar`, from
## `blueprint run smoke`); the Android build gets the link audit.
##
## The package compiler is the `roc` on PATH.
main! : List(OsStr) => Try({}, _)
main! = |raw_args| {
	options = match Bundle.options(raw_args.map(OsStr.display)) {
		Ok(found) => found
		Err(Unknown(arg)) => {
			warn!("bundle: unknown option ${arg}; the options are --output-dir DIR, --prebuilt and --test")
			return Err(Exit(2))
		}
		Err(MissingValue(arg)) => {
			warn!("bundle: ${arg} needs a value")
			return Err(Exit(2))
		}
	}
	root = Env.cwd!()?.display()
	out = if (options.output_dir.starts_with("/")) options.output_dir else "${root}/${options.output_dir}"
	if !options.prebuilt {
		step!(Cmd.new("roc-stable").args(["scripts/build.roc", "--", "--host"].map(OsStr.from_str)), "scripts/build.roc --host")?
	}
	for input in Bundle.host_libraries {
		if !(Path.exists!(Path.utf8("platform/${input}"))?) {
			warn!("bundle: platform/${input} is not built: run `blueprint run build -- --host` (or drop --prebuilt)")
			return Err(Exit(1))
		}
	}

	# Stage the platform with its notices, so `roc bundle` sees one tree.
	stage = "${root}/build/bundle-stage"
	fresh!(stage)?
	listed = Cmd.new("git").args(["ls-files", "--", "platform/*.roc"].map(OsStr.from_str)).capture!()?
	var $modules = []
	for path in listed.split_on("\n").keep_if(|line| !line.is_empty()) {
		if Path.exists!(Path.utf8(path))? {
			name = path.drop_prefix("platform/")
			Path.copy!(Path.utf8(path), Path.utf8("${stage}/${name}"))?
			$modules = $modules.append(name)
		}
	}
	for input in Bundle.host_libraries {
		target_dir = input.drop_suffix("/libhost.a")
		Path.create_all!(Path.utf8("${stage}/${target_dir}"))?
		Path.copy!(Path.utf8("platform/${input}"), Path.utf8("${stage}/${input}"))?
	}
	for notice in Bundle.notices {
		Path.copy!(Path.utf8(notice), Path.utf8("${stage}/${notice}"))?
	}
	files = Bundle.files($modules)

	fresh!(out)?
	step!(Cmd.new("roc").args(["bundle"].concat(files).concat(["--output-dir", out]).map(OsStr.from_str)).cwd(Path.utf8(stage)), "roc bundle (${files.len().to_str()} files)")?
	bundle = match Path.list!(Path.utf8(out))?.map(Path.display).keep_if(|path| path.ends_with(".tar.zst")) {
		[one] => Path.filename(Path.utf8(one))?.display()
		found => {
			warn!("bundle: roc bundle wrote ${found.len().to_str()} .tar.zst files to ${out}, not one")
			return Err(Exit(1))
		}
	}
	digest = Cmd.new("shasum").args(["-a", "256", "${out}/${bundle}"].map(OsStr.from_str)).capture!()?.split_on(" ").first() ?? ""
	size = Path.size_in_bytes!(Path.utf8("${out}/${bundle}"))?
	note!("bundle: ${out}/${bundle}")
	note!("bundle: ${size.to_str()} bytes, sha256 ${digest}")
	if options.test {
		consumer_test!(root, out, bundle)?
	}
	Ok({})
}

## Build `examples/counter` outside the platform against the served bundle,
## with an empty package cache, for both targets, and check each build.
consumer_test! : Str, Str, Str => Try({}, _)
consumer_test! = |root, out, bundle| {
	server = Cmd.new("python3")
		.args(["-I", "-u", "-m", "http.server", "0", "--bind", "::1", "--directory", out].map(OsStr.from_str))
		.stdin(Null)
		.stdout(Pipe)
		.stderr(MergeWithStdout)
		.spawn!()?
	result = match served!(server) {
		Ok(port) => counter!(root, "http://localhost:${port.to_str()}/${bundle}")
		Err(problem) => Err(problem)
	}
	server.kill!() ?? {}
	server.close!() ?? {}
	match result {
		Ok({}) => {
			note!("bundle: the counter example built against the served bundle, ran on the desktop and passed the Android link audit")
			Ok({})
		}
		Err(problem) => {
			warn!("bundle: ${problem}")
			Err(Exit(1))
		}
	}
}

## The port the server reports, from its first line of output.
served! : Cmd.Child => Try(U16, Str)
served! = |server|
	match server.read!(Duration.seconds(10)) {
		Ok(Stdout(bytes)) => Bundle.served_port(Str.from_utf8_lossy(bytes)).map_err(|_| "the local server printed no port: ${Str.from_utf8_lossy(bytes)}")
		Ok(Stderr(bytes)) => Err("the local server failed: ${Str.from_utf8_lossy(bytes)}")
		Err(error) => Err("the local server did not start: ${Str.inspect(error)}")
	}

counter! : Str, Str => Try({}, Str)
counter! = |root, url| {
	dir = "${root}/build/bundle-test"
	app_dir = "${dir}/counter"
	cache = "${dir}/cache"
	if !(Path.exists!(Path.utf8("build/smoke.jar")) ?? Bool.False) {
		return Err("build/smoke.jar is not built: run `blueprint run smoke` first")
	}
	fresh!(dir) ? |error| "could not empty ${dir}: ${Str.inspect(error)}"
	Path.create_all!(Path.utf8(app_dir)) ? |error| "could not create ${app_dir}: ${Str.inspect(error)}"
	Path.create_all!(Path.utf8(cache)) ? |error| "could not create ${cache}: ${Str.inspect(error)}"
	source = Path.read_utf8!(Path.utf8("examples/counter/main.roc")) ? |error| "could not read examples/counter/main.roc: ${Str.inspect(error)}"
	released = Bundle.released_main(source, url) ? |_| "examples/counter/main.roc does not name ../../platform/main.roc"
	Path.write_utf8!(Path.utf8("${app_dir}/main.roc"), released) ? |error| "could not write ${app_dir}/main.roc: ${Str.inspect(error)}"
	note!("bundle: building ${app_dir}/main.roc against ${url}, package cache ${cache}")
	dylib = "${app_dir}/libcounter.dylib"
	so = "${app_dir}/libcounter.so"
	for (target, output) in [("arm64mac", dylib), ("arm64musl", so)] {
		step!(
			Cmd.new("roc").args(["build", "--target=${target}", "--output=${output}", "main.roc"].map(OsStr.from_str)).cwd(Path.utf8(app_dir)).env("XDG_CACHE_HOME", OsStr.from_str(cache)),
			"roc build --target=${target} main.roc",
		) ? |_| "roc build --target=${target} failed against the bundle"
	}
	smoke = Cmd.new("java")
		.args(["-jar", "build/smoke.jar", dylib, "counter"].map(OsStr.from_str))
		.stdin(Null)
		.stderr(MergeWithStdout)
		.timeout(Duration.seconds(120))
		.run!() ? |error| "the smoke test did not finish: ${Str.inspect(error)}"
	if smoke.status != Exited(0) {
		return Err("the smoke test failed on ${dylib}:\n${Str.from_utf8_lossy(smoke.stdout_bytes)}")
	}
	note!("bundle: PASS smoke test on ${dylib}")
	problems = audit!(so) ? |error| "could not audit ${so}: ${Str.inspect(error)}"
	if !problems.is_empty() {
		return Err("${so}:\n  ${Str.join_with(problems, "\n  ")}")
	}
	note!("bundle: PASS link audit of ${so}")
	Ok({})
}

## What is wrong with an Android library (see `Repo.library_problems`).
audit! : Str => Try(List(Str), _)
audit! = |library| {
	listed = Cmd.new("git").args(["ls-files", "--", "android/app/src/main/java/dev/roc/android/*.kt"].map(OsStr.from_str)).capture!()?
	var $natives = []
	for path in listed.split_on("\n").keep_if(|line| !line.is_empty()) {
		$natives = $natives.concat(Repo.jni_natives(Path.read_utf8!(Path.utf8(path))?))
	}
	headers = Cmd.new("objdump").args(["-p", library].map(OsStr.from_str)).capture!()?
	undefined = Cmd.new("nm").args(["-D", "--undefined-only", library].map(OsStr.from_str)).capture!()?
	defined = Cmd.new("nm").args(["-D", "--defined-only", library].map(OsStr.from_str)).capture!()?
	Ok(Repo.library_problems({ headers, undefined, defined, natives: $natives }))
}

## Remove a directory and everything in it, then create it empty.
fresh! : Str => Try({}, _)
fresh! = |dir| {
	if Path.exists!(Path.utf8(dir))? {
		Path.delete_all!(Path.utf8(dir))?
	}
	Path.create_all!(Path.utf8(dir))
}

## Run a program with the terminal inherited; it must exit 0.
step! : Cmd.Command, Str => Try({}, _)
step! = |command, shown| {
	note!("RUN ${shown}")
	output = command.inherit_stdio().run!()?
	match output.status {
		Exited(0) => Ok({})
		_ => {
			warn!("bundle: ${shown} failed: ${Str.inspect(output.status)}")
			Err(Exit(1))
		}
	}
}

note! : Str => {}
note! = |line| Stdout.line!(line) ?? {}

warn! : Str => {}
warn! = |line| Stderr.line!(line) ?? {}
