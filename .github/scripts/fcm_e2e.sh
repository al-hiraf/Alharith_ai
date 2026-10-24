#!/usr/bin/env bash
# Signed 3.4.8 -> signed 3.4.9 upgrade + real FCM pipeline E2E, v2.
# v2: fail-fast deterministic onboarding (content-desc field, typed-text assert),
# unique per-run beacon branch with write+readback, operator gates via ops.txt.
set -x
PKG=com.alharith.ai
RUNID="${GITHUB_RUN_ID:-local}"
FIXNAME="FxR${GITHUB_RUN_NUMBER:-0}"
MARK="FCMCI-$RUNID"
BRANCH="fcm-run-$RUNID"
REPO="${GITHUB_REPOSITORY}"
AUTH="https://x-access-token:${GH_TOKEN}@github.com/${REPO}"
mkdir -p shots

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
uid_xml() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null; }

push_shots() {
  (cd shots && git add -A && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "$1" \
   && (git push -q "$AUTH" "$BRANCH" || { git pull -q --rebase "$AUTH" "$BRANCH" && git push -q "$AUTH" "$BRANCH"; }))
}

beacon() { # write phase.txt -> push -> readback verify -> one retry
  echo "$1" > shots/phase.txt
  for try in 1 2; do
    push_shots "$1" || true
    RB=$(gh api "repos/$REPO/contents/phase.txt?ref=$BRANCH" --jq '.content' 2>/dev/null | base64 -d 2>/dev/null)
    [ "$RB" = "$1" ] && { echo "BEACON-OK $1"; return 0; }
    echo "BEACON-READBACK-MISS try=$try"
    sleep 3
  done
  echo "BEACON-FAILED $1"; return 1
}

die() { shot "fail_$2" 1; beacon "FAIL $1" || true; echo "DIE: $1"; exit 1; }

wait_text() { # text timeout_sec
  local end=$((SECONDS+${2:-60}))
  while [ $SECONDS -lt $end ]; do uid_xml; grep -q "$1" /tmp/ui.xml && return 0; sleep 4; done; return 1
}

tap_node() { # attr value  (attr = text | content-desc)
  uid_xml
  XY=$(python3 - "$1" "$2" <<'PY'
import re,sys
attr,val=sys.argv[1],sys.argv[2]
x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node[^>]*>', x):
    tag=m.group(0)
    am=re.search(attr+r'="([^"]*)"', tag)
    if am and am.group(1).strip()==val:
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b: continue
        a,b1,c,d=map(int,b.groups()); print((a+c)//2,(b1+d)//2); break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY && sleep 2 && return 0 || return 1
}

focus_pkg() { adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -c "$PKG"; }
pkgver() { adb shell dumpsys package $PKG 2>/dev/null | grep -E "versionCode|versionName" | head -2 | tr -d ' '; }

OPS=""
wait_ops() { # wanted-prefix timeout_sec ; sets OPS ; rc 0=match 2=ABORT 1=timeout
  local end=$((SECONDS+${2:-480}))
  while [ $SECONDS -lt $end ]; do
    OPS=$(gh api "repos/$REPO/contents/ops.txt?ref=$BRANCH" --jq '.content' 2>/dev/null | base64 -d 2>/dev/null)
    case "$OPS" in "$1"*) return 0;; ABORT*) return 2;; esac
    sleep 20
  done; return 1
}

# --- init unique per-run branch
(cd shots && git init -q && git checkout -q -b "$BRANCH" \
 && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm init --allow-empty \
 && git push -qf "$AUTH" "$BRANCH") || { echo "BRANCH-INIT-FAILED"; exit 1; }

adb shell settings put global hide_error_dialogs 1
adb wait-for-device
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 2; done'

# --- 0. Play Services present (never blame FCM without it)
adb shell pm list packages 2>/dev/null | grep -q com.google.android.gms || die "no-play-services" 00
adb shell dumpsys package com.google.android.gms 2>/dev/null | grep versionName | head -1

# --- 1. pinned signed APKs, hash-verified
gh release download rafiq-3.4.8 -R al-hiraf/Alharith_ai -p Rafiq-full.apk --clobber --dir dl348
gh release download rafiq-3.4.9 -R al-hiraf/Alharith_ai -p Rafiq-full.apk --clobber --dir dl349
echo "cd9bb64a4e830304ab68188de80338a376466164d73aa132c9904aad0a44c3be  dl348/Rafiq-full.apk" | sha256sum -c - || die "HASH-348-MISMATCH" 01
echo "ea5d5211d92c63851c62a0dfcbe68c1f6ff2c3138340a2d8c15cb8b6bf83f342  dl349/Rafiq-full.apk" | sha256sum -c - || die "HASH-349-MISMATCH" 01

# --- 2. install signed 3.4.8, deterministic onboarding
adb install -r -g dl348/Rafiq-full.apk | tee shots/install348.txt
grep -q Success shots/install348.txt || die "install348-failed" 02
sleep 8
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep 12
uid_xml; grep -q "اكتب اسمك" /tmp/ui.xml || { shot 02_first; die "namescreen-not-shown" 02; }
tap_node content-desc "اسمك" || die "namefield-not-found" 03
adb shell input text "$FIXNAME"; sleep 2
uid_xml; grep -q "$FIXNAME" /tmp/ui.xml || { shot 04_typed; die "nametext-not-landed" 04; }
shot 04_typed 1
tap_node text "ابدأ" || die "startbtn-not-found" 05
wait_text "ابدأ فورًا" 20 || { shot 06_keystep; die "keystep-not-reached" 06; }
shot 06_keystep 1
tap_node text "ابدأ فورًا" || die "provbtn-not-found" 07
wait_text "✓ رفيق جاهز" 150 || { shot 08_prov; die "provisioning-failed" 08; }
shot 08_ready 1
for i in 1 2 3 4 5 6 7 8; do
  uid_xml
  grep -qE "تكلّم مع رفيق الآن|الرئيسية" /tmp/ui.xml && break
  grep -q "امنح الكل بضغطة" /tmp/ui.xml && { tap_node text "امنح الكل بضغطة"; sleep 3; }
  for T in "While using the app" "أثناء استخدام التطبيق" "Allow" "ALLOW" "السماح" "التالي"; do
    grep -q ">$T<" /tmp/ui.xml || grep -q "\"$T\"" /tmp/ui.xml && { tap_node text "$T"; sleep 2; }
  done
  sleep 4
done
wait_text "الرئيسية" 90 || wait_text "تكلّم مع رفيق الآن" 10 || { shot 09_home; die "home-not-reached" 09; }
[ "$(focus_pkg)" -ge 1 ] || { shot 09_home; die "home-not-foreground" 09; }
shot 09_home_348 2
V348=$(pkgver); echo "$V348"
beacon "BASELINE-READY mark=$MARK name=$FIXNAME $V348" || die "beacon-baseline-failed" 10

# --- 3. OPERATOR GATE 1: fixture uid must exist server-side before any upgrade
wait_ops "GO $MARK" 480 || die "gate1-timeout-or-abort ops=[$OPS]" 11

# --- 4. upgrade to signed 3.4.9
adb install -r dl349/Rafiq-full.apk | tee shots/upgrade349.txt
grep -q Success shots/upgrade349.txt || die "upgrade-install-failed" 12
V349=$(pkgver); echo "$V349"
echo "$V349" | grep -q "versionCode=43" || die "version-not-43 [$V349]" 13
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep 12
uid_xml
grep -q "اكتب اسمك" /tmp/ui.xml && { shot 14_sess; die "SESSION-LOST-onboarding-shown" 14; }
wait_text "الرئيسية" 60 || { shot 14_home349; die "home349-not-reached" 14; }
[ "$(focus_pkg)" -ge 1 ] || die "home349-not-foreground" 14
shot 15_home_349 2
tap_node text "حسابي" && sleep 4 && shot 16_account_349 1 && adb shell input keyevent 4 && sleep 2
beacon "UPGRADE-OK $V349 SESSION-PRESERVED name=$FIXNAME" || die "beacon-upgrade-failed" 17

# --- 5. OPERATOR GATE 2: push_tokens row count verified, operator sends real-pipeline notice
beacon "FCM-READY mark=$MARK name=$FIXNAME" || die "beacon-fcmready-failed" 18
wait_ops "SENT $MARK" 480 || die "gate2-timeout-or-abort ops=[$OPS]" 19
echo "OPERATOR-NOTE: $OPS"

# --- 6. receipt capture (bounded 4 min from SENT)
FCM_HIT=0
end=$((SECONDS+240))
while [ $SECONDS -lt $end ]; do
  C=$(adb shell dumpsys notification 2>/dev/null | grep -c "NotificationRecord.*$PKG")
  [ "${C:-0}" -ge 1 ] && { FCM_HIT=1; break; }
  sleep 15
done
adb shell cmd statusbar expand-notifications
shot 50_tray_fcm 4
adb shell dumpsys notification > shots/50_dumpsys_fcm.txt
adb shell cmd statusbar collapse
tap_node text "حسابي" && sleep 3 && tap_node text "سجل النشاط" && sleep 3 && shot 51_activitylog 1
adb logcat -d > shots/logcat.txt
grep -iE "firebase|fcm|messaging|pushservice" shots/logcat.txt | tail -30 > shots/logcat_fcm_excerpt.txt
beacon "FCM-E2E-DONE hit=$FCM_HIT v349=$(echo "$V349" | head -1)"
