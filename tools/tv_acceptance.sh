#!/usr/bin/env bash
# Android TV APK installation and remote control acceptance.
set -euo pipefail
PKG="io.personal.filemanager"
APK="app/build/outputs/apk/debug/app-universal-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb install -g "$APK"
adb install "$TEST_APK"
if [[ "${API_LEVEL:-30}" -ge 30 ]]; then
  adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
fi
# Catch instrumentation failure even when piped through tee.
set +e
adb shell am instrument -w -r -e class "$PKG.TvNavigationTest" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee tv-test.log
rc=${PIPESTATUS[0]}
set -e
adb exec-out screencap -p > tv-screen.png || true
adb logcat -d -t 3000 > tv-logcat.txt || true
if [[ "$rc" != 0 ]] || ! grep -q 'OK (' tv-test.log || grep -q 'FAILURES' tv-test.log; then
  echo "::error::Android TV D-pad instrumentation failed"
  exit 1
fi
adb shell am start -W -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER -p "$PKG"
