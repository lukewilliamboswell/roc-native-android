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
  - `host/resources.zig`: the file-handle heap, copied from roc-signals'
    host (see [Files](#files)).
  - `android/`: the Kotlin host and app.
- **No glue file**: the Roc↔host boundary is four functions taking
  `List(U8)`, a `Box` and `U32`, so a handwritten `extern struct` covers it.

## Examples

| Example | Exercises |
|---|---|
| `examples/counter` | local state, signals into text, `Engine.when` |
| `examples/todos` | store cells, a keyed table drawn through `table_view` row templates, a filter query, a text field and checkboxes |
| `examples/stopwatch` | an interval effect held by a watch site, cancelled when it is disposed |
| `examples/files` | a file chosen with the system chooser held as a handle: its name, size and type, a text preview (FX 11), its SHA-256 (FX 13), its URL (FX 14), and a saved copy (SAVE) |

Each example is its own launcher icon, with its own process and task, because a
library holds one engine and every app exports the same JNI names.

## Layout

```
host/host.zig        the JNI host (Zig), built into each target's libhost.a
platform/main.roc    the platform root, Ui.roc and the verbatim engine
platform/targets/    host outputs, built by build-host.sh, never committed
examples/            the apps, depending on the working tree
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

## Files

Files follow roc-signals' design ("Files"): the app holds a handle, never a
file's bytes. `Ui.file_picker` is an `input type=file` with a files-kind
`change` handler (`Engine.on_files`), drawn as a Button that opens
`ACTION_OPEN_DOCUMENT`. `FilesFeature` keeps each chosen file (its content
`Uri`) under the token of a `Box(U64)` handle that `host.zig` mints in its
blob heap (`RocFiles.nativeStage`), then delivers the file record through
`roc_engine_files` (`RocFiles.nativeEvent`). Roc's reference count decides
when a handle goes; after every batch whose effects were performed the host
drains the released tokens (`RocFiles.nativeReleased`) and lets their files
go. A request holds its file until it is answered.

- FX 11 reads a range as bytes or text, bounded by the request's
  `max_bytes` and `maxFileBytes` (256 MiB); FX 13 is SHA-256 of a range;
  SAVE asks where to save with `ACTION_CREATE_DOCUMENT` and writes the range.
- FX 14 answers the file's `content://` URI as its URL, or for a part of a
  file a `file://` URI of a cached copy of the range (deleted with the
  handle). Neither is a web URL: an `ImageView` can show either.
- FX 12 (upload a range as an HTTP body, answered as kind 1) and FX 15
  (download a body into a new file handle, tag 21) use a small
  `HttpURLConnection` client of their own, without progress reports, cache
  or credentials policies.
- The plumbing is plain Kotlin over a `FileAccess`: the desktop smoke test
  serves it with temp files (`android/desktop/FilesSmoke.kt`).

## Spike limits

- The host implements only what the examples need: element, text, template
  and row commands, LISTEN, PROP value/checked, timers and intervals,
  startup ack and opportunity. Other effects (HTTP, storage, workers, ports)
  stop with "unsupported effect kind". NAV and STORAGE writes are ignored.
- Events: click, input, change (checkbox), and `done` (keyboard action).
- A Roc crash ends the process through JNI `FatalError` (the message goes to
  logcat). Boot bytes are empty: fallback entry, no storage or environment.
- Files: no drop or paste of files, no persisted Uri permissions (a chosen
  file is readable for the life of the process), no `File.fetch`/`upload`
  progress, and the INTERNET permission is not declared, so FX 12 and 15
  answer `Network` on a device until it is. A chooser or save dialog open
  when the process dies is forgotten.
- arm64 only.

## License

[UPL-1.0](LICENSE). See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for
what the platform bundle contains.
