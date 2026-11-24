#!/usr/bin/env bash
# Test exact signed candidate from dist, BEFORE publishing it.
set -euo pipefail
PKG=io.personal.filemanager
BASE=https://raw.githubusercontent.com/georg1995105-hash/android-file-manager/apk-downloads
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 --connect-timeout 15 --max-time 60 "$BASE/RELEASE_IDENTITY.json" -o "$TMP/old.json"
readarray -t old < <(python3 - "$TMP/old.json" <<'PY'
import json,re,sys
r=json.load(open(sys.argv[1]))[0]
assert r['package']=='io.personal.filemanager' and r['signature_verified'] and not r['debuggable']
assert re.fullmatch(r'personal-file-manager-[0-9A-Za-z.]+-release-vc\d+-universal\.apk',r['file'])
assert re.fullmatch(r'[a-f0-9]{64}',r['sha256'])
print(r['file']);print(r['sha256']);print(r['version_code'])
PY
)
readarray -t new < <(python3 - <<'PY'
import json,re
r=json.load(open('dist/RELEASE_IDENTITY.json'))[0]
assert r['package']=='io.personal.filemanager' and r['signature_verified'] and not r['debuggable']
assert re.fullmatch(r'personal-file-manager-[0-9A-Za-z.]+-release-vc\d+-universal\.apk',r['file'])
print(r['file']);print(r['sha256']);print(r['version_code'])
PY
)
test "${new[2]}" -gt "${old[2]}"
curl -fsSL --retry 3 --connect-timeout 15 --max-time 120 "$BASE/${old[0]}" -o "$TMP/old.apk"
printf '%s  %s\n' "${old[1]}" "$TMP/old.apk" | sha256sum -c -
printf '%s  %s\n' "${new[1]}" "dist/${new[0]}" | sha256sum -c -
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner | sort -V | tail -1)
CERT=d282ae0bb49c30bd848fcae3ae02533ff446a12ef7e3691863af53c4aa0ecda7
for f in "$TMP/old.apk" "dist/${new[0]}"; do
  "$APKSIGNER" verify --print-certs "$f" | grep -Fiq "$CERT"
done
adb install -r "$TMP/old.apk"
oldInfo=$(adb shell dumpsys package "$PKG")
grep -q "versionCode=${old[2]} " <<<"$oldInfo"
uidOld=$(sed -n 's/.*userId=\([0-9]*\).*/\1/p' <<<"$oldInfo" | head -1)
test -n "$uidOld"
adb shell am start -W -n "$PKG/.MainActivity"
adb shell am force-stop "$PKG"
adb install -r "dist/${new[0]}"
newInfo=$(adb shell dumpsys package "$PKG")
grep -q "versionCode=${new[2]} " <<<"$newInfo"
uidNew=$(sed -n 's/.*userId=\([0-9]*\).*/\1/p' <<<"$newInfo" | head -1)
test "$uidOld" = "$uidNew"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 3
adb shell dumpsys activity activities > ota-activity.txt
grep -Eq 'mResumedActivity:.*io\.personal\.filemanager/\.MainActivity|topResumedActivity=ActivityRecord.*io\.personal\.filemanager/\.MainActivity|ResumedActivity: ActivityRecord.*io\.personal\.filemanager/\.MainActivity' ota-activity.txt
adb exec-out screencap -p > ota-screen.png
echo "PASS: verified signed candidate, upgraded vc${old[2]} -> vc${new[2]} without uninstall, same UID"
