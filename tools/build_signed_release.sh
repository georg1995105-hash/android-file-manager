#!/usr/bin/env bash
# Keys exist only in an owned temporary directory and never enter dist/ or caches.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pfm-release.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
umask 077
export PFM_STORE_FILE="$WORK/release.p12"
CERT_FILE=${PFM_RELEASE_CERT_FILE:-$ROOT/tools/release-signing.sha256}
python3 "$ROOT/tools/release_signing.py" restore --destination "$PFM_STORE_FILE" --certificate-file "$CERT_FILE"
cd "$ROOT"
./gradlew --no-daemon --no-configuration-cache assembleRelease
python3 "$ROOT/tools/release_signing.py" inspect \
  --apk-dir app/build/outputs/apk/release \
  --output-dir "${RELEASE_OUTPUT_DIR:-dist}" --certificate-file "$CERT_FILE"
