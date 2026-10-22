#!/usr/bin/env bash
set -euo pipefail
baseline=$1
updated=$2
package=com.stepbystep.salsa
# This runs only on the disposable CI emulator, after the normal UI tests.
adb uninstall "$package" >/dev/null 2>&1 || true
adb install "$baseline"
adb shell run-as "$package" mkdir -p files
printf 'upgrade-data-survives' | adb shell run-as "$package" tee files/signing-upgrade-marker >/dev/null
adb install -r "$updated"
actual=$(adb shell run-as "$package" cat files/signing-upgrade-marker | tr -d '\r')
test "$actual" = 'upgrade-data-survives'
echo 'APK upgrade succeeded and preserved app data.'
