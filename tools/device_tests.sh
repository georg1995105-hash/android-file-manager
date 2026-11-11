#!/usr/bin/env bash
# Проверка на эмуляторе: установка, доступ к файлам, тестовые образцы, инструментальные тесты, скриншоты.
# DEVICE=phone|tablet. На телефоне дополнительно — «обновление поверх» и проверка горизонтальной ориентации.
set -uo pipefail
PKG=io.personal.filemanager
DEVICE=${DEVICE:-phone}
DEVICE_TEST_SCOPE=${DEVICE_TEST_SCOPE:-full}
case "$DEVICE_TEST_SCOPE" in
  full) ;;
  upgrade) [ "$DEVICE" = phone ] || { echo "Upgrade scope requires phone" >&2; exit 1; } ;;
  *) echo "Unknown DEVICE_TEST_SCOPE: $DEVICE_TEST_SCOPE" >&2; exit 1 ;;
esac
APK=app/build/outputs/apk/debug/app-universal-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
SHOTS=/sdcard/Download/.pfm-test-shots

# Ошибка подписи — ошибка сборки, а не повод удалять приложение или чистить данные.
SIGNING_APKS=("$APK" "$TEST_APK")
if [ "$DEVICE" = "phone" ]; then
  CUR=$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
  # Берём только опубликованный DEBUG/TEST APK; некоторые подписанные RELEASE
  # выпуски исторически имели «debug» в имени тега, но другой сертификат.
  PREV_TAG=$(gh release list --repo "$GITHUB_REPOSITORY" --limit 100 --json tagName -q '.[].tagName' | grep -E '(^v.*-debug-build[0-9]+$|^v.*-build[0-9]+$)' | grep -v "^v${CUR}-" | head -1) || exit 1
  if [ -z "$PREV_TAG" ]; then
    echo "Предыдущий пререлиз не найден — проверка обновления невозможна" >&2
    exit 1
  fi
  gh release download "$PREV_TAG" --repo "$GITHUB_REPOSITORY" --pattern '*debug*universal*.apk' --dir prev-apk --clobber || exit 1
  PREV_APKS=(prev-apk/*.apk)
  [ "${#PREV_APKS[@]}" -eq 1 ] && [ -f "${PREV_APKS[0]}" ] || exit 1
  SIGNING_APKS+=("${PREV_APKS[0]}")
fi
bash tools/check_debug_signatures.sh "${SIGNING_APKS[@]}" || exit 1
timeout 90s adb wait-for-device || { echo "::error::Emulator never became available through adb"; exit 1; }

# «Доступ ко всем файлам» — режим appop по uid (как переключатель в настройках).
grant_files() {
  adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  adb shell appops get --uid $PKG MANAGE_EXTERNAL_STORAGE
}

# Поворот без датчика: 0 — естественная ориентация (телефон — вертикально, планшет — горизонтально), 1 — на 90°.
rotate() {
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation "$1"
  adb shell wm user-rotation lock "$1" >/dev/null 2>&1 || true
  sleep 3
  adb shell dumpsys window displays | grep -m2 -E "mCurrentRotation|cur=" || true
}

RESULTS=()
run_tests() { # $1 — метка, $2 — классы через запятую
  local label="$1" classes="$2" rc
  # Instrumentation may block forever after an emulator/app deadlock.
  # Bound each suite and retain the last successful test in the output.
  echo "===== INSTRUMENTATION START: $label ($(date -u +%FT%TZ)) ====="
  timeout --signal=TERM --kill-after=20s 480s \
    adb shell am instrument -w -r -e class "$classes" "$RUNNER" 2>&1 | tee "instr-$label.log"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 0 ] && grep -q "OK (" "instr-$label.log"; then
    RESULTS+=("$label: OK")
    echo "===== INSTRUMENTATION OK: $label ====="
    return 0
  fi
  RESULTS+=("$label: FAIL (exit $rc)")
  echo "::error::Android instrumentation $label failed or timed out (exit $rc)"
  echo "===== LAST INSTRUMENTATION OUTPUT: $label ====="
  tail -100 "instr-$label.log" || true
  echo "===== LAST ANDROID LOGCAT ====="
  timeout 20s adb logcat -d -t 1200 | tail -160 || true
  echo "===== ANDROID ACTIVITY STATE ====="
  timeout 20s adb shell dumpsys activity activities | tail -100 || true
  timeout 15s adb shell am force-stop "$PKG" || true
  return 1
}

# «Обновление поверх» (приёмочный сценарий ТЗ): предыдущий опубликованный APK -> заметка ->
# новая сборка поверх без удаления -> заметка на месте. Оба подписаны одним debug-ключом CI.
UPGRADE="SKIPPED"
if [ "$DEVICE" = "phone" ]; then
  echo "===== UPGRADE: $PREV_TAG -> $CUR"
  UPGRADE="FAIL"
  if adb install "${PREV_APKS[0]}" && adb install -r "$TEST_APK"; then
    grant_files
    adb shell am instrument -w -r -e class $PKG.UpgradeCheckTest#seed $RUNNER | tee upgrade-seed.log
    if grep -q "OK (" upgrade-seed.log && adb install -r "$APK"; then
      adb shell dumpsys package $PKG | grep -E "versionCode|versionName" | head -2
      adb shell am instrument -w -r -e class $PKG.UpgradeCheckTest#verify $RUNNER | tee upgrade-verify.log
      grep -q "OK (" upgrade-verify.log && UPGRADE="OK"
    fi
  fi
  echo "===== UPGRADE RESULT: $UPGRADE"
fi
if [ "$DEVICE_TEST_SCOPE" = upgrade ]; then
  [ "$UPGRADE" = OK ]
  exit $?
fi

adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1
grant_files
adb shell mkdir -p /sdcard/Download/pfm-fixtures
adb push fixtures/. /sdcard/Download/pfm-fixtures/ >/dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 8
adb logcat -c

rotate 0
run_tests main "$PKG.DeviceSmokeTest,$PKG.ViewerSelectionTest,$PKG.GridViewTest" || exit 1
# Горизонтальная ориентация: тест сам поворачивает экран через UiAutomation (команды adb на эмуляторе
# телефона не меняли поворот дисплея).
run_tests landscape "$PKG.OrientationTest" || exit 1

mkdir -p device-shots
adb pull "$SHOTS/." device-shots/ >/dev/null 2>&1 || true
echo "===== CRASHES ====="
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (Process|java|Caused)" -A 12 | head -120 || true
echo "===== RESULT ($DEVICE) ====="
grep -hE "OK \(|FAILURES|Tests run" instr-*.log || true
printf '%s\n' "${RESULTS[@]}"
echo "upgrade: $UPGRADE"
! printf '%s\n' "${RESULTS[@]}" | grep -q FAIL && [ "$UPGRADE" != "FAIL" ]
