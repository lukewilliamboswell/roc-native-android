#!/bin/sh
# Run each example's engine on the desktop JVM through the same JNI host and
# Kotlin Tree/Host code as the app (needs kotlinc and ./build.sh first).
set -e
cd "$(dirname "$0")"
K=android/app/src/main/java/dev/roc/android
kotlinc $K/Tree.kt $K/Host.kt $K/RocEngine.kt $K/Wire.kt $K/Boot.kt $K/Feature.kt $K/NavigationFeature.kt android/desktop/*.kt -include-runtime -d build/smoke.jar 2>/dev/null
for e in ${*:-counter todos stopwatch pages}; do java -jar build/smoke.jar "build/lib$e.dylib" "$e"; done
