#!/bin/sh
# Contributor build: the host, then every example for macOS (.dylib, for
# the desktop smoke test) and for Android (build/android/*.so, to inspect;
# the APK's own copies are built by Gradle, see android/app/build.gradle.kts).
#
#   ROC=~/roc-macos_apple_silicon-0.1.0_RC1/roc ZIG=zig ./build.sh [example...]
set -e
cd "$(dirname "$0")"
ROC=${ROC:-roc}
EXAMPLES=${*:-counter todos stopwatch http}
./build-host.sh
mkdir -p build/android
for e in $EXAMPLES; do
	"$ROC" build --target=arm64musl --output="build/android/lib$e.so" "examples/$e/main.roc"
	"$ROC" build --target=arm64mac --output="build/lib$e.dylib" "examples/$e/main.roc"
done
