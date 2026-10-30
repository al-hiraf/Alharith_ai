#!/usr/bin/env bash
# Signed 3.4.8 -> signed 3.4.9 upgrade + real FCM pipeline E2E, v2.3.
# v2.3 = v2.2 review fixes + sealed evidence: public branch carries ONLY
# phase.txt beacons and a GPG-encrypted archive (key: rafiq-ci-evidence, public
# part committed at .github/ci-evidence-pub.asc; private part in vault). No
# plaintext screenshots/logs/ui/xml leave the runner. Fail-closed: no key or
# encryption failure => no archive publication. v2.2 implemented:
#  1 KeyStep auto-register loop (no manual-button/✓ requirement, die on 2x ✗)
#  2 exact home asserts (مهامي + إنجاز اليوم) after tapping الذهاب للرئيسية
#  3 FCM receipt = NEW notice-record id == 900000+(noticeId%90000) on
#    harith_reminders/harith_briefing, snapshotted before SENT, 5s poll
#  4 attribution: HOME + am kill before send; require Start proc PushService
#  5 public evidence: phase beacons + GPG-sealed archive only (v2.3 supersedes);
#    run branch deleted at end (workflow step)
#  6 bottom-bar tap الرئيسية instead of keyevent 4 + focus_pkg checks
#  7 IME dismissal (keyevent 111) + enabled="true" assert before tapping ابدأ
#  8 prefer التالي; امنح الكل only after stuck loops; relaunch on focus loss
#  9 fixture isolation notes: per-uid dedupe fcm-e2e:<RUNID>:<uid>, one send,
#    CLEANUP note on every terminal beacon incl. die
# 10 hash gates, beacon readback, GMS check kept
set -x
PKG=com.alharith.ai
RUNID="${GITHUB_RUN_ID:-local}"
FIXNAME="FxR${GITHUB_RUN_NUMBER:-0}"
MARK="FCMCI-$RUNID"
BRANCH="fcm-run-$RUNID"
REPO="${GITHUB_REPOSITORY}"
{ set +x; AUTH="https://x-access-token:${GH_TOKEN}@github.com/${REPO}"; } 2>/dev/null
mkdir -p shots rawlogs pub

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
uid_xml() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null; }

# v2.3: public branch carries ONLY phase.txt + evidence.tar.gpg (ciphertext).
EVKEY_FPR=""
EVKEY_EXPECTED="5938A7146717E9B11423EE94D64EA219EEAAB2D3"
seal_init() { # import repo-committed public key; pin fingerprint; fail-closed
  gpg --batch --import .github/ci-evidence-pub.asc >/dev/null 2>&1
  EVKEY_FPR=$(gpg --batch --with-colons -k rafiq-ci-evidence 2>/dev/null | awk -F: '/^fpr/{print $10; exit}')
  [ "$EVKEY_FPR" = "$EVKEY_EXPECTED" ] || { EVKEY_FPR=""; echo "EVIDENCE-KEY-PIN-MISMATCH archive-publication-disabled"; return 1; }
  echo "EVIDENCE-KEY-OK"
}
seal_evidence() { # tar+encrypt; publish ciphertext only; fail-closed
  [ -n "$EVKEY_FPR" ] || { echo "SEAL-SKIP no-key"; return 1; }
  tar czf /tmp/evidence.tar.gz shots rawlogs 2>/dev/null || { echo "SEAL-TAR-FAILED"; return 1; }
  gpg --batch --yes --trust-model always -r "$EVKEY_FPR" -o pub/evidence.tar.gpg -e /tmp/evidence.tar.gz \
    || { rm -f pub/evidence.tar.gpg; echo "SEAL-FAILED not-publishing"; return 1; }
  gpg --batch --list-packets pub/evidence.tar.gpg 2>/dev/null | grep -q "pubkey enc packet" \
    || { rm -f pub/evidence.tar.gpg; echo "SEAL-VERIFY-FAILED not-publishing"; return 1; }
  { set +x; (cd pub && git add evidence.tar.gpg \
    && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "evidence gpg-encrypted $RUNID" \
    && (git push -q "$AUTH" "$BRANCH" || { git pull -q --rebase "$AUTH" "$BRANCH" && git push -q "$AUTH" "$BRANCH"; })) || true; } 2>/dev/null
  echo "SEAL-OK"
}
push_beacon() {
  { set +x; (cd pub && git add phase.txt && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "$1" \
   && (git push -q "$AUTH" "$BRANCH" || { git pull -q --rebase "$AUTH" "$BRANCH" && git push -q "$AUTH" "$BRANCH"; })); } 2>/dev/null
}

beacon() { # write phase.txt -> push -> readback verify -> one retry
  echo "$1" > pub/phase.txt
  for try in 1 2; do
    push_beacon "$1" || true
    RB=$(gh api "repos/$REPO/contents/phase.txt?ref=$BRANCH" --jq '.content' 2>/dev/null | base64 -d 2>/dev/null)
    [ "$RB" = "$1" ] && { echo "BEACON-OK $1"; return 0; }
    echo "BEACON-READBACK-MISS try=$try"
    sleep 3
  done
  echo "BEACON-FAILED $1"; return 1
}

die() { # fix9: every terminal beacon carries the cleanup instruction
  cp /tmp/ui.xml shots/fail_ui.xml 2>/dev/null; shot "fail_$2" 1
  adb logcat -d > rawlogs/logcat_on_die.txt 2>/dev/null
  seal_evidence || echo "die: archive not published (fail-closed)"
  beacon "FAIL $1 CLEANUP:fixture($FIXNAME)" || true
  echo "DIE: $1"; exit 1
}

wait_node() { # attr value timeout_sec
  local end=$((SECONDS+${3:-60}))
  while [ $SECONDS -lt $end ]; do uid_xml; grep -q "$1=\"$2\"" /tmp/ui.xml && return 0; sleep 4; done; return 1
}

tap_node() { # attr value  (attr = text | content-desc), exact match
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

assert_enabled() { # fix7: exact-text node exists with enabled="true"
  python3 - "$1" <<'PY'
import re,sys
val=sys.argv[1]
x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node[^>]*>', x):
    tag=m.group(0)
    am=re.search(r'text="([^"]*)"', tag)
    en=re.search(r'enabled="([^"]*)"', tag)
    if am and am.group(1).strip()==val and en and en.group(1)=='true':
        sys.exit(0)
sys.exit(1)
PY
}

dismiss_ime() { # fix7: ESC a shown keyboard, then re-dump
  if adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"; then
    adb shell input keyevent 111; sleep 1
  fi
  uid_xml
}

focus_pkg() { adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -c "$PKG"; }
relaunch_if_lost() { [ "$(focus_pkg)" -ge 1 ] || { adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null 2>&1; sleep 3; }; }
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

# fix3: ids of OUR notice records (900000-range) on the notice channels
notice_ids() {
  adb shell dumpsys notification --noredact > /tmp/notif_dump.txt 2>/dev/null || adb shell dumpsys notification > /tmp/notif_dump.txt 2>/dev/null
  python3 - <<'PY'
import re
try: x=open('/tmp/notif_dump.txt',encoding='utf-8',errors='ignore').read()
except Exception: x=''
ids=set()
for blk in x.split('NotificationRecord(')[1:]:
    if 'com.alharith.ai' not in blk: continue
    if not any(c in blk for c in ('harith_reminders','harith_briefing','rafiq_reminders_v2','rafiq_briefing_v2')): continue
    m=re.search(r'(?:/|id=)(9\d{5})\b', blk)
    if m: ids.add(m.group(1))
print('\n'.join(sorted(ids)))
PY
}

# --- init unique per-run branch (pub/ carries ONLY phase.txt + ciphertext)
{ set +x; (cd pub && git init -q && git checkout -q -b "$BRANCH" \
 && git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm init --allow-empty \
 && git push -qf "$AUTH" "$BRANCH"); } 2>/dev/null || { echo "BRANCH-INIT-FAILED"; exit 1; }
seal_init

adb shell settings put global hide_error_dialogs 1
adb wait-for-device
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 2; done'

# --- 0. Play Services present (never blame FCM without it)  [fix10]
adb shell pm list packages 2>/dev/null | grep -q com.google.android.gms || die "no-play-services" 00
adb shell dumpsys package com.google.android.gms 2>/dev/null | grep versionName | head -1

# --- 1. pinned signed APKs, hash-verified  [fix10]
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
wait_node content-desc "اسمك" 60 || { shot 02_first; die "namescreen-not-shown" 02; }
tap_node content-desc "اسمك" || die "namefield-not-found" 03
adb shell input text "$FIXNAME"; sleep 2
uid_xml; grep -q "$FIXNAME" /tmp/ui.xml || { shot 04_typed; die "nametext-not-landed" 04; }
shot 04_typed 1
dismiss_ime                                                    # fix7
assert_enabled "ابدأ" || { shot 05_start; die "startbtn-disabled" 05; }
tap_node text "ابدأ" || die "startbtn-not-found" 05

# fix1: KeyStep auto-register loop (spinner path normally replaces the button)
PROV_OK=0; XCOUNT=0
end=$((SECONDS+150))
while [ $SECONDS -lt $end ]; do
  uid_xml
  if grep -qE 'text="(امنح الكل بضغطة|التالي|تكلّم مع رفيق الآن|الذهاب للرئيسية)"' /tmp/ui.xml; then PROV_OK=1; break; fi
  if grep -q "✗" /tmp/ui.xml; then XCOUNT=$((XCOUNT+1)); else XCOUNT=0; fi
  [ "$XCOUNT" -ge 2 ] && { shot 08_prov; die "provisioning-failed-x2" 08; }
  grep -q 'text="ابدأ فورًا"' /tmp/ui.xml && tap_node text "ابدأ فورًا"
  sleep 5
done
[ "$PROV_OK" = 1 ] || { shot 08_prov; die "provisioning-timeout" 08; }
shot 08_ready 1

# fix8: permissions/final pages — prefer التالي; امنح الكل only after 2 stuck loops
STUCK=0
for i in $(seq 1 10); do
  uid_xml
  grep -q 'text="الذهاب للرئيسية"' /tmp/ui.xml && break
  grep -q 'text="مهامي"' /tmp/ui.xml && break
  if grep -q 'text="التالي"' /tmp/ui.xml; then tap_node text "التالي"; sleep 3; continue; fi
  if grep -q 'text="امنح الكل بضغطة"' /tmp/ui.xml; then
    STUCK=$((STUCK+1))
    if [ "$STUCK" -ge 2 ]; then
      tap_node text "امنح الكل بضغطة"; sleep 3
      for T in "While using the app" "أثناء استخدام التطبيق" "Allow" "السماح"; do tap_node text "$T" 2>/dev/null || true; sleep 1; done
    fi
    relaunch_if_lost
    sleep 4; continue
  fi
  relaunch_if_lost
  sleep 4
done

# fix2: final page -> tap الذهاب للرئيسية (sets onboarded), assert exact home nodes
uid_xml
grep -q 'text="مهامي"' /tmp/ui.xml || { tap_node text "الذهاب للرئيسية" || { shot 09_final; die "finalpage-skiplink-missing" 09; }; }
wait_node text "مهامي" 90 || { shot 09_home; die "home-not-reached" 09; }
wait_node text "إنجاز اليوم" 30 || { shot 09_home; die "home-assert-missing" 09; }
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
grep -q 'content-desc="اسمك"' /tmp/ui.xml && { shot 14_sess; die "SESSION-LOST-onboarding-shown" 14; }
# fix2 (post-upgrade): exact home nodes, never substring الرئيسية
grep -q 'text="مهامي"' /tmp/ui.xml || tap_node text "الذهاب للرئيسية" || true
wait_node text "مهامي" 60 || { shot 14_home349; die "home349-not-reached" 14; }
wait_node text "إنجاز اليوم" 30 || { shot 14_home349; die "home349-assert-missing" 14; }
[ "$(focus_pkg)" -ge 1 ] || die "home349-not-foreground" 14
shot 15_home_349 2
tap_node text "حسابي" && sleep 4 && shot 16_account_349 1
tap_node text "الرئيسية" && sleep 2                                  # fix6
relaunch_if_lost
beacon "UPGRADE-OK $V349 SESSION-PRESERVED name=$FIXNAME" || die "beacon-upgrade-failed" 17

# --- 5. fix4: background-kill so the push itself must wake the process
adb shell input keyevent 3
sleep 2
adb shell am kill $PKG        # NOT force-stop (force-stop blocks FCM)
sleep 2
adb logcat -c 2>/dev/null || true   # clear buffer: any later Start proc is attributable
BEFORE_IDS=$(notice_ids)            # fix3: snapshot existing notice records
echo "BEFORE_IDS: $BEFORE_IDS"

# --- 6. OPERATOR GATE 2: operator verifies push_tokens row, then sends the real
#        pipeline notice to the FIXTURE UID ONLY with dedupe fcm-e2e:<RUNID>:<uid>
#        and writes: SENT $MARK id=<noticeId> epoch=<unix>      [fix3+fix9]
beacon "FCM-READY mark=$MARK name=$FIXNAME" || die "beacon-fcmready-failed" 18
wait_ops "SENT $MARK" 480 || die "gate2-timeout-or-abort ops=[$OPS]" 19
echo "OPERATOR-NOTE: $OPS"
NOTICE_ID=$(echo "$OPS" | sed -nE 's/.*id=([0-9]+).*/\1/p')
SENT_EPOCH=$(echo "$OPS" | sed -nE 's/.*epoch=([0-9]+).*/\1/p')
[ -n "$NOTICE_ID" ] || die "ops-missing-notice-id [$OPS]" 20
REC_ID=$((900000 + NOTICE_ID % 90000))
echo "EXPECT-RECORD id=$REC_ID from-notice=$NOTICE_ID"

# --- 7. receipt: NEW matching record only, 5s poll, elapsed from operator epoch
FCM_HIT=0; ELAPSED=-1
end=$((SECONDS+240))
while [ $SECONDS -lt $end ]; do
  NOW_IDS=$(notice_ids)
  if echo "$NOW_IDS" | grep -qx "$REC_ID" && ! echo "$BEFORE_IDS" | grep -qx "$REC_ID"; then
    FCM_HIT=1
    [ -n "$SENT_EPOCH" ] && ELAPSED=$(( $(date +%s) - SENT_EPOCH ))
    break
  fi
  sleep 5
done
echo "FCM-RECORD-HIT=$FCM_HIT elapsed=${ELAPSED}s"

# --- 8. attribution evidence  [fix4]
ATTRIB="ambiguous"
adb logcat -d > rawlogs/logcat.txt
if [ "$FCM_HIT" = 1 ]; then
  grep -qE "Start proc [0-9]+:com\.alharith\.ai[^ ]* for service [^ ]*PushService" rawlogs/logcat.txt \
    && ATTRIB="pushservice-wake"
fi

# --- 9. capture + closeout
adb shell cmd statusbar expand-notifications
shot 50_tray_fcm 4
adb shell dumpsys notification > rawlogs/50_dumpsys_fcm.txt
adb shell cmd statusbar collapse
relaunch_if_lost
[ "$(focus_pkg)" -ge 1 ] || die "relaunch-failed" 21
tap_node text "حسابي" && sleep 3 && tap_node text "سجل النشاط" && sleep 3 && shot 51_activitylog 1
tap_node text "الرئيسية" || true                                    # fix6
grep -iE "firebase|fcm|messaging|pushservice" rawlogs/logcat.txt | tail -30 > shots/logcat_fcm_excerpt.txt
seal_evidence || echo "final: archive not published (fail-closed)"
beacon "FCM-E2E-DONE hit=$FCM_HIT attrib=$ATTRIB elapsed=${ELAPSED}s rec=$REC_ID OPERATOR:confirm-last_ok_at CLEANUP:fixture($FIXNAME)"
