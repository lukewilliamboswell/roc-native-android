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
  `WorkerCodec` (which brings the `cbor` package). Never edit these here. `Web` and the `parser` package are present only because the engine
  imports them.
- **This project's**:
  - `platform/main.roc`: the platform root.
  - `platform/Ui.roc`: the native vocabulary.
  - `host/host.zig`: the JNI host.
  - `android/`: the Kotlin host and the app shell.
- **No glue file**: the Roc↔host boundary is a handful of functions over
  `List(U8)`, a `Box` and `U32`, so a handwritten `extern struct` covers it.

## Layout

```
host/host.zig        the JNI host (Zig), built into each target's libhost.a
platform/main.roc    the platform root, Ui.roc and the verbatim engine
platform/targets/    host outputs, built by build-host.sh, never committed
examples/            apps, depending on the working tree
android/             Gradle project: the Kotlin host and the app shell
android/desktop/     the desktop smoke test (runs the engine on the JVM)
```

## Build and test

```sh
export ROC=~/roc-macos_apple_silicon-0.1.0_RC1/roc ZIG=zig
./build.sh            # host, then every example as .dylib (and .so in build/android)
./smoke.sh            # desktop JVM: the real engine through JNI, scripted interactions
./bundle.sh           # the platform bundle, into build/bundle/
cd android && ./gradlew installDebug   # roc-builds every example, packages the APK
```

`smoke.sh` needs `kotlinc`. It loads each example's macOS `.dylib`, which is
the same Roc app and Zig host as the Android `.so`, and drives it through the
same `Tree`/`Host` code the app uses. Features whose Android parts can't run
on the desktop keep their protocol logic in plain Kotlin, so the smoke test
can exercise it with fakes.

On a device, check what the smoke test can't: views, gestures, the keyboard
and rotation. The examples have been run on the API 37 arm64 emulator. If
the emulator refuses to start for lack of disk space, run
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
- **HTTP** (FX 1, `HttpFeature.kt`): `HttpURLConnection` on a background
  pool, with answers, timeouts and progress (tag 26, receiving only) delivered
  on the engine thread. CANCEL disconnects and nothing is answered after it.
  Best effort: there is no HTTP cache or cookie store, so `cache` only adds
  fetch's request headers and `SameOrigin`/`Include` behave alike. Redirects
  follow `HttpURLConnection` (never across http/https). The response body
  has no bound beyond memory. A method `HttpURLConnection` refuses answers
  `Network`. File bodies (FX 12, 15) belong to the files feature.
- **Events:** click, input, change (checkbox) and `done` (the keyboard's
  action key).
- **Faults:** a Roc crash ends the process through JNI `FatalError`, and the
  message goes to logcat.

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
  APIs are called from Kotlin. Check with
  `llvm-readelf -lW --dyn-syms -d lib<app>.so | grep -E 'TLS|NEEDED| UND [a-z_]'`,
  which should print nothing.
- **macOS:** the host uses only libSystem, which Roc's own stub resolves.

If the host ever calls an NDK library directly (`liblog`, `libandroid`), the
counterpart of a `.tbd` is a stub ELF `.so`. It would be generated from a
reviewed symbol catalog and released as an external linker input; apps link
against it, and the real library comes from the device. The Android SDK, AGP
and the Kotlin app shell are build tools of the app, never part of the
bundle.

## Not yet done

These items come from the roc-automation guidance for platforms:

- A `Blueprint.roc` pinning the toolchain, with the scripts turned into tasks.
- A CI job that builds the host, keyed on a fingerprint of its inputs.
- SBOM sidecars for the bundle.
- Example apps built from the bundle served on a loopback address, not from
  the working tree.
- A starter project for app authors: the Kotlin shell as a library or
  template, so an app need not live in this repository.
