#!/bin/sh
# Run each example (or those named) on the desktop JVM through the same JNI
# host and Kotlin Tree/Host code as the app (needs kotlinc and ./build.sh
# first). Every host file that does not import android.* is compiled in.
set -e
cd "$(dirname "$0")"
K=android/app/src/main/java/dev/roc/android
kotlinc $(grep -L '^import android' $K/*.kt) android/desktop/*.kt -include-runtime -d build/smoke.jar 2>/dev/null
for e in ${*:-$(ls examples)}; do java -jar build/smoke.jar "build/lib$e.dylib" "$e"; done
