#!/usr/bin/env bash
# Проверка на эмуляторе: установка, доступ к файлам, тестовые образцы, инструментальный тест, скриншоты.
set -uo pipefail
PKG=io.personal.filemanager
APK=app/build/outputs/apk/debug/app-universal-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
adb wait-for-device

# «Доступ ко всем файлам» — режим appop по uid (как переключатель в настройках).
grant_files() {
  adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  adb shell appops get --uid $PKG MANAGE_EXTERNAL_STORAGE
}

# «Обновление поверх» (приёмочный сценарий ТЗ): предыдущий опубликованный APK -> заметка ->
# новая сборка поверх без удаления -> заметка на месте. Оба подписаны одним debug-ключом CI.
UPGRADE="SKIPPED"
CUR=$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
PREV_TAG=$(gh release list --repo "$GITHUB_REPOSITORY" --limit 20 --json tagName -q '.[].tagName' 2>/dev/null | grep -v "^v${CUR}-" | head -1 || true)
if [ -n "$PREV_TAG" ] && gh release download "$PREV_TAG" --repo "$GITHUB_REPOSITORY" --pattern '*universal*.apk' --dir prev-apk --clobber; then
  echo "===== UPGRADE: $PREV_TAG -> $CUR"
  UPGRADE="FAIL"
  if adb install "$(ls prev-apk/*.apk | head -1)" && adb install -r "$TEST_APK"; then
    grant_files
    adb shell am instrument -w -r -e class $PKG.UpgradeCheckTest#seed $RUNNER | tee upgrade-seed.log
    if grep -q "OK (" upgrade-seed.log && adb install -r "$APK"; then
      adb shell dumpsys package $PKG | grep -E "versionCode|versionName" | head -2
      adb shell am instrument -w -r -e class $PKG.UpgradeCheckTest#verify $RUNNER | tee upgrade-verify.log
      grep -q "OK (" upgrade-verify.log && UPGRADE="OK"
    fi
  fi
else
  echo "Предыдущий пререлиз не найден — проверка обновления пропущена"
fi
echo "===== UPGRADE RESULT: $UPGRADE"

adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1
grant_files
adb shell mkdir -p /sdcard/Download/pfm-fixtures
adb push fixtures/. /sdcard/Download/pfm-fixtures/ >/dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 8
adb logcat -c
adb shell am instrument -w -r -e class $PKG.DeviceSmokeTest $RUNNER | tee instr.log
mkdir -p device-shots
adb pull /sdcard/Download/pfm-test-shots/. device-shots/ >/dev/null 2>&1 || true
echo "===== CRASHES ====="
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (Process|java|Caused)" -A 12 | head -120 || true
echo "===== RESULT ====="
grep -E "OK \(|FAILURES|Tests run" instr.log || true
echo "upgrade: $UPGRADE"
grep -q "OK (" instr.log && [ "$UPGRADE" != "FAIL" ]
