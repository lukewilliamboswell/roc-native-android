# Third-party licenses

What the roc-native-android platform bundle contains, and the license of each.

| Bundle path | Contents | License |
|---|---|---|
| `*.roc` | Platform source. `Core`, `Engine`, `SignalCore`, `TableCore`, `View`, `Seq`, `Layout`, `EntryRoutes`, `ReadyIndex`, `Url`, `Spelling`, `MarkupAdvice`, `Web`, `Port`, `PortJson`, `PortJsonBody`, `PortRegistry`, `Wire` and `WorkerCodec` are copied unchanged from roc-signals; `main.roc` and `Ui.roc` are this project's | UPL-1.0 (`LICENSE`) |
| `targets/arm64mac/libhost.a`, `targets/arm64musl/libhost.a` | `host.o`: this project's `host/host.zig` (including its own `memcpy`/`memset` on Android) and `host/resources.zig` (the file-handle heap, copied from roc-signals, UPL-1.0), compiled with parts of Zig's standard library (its allocator and formatting code) | UPL-1.0 (`LICENSE`); the Zig standard library code is MIT, below |

Not contained, and not redistributed by this bundle:

- The `parser` and `cbor` packages, which a consumer fetches from the URLs
  in `main.roc` (`cbor` because `WorkerCodec`, which the port layer imports,
  uses it).
- Anything from the Android SDK or NDK, or from a JDK. The host includes no
  JNI header: it calls the JNI function table by its index, as the JNI
  specification defines it. On Android the `.so` an app links needs no other
  library at all.
- Anything from Apple's SDKs. `targets/arm64mac/libhost.a` refers to the
  libSystem functions `mmap`, `munmap`, `bzero`, `memcpy`, `memset` and
  `__error`, which Roc's own libSystem stub resolves when an app is linked.

## Zig standard library

The MIT License (Expat)

Copyright (c) Zig contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
