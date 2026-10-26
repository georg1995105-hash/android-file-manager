#!/usr/bin/env bash
# Actual main release plus a disposable next-version build; no tracked app source is edited.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
export RELEASE_OUTPUT_DIR="$ROOT/dist/current"
bash tools/build_signed_release.sh
NEXT=$(mktemp -d "$RUNNER_TEMP/pfm-next-version.XXXXXX")
trap 'git -C "$ROOT" worktree remove --force "$NEXT" >/dev/null 2>&1 || rm -rf "$NEXT"' EXIT
git worktree add --detach "$NEXT" "$GITHUB_SHA"
python3 - "$NEXT/app/build.gradle.kts" <<'PY'
from pathlib import Path
import re
import sys
path = Path(sys.argv[1])
text = path.read_text()
match = re.search(r'^val appVersionCode = (\d+)$', text, re.M)
if not match:
    raise SystemExit('Cannot prepare the disposable next-version fixture')
following = int(match[1]) + 1
text, count = re.subn(r'^val appVersionCode = \d+$', f'val appVersionCode = {following}', text, flags=re.M)
if count != 1:
    raise SystemExit('Ambiguous version configuration')
path.write_text(text)
print(f'Disposable update fixture versionCode: {following}; main source unchanged')
PY
export RELEASE_OUTPUT_DIR="$ROOT/dist/validation-next"
(cd "$NEXT" && bash tools/build_signed_release.sh)
