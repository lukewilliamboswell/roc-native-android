import cli.OsStr
import cli.Stderr
import cli.Stdout
import Apk
import Bundle
import Init

## The `android.roc` tool: create, build and run a roc-native-android app.
## A project runs it with the small `android.roc` app each release
## publishes, from the app's directory:
##
## ```sh
## roc android.roc init my-app   # create an app
## roc android.roc run           # build it, install it on a device and start it
## roc android.roc build         # build its APK
## ```
Android :: [].{

	## What the user asked for.
	Command : [Help, Init(Str), Build(Apk.Options), Run(Apk.Options)]

	## Run the tool on the arguments `roc android.roc` passed it. Every
	## failure is explained in a sentence on standard error and ends the
	## program with a non-zero exit code (2 for a misused command line).
	run! : List(OsStr.OsStr) => Try({}, [Exit(I32)])
	run! = |raw| {
		args = raw.map(OsStr.display).drop_if(|arg| arg == "--")
		outcome = match Android.parse(args) {
			Ok(Help) => Stdout.line!(Android.usage)
			Ok(Init(dir)) => Init.run!(dir)
			Ok(Build(chosen)) => Apk.build!(chosen).map_ok(|_| {})
			Ok(Run(chosen)) => Apk.run!(chosen)
			Err(problem) => Err(problem)
		}
		match outcome {
			Ok({}) => Ok({})
			Err(problem) => {
				{ text, code } = Android.explain(problem)
				Stderr.line!(text) ?? {}
				Err(Exit(code))
			}
		}
	}

	## The command the arguments name.
	parse : List(Str) -> Try(Command, [UnknownCommand(Str), UnexpectedArguments({ command : Str, extra : List(Str) })])
	parse = |args|
		match args {
			[] => Ok(Help)
			["help", ..] | ["--help", ..] | ["-h", ..] => Ok(Help)
			["init"] => Ok(Init("."))
			["init", dir] if !dir.starts_with("-") => Ok(Init(dir))
			["build", .. as rest] => Android.options("build", rest, { id: "", label: "" }).map_ok(|chosen| Build(chosen))
			["run", .. as rest] => Android.options("run", rest, { id: "", label: "" }).map_ok(|chosen| Run(chosen))
			[command, .. as extra] if command == "init" => Err(UnexpectedArguments({ command, extra }))
			[command, ..] => Err(UnknownCommand(command))
		}

	options : Str, List(Str), Apk.Options -> Try(Apk.Options, [UnexpectedArguments({ command : Str, extra : List(Str) })])
	options = |command, args, found|
		match args {
			[] => Ok(found)
			["--id", id, .. as rest] if !id.starts_with("-") => Android.options(command, rest, { ..found, id })
			["--label", label, .. as rest] if !label.starts_with("-") => Android.options(command, rest, { ..found, label })
			extra => Err(UnexpectedArguments({ command, extra }))
		}

	usage =
		\\android.roc creates, builds and runs a roc-native-android app. Run it
		\\from the app's directory:
		\\
		\\  roc android.roc init [DIR]   Create an app in DIR, or in this directory.
		\\  roc android.roc build        Build the app's APK, <name>.apk.
		\\  roc android.roc run          Build it, install it on the connected device or
		\\                               emulator, and start it.
		\\
		\\build and run take --id ID (the application id, by default com.example.<name>)
		\\and --label TEXT (the name under the launcher icon, by default <name>), where
		\\<name> is the app's directory.
		\\
		\\They need Roc as `roc` and the Android SDK, as Android Studio installs it.
		\\This is roc-native-android ${Bundle.version}, tested with Roc ${Bundle.roc_version}.

	## The sentence a failure is explained with, and the exit code.
	explain : _ -> { text : Str, code : I32 }
	explain = |problem|
		match problem {
			UnknownCommand(command) => { code: 2, text: "There is no command \"${command}\". The commands are init, build and run; `roc android.roc help` describes them." }
			UnexpectedArguments({ command, extra }) => { code: 2, text: "${command} does not take ${Str.join_with(extra.map(|a| "\"${a}\""), " ")}. `roc android.roc help` describes it." }
			RocMissing => { code: 1, text: "The Roc compiler is not on your PATH as `roc`, so the app cannot be built. Install Roc ${Bundle.roc_version} (or a later nightly) and put it on your PATH as `roc`." }
			NoMainRoc => { code: 1, text: "There is no main.roc here, so there is no app to build. Run this from your app's directory, or create an app with `roc android.roc init my-app`." }
			SdkMissing => { code: 1, text: "The Android SDK was not found. Install Android Studio, which installs the SDK, or set ANDROID_HOME to the SDK's directory." }
			SdkPartMissing(part) => { code: 1, text: "The Android SDK has no ${part}. Install it with Android Studio's SDK Manager." }
			JavaMissing => { code: 1, text: "Java was not found, and the Android SDK's apksigner needs it. Install Android Studio (its bundled JDK is used), or set JAVA_HOME." }
			BadId(id) => { code: 2, text: "\"${id}\" is not an application id: it needs two or more parts separated by dots, each starting with a letter, such as com.example.counter." }
			BuildFailed => { code: 1, text: "The build failed: the compiler's report is above." }
			ToolFailed({ tool, output }) => { code: 1, text: "${output.trim()}\n\nThe Android SDK's ${tool} failed: its report is above." }
			NoDevice => { code: 1, text: "No Android device is connected. Start an arm64 emulator in Android Studio's Device Manager, or connect a phone with USB debugging, then try again." }
			InitWouldOverwrite(path) => { code: 1, text: "${path} already exists, so init changed nothing: it only creates files that are not there yet. Choose a new directory, for example `roc android.roc init my-app`." }
			NoToolToCopy(dir) => { code: 1, text: "init copies android.roc into the new app, but there is no android.roc in this directory, so ${dir} was not created. Run init from the directory that holds android.roc." }
			other => { code: 1, text: "android.roc stopped on an error it did not expect: ${Str.inspect(other)}" }
		}
}

## Each command line names its command, with the defaults filled in.
expect {
	actual = [[], ["help"], ["init"], ["init", "my-app"], ["build"], ["run", "--id", "org.me.app", "--label", "My App"]].map(Android.parse)
	expected = [Ok(Help), Ok(Help), Ok(Init(".")), Ok(Init("my-app")), Ok(Build({ id: "", label: "" })), Ok(Run({ id: "org.me.app", label: "My App" }))]
	actual == expected
}

## A misused command line is refused with what was wrong.
expect {
	actual = [["buld"], ["build", "--watch"], ["run", "--id"], ["init", "a", "b"]].map(Android.parse)
	expected = [Err(UnknownCommand("buld")), Err(UnexpectedArguments({ command: "build", extra: ["--watch"] })), Err(UnexpectedArguments({ command: "run", extra: ["--id"] })), Err(UnexpectedArguments({ command: "init", extra: ["a", "b"] }))]
	actual == expected
}
