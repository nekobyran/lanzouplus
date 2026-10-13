#!/usr/bin/env bash
set -euo pipefail
mkdir -p signed-evidence
trap 'adb logcat -d -s AndroidRuntime > signed-evidence/android-runtime.txt || true' EXIT
adb logcat -c
for apk in signed-apks/*.apk; do
  name=$(basename "$apk" .apk)
  "$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$apk" > "signed-evidence/$name-manifest.txt"
  package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "signed-evidence/$name-manifest.txt")
  activity=$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" "signed-evidence/$name-manifest.txt")
  test -n "$package"
  test -n "$activity"
  adb install "$apk"
  adb shell am start -W -n "$package/$activity" > "signed-evidence/$name-start.txt"
  sleep 5
  adb shell pidof "$package" > "signed-evidence/$name-pid.txt"
  adb exec-out screencap -p > "signed-evidence/$name-start.png"
  grep -q 'Status: ok' "signed-evidence/$name-start.txt"
  test -s "signed-evidence/$name-pid.txt"
  adb shell am force-stop "$package"
done
adb logcat -d -s AndroidRuntime > signed-evidence/android-runtime.txt
if grep -q 'FATAL EXCEPTION' signed-evidence/android-runtime.txt; then exit 1; fi
