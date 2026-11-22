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
import src/Release

## The `release` task: every asset of a release, in `build/release/<version>/`.
##
##     blueprint run release -- 0.0.1                              # for GitHub
##     blueprint run release -- 0.0.1 --base-url http://localhost:8787   # to test locally
##
## The assets, each named by `roc bundle`'s content hash or fixed:
##
## - `<hash>.tar.zst`: the platform bundle (`scripts/bundle.roc`).
## - `<hash>.tar.zst`: the `android.roc` tool package (`cli/`), with the
##   Kotlin shell's `classes.dex` (the Gradle release build, shrunk by R8),
##   the counter scaffold and the platform's URL (`Bundle.roc`, generated).
## - `android.roc`: the small app an author downloads, naming the tool.
##
## `--base-url` is where the assets will be served; by default the GitHub
## release download URL for the version.
main! : List(OsStr) => Try({}, _)
main! = |raw_args| {
	{ version, base_url } = match Release.options(raw_args.map(OsStr.display)) {
		Ok(found) => found
		Err(Usage) => {
			warn!("release: usage: scripts/release.roc VERSION [--base-url URL]")
			return Err(Exit(2))
		}
	}
	root = Env.cwd!()?.display()
	out = "${root}/build/release/${version}"
	fresh!(out)?

	# The platform bundle.
	step!(Cmd.new("roc-stable").args(["scripts/bundle.roc", "--", "--output-dir", "${root}/build/release/platform"].map(OsStr.from_str)), "scripts/bundle.roc")?
	platform_bundle = only_bundle!("${root}/build/release/platform")?
	Path.copy!(Path.utf8("${root}/build/release/platform/${platform_bundle}"), Path.utf8("${out}/${platform_bundle}"))?

	# The Kotlin shell's classes.dex, from the release build with no app.
	step!(Cmd.new("android/gradlew").args(["-q", "-p", "android", ":app:assembleRelease", "-Proc.examples="].map(OsStr.from_str)), "gradle :app:assembleRelease (the shell)")?
	shell_apk = "android/app/build/outputs/apk/release/app-release-unsigned.apk"
	stage = "${root}/build/release/tool-stage"
	fresh!(stage)?
	step!(Cmd.new("unzip").args(["-q", "-o", "-j", shell_apk, "classes.dex", "-d", stage].map(OsStr.from_str)), "unzip classes.dex")?
	Path.rename!(Path.utf8("${stage}/classes.dex"), Path.utf8("${stage}/shell.dex"))?

	# The tool package, staged with its generated Bundle.roc.
	listed = Cmd.new("git").args(["ls-files", "--cached", "--others", "--exclude-standard", "--", "cli/*.roc"].map(OsStr.from_str)).capture!()?
	modules = listed.split_on("\n").keep_if(|line| !line.is_empty()).map(|path| path.drop_prefix("cli/"))
	for name in modules {
		Path.copy!(Path.utf8("cli/${name}"), Path.utf8("${stage}/${name}"))?
	}
	Path.copy!(Path.utf8("examples/counter/main.roc"), Path.utf8("${stage}/scaffold-main.txt"))?
	for notice in ["LICENSE", "THIRD_PARTY_LICENSES.md"] {
		Path.copy!(Path.utf8(notice), Path.utf8("${stage}/${notice}"))?
	}
	roc_version = Cmd.new("roc").args([OsStr.from_str("version")]).capture!()?.trim()
	Path.write_utf8!(Path.utf8("${stage}/Bundle.roc"), Release.bundle_module({ version, platform_url: "${base_url}/${platform_bundle}", roc_version }))?
	step!(Cmd.new("roc").args(["test", "main.roc"].map(OsStr.from_str)).cwd(Path.utf8(stage)), "roc test (the tool)")?
	tool_out = "${root}/build/release/tool"
	fresh!(tool_out)?
	files = Release.tool_files(modules)
	step!(Cmd.new("roc").args(["bundle"].concat(files).concat(["--output-dir", tool_out]).map(OsStr.from_str)).cwd(Path.utf8(stage)), "roc bundle (the tool, ${files.len().to_str()} files)")?
	tool = only_bundle!(tool_out)?
	Path.copy!(Path.utf8("${tool_out}/${tool}"), Path.utf8("${out}/${tool}"))?

	# The launcher.
	Path.write_utf8!(Path.utf8("${out}/android.roc"), Release.launcher({ version, tool_url: "${base_url}/${tool}" }))?

	note!("release ${version}, for ${base_url}:")
	note!("  ${platform_bundle}  the platform")
	note!("  ${tool}  the android.roc tool package")
	note!("  android.roc  the launcher")
	note!("in ${out}")
	Ok({})
}

## The one `.tar.zst` `roc bundle` wrote into `dir`.
only_bundle! : Str => Try(Str, _)
only_bundle! = |dir|
	match Path.list!(Path.utf8(dir))?.map(Path.display).keep_if(|path| path.ends_with(".tar.zst")) {
		[one] => Ok(Path.filename(Path.utf8(one))?.display())
		found => {
			warn!("release: ${dir} holds ${found.len().to_str()} bundles, not one")
			Err(Exit(1))
		}
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
			warn!("release: ${shown} failed: ${Str.inspect(output.status)}")
			Err(Exit(1))
		}
	}
}

note! : Str => {}
note! = |line| Stdout.line!(line) ?? {}

warn! : Str => {}
warn! = |line| Stderr.line!(line) ?? {}
