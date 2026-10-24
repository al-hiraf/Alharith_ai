#!/usr/bin/env bash
# فحص 3.4.8 الموقّعة (full) على محاكي أندرويد 14: تثبيت جديد + إكمال التهيئة + الواجهة الرئيسية + النسخة
# ملاحظة: وسائط open_screen/seed_demo تعمل في بناءات DEBUG فقط؛ النسخة الموقّعة release — نكمل التهيئة يدويًا
set -euo pipefail
PKG=com.alharith.ai
mkdir -p shots

dump_ui() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > "$1" 2>/dev/null; }
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

adb install -g Rafiq-full.apk | tee shots/A_install.txt
grep -q Success shots/A_install.txt || { echo "A-INSTALL-FAILED"; exit 1; }
adb shell pm revoke $PKG android.permission.RECORD_AUDIO || true
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
sleep 8
adb logcat -c
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.ui.MainActivity
# أكمل التهيئة (شاشة الاسم متوقعة في التثبيت الجديد) حتى الواجهة الرئيسية
ok=0
for i in 1 2 3 4 5 6 7 8; do
  sleep 8
  dump_ui shots/A_nav$i.xml
  if grep -q "مهامي" shots/A_nav$i.xml && grep -q "رفيق AI" shots/A_nav$i.xml; then cp shots/A_nav$i.xml shots/ui_home.xml; ok=1; break; fi
  if grep -q "بماذا أناديك؟" shots/A_nav$i.xml; then type_name "HarithA${GITHUB_RUN_ID}"; tap_text "ابدأ"; continue; fi
  moved=0
  if grep -q "While using the app" shots/A_nav$i.xml; then tap_text "While using the app"; continue; fi
  if grep -q ">Allow<" shots/A_nav$i.xml; then tap_text "Allow"; continue; fi
  for t in "الذهاب للرئيسية" "تكلّم مع رفيق الآن" "التالي" "ابدأ فورًا" "تخطي" "لاحقًا"; do
    if grep -q "text=\"$t\"" shots/A_nav$i.xml; then tap_text "$t"; moved=1; break; fi
  done
  [ "$moved" = 0 ] && sleep 5
done
[ "$ok" = 1 ] || { echo "A-HOME-UI-FAILED"; adb exec-out screencap -p > shots/A_home.png; exit 1; }
adb exec-out screencap -p > shots/A_home.png
adb shell dumpsys package $PKG | grep -E 'versionCode=|versionName=' | tee shots/A_version.txt
grep -q 'versionName=3.4.8' shots/A_version.txt && grep -q 'versionCode=42 ' shots/A_version.txt || { echo 'A-VERSION-MISMATCH'; exit 1; }
adb logcat -d | grep -iE "FATAL EXCEPTION.*$PKG" && { echo "A-CRASH"; exit 1; } || true
echo A-FRESH-OK
