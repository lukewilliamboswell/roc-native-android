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
app [main] { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst", pf: platform "../../platform/main.roc" }

import engine.Engine exposing [Elem, In, Root, State]
import pf.Ui

main : Engine.Program
main = Engine.program_startup({}, Engine.entries({ fallback: Some("/") }, [{ path: "/", route: 0.U8 }]), |_route| Engine.state(0, view))

view : State(In(Root, I64), I64) -> Elem(In(Root, I64))
view = |count|
	Ui.column([
		Ui.title("Counter"),
		Ui.text_s(count.signal().map(|n| "Count: ${n.to_str()}")),
		Ui.row([
			Ui.button("-", count.update(|n| n - 1)),
			Ui.button("+", count.update(|n| n + 1)),
		]),
	])
```

Tapping "+" changes one text view; nothing else is redrawn.

## Getting started

You need:

- **Roc** as `roc` on your PATH: the 0.1.0 release candidate or a recent
  nightly.
- **Android Studio**, which installs the Android SDK and an emulator. In its
  Device Manager, create an **arm64** device (API 26 or later), or connect an
  arm64 phone with USB debugging on. Apps are built for arm64 only.

Download `android.roc`, the tool for creating, building and running an app,
and create one:

```sh
curl -LO https://github.com/lukewilliamboswell/roc-native-android/releases/download/0.0.1/android.roc
roc android.roc init counter
cd counter
roc android.roc run
```

`init` writes a counter app, `main.roc`, whose header names the platform and engine
releases, and a copy of `android.roc`. `run` builds the app, installs it on
the running emulator or connected phone, and starts it. Edit `main.roc` and
run it again.

| Command | What it does |
|---|---|
| `roc android.roc init [DIR]` | create an app in `DIR`, or in this directory |
| `roc android.roc build` | build `<name>.apk`, signed with the Android debug key |
| `roc android.roc run` | build, install and start the app |

`build` and `run` take `--id ID` (the application id, by default
`com.example.<name>`) and `--label TEXT` (the name under the launcher icon,
by default `<name>`), where `<name>` is the app's directory.

### What `android.roc` does

A phone runs your app as a native library, so the tool builds it with:

```sh
roc build --target=arm64musl --output=.android/lib/arm64-v8a/libapp.so main.roc
```

It then makes an APK from the library, a manifest written for your app, and
the platform's small Kotlin shell (`classes.dex`, which each release carries
in the tool). The SDK's `aapt2`, `zipalign` and `apksigner` do the
packaging; no Gradle project is involved. The tool finds the SDK through
`ANDROID_HOME` or where Android Studio puts it, and uses Android Studio's
JDK when `JAVA_HOME` is not set.

A plain `roc build main.roc` builds for the computer you are on, which is not
something a phone can load.

## Writing an app

Apps import `engine.Engine`, `engine.Port` and `engine.PortJson` from the
roc-signals package, and `pf.Ui` from the Android platform. When upgrading
an existing app, copy the `engine` dependency from the example above and
change imports of `pf.Engine`, `pf.Port` and `pf.PortJson` to use `engine`.
Use the same engine URL as the Android platform release your app builds with.

- **`Engine`**: state, signals, the shared store, tables, effects and entry
  routes. It is the same engine as roc-signals, so its
  [guide](https://lukewilliamboswell.github.io/roc-signals/docs/guide/)
  applies. Read "Ui" where it says "Html".
- **`Port`**: typed messages to services written in Kotlin, which is how an
  app reaches the rest of Android (sensors, notifications, the camera)
  without changes to the engine. An app's activity supplies its services by
  overriding `RocActivity.portServices`; see
  [`DevicePorts.kt`](android/app/src/main/java/dev/roc/android/DevicePorts.kt).
  An app built by `android.roc` cannot add Kotlin services yet, so ports
  work only in this repository's Gradle build for now.
- **`Ui`**: the screens. Each builder becomes one Android view:

| Builder | View |
|---|---|
| `Ui.column`, `Ui.row`, `Ui.card` (and `_with` forms taking attributes) | vertical and horizontal layouts, and a rounded card |
| `Ui.list` | a vertical layout with dividers |
| `Ui.scroll` | a scrolling area; it reports its scroll position and size, so long lists can draw only what is on screen |
| `Ui.rows`, `Ui.window` | a window of fixed-height rows from a long list (see the contacts example) |
| `Ui.spacer`, `Ui.divider` | spare space and a hairline |
| `Ui.title`, `Ui.text`, `Ui.text_s`, `Ui.warning`, `Ui.label` | text; `label` takes a `tone`, `size` and `bold` |
| `Ui.button`, `Ui.button_s` | a button |
| `Ui.link` | a link: a tap gives your handler the URL, and you decide where to go |
| `Ui.text_field`, `Ui.text_field_with` | a one-line text field, with focus, blur, the keyboard's action key and key handlers |
| `Ui.checkbox`, `Ui.switch`, `Ui.slider` | on/off controls and a slider |
| `Ui.file_picker`, `Ui.file_picker_with` | a button that opens the system file chooser; your handler gets the chosen files as handles |
| `Ui.fill`, `Ui.pad`, `Ui.selected`, `Ui.describe` | attributes: take the spare space, padding, a highlight, an accessibility description |

Sizes are in dp. Any element with a click handler is tappable.

## Examples

The examples live in this repository and depend on its working tree. To run
them all, see [CONTRIBUTING.md](CONTRIBUTING.md); to try one as an app, copy
its `main.roc` over the one `init` wrote and keep the dependency header written by `init`.

| Example | Shows |
|---|---|
| [`counter`](examples/counter/main.roc) | local state, text that follows it, showing and hiding |
| [`todos`](examples/todos/Todos.roc) | a store, a table of rows with a filter, a text field and checkboxes; the list is saved and comes back when the app restarts |
| [`stopwatch`](examples/stopwatch/main.roc) | a repeating timer that starts and stops with the app's state |
| [`device`](examples/device/main.roc) | device facts: online, dark mode, languages, a local clock through the time zone, and a saved preference |
| [`pages`](examples/pages/main.roc) | several pages with links between them, a page with a parameter, and the system back button |
| [`contacts`](examples/contacts/main.roc) | 10 000 contacts in a scrolling list that draws only what is on screen, a search field, keyboard shortcuts, and a detail card |
| [`files`](examples/files/main.roc) | choosing a file with the system chooser: its name, size and type, a text preview, its SHA-256, and saving a copy |
| [`ports`](examples/ports/main.roc) | calling Android from Roc through ports: device info, a Toast, the battery level and the accelerometer |
| [`http`](examples/http/main.roc) | an HTTPS request: loading, progress, status, network and timeout errors, retry and cancel |

## Limits

- Supported effects: timers and intervals; HTTP (`Engine.http`; no cookie
  store or HTTP cache, and plain `http://` is blocked by Android's default);
  local storage (kept across restarts) and session storage (kept while the
  process lives); and device facts: online, visible, languages, time zone,
  and the media queries `prefers-color-scheme` and `prefers-reduced-motion`
  (any other query reads false); ports; and files (choose, read, digest,
  save, upload and download). Workers are not supported yet: an app that
  asks for one stops with "unsupported effect kind".
- Input: taps, text entry with focus and blur, checkboxes, switches,
  sliders, the keyboard's action key and hardware keys on a focused control.
  No gestures (drag, long press, swipe) yet.
- arm64 devices and emulators only. CI cross-compiles every example for
  Android (`arm64musl`) and checks what each library links, and it runs
  every example on macOS (`arm64mac`) through the same host code. The
  Android builds themselves are run by hand on an emulator, not in CI.
- If your app crashes, the process ends and the message is in logcat
  (`adb logcat | grep -i roc`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md): how the platform works, building and
testing it, and what its bundle redistributes.

## License

[UPL-1.0](LICENSE). [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) lists
what the platform bundle contains.
