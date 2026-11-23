#!/usr/bin/env bash
# End-to-end installation acceptance of the EXACT published signed universal APK
# on a disposable Android TV emulator, upgrading with adb install -r (no uninstall).
set -euo pipefail

PKG=io.personal.filemanager
BASE="https://raw.githubusercontent.com/georg1995105-hash/android-file-manager/apk-downloads"
OLD_NAME="personal-file-manager-0.12.24-release-vc39-universal.apk"
NEW_NAME="personal-file-manager-0.12.25-release-vc40-universal.apk"
OLD_SHA="e404613cce47b441c3922a7baff9969b9ceede6b339e098ee3cdf3d3b7b13149"
NEW_SHA="9ebeb3a4346dd51724ac241469a25b119d4c8e81ba4e5bad9688bee053d862df"
CERT="d282ae0bb49c30bd848fcae3ae02533ff446a12ef7e3691863af53c4aa0ecda7"
DIR="${RUNNER_TEMP:-/tmp}/file-manager-tv-release"
mkdir -p "$DIR"
exec > >(tee signed-tv-install.log) 2>&1

fetch_and_check() {
  local name="$1" hash="$2"
  echo "Downloading signed APK: $name"
  curl -fL --retry 3 --connect-timeout 20 --max-time 120 \
    "$BASE/$name" -o "$DIR/$name"
  printf '%s  %s\n' "$hash" "$DIR/$name" | sha256sum --check --status
}
fetch_and_check "$OLD_NAME" "$OLD_SHA"
fetch_and_check "$NEW_NAME" "$NEW_SHA"
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner | sort -V | tail -1)
test -n "$APKSIGNER"
for apk in "$DIR/$OLD_NAME" "$DIR/$NEW_NAME"; do
  "$APKSIGNER" verify --print-certs "$apk" | tee -a signed-tv-certificates.log
  "$APKSIGNER" verify --print-certs "$apk" | grep -Fiq "$CERT"
done

adb install -r "$DIR/$OLD_NAME"
adb shell pm path "$PKG" | grep -q "package:"
oldInfo=$(adb shell dumpsys package "$PKG")
echo "$oldInfo" | grep -q "versionCode=39"
oldUid=$(printf '%s\n' "$oldInfo" | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
test -n "$oldUid"
adb shell am start -W -n "$PKG/.MainActivity" | tee -a signed-tv-start.log
sleep 3
adb shell dumpsys activity activities > signed-tv-activity-old.txt
# The app may display permission onboarding, but should launch without parsing/ABI failure.
grep -Eq "io\.personal\.filemanager/\.MainActivity" signed-tv-activity-old.txt
adb shell am force-stop "$PKG"

# Critical: same APK signing, same package, same user data and UID; NO uninstall/pm clear.
adb install -r "$DIR/$NEW_NAME"
newInfo=$(adb shell dumpsys package "$PKG")
echo "$newInfo" | grep -q "versionCode=40"
newUid=$(printf '%s\n' "$newInfo" | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
test -n "$newUid"
if [[ "$oldUid" != "$newUid" ]]; then
  echo "::error::Package UID changed during the OTA upgrade"
  exit 1
fi
adb shell am start -W -n "$PKG/.MainActivity" | tee -a signed-tv-start.log
sleep 3
adb shell dumpsys activity activities > signed-tv-activity-new.txt
adb exec-out screencap -p > signed-tv-release.png
adb logcat -d -t 6000 > signed-tv-logcat.txt || true
grep -Eq "io\.personal\.filemanager/\.MainActivity" signed-tv-activity-new.txt
echo "PASS: Signed vc39 -> vc40, same UID $oldUid, APK launch on Android TV API ${API_LEVEL:-unknown}"
