# roc-native-android (spike)

> This is an early proof of concept. My goal is to show how roc-signals might
> look for native UI. I'm not sure where this project will go: I've written it
> for a bit of fun, and to help with the development of the reactive engine in
> Roc. If you're interested in this work, let me know.

A Roc platform for native Android UI, built on the reactive engine from
[roc-signals](../roc-signals). Apps are pure Roc; the engine decides exactly
which Views change, and a Kotlin host applies its command stream to real
Android widgets (no WebView).

```
 Roc app (examples/*)          platform/ (Roc)                 android/ (Kotlin)
 Ui.column / Ui.button  ──►  Engine + Core (verbatim)  ──►  Tree.kt   decode commands (protocol 23)
                             main.roc  provides init/event   Host.kt   turns, timers, startup ack
                             host/host.zig  JNI, no libc     ViewRenderer.kt  Tree ─► Views
                                   │
                    roc build --target=arm64musl ─► libcounter.so (jniLibs/arm64-v8a)
                    roc build --target=arm64mac  ─► libcounter.dylib (desktop smoke test)
```

## What is copied, what is new

- **Verbatim from roc-signals** (`platform/`): `Core`, `Engine`, `SignalCore`,
  `TableCore`, `View`, `Seq`, `Layout`, `EntryRoutes`, `ReadyIndex`, `Url`,
  `Spelling`, `MarkupAdvice`, `Web`. `Web` and the `parser` package are there
  only because the engine imports them (it checks element/attribute names);
  apps never see them.
- **New**:
  - `platform/Ui.roc`: the native vocabulary (`column`, `row`, `list`,
    `title`, `text`, `button`, `text_field`, `checkbox`, …), one engine
    element per View.
  - `platform/main.roc`: the platform root, a slimmed roc-signals root with
    mount, event and drop only.
  - `host/host.zig`: the JNI host. It is freestanding, with no libc
    and no NDK: the `.so` has no undefined symbols, no `NEEDED` entries and
    no TLS.
  - `android/`: the Kotlin host and app.
- **No glue file**: the Roc↔host boundary is four functions taking
  `List(U8)`, a `Box` and `U32`, so a handwritten `extern struct` covers it.

## Examples

| Example | Exercises |
|---|---|
| `examples/counter` | local state, signals into text, `Engine.when` |
| `examples/todos` | store cells, a keyed table drawn through `table_view` row templates, a filter query, a text field and checkboxes |
| `examples/stopwatch` | an interval effect held by a watch site, cancelled when it is disposed |
| `examples/http` | `Engine.http` over HTTPS from a `watch_latest` site: loading, progress, status, network and timeout failures, retry and cancel |

Each example is its own launcher icon, with its own process and task, because a
library holds one engine and every app exports the same JNI names.

## Layout

```
host/host.zig        the JNI host (Zig), built into each target's libhost.a
platform/main.roc    the platform root, Ui.roc and the verbatim engine
platform/targets/    host outputs, built by build-host.sh, never committed
examples/            the three apps, depending on the working tree
android/             the Kotlin host and app shell (not part of the bundle)
```

## Build and run

```sh
ROC=~/roc-macos_apple_silicon-0.1.0_RC1/roc ZIG=zig ./build.sh   # host + all examples (.so and .dylib)
./smoke.sh                                                        # desktop JVM: real engine via JNI, scripted taps
ROC=... ./bundle.sh                                               # the platform bundle, into build/bundle/
cd android && ./gradlew installDebug                              # needs the Android SDK (Android Studio)
```

## What the platform bundle redistributes

`roc bundle` finds the `.roc` modules itself. It only checks that the target
inputs exist, so `bundle.sh` names them, and the license files, explicitly.
The bundle holds:

- the platform's `.roc` source;
- `targets/arm64musl/libhost.a` and `targets/arm64mac/libhost.a`: one
  `host.o` each. On Android it defines `memcpy`/`memset` itself, the only C
  runtime routines a freestanding `.so` needs, so no runtime library ships;
- `LICENSE` (UPL-1.0) and `THIRD_PARTY_LICENSES.md`.

The archives are assembled from objects with fixed member names, so their
bytes carry no build-machine paths. There are no external linker inputs, and
so nothing like `.tbd` stubs:

- **Android:** the linked `.so` has no undefined symbols, no `NEEDED`
  entries and no TLS. It uses no NDK library and no JNI header: JNI is
  reached through the `JNIEnv` function table the VM hands it, and Android
  APIs are called from Kotlin.
- **macOS:** the host uses only libSystem, which Roc's own stub resolves.

If the host ever calls an NDK library directly (`liblog`, `libandroid`), the
counterpart of a `.tbd` is a stub ELF `.so`: generated from a reviewed symbol
catalog, released as an external linker input, and linked against, while the
real library comes from the device. The Android SDK, AGP and the Kotlin app
shell are build tools of the app, never part of the bundle.

Alternatively, open `android/` in Android Studio and press Run with an arm64
emulator or a device. The app targets API 37 with AGP 9.5 (built-in Kotlin).
It has been run on the API 37 arm64 emulator: all three examples mount, take
taps and typing, and the stopwatch's interval stops when it is cancelled. If the
emulator refuses to start for lack of disk space, run
`emulator -avd <name> -partition-size 4096`.

## Spike limits

- The host implements only what the examples need: element, text, template
  and row commands, LISTEN, PROP value/checked, timers and intervals,
  startup ack and opportunity, and HTTP. Other effects (storage, workers, ports)
  stop with "unsupported effect kind". NAV and STORAGE writes are ignored.
- HTTP (FX kind 1, `HttpFeature.kt`) runs on `HttpURLConnection` in a
  background pool; answers, timeouts (one engine-thread timer, capped at
  600 s) and progress (tag 26, receiving only) are delivered on the engine
  thread, and CANCEL disconnects without answering. Best effort: no HTTP
  cache or cookie store is installed, so `cache` only adds fetch's request
  headers (`NoStore`/`Reload`: `Cache-Control`/`Pragma: no-cache`,
  `NoCache`: `max-age=0`) and `SameOrigin`/`Include` behave alike (no ambient
  cookies, app headers always sent). Redirects follow `HttpURLConnection`
  (not across http/https). The response body has no host bound beyond
  memory. Methods `HttpURLConnection` refuses (PATCH on the desktop JVM)
  answer `Network`. Cleartext http is off (Android's default). File bodies (FX 12, 15)
  are not here.
- Events: click, input, change (checkbox), and `done` (keyboard action).
- A Roc crash ends the process through JNI `FatalError` (the message goes to
  logcat). Boot bytes are empty: fallback entry, no storage or environment.
- arm64 only.

## License

[UPL-1.0](LICENSE). See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for
what the platform bundle contains.
