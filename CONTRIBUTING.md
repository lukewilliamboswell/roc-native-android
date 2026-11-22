# Contributing

How the platform works, how to build and test it, and what its bundle
redistributes. For writing apps, see the [README](README.md).

## How it works

```
 Roc app (examples/*)          platform/ (Roc)                 android/ (Kotlin)
 Ui.column / Ui.button  ──►  Engine + Core (verbatim)  ──►  Tree.kt         decode commands (protocol 23)
                             main.roc  keys/init/event      Host.kt         turns, effects → features
                             host/host.zig  JNI, no libc    ViewRenderer.kt Tree ─► Views
                                   │
                    roc build --target=arm64musl ─► lib<app>.so (packaged by Gradle)
                    roc build --target=arm64mac  ─► lib<app>.dylib (desktop smoke test)
```

The engine emits the same command stream it emits in the browser (see
roc-signals' `design.md`, Appendix B). The Kotlin host decodes it into a node
tree, and `ViewRenderer` maps `Ui` elements to Views, refreshing only the
elements a batch marked dirty. Effects and commands go to the `Feature` that
declares their kind or opcode (`Feature.kt`), one file per feature.

The engine lives in a per-process `RocRuntime`. An activity recreated by a
configuration change (rotation, theme) reattaches its views instead of
remounting. Before mount, the host reads the program's declarations
(`roc_engine_keys`) and builds the boot bytes from them (`Boot.kt`), with each
feature supplying its own facts.

### What is copied, what is new

- **Verbatim from roc-signals** (`platform/`): `Core`, `Engine`,
  `SignalCore`, `TableCore`, `View`, `Seq`, `Layout`, `EntryRoutes`,
  `ReadyIndex`, `Url`, `Spelling`, `MarkupAdvice`, `Web`, and for ports
  `Port`, `PortJson`, `PortJsonBody`, `PortRegistry`, `Wire` and
  `WorkerCodec` (which brings the `cbor` package). Never edit these here:
  `tests/engine-modules.sha256` records their digests, and `check` compares
  them. `Web` and the `parser` package are present only because the engine
  imports them.
- **This project's**:
  - `platform/main.roc`: the platform root.
  - `platform/Ui.roc`: the native vocabulary.
  - `host/host.zig`: the JNI host, with `host/resources.zig` (the file-handle
    heap, copied from roc-signals' host), built by `host/build.zig`.
  - `android/`: the Kotlin host and the app shell.
  - `cli/`: the `android.roc` tool package app authors use.
- **No glue file**: the Roc↔host boundary is a handful of functions over
  `List(U8)`, a `Box` and `U32`, so a handwritten `extern struct` covers it.

## Layout

```
Blueprint.roc        the development environment and its tasks (Blueprint.lock pins it)
host/                the JNI host (Zig); `zig build` makes each target's libhost.a
platform/main.roc    the platform root, Ui.roc and the verbatim engine
platform/targets/    host outputs, never committed
examples/            apps, depending on the working tree
android/             Gradle project: the Kotlin host and the app shell
android/desktop/     the desktop smoke test (runs the engine on the JVM)
cli/                 the android.roc tool package (Bundle.roc is generated per release)
scripts/             the tasks, as Roc scripts run by roc-stable
tests/               the engine modules' recorded digests
```

## Build and test

The environment is [roc-blueprint](https://github.com/lukewilliamboswell/roc-blueprint)
0.13.0 on Apple silicon macOS. It fetches `roc-stable` (which runs the
scripts), Zig and the Kotlin compiler at the digests `Blueprint.lock`
records. It expects a current Roc nightly (or 0.1.0 RC1) as `roc`, and Java,
Python 3 and Xcode's command-line tools from the machine.

```sh
blueprint run check            # the pull-request gate, as CI runs it
blueprint run check -- smoke   # one stage (pins engine fmt scripts build test smoke android bundle)
blueprint run build            # host, then every example (build/lib<e>.dylib, build/android/lib<e>.so)
blueprint run build -- counter # host and one example
blueprint run smoke            # every example on the desktop JVM
blueprint run bundle           # the platform bundle, into build/platform/
blueprint run bundle -- --test # and examples/counter built against it from localhost
blueprint run apk              # the Android app shell, with every example (needs the Android SDK)
blueprint run release -- 0.0.2 # a release's assets, into build/release/0.0.2/
```

### Running the examples on a device

Every example gets its own launcher icon in one Gradle-built app:

```sh
cd android
printf 'roc.exe=%s\nzig.exe=%s\n' "$(which roc)" "$(which zig)" >> local.properties
./gradlew installDebug                          # or -Proc.examples=counter,todos
```

Or open `android/` in Android Studio and press **Run**. A new example needs
`examples/<name>/main.roc`, an activity subclass in `RocActivity.kt` and a
manifest entry (see "Adding a feature or an example").

The smoke test loads each example's macOS `.dylib`, which is the same Roc app
and Zig host as the Android `.so`, and drives it through the same
`Tree`/`Host` code the app uses. It compiles every app Kotlin file without an
`import android` line, with `android/desktop/`. Features whose Android parts
can't run on the desktop keep their protocol logic in plain Kotlin, so the
smoke test can exercise it with fakes.

The `android` stage audits each `.so`. It must need no other library, leave
no symbol undefined and have no TLS segment, and it must export every
`external fun` the Kotlin side declares. CI cannot run an Android device, so
on a device or emulator check what neither stage can: views, gestures, the
keyboard and rotation. The examples have been run on the API 37 arm64
emulator. If the emulator refuses to start for lack of disk space, run
`emulator -avd <name> -partition-size 4096`.

### Adding a feature or an example

- **A feature:** a `Feature` in its own file that declares the FX kinds and
  opcodes it owns, registered in `androidFeatures` in `RocRuntime.kt`.
- **An example:** `examples/<name>/main.roc`, an activity subclass in
  `RocActivity.kt`, and a manifest entry with its own process and task
  affinity. Each library holds one engine, and every app exports the same
  JNI names.

## Protocol coverage

- **Commands:** element, text, template and row commands, LISTEN, PROP
  value/checked, REF focus, TITLE, startup and opportunity.
- **Effects:** timers and intervals; subscriptions no feature serves are
  accepted and stay idle. Any other effect kind stops with "unsupported
  effect kind".
- **Storage** (op 16, FX 2, 17, 18, 20, boot sections 2 and 11;
  `StorageFeature.kt`, `AndroidStorage.kt`): local storage is one
  SharedPreferences file per app library; session storage is in memory for
  the process. Reads answer one task later. With a single window
  `StorageChanges` never reports. A write that throws reaches
  `StorageWriteFailures` (as `Unavailable`), but SharedPreferences applies
  writes asynchronously, so a disk failure is not seen.
- **Environment** (boot sections 3, 4, 6–10, FX 6, 7, 16, 22;
  `EnvironmentFeature.kt`, `AndroidEnvironment.kt`): online from
  ConnectivityManager, visible while the activity is started, languages from
  LocaleList, a SecureRandom seed, and the zone from java.time's rules (at
  most 128 offsets over the declared `zone_days`). Media queries know only
  `prefers-color-scheme` and `prefers-reduced-motion`. A
  deterministic-startup program boots on neutral facts and samples them with
  FX 22, as in the browser.
- **Navigation** (op 15, FX 5, boot section 1; `NavigationFeature.kt`):
  history lives in the runtime, so it survives rotation. System back pops it
  and answers popstate subscribers with tag 6, and at the root it leaves the
  app. URLs resolve against the current entry; other origins are refused.
  A click inside a `ui-link`/`a` with `href` carries the link facts (the app
  decides whether to navigate; nothing happens by default). The launching
  Intent's path is the first entry (deep links). History is not saved
  across process death.
- **Ports** (op 19, op 27, FX 9, FX 10, declaration section 5;
  `PortsFeature.kt`, `PortServices.kt`, `PortJson.kt`): these follow the
  browser runtime. The catalogue is checked at mount: a declared port
  without a Kotlin service of its shape, or a service for an undeclared
  port, refuses to mount. Calls answer once or time out. `Latest`/`Every(n)`
  subscriptions deliver one message per turn. Command failures and `UNSENT`
  go to logcat. Not yet: files in port messages, push ports, per-port
  counters.
- **Files** (`roc_engine_files`, FX 11–15, op 20; `FilesFeature.kt`,
  `AndroidFiles.kt`): the app holds a handle, never a file's bytes.
  `host.zig` mints `Box(U64)` handles in its blob heap (`RocFiles.nativeStage`)
  and delivers the file record through `roc_engine_files`. After each batch
  the host drains released tokens and lets their files go. FX 11 reads a
  range as bytes or text (bounded by `max_bytes` and 256 MiB). FX 13 is
  SHA-256. SAVE uses `ACTION_CREATE_DOCUMENT`. FX 14 answers the file's
  `content://` URI, or for part of a file a `file://` URI of a cached copy.
  FX 12/15 (upload and download) use a small client of their own, without
  progress, cache or credentials policies. Not yet: drop and paste of files,
  persisted Uri permissions.
- **HTTP** (FX 1, `HttpFeature.kt`): `HttpURLConnection` on a background
  pool, with answers, timeouts and progress (tag 26, receiving only) delivered
  on the engine thread. CANCEL disconnects and nothing is answered after it.
  Best effort: there is no HTTP cache or cookie store, so `cache` only adds
  fetch's request headers and `SameOrigin`/`Include` behave alike. Redirects
  follow `HttpURLConnection` (never across http/https). The response body
  has no bound beyond memory. A method `HttpURLConnection` refuses answers
  `Network`. File bodies (FX 12, 15) belong to the files feature.
- **Events:** click (with link facts), input, change (checkbox, switch,
  slider), `done` (the keyboard's action key), focus and blur, keys (KIND_KEY
  through keymaps, PROP 4/5) on a focused view, and scroll and size reports
  (KIND_SCROLL, KIND_SIZE) from `ui-scroll`, paced per frame through
  `Host.report` as the browser runtime does. A controlled value is deferred
  while its field is focused (`Input.kt`'s `TextControl`). An IME only
  composes in the focused view, so focus stands in for composition, and
  REF 4 applies at once. REF 1–7 are performed by the renderer. Not yet:
  document keys, `seen`, pointer events, horizontal scrolling.
- **Faults:** a Roc crash ends the process through JNI `FatalError`, and the
  message goes to logcat.

## Releasing

`blueprint run release -- VERSION` writes a release's three assets into
`build/release/VERSION/`:

- the platform bundle, `<hash>.tar.zst`;
- the `android.roc` tool package, `<hash>.tar.zst`. It is `cli/` with a
  generated `Bundle.roc` naming the version, the platform's URL and the
  `roc` it was tested with. It embeds the counter as the `init` scaffold and
  the Kotlin shell's `classes.dex`, which comes from the Gradle release build
  (`-Proc.examples=`, shrunk by R8, every `dev.roc.android` class kept);
- `android.roc`, the launcher an author downloads.

Before publishing, test the exact flow an author follows against a loopback
copy: run `release -- VERSION --base-url http://localhost:8787`, then serve
that directory with `python3 -m http.server 8787 --bind ::`. In an empty
directory, with an empty `XDG_CACHE_HOME`, download `android.roc`, then run
`init` and `run` on an emulator. Then build again without `--base-url`.
Publish the three files with
`gh release create VERSION build/release/VERSION/*`, and repeat the test
against GitHub. The platform bundle's name is its content hash, so it is the
same in both builds.

## What the platform bundle redistributes

`roc bundle` packs exactly the files it is given. `scripts/bundle.roc` stages
the tracked platform modules, each target's `libhost.a` and the root's
license files, and names every one of them. The bundle holds:

- the platform's `.roc` source;
- `targets/arm64musl/libhost.a` and `targets/arm64mac/libhost.a`: one
  `host.o` each. On Android it defines `memcpy`/`memset` itself, the only C
  runtime routines a freestanding `.so` needs, so no runtime library ships;
- `LICENSE` (UPL-1.0) and `THIRD_PARTY_LICENSES.md`.

`host/build.zig` archives one object, `host.o`, by name, so the archives'
bytes carry no build-machine paths. There are no external linker inputs, and
so nothing like `.tbd` stubs:

- **Android:** the linked `.so` has no undefined symbols, no `NEEDED`
  entries and no TLS. It uses no NDK library and no JNI header: JNI is
  reached through the `JNIEnv` function table the VM hands it, and Android
  APIs are called from Kotlin. The `android` stage of `check` checks
  this for every example and for the bundle's consumer test.
- **macOS:** the host uses only libSystem, which Roc's own stub resolves.

If the host ever calls an NDK library directly (`liblog`, `libandroid`), the
counterpart of a `.tbd` is a stub ELF `.so`. It would be generated from a
reviewed symbol catalog and released as an external linker input; apps link
against it, and the real library comes from the device. The Android SDK, AGP
and the Kotlin app shell are build tools of the app, never part of the
bundle.

## Not yet done

These come from the
[roc-automation](https://github.com/lukewilliamboswell/roc-automation)
guidance for platforms:

- **Releases from CI:** releases are built with `blueprint run release` and
  published by hand. Still to come: a `release.yml` that builds once, tests
  those bytes, generates SBOM sidecars and publishes through
  `actions/publish-release`, and a `nightly.yml` on the shared compatibility
  runner.
- **Recorded example output:** the smoke test's scripted cases live in
  Kotlin (`android/desktop/`), not as recorded expected output under
  `tests/`, so `release-package test-examples` cannot run them.
- **A fingerprinted host job:** a CI job that builds `libhost.a` once,
  keyed on a fingerprint of `host/`, Zig's version and the build options.
  The host builds in seconds, so `check` builds it every time.
- **An Android runner:** `arm64musl` is cross-compiled and audited in CI but
  only run by hand on an emulator.
- **Settings:** make `CI` the required status check, protect tags, set the
  default token to read-only and enable private vulnerability reporting.
  Then run roc-automation's `check-settings`.
- **Ports in tool-built apps:** `android.roc` packages one generic
  activity, so an app cannot yet supply Kotlin port services.
