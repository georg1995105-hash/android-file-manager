#!/usr/bin/env bash
# Android TV APK installation and remote control acceptance.
set -euo pipefail
PKG="io.personal.filemanager"
APK="app/build/outputs/apk/debug/app-universal-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb install -g "$APK"
adb install "$TEST_APK"
# Grant only the disposable emulator the normal install-source permission for the OTA UI test.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
if [[ "${API_LEVEL:-30}" -ge 30 ]]; then
  adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
fi
# Small disposable media fixture for remote playback and thumbnail tests.
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=320x240:rate=24 -t 4 -c:v libx264 -pix_fmt yuv420p "$fixture/pfm-tv-test.mp4"
adb push "$fixture/pfm-tv-test.mp4" /sdcard/Download/pfm-tv-test.mp4
# Catch instrumentation failure even when piped through tee.
set +e
adb shell am instrument -w -r -e class "$PKG.TvNavigationTest,$PKG.TransferUiTest,$PKG.TransferIntegrationTest,$PKG.TvMediaTest,$PKG.OtaInstallTest" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee tv-test.log
rc=${PIPESTATUS[0]}
set -e
if [[ "$rc" != 0 ]] || ! grep -q 'OK (' tv-test.log || grep -q 'FAILURES' tv-test.log; then
  adb exec-out screencap -p > tv-screen.png || true
  adb logcat -d -t 3000 > tv-logcat.txt || true
  echo "::error::Android TV D-pad instrumentation failed"
  exit 1
fi
# The instrumentation test already checks the LEANBACK intent filter. ActivityManager's
# implicit -p resolution is unreliable on some Android TV system images: use the verified
# exported launch component for the actual launch and check that it stays resumed.
adb shell am start -W -n "$PKG/.MainActivity"
sleep 2
adb shell dumpsys activity activities > tv-activity.txt || true
adb exec-out screencap -p > tv-screen.png || true
adb logcat -d -t 3000 > tv-logcat.txt || true
if ! grep -Eq 'mResumedActivity:.*io\.personal\.filemanager/\.MainActivity|topResumedActivity=ActivityRecord.*io\.personal\.filemanager/\.MainActivity|ResumedActivity: ActivityRecord.*io\.personal\.filemanager/\.MainActivity' tv-activity.txt; then
  echo "::error::Android TV launcher did not leave MainActivity resumed"
  tail -60 tv-activity.txt || true
  exit 1
fi
