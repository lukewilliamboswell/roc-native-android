# Development environment and tasks for roc-native-android, realised by roc-blueprint.
#
#     blueprint run check                  # the pull-request gate
#     blueprint run build                  # host, then every example (.dylib and Android .so)
#     blueprint run build -- counter       # the host and one example
#     blueprint run smoke                  # every example on the desktop JVM, through JNI
#     blueprint run bundle                 # the platform bundle, in build/platform/<hash>.tar.zst
#     blueprint run bundle -- --test       # and the counter example built and run against it
#     blueprint run release -- 0.0.1       # the release's assets, in build/release/0.0.1/
#     blueprint run apk                    # the Android app shell, every example packaged
#     blueprint tasks                      # list every task
#     blueprint update                     # refresh Blueprint.lock
#
# Two Roc compilers have two jobs (roc-automation's model): `roc-stable`,
# pinned below, runs the scripts under scripts/; `roc`, the latest Roc
# nightly, checks, builds and tests the platform and the examples. `roc` is
# never pinned: CI installs it with roc-lang/setup-roc, and a contributor
# installs a current nightly (or a release such as 0.1.0 RC1) as `roc`, so the
# environment expects it on PATH.
#
# Both targets are built on Apple silicon macOS: `arm64mac` is run there (the
# desktop smoke test), and `arm64musl`, the Android .so, is cross-compiled and
# checked for what it links, but not run (no hosted runner has an arm64
# Android device).
app [config] { pf: platform "https://github.com/lukewilliamboswell/roc-blueprint/releases/download/0.13.0/DBtzmMfrSgt6nCGVAcZYCVPxWbhpqw3MXpm6sdQzMn4v.tar.zst" }

import pf.Config
import pf.Recipes
import pf.System

# The scripts' tooling compiler (`roc-stable`): every script's `roc` header
# field names this release, and `check` fails until they match. It is the
# same release roc-signals' scripts use.
roc_stable_program = Fetch("roc-stable", Recipes.roc_nightly("nightly-2026-11-14-a87808d"))

# The host's compiler: `zig build` in host/ makes each target's libhost.a.
zig_program = Fetch("zig", zig)

# The desktop smoke test's Kotlin compiler. Java comes from the machine.
kotlinc_program = Fetch("kotlinc", kotlinc)

config = [
	Name("roc-native-android"),
	Systems(["aarch64-darwin"]),
	Environment(
		"dev",
		[
			Programs([roc_stable_program, zig_program, kotlinc_program]),
			# Every bundle the scripts depend on, including dependencies of
			# dependencies: `roc-stable deps scripts/check_all.roc` lists them.
			RocPackages([
				"https://github.com/roc-lang/basic-cli/releases/download/0.28.1/7brgwR8YUqfeGrkdfF1V4GPPN7DnP7ftqub9KcrTEZtr.tar.zst",
				"https://github.com/roc-lang/http/releases/download/1.0.0/6ZUwqYhCS8PU9Mo6MF7oV82ET2o7KYb57CLKDq4cq4sS.tar.zst",
			]),
			# `java` runs the smoke test (and kotlinc), `objdump` and `nm`
			# (Xcode's LLVM tools, which read ELF) check what the Android
			# libraries link, `python3` serves the bundle to its consumer test,
			# `shasum` checks the engine modules' digests and `git` lists the
			# tracked files the checks read.
			Expect(["roc", "java", "objdump", "nm", "python3", "shasum", "git"]),
		],
	),
	# The Android app shell: Gradle builds the host and every example and
	# packages them. The Android SDK is the machine's (ANDROID_HOME, or
	# sdk.dir in android/local.properties).
	Environment("android", [Programs([zig_program]), Expect(["roc", "java"])]),
	Shell("default", [Use("dev")]),
	# The per-pull-request gate: compiler pins, engine digests, formatting,
	# the scripts' tests, the build, `roc test`, the smoke test, the Android
	# libraries' link audit and the bundle with its consumer test.
	# `check -- STAGE...` runs only the named stages, in order.
	Task("check", [Use("dev"), Run(["scripts/check_all.roc", "--"])]),
	Task("build", [Use("dev"), Run(["scripts/build.roc", "--"])]),
	Task("test", [Use("dev"), Run(["scripts/check_all.roc", "--", "test"])]),
	Task("smoke", [Use("dev"), Run(["scripts/check_all.roc", "--", "smoke"])]),
	# The platform bundle an app's header names: the platform modules, each
	# target's libhost.a and the notices, `roc bundle`d into build/platform/.
	# `-- --output-dir DIR`, `-- --prebuilt` (skip the host build) and
	# `-- --test` (serve it on localhost and build examples/counter against it).
	Task("bundle", [Use("dev"), Run(["scripts/bundle.roc", "--"])]),
	# Every asset of a release into build/release/<version>/: the platform
	# bundle, the android.roc tool package and its launcher.
	# `-- VERSION [--base-url URL]`; needs the Android SDK for the shell.
	Task("release", [Use("dev"), Run(["scripts/release.roc", "--"])]),
	Task("apk", [Use("android"), Run(["android/gradlew", "-p", "android", "assembleDebug"])]),
	# The tooling compiler, as a log records it.
	Task("tooling-version", [Use("dev"), Run(["roc-stable", "version"])]),
]

zig : System -> Config.Download
zig = |system| {
	base = "https://ziglang.org/download/0.17.1"
	match system {
		"aarch64-darwin" => Archive("${base}/zig-aarch64-macos-0.17.1.tar.xz", TarXz, "zig-aarch64-macos-0.17.1/zig")
		_ => Unavailable
	}
}

kotlinc : System -> Config.Download
kotlinc = |_system| Archive("https://github.com/JetBrains/kotlin/releases/download/v2.4.21/kotlin-compiler-2.4.21.zip", Zip, "kotlinc/bin/kotlinc")
