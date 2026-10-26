#!/usr/bin/env bash
# Restore the existing CI identity; never let AGP generate a replacement key.
set -euo pipefail
if [ -z "${PFM_DEBUG_KEYSTORE_B64:-}" ]; then
  echo "Missing PFM_DEBUG_KEYSTORE_B64: restore the original CI key; do not generate a new one." >&2
  exit 1
fi
EXPECTED=$(cat "$(dirname "$0")/debug-signing.sha256")
umask 077
KEY_DIR="$HOME/.android"
mkdir -p "$KEY_DIR"
TEMP_KEY=$(mktemp "$KEY_DIR/debug.keystore.XXXXXX")
trap 'rm -f "$TEMP_KEY"' EXIT
printf '%s' "$PFM_DEBUG_KEYSTORE_B64" | base64 --decode > "$TEMP_KEY"
ACTUAL=$(keytool -exportcert -keystore "$TEMP_KEY" -storepass android -alias androiddebugkey | sha256sum | cut -d ' ' -f1)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "CI debug signing certificate mismatch: expected $EXPECTED, got $ACTUAL. Refusing to change identity." >&2
  exit 1
fi
mv "$TEMP_KEY" "$KEY_DIR/debug.keystore"
echo "CI debug signing certificate: $ACTUAL"
