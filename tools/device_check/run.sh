#!/usr/bin/env bash
# Runs the app's parsing/matching code on a connected Android device or emulator.
# JVM unit tests can't catch Android-only problems (e.g. Android's stricter regex
# engine), which is exactly what crashed Jarvis once. Needs: a debug build, adb,
# javac, and the SDK's d8. Build with -PwithEmulator when using an x86_64 emulator.
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK=${ANDROID_HOME:-$HOME/android-sdk}
STDLIB=$(find ~/.gradle/caches -name 'kotlin-stdlib-2.*.jar' | head -1)
OUT=$(mktemp -d)
javac -source 11 -target 11 -cp "app/build/tmp/kotlin-classes/debug:$STDLIB" -d "$OUT" tools/device_check/DeviceCheck.java
"$SDK"/build-tools/*/d8 --min-api 26 --output "$OUT" "$OUT"/DeviceCheck*.class
adb push "$OUT/classes.dex" /data/local/tmp/check.dex >/dev/null
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk >/dev/null
adb shell dalvikvm64 -cp /data/local/tmp/check.dex:/data/local/tmp/app.apk DeviceCheck
