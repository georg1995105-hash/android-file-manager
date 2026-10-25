#!/usr/bin/env bash
# Проверка на эмуляторе: установка, доступ к файлам, тестовые образцы, инструментальный тест, скриншоты.
set -uo pipefail
PKG=io.personal.filemanager
APK=app/build/outputs/apk/debug/app-universal-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb wait-for-device
adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/Download/pfm-fixtures
adb push fixtures/. /sdcard/Download/pfm-fixtures/ >/dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 8
adb logcat -c
adb shell am instrument -w -r -e class $PKG.DeviceSmokeTest $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee instr.log
mkdir -p device-shots
adb pull /sdcard/Download/pfm-test-shots/. device-shots/ >/dev/null 2>&1 || true
echo "===== CRASHES ====="
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (Process|java|Caused)" -A 12 | head -120 || true
echo "===== RESULT ====="
grep -E "OK \(|FAILURES|Tests run" instr.log || true
grep -q "OK (" instr.log
