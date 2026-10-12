#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-evidence
trap 'adb pull /sdcard/Android/data/cc.nkbr.lanzouplus/files/popup183 ui-evidence/ || true; adb logcat -d -s AndroidRuntime > ui-evidence/android-runtime.txt || true' EXIT
adb install -r app/build/outputs/apk/empty/debug/app-empty-debug.apk
adb install -r app/build/outputs/apk/androidTest/empty/debug/app-empty-debug-androidTest.apk
adb shell appops set cc.nkbr.lanzouplus MANAGE_EXTERNAL_STORAGE allow
adb shell dumpsys deviceidle whitelist +cc.nkbr.lanzouplus
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell wm density 320
for case_name in portrait-light portrait-dark landscape-dark-font150; do
  adb shell am force-stop cc.nkbr.lanzouplus
  if [[ "$case_name" == portrait-light ]]; then
    adb shell cmd uimode night no
    adb shell settings put system font_scale 1.0
    adb shell wm size 780x1688
  elif [[ "$case_name" == portrait-dark ]]; then
    adb shell cmd uimode night yes
    adb shell settings put system font_scale 1.0
    adb shell wm size 780x1688
  else
    adb shell cmd uimode night yes
    adb shell settings put system font_scale 1.5
    adb shell wm size 1688x780
  fi
  adb shell am instrument -w -e class cc.nkbr.lanzouplus.PopupRuntime183Test -e caseName "$case_name" cc.nkbr.lanzouplus.test/androidx.test.runner.AndroidJUnitRunner > "ui-evidence/$case_name.txt"
  cat "ui-evidence/$case_name.txt"
  if ! grep -Eq 'OK \(1 test\)' "ui-evidence/$case_name.txt"; then
    exit 1
  fi
  adb pull /sdcard/Android/data/cc.nkbr.lanzouplus/files/popup183 ui-evidence/
done
