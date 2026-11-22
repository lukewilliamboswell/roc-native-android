# Working in this repository

These are the rules for any agent, human or automated, changing this
repository. `blueprint run check` enforces the ones a machine can check; the
rest need a reviewer.

## Before you open a pull request

Run `blueprint run check` with a current Roc nightly installed as `roc`. It
is the same gate CI runs on `macos-15`. It checks the compiler pins, the
engine digests and formatting, and runs the scripts' tests. It then builds the
host and every example for both targets, runs `roc test`, and runs the
desktop smoke test of every example. It audits the Android libraries' links.
Last, it builds the platform bundle and an example against it. On a device
or emulator, check what the smoke test cannot: views, gestures, the keyboard
and rotation (see CONTRIBUTING.md).

## The engine is a verbatim copy

The modules `Repo.engine_modules` lists (`scripts/src/Repo.roc`) are
byte-identical copies of roc-signals' `platform/`. `tests/engine-modules.sha256`
holds their digests, checked against roc-signals `a13ff70e`. Never edit or
reformat them here. A change the engine needs is made in roc-signals first.

To take a newer engine, copy all of these modules from one roc-signals
commit. In the same commit, regenerate the digests with
`shasum -a 256 <the modules> > tests/engine-modules.sha256` and update the
revision above. The native vocabulary belongs in `platform/Ui.roc`, and the
root in `platform/main.roc`.

## The Android library links nothing

Each example's `lib<name>.so` must load on any Android: no `NEEDED` entry, no
undefined symbol, no TLS segment, and an export for every `external fun` the
Kotlin side declares. The `android` stage checks all four. Keep these in
`host/build.zig`:

- The `aarch64-linux-none` target, without libc or the NDK.
- `single_threaded` and `no_builtin`, with `bundle_compiler_rt = false`.
- The host's own `memcpy`, `memset` and `getauxval`.
- Its `DebugAllocator(thread_safe = false)` and its custom panic handler.

JNI is reached through the `JNIEnv` function table by index. Never add a JNI
header, `-lc`, `liblog` or another NDK library to make a link pass. A new
external library would be a linker input with its own release (see
CONTRIBUTING.md, "What the platform bundle redistributes").

## Roc runs on one thread

Every call into a Roc entry point happens on the engine's thread (the main
thread), one turn at a time. Work done elsewhere, such as HTTP, a file read
or a sensor, hops back before it calls the engine. Never re-enter the engine
from inside a turn. The host is built single-threaded and must stay so.

## The JNI boundary

The JNI exports in `host/host.zig` and the `external fun`s in `RocEngine.kt`
and `FilesFeature.kt` change together. A Roc list's capacity field stores the
capacity shifted left one bit, and the refcount (`usize` 1) sits before the
elements. Build lists through the host's helpers, never by hand.

## Roc compiler gotchas

- The platform and examples are checked, built and tested with `roc`, the
  latest nightly. Pin no compiler in them. Only the scripts under `scripts/`
  pin `roc: "<tag>"`, the `roc-stable` release `Blueprint.roc` names. Format
  scripts with `roc-stable fmt`, everything else with `roc fmt`.
- `app` is a reserved word; don't use it as a name.
- A parameter shadows a module function of the same name: `Ui.link`'s
  parameter is `link_text`, not `label`.
- `Engine.watch` never cancels what it issued. A start/stop effect such as an
  interval uses `Engine.watch_latest`.

## Change discipline

- `README.md` is the landing page for app authors: keep host internals and
  contributor material in `CONTRIBUTING.md`. When `platform/Ui.roc` changes,
  update the README's `Ui` table in the same commit.
- An unknown command or effect in the host stops with a clear message; never
  skip one silently.
- Each feature lives in its own Kotlin file and declares the FX kinds and
  opcodes it owns. A feature that can't run on the desktop keeps its protocol
  logic in plain Kotlin, so the smoke test can exercise it with fakes.
- Do not commit build outputs (`build/`, `platform/targets/`, `android/**/build/`)
  or `.blueprint/`.
- Do not commit or push unless asked.
