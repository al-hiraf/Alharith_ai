#!/usr/bin/env bash
# فحص الترقية 3.4.7→3.4.8 على البايتات المنشورة: حساب مزروع + بقاء الجلسة بعد الترقية
set -euo pipefail
PKG=com.alharith.ai
mkdir -p shots

dump_ui() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > "$1" 2>/dev/null; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }
tap_text() {
  dump_ui /tmp/ui.xml
  XY=$(python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
t=sys.argv[1]
try: root=ET.parse('/tmp/ui.xml').getroot()
except Exception: sys.exit(0)
def center(n):
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds','') or '')
    if not m: return None
    a,b,c,d=map(int,m.groups()); return (a+c)//2,(b+d)//2
def walk(n,chain):
    if (n.get('text') or '').strip()==t:
        for a in reversed(chain):
            if a.get('clickable')=='true' and a.get('enabled')!='false':
                c=center(a)
                if c: print(*c); return True
        c=center(n)
        if c: print(*c); return True
        return False
    return any(walk(ch,chain+[n]) for ch in n)
walk(root,[])
PY
)
  [ -n "$XY" ] && adb shell input tap $XY
}
type_name() {
  dump_ui /tmp/ui.xml
  XY=$(python3 - <<'PY'
import re
x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
m=re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
if m: a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2)
PY
)
  [ -n "$XY" ] && adb shell input tap $XY
  sleep 1
  adb shell input text "$1"; sleep 1
  adb shell input keyevent 4 || true
  sleep 1
}
nav_to_chat() { # يتنقل حتى شاشة المحادثة أو يفشل؛ يكتب الاسم عند شاشة الاسم
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 10
    dump_ui shots/B_nav$i.xml
    if grep -q "اكتب أمرًا لرفيق" shots/B_nav$i.xml; then return 0; fi
    if grep -q "بماذا أناديك؟" shots/B_nav$i.xml; then
      type_name "HarithB${GITHUB_RUN_ID}"; tap_text "ابدأ"; continue
    fi
    local moved=0
    for t in "التالي" "ابدأ فورًا" "تكلّم مع رفيق الآن" "تخطي" "لاحقًا"; do
      if grep -q "text=\"$t\"" shots/B_nav$i.xml; then tap_text "$t"; moved=1; break; fi
    done
    [ "$moved" = 0 ] && sleep 5
  done
  dump_ui shots/B_nav_final.xml
  grep -q "اكتب أمرًا لرفيق" shots/B_nav_final.xml
}

adb shell settings put global hide_error_dialogs 1

echo "== (1) تثبيت 3.4.6 المنشورة وتسجيل حساب مزروع =="
adb install -g Rafiq-3.4.7-lite.apk | tee shots/B_v41_install.txt
grep -q Success shots/B_v41_install.txt
adb shell am start -W -n $PKG/.ui.MainActivity > shots/B_v41_start.txt 2>&1
nav_to_chat || { echo "B-V40-SEED-FAILED: chat UI not reached on v41"; shot B_v41_seed_fail; exit 1; }
shot B_v41_seeded
dump_ui shots/B_v41_seeded.xml
grep -q "HarithB${GITHUB_RUN_ID}" shots/B_v41_seeded.xml \
  && echo "B-V40-NAME-VISIBLE" | tee shots/B_v41_state.txt \
  || echo "B-V40-NAME-NOT-ON-CHAT-SCREEN" | tee shots/B_v41_state.txt
echo "B-V40-SEEDED-OK" | tee -a shots/B_v41_state.txt

echo "== (2) ترقية في المكان إلى 3.4.7 (نفس البايتات المنشورة) =="
adb install -r Rafiq-lite.apk | tee shots/B_upgrade_install.txt
adb shell dumpsys package $PKG | grep -E 'versionCode=|versionName=' | tee shots/B_version.txt
grep -q 'versionName=3.4.8' shots/B_version.txt && grep -q 'versionCode=42 ' shots/B_version.txt || { echo 'B-VERSION-MISMATCH'; exit 1; }
echo B-VERSION-OK
grep -q Success shots/B_upgrade_install.txt || { echo "B-UPGRADE-INSTALL-FAILED"; exit 1; }
echo "B-UPGRADE-INSTALL-OK" | tee shots/B_upgrade_state.txt

echo "== (3) تشغيل 3.4.7 — الجلسة يجب أن تبقى (شاشة محادثة، لا إعداد) =="
adb shell am start -W -n $PKG/.ui.MainActivity > shots/B_v41_start.txt 2>&1
ok=0
for i in 1 2 3 4 5 6; do
  sleep 10
  dump_ui shots/B_v41_check$i.xml
  if grep -q "بماذا أناديك؟" shots/B_v41_check$i.xml; then
    echo "B-SESSION-LOST: onboarding name screen after upgrade" | tee shots/B_result.txt
    shot B_session_lost
    exit 1
  fi
  if python3 -c "
import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
m=re.search(r'(?:مساء|صباح) النور يا ([A-Za-z0-9]+)', x)
sys.exit(0 if (m and sys.argv[2].startswith(m.group(1))) else 1)
" shots/B_v41_check$i.xml "HarithB${GITHUB_RUN_ID}"; then ok=1; cp shots/B_v41_check$i.xml shots/B_v41_session.xml; break; fi
  if grep -q "While using the app" shots/B_v41_check$i.xml; then tap_text "While using the app"; fi
  if grep -q ">Allow<" shots/B_v41_check$i.xml; then tap_text "Allow"; fi
  if grep -q "اكتب أمرًا لرفيق" shots/B_v41_check$i.xml; then ok=1; cp shots/B_v41_check$i.xml shots/B_v41_session.xml; break; fi
done
[ "$ok" = 1 ] || { echo "B-SESSION-UNCONFIRMED: neither retained-session (greeting/chat) nor name screen"; shot B_session_unknown; exit 1; }
shot B_v41_session
grep -qE "(مساء|صباح) النور يا HarithB" shots/B_v41_session.xml \
  && echo "B-NAME-RETAINED-AFTER-UPGRADE" | tee -a shots/B_upgrade_state.txt \
  || echo "B-NAME-NOT-VISIBLE-ON-CHAT-SCREEN" | tee -a shots/B_upgrade_state.txt
echo "B-SESSION-PRESERVED-OK: v41 seeded account → v41 chat UI without re-onboarding" | tee shots/B_result.txt
