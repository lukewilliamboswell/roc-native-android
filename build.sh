#!/bin/sh
# Build the host, then every example for Android (arm64 .so into the app's
# jniLibs) and for macOS (.dylib for the desktop smoke test).
#
#   ROC=~/roc-macos_apple_silicon-0.1.0_RC1/roc ZIG=zig ./build.sh [example...]
set -e
cd "$(dirname "$0")"
ROC=${ROC:-roc}
EXAMPLES=${*:-counter todos stopwatch pages}
./build-host.sh
mkdir -p build android/app/src/main/jniLibs/arm64-v8a
for e in $EXAMPLES; do
	"$ROC" build --target=arm64musl --output="android/app/src/main/jniLibs/arm64-v8a/lib$e.so" "examples/$e/main.roc"
	"$ROC" build --target=arm64mac --output="build/lib$e.dylib" "examples/$e/main.roc"
done
