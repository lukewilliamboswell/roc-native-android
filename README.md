# Roc Native Android

> This is an early proof of concept. My goal is to show how roc-signals might
> look for native UI. I'm not sure where this project will go: I've written it
> for a bit of fun, and to help with the development of the reactive engine in
> Roc. If you're interested in this work, let me know.

Write Android apps in [Roc](https://www.roc-lang.org). Your app is pure Roc;
it draws real Android widgets (no WebView), and the reactive engine from
[roc-signals](https://github.com/lukewilliamboswell/roc-signals) updates
exactly the views that depend on what changed.

```roc
app [main] { pf: platform "../../platform/main.roc" }

import pf.Engine exposing [Elem, In, Root, State]
import pf.Ui

main : Engine.Program
main = Engine.program_startup({}, Engine.entries({ fallback: Some("/") }, [{ path: "/", route: 0.U8 }]), |_route| Engine.state(0, view))

view : State(In(Root, I64), I64) -> Elem(In(Root, I64))
view = |count|
	Ui.column(
		[
			Ui.title("Counter"),
			Ui.text_s(count.signal().map(|n| "Count: ${n.to_str()}")),
			Ui.row([
				Ui.button("-", count.update(|n| n - 1)),
				Ui.button("+", count.update(|n| n + 1)),
			]),
		],
	)
```

Tapping "+" changes one text view; nothing else is redrawn.

## Getting started

You need:

- **Roc**: the 0.1.0 release candidate or a recent nightly.
- **Android Studio**: it brings the SDK and an emulator. Create an **arm64**
  device (API 26 or later). Apps are built for arm64 only.
- **Zig**: only while the platform is built from source. A published
  platform release will carry its native parts, and Zig won't be needed.

There is no published release yet, so start from this repository:

```sh
git clone https://github.com/lukewilliamboswell/roc-native-android
cd roc-native-android/android
printf 'roc.exe=%s\nzig.exe=%s\n' "$(which roc)" "$(which zig)" >> local.properties
./gradlew installDebug
```

Or open `android/` in Android Studio and press **Run**. The first build takes
a few minutes. After that, only the apps you changed are rebuilt.

Each app in `examples/` gets its own launcher icon. To start your own, copy
an example to `examples/<name>/main.roc`. Gradle finds it automatically. For
now you also add a one-line activity for it, in
`android/app/src/main/java/dev/roc/android/RocActivity.kt` and
`AndroidManifest.xml`; follow the existing examples.

### What Gradle does with `roc build`

The phone runs your app as a native library, so Gradle builds each app with:

```sh
roc build --target=arm64musl --output=libcounter.so examples/counter/main.roc
```

It then packages the result with the platform's small Kotlin shell into an
APK. A plain `roc build main.roc` builds for the computer you are on. That
gives a desktop library the platform uses for its own tests, not something a
phone can load.

## Writing an app

Apps use two modules from the platform:

- **`Engine`**: state, signals, the shared store, tables, effects and entry
  routes. It is the same engine as roc-signals, so its
  [guide](https://lukewilliamboswell.github.io/roc-signals/docs/guide/)
  applies. Read "Ui" where it says "Html".
- **`Ui`**: the screens. Each builder becomes one Android view:

| Builder | View |
|---|---|
| `Ui.column`, `Ui.list` | a vertical layout (`list` adds dividers) |
| `Ui.row` | a horizontal layout; plain text in it takes the spare width |
| `Ui.title`, `Ui.text`, `Ui.text_s`, `Ui.warning` | text |
| `Ui.button`, `Ui.button_s` | a button |
| `Ui.text_field` | a one-line text field (`on_change`, `on_done`) |
| `Ui.checkbox` | a checkbox |
| `Ui.describe` | an accessibility description for any element |

## Examples

| Example | Shows |
|---|---|
| [`counter`](examples/counter/main.roc) | local state, text that follows it, showing and hiding |
| [`todos`](examples/todos/Todos.roc) | a store, a table of rows with a filter, a text field and checkboxes; the list is saved and comes back when the app restarts |
| [`stopwatch`](examples/stopwatch/main.roc) | a repeating timer that starts and stops with the app's state |
| [`device`](examples/device/main.roc) | device facts: online, dark mode, languages, a local clock through the time zone, and a saved preference |
| [`http`](examples/http/main.roc) | an HTTPS request: loading, progress, status, network and timeout errors, retry and cancel |

## Limits

- Supported effects: timers and intervals; HTTP (`Engine.http`; no cookie
  store or HTTP cache, and plain `http://` is blocked by Android's default);
  local storage (kept across restarts) and session storage (kept while the
  process lives); and device facts: online, visible, languages, time zone,
  and the media queries `prefers-color-scheme` and `prefers-reduced-motion`
  (any other query reads false). Files, workers and ports are not supported
  yet: an app that asks for one stops with "unsupported effect kind".
- Input is limited to taps, text entry, checkboxes and the keyboard's action
  key.
- arm64 devices and emulators only.
- If your app crashes, the process ends and the message is in logcat
  (`adb logcat | grep -i roc`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md): how the platform works, building and
testing it, and what its bundle redistributes.

## License

[UPL-1.0](LICENSE). [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) lists
what the platform bundle contains.
