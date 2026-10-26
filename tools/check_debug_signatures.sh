#!/usr/bin/env bash
# Validate all APKs before touching the emulator or its app data.
set -euo pipefail
EXPECTED=$(cat "$(dirname "$0")/debug-signing.sha256")
[ "$#" -gt 0 ] || { echo "No APKs supplied" >&2; exit 1; }
APK_SIGNER=${APK_SIGNER:-$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner}
RESULT=0
for APK in "$@"; do
  if ! CERTS=$("$APK_SIGNER" verify --print-certs "$APK"); then
    echo "APK signature verification failed: $APK. Emulator unchanged." >&2
    RESULT=1
    continue
  fi
  ACTUAL=$(printf '%s\n' "$CERTS" | sed -n 's/^.*certificate SHA-256 digest: //p' | tr -d '\r')
  echo "Signing certificate SHA-256: $APK: $ACTUAL"
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Signing certificate mismatch for $APK: expected $EXPECTED, got $ACTUAL. Emulator unchanged." >&2
    RESULT=1
  fi
done
exit "$RESULT"
