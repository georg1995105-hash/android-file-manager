#!/usr/bin/env bash
set -euo pipefail
PKG=io.personal.filemanager
fixture=$(mktemp -d)
cleanup() {
  if [[ -n "$PID" ]]; then kill "$PID" 2>/dev/null || true; fi
  adb reverse --remove tcp:8788 >/dev/null 2>&1 || true
  rm -rf "$fixture"
}
PID=""
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
  -subj /CN=localhost -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "$fixture/key.pem" -out "$fixture/cert.pem" >/dev/null 2>&1
code=$(openssl rand -hex 32)
cert=$(openssl x509 -in "$fixture/cert.pem" -outform DER | base64 -w0)
python3 storage-server/test_tls_fixture.py "$fixture/data" "$fixture/cert.pem" "$fixture/key.pem" "$code" 8788 &
PID=$!
for i in $(seq 1 30); do
  if curl -ksS --max-time 2 https://localhost:8788/v1/devices >/dev/null 2>&1; then break; fi
  kill -0 "$PID"
  sleep 1
done
adb reverse tcp:8788 tcp:8788
adb install -g app/build/outputs/apk/debug/app-universal-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class "$PKG.StorageEndToEndTest" -e storageCert "$cert" -e storageCode "$code" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee storage-device-test.log
grep -q 'OK (' storage-device-test.log
! grep -q 'FAILURES' storage-device-test.log
