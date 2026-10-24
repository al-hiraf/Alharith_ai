#!/usr/bin/env bash
# Signed 3.4.8 -> signed 3.4.9 upgrade proof + real FCM pipeline receipt.
# Beacons (commits to screenshots branch, phase.txt at branch ROOT) drive the operator.
set -x
PKG=com.alharith.ai
RUNID="${GITHUB_RUN_ID:-local}"
FIXNAME="FxU${GITHUB_RUN_NUMBER:-0}"
MARK="FCMCI-$RUNID"
mkdir -p shots
(cd shots && git init -q && git checkout -q -b screenshots \
  && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm init --allow-empty \
  && git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}" screenshots) || true
beacon() {
  echo "$1" > shots/phase.txt
  (cd shots && git add phase.txt \
    && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "$1" \
    && git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}" screenshots) || true
  echo "BEACON $1"
}
adb shell settings put global hide_error_dialogs 1
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
uid_xml() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null; }
tap_text() {
  uid_xml
  XY=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1]; x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1).strip()==t:
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY && return 0 || return 1
}
pkgver() { adb shell dumpsys package $PKG 2>/dev/null | grep -E "versionCode|versionName" | head -2 | tr -d ' '; }

# --- 1. pinned signed APKs
gh release download rafiq-3.4.8 -R al-hiraf/Alharith_ai -p Rafiq-full.apk --clobber --dir dl348
gh release download rafiq-3.4.9 -R al-hiraf/Alharith_ai -p Rafiq-full.apk --clobber --dir dl349
echo "cd9bb64a4e830304ab68188de80338a376466164d73aa132c9904aad0a44c3be  dl348/Rafiq-full.apk" | sha256sum -c - || { echo "HASH-348-MISMATCH"; exit 1; }
echo "ea5d5211d92c63851c62a0dfcbe68c1f6ff2c3138340a2d8c15cb8b6bf83f342  dl349/Rafiq-full.apk" | sha256sum -c - || { echo "HASH-349-MISMATCH"; exit 1; }

# --- 2. install signed 3.4.8, onboard fixture
adb install -r -g dl348/Rafiq-full.apk | tee shots/install348.txt
sleep 8
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep 12
shot 40_onb_welcome 2
tap_text "اكتب اسمك" || tap_text "بماذا أناديك؟"
adb shell input text "$FIXNAME"; sleep 1
tap_text "ابدأ"
for i in $(seq 1 30); do
  uid_xml
  grep -q 'text="التالي"' /tmp/ui.xml && break
  grep -q "ابدأ فورًا" /tmp/ui.xml && tap_text "ابدأ فورًا"
  sleep 4
done
tap_text "التالي"; sleep 3
uid_xml
if grep -q "امنح الكل بضغطة" /tmp/ui.xml; then
  tap_text "امنح الكل بضغطة"
  for i in 1 2 3 4 5 6 7 8; do
    uid_xml; grep -q "امنح الكل بضغطة" /tmp/ui.xml || break
    tap_text "While using the app" || tap_text "Allow" || tap_text "سماح" || tap_text "أثناء استخدام التطبيق" || true
    sleep 2
  done
  tap_text "التالي"
fi
for i in $(seq 1 10); do uid_xml; grep -q "تكلّم مع رفيق الآن" /tmp/ui.xml && break; sleep 3; done
tap_text "تكلّم مع رفيق الآن" || true
sleep 6
shot 41_home_348 4
V348=$(pkgver); echo "VERSION-348: $V348"
beacon "UPGRADE-BASE $V348 name=$FIXNAME"

# --- 3. upgrade in place to signed 3.4.9 (same-signer proof)
adb install -r dl349/Rafiq-full.apk | tee shots/upgrade349.txt
grep -q "Success" shots/upgrade349.txt && echo "UPGRADE-INSTALL-OK" || echo "UPGRADE-INSTALL-FAILED"
V349=$(pkgver); echo "VERSION-349: $V349"
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep 10
uid_xml
grep -q "اكتب اسمك" /tmp/ui.xml && echo "SESSION-LOST" || echo "SESSION-PRESERVED"
shot 42_home_349 3
beacon "UPGRADE-RESULT $V349 $(grep -c Success shots/upgrade349.txt)"

# --- 4. FCM registration window (operator verifies push_tokens row server-side)
sleep 45
beacon "CI-FCM-READY mark=$MARK name=$FIXNAME"

# --- 5. wait for the operator's REAL-pipeline send (core.notify -> FcmPush)
FCM_HIT=0
for i in $(seq 1 12); do
  sleep 25
  if adb shell dumpsys notification 2>/dev/null | grep -q "$MARK"; then FCM_HIT=1; break; fi
done
echo "FCM-TRAY-HIT=$FCM_HIT"
adb shell cmd statusbar expand-notifications
shot 50_tray_fcm 4
adb shell dumpsys notification > shots/50_dumpsys_fcm.txt
adb shell cmd statusbar collapse
adb logcat -d > shots/logcat.txt
grep -iE "firebase|fcm|messaging" shots/logcat.txt | tail -20 > shots/logcat_fcm_excerpt.txt
beacon "FCM-E2E-DONE hit=$FCM_HIT v349=$V349"
