import cli.Cmd
import cli.Env
import cli.OsStr
import cli.Path
import cli.Stdout
import Bundle

## Build an app's APK with Roc and the Android SDK, and install and start it.
##
## The APK holds the app as `lib/arm64-v8a/libapp.so` (`roc build
## --target=arm64musl`), the platform's Kotlin shell as `classes.dex` (which
## this release carries), and a manifest written for the app. `aapt2` links
## the manifest, `zipalign` aligns the archive and `apksigner` signs it with
## the Android debug key, all from the SDK Android Studio installs. No Gradle
## project is involved.
Apk := [].{

	## The app's application id and launcher label; empty means the default.
	Options : { id : Str, label : Str }

	## Where the SDK tools are.
	Sdk : { build_tools : Str, android_jar : Str, api : Str, adb : Str, java_home : Str }

	## The build directory, beside `main.roc`.
	work : Str
	work = ".android"

	## Build `<name>.apk` from `main.roc`, and say what it is.
	build! : Options => Try({ apk : Str, id : Str, sdk : Sdk }, _)
	build! = |options| {
		if !(Path.exists!(Path.utf8("main.roc"))?) {
			return Err(NoMainRoc)
		}
		if !Cmd.check_available!("roc") {
			return Err(RocMissing)
		}
		sdk = Apk.sdk!()?
		cwd = Env.cwd!()?.display()
		name = Apk.app_name(cwd.split_on("/").last() ?? "app")
		id = if (options.id.is_empty()) "com.example.${name}" else options.id
		if !Apk.valid_id(id) {
			return Err(BadId(id))
		}
		label = if (options.label.is_empty()) name else options.label
		apk = "${name}.apk"

		if Path.exists!(Path.utf8(Apk.work))? {
			Path.delete_all!(Path.utf8(Apk.work))?
		}
		Path.create_all!(Path.utf8("${Apk.work}/lib/arm64-v8a"))?
		Stdout.line!("Building main.roc for Android (arm64)...")?
		built = Cmd.new("roc").args(["build", "--target=arm64musl", "--output=${Apk.work}/lib/arm64-v8a/libapp.so", "main.roc"].map(OsStr.from_str)).inherit_stdio().run!()?
		if built.status != Exited(0) {
			return Err(BuildFailed)
		}
		Path.write_utf8!(Path.utf8("${Apk.work}/AndroidManifest.xml"), Apk.manifest({ id, label, api: sdk.api }))?
		Path.write_bytes!(Path.utf8("${Apk.work}/classes.dex"), Bundle.shell_dex)?
		tool!(sdk, "aapt2", "${sdk.build_tools}/aapt2", ["link", "-o", "${Apk.work}/base.apk", "-I", sdk.android_jar, "--manifest", "${Apk.work}/AndroidManifest.xml"], ".")?
		tool!(sdk, "zip", "zip", ["-q", "-X", "base.apk", "classes.dex"], Apk.work)?
		# The library is stored, and zipalign puts it on a 16 KiB boundary,
		# so Android maps it from the APK on 4 KiB and 16 KiB devices alike.
		tool!(sdk, "zip", "zip", ["-q", "-X", "-0", "base.apk", "lib/arm64-v8a/libapp.so"], Apk.work)?
		tool!(sdk, "zipalign", "${sdk.build_tools}/zipalign", ["-f", "-P", "16", "4", "${Apk.work}/base.apk", "${Apk.work}/aligned.apk"], ".")?
		keystore = keystore!(sdk)?
		tool!(
			sdk,
			"apksigner",
			"${sdk.build_tools}/apksigner",
			["sign", "--ks", keystore, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", apk, "${Apk.work}/aligned.apk"],
			".",
		)?
		Stdout.line!("Built ${apk}: ${label} (${id}), signed with the Android debug key.")?
		Ok({ apk, id, sdk })
	}

	## Build the APK, install it on the connected device and start it.
	run! : Options => Try({}, _)
	run! = |options| {
		{ apk, id, sdk } = Apk.build!(options)?
		Stdout.line!("Installing ${apk}...")?
		installed = Cmd.new(OsStr.from_str(sdk.adb)).args(["install", "-r", apk].map(OsStr.from_str)).stdin(Null).stderr(MergeWithStdout).run!()?
		said = Str.from_utf8_lossy(installed.stdout_bytes)
		if installed.status != Exited(0) {
			return if (said.contains("no devices") or said.contains("device offline") or said.contains("not found")) Err(NoDevice) else Err(ToolFailed({ tool: "adb install", output: said }))
		}
		tool!(sdk, "adb shell am start", sdk.adb, ["shell", "am", "start", "-n", "${id}/dev.roc.android.AppActivity"], ".")?
		Stdout.line!("Started ${id}. Its log: adb logcat | grep -i roc")?
		Ok({})
	}

	## Find the SDK, its newest build tools and platform, and a JDK.
	sdk! : () => Try(Sdk, _)
	sdk! = || {
		home = Env.var_str!("HOME") ?? ""
		candidates = [Env.var_str!("ANDROID_HOME") ?? "", Env.var_str!("ANDROID_SDK_ROOT") ?? "", "${home}/Library/Android/sdk", "${home}/Android/Sdk"]
		var $found = Err(SdkMissing)
		for candidate in candidates {
			if $found.is_err() and !candidate.is_empty() and (Path.is_dir!(Path.utf8(candidate)) ?? Bool.False) {
				$found = Ok(candidate)
			}
		}
		root = $found?
		tools = Apk.newest(names!("${root}/build-tools"), "") ? |_| SdkPartMissing("build tools (build-tools)")
		newest_platform = Apk.newest(names!("${root}/platforms"), "android-") ? |_| SdkPartMissing("Android platform (platforms/android-N)")
		adb = "${root}/platform-tools/adb"
		if !(Path.exists!(Path.utf8(adb)) ?? Bool.False) {
			return Err(SdkPartMissing("platform tools (platform-tools/adb)"))
		}
		java_home = java_home!(home)?
		Ok({ build_tools: "${root}/build-tools/${tools}", android_jar: "${root}/platforms/${newest_platform}/android.jar", api: Apk.api_level(newest_platform), adb, java_home })
	}

	## The newest version among `names` that start with `prefix`, such as
	## `36.0.0` among build tools or `android-37.0` among platforms.
	newest : List(Str), Str -> Try(Str, [None])
	newest = |names, prefix| {
		versions = names.keep_oks(
			|name|
				if name.starts_with(prefix) {
					parts = name.drop_prefix(prefix).split_on(".").map(|part| U64.from_str(part))
					if (parts.all(|part| part.is_ok())) Ok({ name, parts: parts.keep_oks(|part| part) }) else Err(NotAVersion)
				} else {
					Err(NotAVersion)
				},
		)
		match versions {
			[] => Err(None)
			[first, .. as rest] => Ok(rest.fold(first, |best, next| if (Apk.later(next.parts, best.parts)) next else best).name)
		}
	}

	later : List(U64), List(U64) -> Bool
	later = |a, b|
		match (a, b) {
			([], _) => Bool.False
			(_, []) => Bool.True
			([x, .. as xs], [y, .. as ys]) => if (x != y) x > y else Apk.later(xs, ys)
		}

	## The API level of a platform directory: `android-37.0` is 37.
	api_level : Str -> Str
	api_level = |dir| dir.drop_prefix("android-").split_on(".").first() ?? "26"

	## The app's name, from its directory: lower-case letters, digits and
	## underscores, starting with a letter.
	app_name : Str -> Str
	app_name = |dir| {
		bytes = dir.to_utf8().map(
			|b|
				if b >= 'A' and b <= 'Z' {
					b + 32
				} else if (b >= 'a' and b <= 'z') or (b >= '0' and b <= '9') {
					b
				} else {
					'_'
				},
		)
		name = Str.from_utf8_lossy(bytes)
		match bytes.first() {
			Ok(b) if b >= 'a' and b <= 'z' => name
			_ => "app${name}"
		}
	}

	## Whether `id` is an application id: two or more dot-separated parts,
	## each a letter followed by letters, digits or underscores.
	valid_id : Str -> Bool
	valid_id = |id| {
		parts = id.split_on(".")
		parts.len() >= 2
			and parts.all(
				|part| {
					bytes = part.to_utf8()
					letter = |b| (b >= 'a' and b <= 'z') or (b >= 'A' and b <= 'Z')
					match bytes {
						[first, ..] => letter(first) and bytes.all(|b| letter(b) or (b >= '0' and b <= '9') or b == '_')
						[] => Bool.False
					}
				},
			)
	}

	## The app's manifest: one activity, the platform shell's
	## `AppActivity`, which loads `libapp.so`.
	manifest : { id : Str, label : Str, api : Str } -> Str
	manifest = |{ id, label, api }|
		\\<?xml version="1.0" encoding="utf-8"?>
		\\<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${id}" android:versionCode="1" android:versionName="1.0">
		\\    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="${api}" />
		\\    <uses-permission android:name="android.permission.INTERNET" />
		\\    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
		\\    <application android:label="${Apk.escape(label)}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:extractNativeLibs="false">
		\\        <activity android:name="dev.roc.android.AppActivity" android:exported="true">
		\\            <intent-filter>
		\\                <action android:name="android.intent.action.MAIN" />
		\\                <category android:name="android.intent.category.LAUNCHER" />
		\\            </intent-filter>
		\\        </activity>
		\\    </application>
		\\</manifest>
		\\

	## Text as an XML attribute value.
	escape : Str -> Str
	escape = |text| text.replace_each("&", "&amp;").replace_each("<", "&lt;").replace_each(">", "&gt;").replace_each("\"", "&quot;")
}

## The names in a directory, or none if it cannot be listed.
names! : Str => List(Str)
names! = |dir|
	match Path.list!(Path.utf8(dir)) {
		Ok(paths) => paths.keep_oks(|path| Path.filename(path).map_ok(Path.display))
		Err(_) => []
	}

## A JDK for apksigner and keytool: JAVA_HOME, or Android Studio's own.
## An empty answer means `java` on PATH.
java_home! : Str => Try(Str, _)
java_home! = |home| {
	set = Env.var_str!("JAVA_HOME") ?? ""
	studio = [
		"/Applications/Android Studio.app/Contents/jbr/Contents/Home",
		"${home}/Applications/Android Studio.app/Contents/jbr/Contents/Home",
		"/opt/android-studio/jbr",
		"${home}/android-studio/jbr",
	]
	var $found = ""
	for candidate in [set].concat(studio) {
		if $found.is_empty() and !candidate.is_empty() and (Path.exists!(Path.utf8("${candidate}/bin/java")) ?? Bool.False) {
			$found = candidate
		}
	}
	if $found.is_empty() and !Cmd.check_available!("java") {
		Err(JavaMissing)
	} else {
		Ok($found)
	}
}

## The Android debug key, `~/.android/debug.keystore`, made if missing as
## Android Studio makes it.
keystore! : Apk.Sdk => Try(Str, _)
keystore! = |sdk| {
	home = Env.var_str!("HOME") ?? "."
	path = "${home}/.android/debug.keystore"
	if !(Path.exists!(Path.utf8(path))?) {
		Path.create_all!(Path.utf8("${home}/.android"))?
		keytool = if (sdk.java_home.is_empty()) "keytool" else "${sdk.java_home}/bin/keytool"
		tool!(sdk, "keytool", keytool, ["-genkeypair", "-keystore", path, "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"], ".")?
	}
	Ok(path)
}

## Run an SDK tool in `dir`, with the JDK on PATH; its output is shown only
## if it fails.
tool! : Apk.Sdk, Str, Str, List(Str), Str => Try({}, _)
tool! = |sdk, name, program, args, dir| {
	base = Cmd.new(OsStr.from_str(program)).args(args.map(OsStr.from_str)).cwd(Path.utf8(dir)).stdin(Null).stderr(MergeWithStdout)
	command =
		if sdk.java_home.is_empty() {
			base
		} else {
			path = Env.var_str!("PATH") ?? ""
			base.env(OsStr.from_str("JAVA_HOME"), OsStr.from_str(sdk.java_home)).env(OsStr.from_str("PATH"), OsStr.from_str("${sdk.java_home}/bin:${path}"))
		}
	output = command.run!()?
	if output.status == Exited(0) {
		Ok({})
	} else {
		Err(ToolFailed({ tool: name, output: Str.from_utf8_lossy(output.stdout_bytes) }))
	}
}

expect Apk.newest(["35.0.1", "36.0.0", "34.0.0", "notes"], "") == Ok("36.0.0")
expect Apk.newest(["android-36", "android-37.0", "android-Baklava"], "android-") == Ok("android-37.0")
expect Apk.newest([], "") == Err(None)
expect Apk.api_level("android-37.0") == "37"

expect [Apk.app_name("My-App"), Apk.app_name("counter"), Apk.app_name("2048")] == ["my_app", "counter", "app2048"]

expect [Apk.valid_id("com.example.counter"), Apk.valid_id("counter"), Apk.valid_id("com.2x.app"), Apk.valid_id("com..app")] == [Bool.True, Bool.False, Bool.False, Bool.False]

expect {
	text = Apk.manifest({ id: "com.example.counter", label: "Tom & Jerry", api: "37" })
	text.contains("package=\"com.example.counter\"") and text.contains("android:label=\"Tom &amp; Jerry\"") and text.contains("android:targetSdkVersion=\"37\"")
}
