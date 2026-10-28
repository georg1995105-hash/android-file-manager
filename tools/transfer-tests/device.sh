#!/usr/bin/env bash
set -euo pipefail
apk=$(find artifacts -name app-universal-debug.apk -print -quit)
test_apk=$(find artifacts -name app-debug-androidTest.apk -print -quit)
bash tools/check_debug_signatures.sh "$apk" "$test_apk"
adb install -r "$apk"
adb install -r "$test_apk"
adb shell appops set --uid io.personal.filemanager MANAGE_EXTERNAL_STORAGE allow
adb shell am instrument -w -r -e class io.personal.filemanager.TransferIntegrationTest,io.personal.filemanager.TransferUiTest io.personal.filemanager.test/androidx.test.runner.AndroidJUnitRunner | tee transfer-instrumentation.log
adb logcat -d > transfer-logcat.txt
# Artifacts may be unreachable for reviewers; keep bounded diagnostics in the job log.
grep -iE 'ssl|keystore|conscrypt|transfer|FATAL|filemanager' transfer-logcat.txt | grep -viE 'chatty|gms' | tail -n 80 || true
adb exec-out screencap -p > transfer-screen.png
grep -q 'OK (' transfer-instrumentation.log && ! grep -q 'FAILURES!!!' transfer-instrumentation.log
