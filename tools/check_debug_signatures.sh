#!/usr/bin/env bash
# Validate all APKs before touching the emulator or its app data.
set -euo pipefail
EXPECTED=$(cat "$(dirname "$0")/debug-signing.sha256")
APK_SIGNER=${APK_SIGNER:-$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner}
for APK in "$@"; do
  CERTS=$("$APK_SIGNER" verify --print-certs "$APK")
  ACTUAL=$(printf '%s\n' "$CERTS" | sed -n 's/^.*certificate SHA-256 digest: //p' | tr -d '\r')
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Signing certificate mismatch for $APK: expected $EXPECTED, got $ACTUAL. Emulator unchanged." >&2
    exit 1
  fi
  echo "Signing certificate OK: $APK ($ACTUAL)"
done
