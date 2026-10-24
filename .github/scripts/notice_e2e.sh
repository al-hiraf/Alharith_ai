#!/usr/bin/env bash
# Notice E2E on the SIGNED 3.4.8 release APK: real onboarding (app self-registers an
# isolated CI fixture account) -> baseline sync -> fixture-only server notices inserted
# by the operator via authorized server ops when this script prints CI-READY markers ->
# P1 foreground pull pixels, P2 permission-denied (cursor-advance proof), P3 background worker.
set -x
PKG=com.alharith.ai
RUNID="${GITHUB_RUN_ID:-local}"
FIXNAME="FxN${GITHUB_RUN_NUMBER:-0}"
MARK="CINOTICE-$RUNID"
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
notif_has() { adb shell dumpsys notification 2>/dev/null | grep -c "$1"; }
relaunch() { adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep "${1:-22}"; }

# --- 1. signed 3.4.8 APK, sha256-pinned
gh release download rafiq-3.4.8 -R al-hiraf/Alharith_ai -p Rafiq-full.apk --clobber
echo "cd9bb64a4e830304ab68188de80338a376466164d73aa132c9904aad0a44c3be  Rafiq-full.apk" | sha256sum -c - || { echo "APK-HASH-MISMATCH"; exit 1; }
adb install -r -g Rafiq-full.apk | tee shots/install.txt
sleep 8

# --- 2. real onboarding: name -> app self-registers fixture -> permissions -> done
relaunch 12
shot 10_onb_welcome 2
tap_text "اكتب اسمك" || tap_text "بماذا أناديك؟"
adb shell input text "$FIXNAME"; sleep 1
shot 11_onb_name 2
tap_text "ابدأ"
for i in $(seq 1 30); do
  uid_xml
  grep -q 'text="التالي"' /tmp/ui.xml && break
  grep -q "ابدأ فورًا" /tmp/ui.xml && tap_text "ابدأ فورًا"
  sleep 4
done
shot 12_onb_keystep 2
tap_text "التالي"
sleep 3
uid_xml
if grep -q "امنح الكل بضغطة" /tmp/ui.xml; then
  tap_text "امنح الكل بضغطة"
  for i in 1 2 3 4 5 6 7 8; do
    uid_xml
    grep -q "امنح الكل بضغطة" /tmp/ui.xml || break
    tap_text "While using the app" || tap_text "Allow" || tap_text "سماح" || tap_text "أثناء استخدام التطبيق" || true
    sleep 2
  done
  shot 13_onb_perms 2
  tap_text "التالي"
fi
for i in $(seq 1 10); do uid_xml; grep -q "تكلّم مع رفيق الآن" /tmp/ui.xml && break; sleep 3; done
tap_text "تكلّم مع رفيق الآن" || true
shot 14_home 8
uid_xml
grep -q "رفيق AI" /tmp/ui.xml && echo "PASS home" || echo "FAIL home"
beacon "CI-BASELINE-DONE name=$FIXNAME"

# --- 3. P1: foreground pull. Operator inserts notice N1 after seeing CI-READY-P1.
beacon "CI-READY-P1 mark=$MARK-1"
P1=0
for i in $(seq 1 16); do
  relaunch 22
  if [ "$(notif_has "$MARK-1")" -ge 1 ]; then P1=1; break; fi
done
beacon "PHASE1-HIT=$P1"
adb shell cmd statusbar expand-notifications
shot 20_tray_p1 4
adb shell dumpsys notification > shots/20_dumpsys_p1.txt
adb shell cmd statusbar collapse
tap_text "حسابي"; sleep 3
tap_text "سجل النشاط"; sleep 3
shot 21_activitylog_p1 2
uid_xml; cp /tmp/ui.xml shots/21_activitylog_p1.xml
grep -o "إشعارات الخادم\|ظهر على الجوال\|لم يظهر" /tmp/ui.xml | sort | uniq -c
adb shell input keyevent KEYCODE_HOME; sleep 1

# --- 4. P2: permission denied. Operator inserts N2 after CI-READY-P2.
adb shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
beacon "CI-READY-P2 mark=$MARK-2"
P2=0
for i in 1 2 3 4 5 6; do
  relaunch 25
  if [ "$(notif_has "$MARK-2")" -ge 1 ]; then P2=-1; break; fi
done
if [ "$P2" = "0" ]; then
  # not displayed while denied. Re-grant: if the cursor advanced during the denied
  # phase, N2 must still NOT appear -> proves pull happened but display was suppressed.
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
  relaunch 25
  if [ "$(notif_has "$MARK-2")" -ge 1 ]; then P2=2; else P2=1; fi
fi
beacon "PHASE2-DENIED=$P2   (1=pulled-but-suppressed, -1=displayed-while-denied, 2=inconclusive)"
shot 22_p2_after 2
adb shell dumpsys notification > shots/22_dumpsys_p2.txt
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
tap_text "حسابي"; sleep 3; tap_text "سجل النشاط"; sleep 3
shot 23_activitylog_p2 2

# --- 5. P3: background worker (HOME background, not force-stopped). Operator inserts N3 after CI-READY-P3.
adb shell input keyevent KEYCODE_HOME
beacon "CI-READY-P3 mark=$MARK-3"
P3=0
for i in $(seq 1 40); do
  sleep 30
  if [ "$(notif_has "$MARK-3")" -ge 1 ]; then P3=1; break; fi
done
beacon "PHASE3-BACKGROUND-HIT=$P3"
adb shell cmd statusbar expand-notifications
shot 30_tray_p3 4
adb shell dumpsys notification > shots/30_dumpsys_p3.txt
adb logcat -d > shots/logcat.txt
beacon "CI-E2E-DONE p1=$P1 p2=$P2 p3=$P3"
