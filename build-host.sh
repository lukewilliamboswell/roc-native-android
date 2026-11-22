#!/bin/sh
# Build the Zig JNI host (host/host.zig) into each target's libhost.a, the
# host output the platform bundle ships (platform/targets/<target>/).
#
# Each archive is assembled from objects with fixed member names, so its
# bytes carry no path from the machine that built it.
#
#   arm64musl: host.o only; it defines memcpy/memset itself, the two C
#              runtime routines a freestanding Android .so needs
#   arm64mac:  host.o only (libSystem is resolved by Roc's own stub at link)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

"$ZIG" build-obj host/host.zig -target aarch64-linux-none -O ReleaseSmall -fPIC -fsingle-threaded -fno-builtin -fno-compiler-rt -femit-bin="$OUT/host.o"
mkdir -p platform/targets/arm64musl
rm -f platform/targets/arm64musl/libhost.a
(cd "$OUT" && "$ZIG" ar rcsD libhost.a host.o && rm host.o)
mv "$OUT/libhost.a" platform/targets/arm64musl/libhost.a

"$ZIG" build-obj host/host.zig -target aarch64-macos -O ReleaseSmall -fPIC -fno-compiler-rt -femit-bin="$OUT/host.o"
mkdir -p platform/targets/arm64mac
rm -f platform/targets/arm64mac/libhost.a
(cd "$OUT" && "$ZIG" ar rcsD libhost.a host.o)
mv "$OUT/libhost.a" platform/targets/arm64mac/libhost.a
