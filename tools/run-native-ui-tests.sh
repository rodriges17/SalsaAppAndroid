#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/native-diagnostics
adb logcat -c
finish() {
  result=$?
  adb logcat -d -v brief > app/build/native-diagnostics/logcat.txt || true
  adb shell dumpsys activity activities > app/build/native-diagnostics/activities.txt || true
  adb shell uiautomator dump /sdcard/native-final-ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/native-final-ui.xml app/build/native-diagnostics/ >/dev/null 2>&1 || true
  adb exec-out screencap -p > app/build/native-diagnostics/screen.png || true
  if [ "$result" -ne 0 ]; then
    echo 'Android failure diagnostics:'
    rg -A 35 -B 3 'FATAL EXCEPTION|AndroidRuntime|chromium.*(ERROR|Uncaught)' app/build/native-diagnostics/logcat.txt | tail -200 || true
    cat app/build/native-diagnostics/native-final-ui.xml || true
  fi
}
trap finish EXIT
gradle --no-daemon :app:connectedDebugAndroidTest
