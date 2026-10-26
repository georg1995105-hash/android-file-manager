#!/usr/bin/env bash
# Restore the existing CI identity; never let AGP generate a replacement key.
set -euo pipefail
EXPECTED=$(cat "$(dirname "$0")/debug-signing.sha256")
umask 077
KEY_DIR="$HOME/.android"
mkdir -p "$KEY_DIR"
SOURCE_KEY="$KEY_DIR/debug.keystore"
TEMP_KEY=""
trap '[ -z "$TEMP_KEY" ] || rm -f "$TEMP_KEY"' EXIT
if [ -n "${PFM_DEBUG_KEYSTORE_B64:-}" ]; then
  TEMP_KEY=$(mktemp "$KEY_DIR/debug.keystore.XXXXXX")
  printf '%s' "$PFM_DEBUG_KEYSTORE_B64" | base64 --decode > "$TEMP_KEY"
  SOURCE_KEY="$TEMP_KEY"
elif [ ! -f "$SOURCE_KEY" ]; then
  echo "Original CI debug keystore unavailable: recover the existing key; do not generate a new one." >&2
  exit 1
fi
ACTUAL=$(keytool -exportcert -keystore "$SOURCE_KEY" -storepass android -alias androiddebugkey | sha256sum | cut -d ' ' -f1)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "CI debug signing certificate mismatch: expected $EXPECTED, got $ACTUAL. Refusing to change identity." >&2
  exit 1
fi
if [ -n "$TEMP_KEY" ]; then
  mv "$TEMP_KEY" "$KEY_DIR/debug.keystore"
fi
echo "CI debug signing certificate: $ACTUAL"
