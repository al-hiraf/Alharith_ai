#!/usr/bin/env bash
# اختبار تشغيل فعلي على محاكي Android: تثبيت، فتح كل الشاشات، لقطات، أمر مكتوب بدون صلاحية الميكروفون، وفحص الأعطال
set -x
APK=app/build/outputs/apk/full/debug/app-full-debug.apk
PKG=com.alharith.ai
mkdir -p shots
adb install -r -g "$APK" | tee shots/install.txt
# نختبر المسار الذي كان معطّلًا: بدون صلاحية الميكروفون
adb shell pm revoke $PKG android.permission.RECORD_AUDIO || true
adb shell settings put system font_scale 1.0
# إخفاء نوافذ "لا يستجيب" الخاصة بالمحاكي نفسه (ليست من رفيق) حتى تكون اللقطات واضحة
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
adb logcat -c

ensure_adb() { [ "$(timeout 10 adb get-state 2>/dev/null)" = "device" ] || { echo "adb reconnect at $1 $(date)" >> shots/progress.txt; adb reconnect offline >/dev/null 2>&1; timeout 90 adb wait-for-device; sleep 5; }; }
shot() { sleep "$2"; ensure_adb "$1"; adb exec-out screencap -p > "shots/$1.png"; }
start() { adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n $PKG/.ui.MainActivity "$@"; }
tap_text() {
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null
  XY=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1]; x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1).strip()==t:
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY
}

start --es open_screen home --ez seed_demo true; shot 01_home 8
# Synthetic avatar fixture, no personal photo. Exercise same decoder/save path as picker.
python3 - <<'PHOTO'
import struct,zlib
w=h=80
raw=b''.join(b'\0'+b'\x1a\x8c\x8c'*w for _ in range(h))
def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
open('/tmp/avatar.png','wb').write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
PHOTO
adb push /tmp/avatar.png /data/local/tmp/avatar.png
adb shell "run-as $PKG cp /data/local/tmp/avatar.png files/avatar.png"
start --es open_screen account --es test_profile_photo avatar.png; shot profile_photo_saved 5
start --es open_screen home; shot profile_photo_home 4
start --es open_screen account; shot profile_photo_persisted 4
adb logcat -d -s RafiqPhoto:I | grep 'PASS profile photo' > shots/profile_summary.txt || true
start --es open_screen account --es test_profile_photo remove; shot profile_photo_removed 4
adb logcat -d -s RafiqPhoto:I | grep 'PASS profile photo removed' >> shots/profile_summary.txt || true

# Recurring creation selector is visible on Tasks; no production data.
start --es open_screen tasks; shot recurring_task_option 4
# Design regression matrix: explicit modes, persisted restart, device-following, small width.
for mode in light dark; do
  for screen in home tasks chat calendar account settings onboarding scheduled; do
    start --es open_screen "$screen" --es test_theme "$mode"
    shot "design_${screen}_${mode}" 3
  done
  # Restart without a theme override to exercise persisted selection.
  start --es open_screen account; shot "design_persisted_${mode}" 3
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/design_ui.xml 2>/dev/null
  grep -q "حسب الجوال" /tmp/design_ui.xml && echo "PASS design persisted route $mode" >> shots/design_summary.txt
 done
start --es open_screen home --es test_theme system
adb shell cmd uimode night no; shot design_system_light 3
adb shell cmd uimode night yes; shot design_system_dark 3
adb shell cmd uimode night no
adb shell wm size 960x2133
adb shell wm density 480
start --es open_screen home; shot design_small_home 3
adb shell input swipe 480 1600 480 500 400; shot design_small_home_scroll 2
adb shell settings put system font_scale 1.3
start --es open_screen onboarding; shot design_large_font_onboarding 3
adb shell settings put system font_scale 1.0
adb shell wm size reset; adb shell wm density reset
start --es open_screen home --es test_theme system; sleep 3

# الواجهة 3.0: الشريط السفلي + الرئيسية تعرض بيانات حقيقية + التقويم وحسابي
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_home.xml 2>/dev/null
  adb shell input swipe 540 1700 540 700 400; sleep 2
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml >> shots/ui_home.xml 2>/dev/null
  grep -q "رفيق AI" shots/ui_home.xml && grep -q "مهامي" shots/ui_home.xml && grep -q "إنجاز اليوم" shots/ui_home.xml && grep -q "إرسال عرض السعر للعميل" shots/ui_home.xml \
    && echo "PASS home 3.0: الشريط السفلي ومؤشرات اليوم ومهام اليوم الفعلية" || echo "FAIL home 3.0"
} | tee shots/ui3.txt
tap_text "التقويم"; shot 31_calendar 5
tap_text "حسابي"; shot 32_account 4
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_account.xml 2>/dev/null
  grep -q "الألوان" shots/ui_account.xml && echo "PASS colors: خيار الألوان ظاهر في حسابي" || echo "FAIL colors"
  adb shell input swipe 540 1800 540 500 400; sleep 2
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml >> shots/ui_account.xml 2>/dev/null
  adb shell input swipe 540 500 540 1800 400; sleep 1
  grep -q "وضع العرض" shots/ui_account.xml && grep -q "الإعدادات" shots/ui_account.xml && echo "PASS account: حسابي يعرض وضع العرض والأقسام" || echo "FAIL account"
} | tee -a shots/ui3.txt
tap_text "زمردي"; sleep 2; shot 35_account_emerald 2
tap_text "الرئيسية"; shot 36_home_emerald 4
tap_text "حسابي"; sleep 3; tap_text "نيلي"; sleep 2; shot 37_account_indigo 2
tap_text "لاله‌زار"; sleep 2; shot 39_account_font 2
tap_text "الرئيسية"; shot 40_home_font 4
tap_text "حسابي"; sleep 3; tap_text "المرعي"; sleep 1
# ——— استيراد خط من ملف مضغوط (مثل ملف «ثمانية» بعد تنزيله) ثم التحقق أنه ظهر وطُبّق
{
  adb push .github/fixtures/font_pack.zip /data/local/tmp/font.zip >/dev/null && adb shell chmod 644 /data/local/tmp/font.zip
  adb shell run-as $PKG cp /data/local/tmp/font.zip files/font.zip
  adb logcat -c; start --es open_screen account --es import_font font.zip; sleep 5
  adb logcat -d -s RafiqFont | tail -2
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_font.xml 2>/dev/null
  adb shell input swipe 540 1700 540 900 400; sleep 2
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml >> shots/ui_font.xml 2>/dev/null
  shot 41_account_custom_font 1
  adb logcat -d -s RafiqFont | grep -q "OK TestSans" && grep -q "TestSans" shots/ui_font.xml && echo "PASS font import: حزمة خطوط مضغوطة (مجلدات + woff) اختير منها Sans العريض وطُبّق" || echo "FAIL font import"
  tap_text "المرعي"; sleep 1
} | tee -a shots/ui3.txt
tap_text "الرئيسية"; shot 38_home_indigo 4
tap_text "حسابي"; sleep 3; tap_text "ذهبي"; sleep 1
tap_text "داكن"; sleep 2; shot 33_account_dark 2
tap_text "الرئيسية"; shot 34_home_dark_mode 4
tap_text "حسابي"; sleep 3; tap_text "حسب الجوال"; sleep 1
start --es open_screen tasks;    shot 02_tasks 5
start --es open_screen settings; shot 03_settings 5
# الإعدادات مجموعات: نفتح كل مجموعة ونلتقطها، ونتأكد أن الصفحة الرئيسية تعرض المجموعات الخمس
timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_settings_hub.xml 2>/dev/null
{ n=0; for g in "الذكاء والخادم" "الصوت والنداء" "البريد" "الموجز والتنبيهات" "البيانات والأمان"; do grep -q "text=\"$g\"" shots/ui_settings_hub.xml && n=$((n+1)); done
  [ $n = 5 ] && echo "PASS settings hub: خمس مجموعات واضحة" || echo "FAIL settings hub ($n/5)"; } | tee shots/settings.txt
i=0; for g in "الذكاء والخادم" "الصوت والنداء" "البريد" "الموجز والتنبيهات" "البيانات والأمان"; do i=$((i+1))
  # المجموعات تُطوى وتُفتح في الصفحة نفسها: نفتح، نلتقط، ثم نطويها
  tap_text "$g"; sleep 3; shot "04_settings_g$i" 1
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > "shots/ui_settings_g$i.xml" 2>/dev/null
  [ "$(grep -o 'text="[^"]' "shots/ui_settings_g$i.xml" | wc -l)" -gt "$(grep -o 'text="[^"]' shots/ui_settings_hub.xml | wc -l)" ] \
    && echo "PASS settings group opens: $g" | tee -a shots/settings.txt || echo "FAIL settings group: $g" | tee -a shots/settings.txt
  tap_text "$g"; sleep 2; done
start --es open_screen memory;   shot 05_memory 4
start --es open_screen chat --es ask "'ذكرني بكرة الساعة 9 أتصل بمحمد'"; shot 06_chat_command 12
# اختبار الطابور: بدون إنترنت يُحفظ الأمر، وعند عودة الاتصال يُنفَّذ تلقائيًا
adb shell svc wifi disable; adb shell svc data disable; sleep 4
start --es open_screen chat --es ask "'رتب لي يومي'"; shot 08_offline_queued 6
adb shell svc wifi enable; adb shell svc data enable; sleep 15; shot 09_back_online 1
start --es open_screen diagnostics; shot 10_diagnostics 10
# الوضع الفاتح
adb shell cmd uimode night no; sleep 2
start --es open_screen home; shot 12_home_light 6
start --es open_screen chat; shot 13_chat_light 4
adb shell cmd uimode night yes; sleep 2
start --es open_screen home; shot 14_home_dark 6
start --es open_screen log;      shot 07_activity_log 4

# ——— العقل المشترك: خادم رفيق الحقيقي على المضيف، والتطبيق يصل له عبر 127.0.0.1 (adb reverse)
echo "== shared brain start $(date)" >> shots/progress.txt
timeout 180 bash -c 'python3 -m venv /tmp/hv && /tmp/hv/bin/pip install -q -r server/requirements.txt' </dev/null
(setsid nohup python3 .github/scripts/fake_openrouter.py </dev/null >/dev/null 2>&1 &)
(cd server && HARITH_DATA_DIR=/tmp/hsrv HARITH_HOME=/tmp/hsrv AI_PROVIDER=gemini GEMINI_API_KEY= TELEGRAM_BOT_TOKEN= \
  OPENROUTER_PROVISIONING_KEY=prov-test OPENROUTER_API_BASE=http://127.0.0.1:8799/api/v1 \
  setsid nohup /tmp/hv/bin/python -m harith run </dev/null > ../shots/server.log 2>&1 &)
for i in $(seq 1 40); do curl -sf -m 3 http://127.0.0.1:8787/api/health && break; sleep 1; done
echo "== server up $(date)" >> shots/progress.txt
J=/tmp/cj; H="X-Harith: 1"
curl -s -m 10 -c $J -b $J -H "$H" -H 'Content-Type: application/json' -d '{"username":"mohand","password":"smoke-test-123","display_name":"مهند"}' http://127.0.0.1:8787/api/setup
TOK=$(curl -s -m 10 -c $J -b $J -H "$H" -H 'Content-Type: application/json' -d '{"name":"emulator"}' http://127.0.0.1:8787/api/tokens | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -m 10 -c $J -b $J -H "$H" -H 'Content-Type: application/json' -d '{"title":"مهمة أُضيفت من تيليجرام","priority":"high"}' http://127.0.0.1:8787/api/tasks
timeout 20 adb reverse tcp:8787 tcp:8787
echo "== token ${#TOK} chars, reverse ok $(date)" >> shots/progress.txt
start --es open_screen tasks --es server_token "$TOK" --es server_url http://127.0.0.1:8787; shot 15_shared_brain_tasks 18
curl -s -m 10 -b $J -H "$H" "http://127.0.0.1:8787/api/tasks?filter=all" > shots/server_tasks.json
{
  grep -q "إرسال عرض السعر للعميل" shots/server_tasks.json && echo "PASS phone→server: مهام الجوال وصلت للخادم" || echo "FAIL phone→server"
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_tasks.xml 2>/dev/null
  grep -q "مهمة أُضيفت من تيليجرام" shots/ui_tasks.xml && echo "PASS server→phone: مهمة الخادم ظهرت في التطبيق" || echo "FAIL server→phone"
} | tee shots/shared_brain.txt
start --es open_screen settings; adb shell input swipe 540 1900 540 300 300; sleep 1; adb shell input swipe 540 1900 540 300 300; shot 16_shared_brain_settings 3
# ——— الأعمال والمالية داخل التطبيق (بيانات حقيقية من الخادم)
BT() { curl -s -m 10 -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "$2" "http://127.0.0.1:8787/api/tool/$1" >/dev/null; }
BT workspace_create '{"name":"مؤسسة الحرف للمقاولات"}'
BT finance_record '{"kind":"income","amount":"62000","workspace":"مؤسسة الحرف للمقاولات","category":"مبيعات"}'
BT finance_record '{"kind":"expense","amount":"21000","workspace":"مؤسسة الحرف للمقاولات","category":"مواد"}'
BT budget_set '{"workspace":"مؤسسة الحرف للمقاولات","category":"مواد","amount":"30000"}'
BT invoice_create '{"kind":"invoice","workspace":"مؤسسة الحرف للمقاولات","contact":"شركة النخبة","amount":"85000","title":"مستخلص 3"}'
BT invoice_update '{"invoice":"INV-'"$(date +%Y)"'-0001","status":"sent"}'
start --es open_screen business; shot 22_business 10
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_business.xml 2>/dev/null
  grep -q "مؤسسة الحرف" shots/ui_business.xml && grep -q "INV-" shots/ui_business.xml && echo "PASS business: شاشة الأعمال تعرض بيانات الخادم" || echo "FAIL business screen"
} | tee -a shots/shared_brain.txt
# ——— إدارة المشاريع داخل التطبيق: مشروع من قالب ← اللوحة بالأعمدة ← المراحل
BT project_from_template '{"template":"construction","name":"فيلا النخيل","start":"'"$(date -d '-60 days' +%F)"'","owner":"م. أحمد"}'
start --es open_screen projects; shot 27_projects 10
tap_text "فيلا النخيل"; shot 28_project_board 8
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_project.xml 2>/dev/null
  grep -q "للتنفيذ" shots/ui_project.xml && grep -q "اعتماد المخططات" shots/ui_project.xml && echo "PASS projects: لوحة المشروع تعرض الأعمدة والمهام من القالب" || echo "FAIL projects board"
} | tee -a shots/shared_brain.txt
tap_text "المراحل والجدول"; shot 29_project_phases 6
# ——— الفريق: عضو ينضم برابط دعوة ← يظهر في تبويب الفريق مع سجل النشاط
PID=$(curl -s -m 10 -H "Authorization: Bearer $TOK" http://127.0.0.1:8787/api/projects | python3 -c 'import sys,json;print(next(p["id"] for p in json.load(sys.stdin) if p["name"]=="فيلا النخيل"))')
ICODE=$(curl -s -m 10 -X POST -H "Authorization: Bearer $TOK" http://127.0.0.1:8787/api/projects/$PID/invite | python3 -c 'import sys,json;print(json.load(sys.stdin)["code"])')
curl -s -m 10 -H 'Content-Type: application/json' -d '{"code":"'"$ICODE"'","name":"خالد","username":"khalid","password":"khalid-pass-1","app":true}' http://127.0.0.1:8787/api/public/join > /tmp/team_join.json
KID=$(curl -s -m 10 -H "Authorization: Bearer $TOK" http://127.0.0.1:8787/api/projects/$PID/team | python3 -c 'import sys,json;print(json.load(sys.stdin)["members"][1]["user_id"])')
T1=$(curl -s -m 10 -H "Authorization: Bearer $TOK" http://127.0.0.1:8787/api/projects/$PID/board | python3 -c 'import sys,json;print(json.load(sys.stdin)["columns"][0]["tasks"][0]["id"])')
curl -s -m 10 -X PATCH -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"assignee_user":'"$KID"'}' http://127.0.0.1:8787/api/tasks/$T1 >/dev/null
start --es open_screen projects; sleep 8; tap_text "فيلا النخيل"; sleep 8
tap_text "الفريق (٢)"; shot 30_project_team 6
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_team.xml 2>/dev/null
  adb shell input swipe 540 1800 540 500 400; sleep 2; adb shell input swipe 540 1800 540 500 400; sleep 2
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml >> shots/ui_team.xml 2>/dev/null
  grep -q "token" /tmp/team_join.json && grep -q "خالد" shots/ui_team.xml && grep -q "انضم إلى الفريق" shots/ui_team.xml && echo "PASS team: عضو انضم برابط الدعوة وظهر في الفريق والنشاط" || echo "FAIL team"
} | tee -a shots/shared_brain.txt
start --es open_screen search; sleep 3; adb shell input text "INV"; shot 23_search 6
echo -n "https://demo-rafiq-link.trycloudflare.com" > /tmp/hsrv/tunnel-url.txt
start --es open_screen server; shot 26_server 8
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_server.xml 2>/dev/null
  grep -q "demo-rafiq-link" shots/ui_server.xml && echo "PASS server screen: خادمي يعرض رابط الإنترنت والمفاتيح" || echo "FAIL server screen"
} | tee -a shots/shared_brain.txt
# ——— الربط برمز من 6 أرقام: تطبيق جديد غير مربوط ← rafiq-pair ← يكتب الرمز ← يرتبط تلقائيًا
CODE=$(cd server && HARITH_DATA_DIR=/tmp/hsrv HARITH_HOME=/tmp/hsrv AI_PROVIDER=gemini timeout 30 /tmp/hv/bin/python -m harith pair 2>/dev/null | grep -o '[0-9]\{3\} [0-9]\{3\}' | head -1 | tr -d ' ')
echo "== pair code: ${#CODE} digits $(date)" >> shots/progress.txt
start --es open_screen business --ez unlink_server true; sleep 5; shot 24_pair_before 1
timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null
EXY=$(python3 - <<'PY'
import re
x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
m=re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m:
    a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2)
PY
)
[ -n "$EXY" ] && adb shell input tap $EXY; sleep 1; adb shell input text "$CODE"; sleep 1
adb shell input keyevent 111 || true; sleep 1
tap_text "اربط"; sleep 12; shot 25_pair_done 1
{
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_pair.xml 2>/dev/null
  grep -q "مؤسسة الحرف" shots/ui_pair.xml && echo "PASS pair: الربط برمز من 6 أرقام نجح وظهرت بيانات الخادم" || echo "FAIL pair code"
} | tee -a shots/shared_brain.txt
# ——— التوزيع بلا إعداد: جهاز جديد يضغط «ابدأ فورًا» فيأخذ مفتاحًا محدودًا من الخادم
adb shell pm clear $PKG >/dev/null; sleep 2
start --es provision_url http://127.0.0.1:8787 --es open_screen onboarding; sleep 6
tap_text "اكتب اسمك"; sleep 1; adb shell input text "Tester"; sleep 1; adb shell input keyevent 111; tap_text "ابدأ"; sleep 4; shot 20_onboarding_instant 1
tap_text "ابدأ فورًا"; sleep 8; shot 21_onboarding_ready 1
curl -s -m 10 -b $J -H "$H" http://127.0.0.1:8787/api/devices > shots/devices.json
python3 -c "import json;d=json.load(open('shots/devices.json'));print('PASS provisioning: جهاز جديد أخذ مفتاحًا محدودًا' if d.get('devices') else 'FAIL provisioning')" | tee -a shots/shared_brain.txt
# ——— التسجيل المفتوح: خادم بلا مفتاح OpenRouter، والذكاء من مفتاح الخادم — المستخدم يضغط «ابدأ فورًا» فقط
(cd server && HARITH_DATA_DIR=/tmp/hsrv2 HARITH_HOME=/tmp/hsrv2 HARITH_PORT=8788 AI_PROVIDER=fake TELEGRAM_BOT_TOKEN= \
  setsid nohup /tmp/hv/bin/python -m harith run </dev/null > ../shots/server_open.log 2>&1 &)
for i in $(seq 1 40); do curl -sf -m 3 http://127.0.0.1:8788/api/health && break; sleep 1; done
timeout 20 adb reverse tcp:8788 tcp:8788
adb shell pm clear $PKG >/dev/null; sleep 2
start --es provision_url http://127.0.0.1:8788 --es open_screen onboarding; sleep 6
tap_text "اكتب اسمك"; sleep 1; adb shell input text "Tester"; sleep 1; adb shell input keyevent 111; tap_text "ابدأ"; sleep 4
tap_text "ابدأ فورًا"; sleep 8; shot 44_open_signup_ready 1
OPEN_USERS=$(python3 -c "import sqlite3;print(sqlite3.connect('/tmp/hsrv2/harith.db').execute(\"SELECT COUNT(*) FROM users WHERE username LIKE 'app_%'\").fetchone()[0])" 2>/dev/null || echo 0)
OPEN_NAME=$(python3 -c "import sqlite3;print(sqlite3.connect('/tmp/hsrv2/harith.db').execute(\"SELECT display_name FROM users WHERE username LIKE 'app_%' ORDER BY id DESC\").fetchone()[0])" 2>/dev/null || echo "")
start --es open_screen chat; sleep 5
timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_open_chat.xml 2>/dev/null
read -r CX CY SX SY <<<"$(python3 - <<'PY'
import re
x=open('shots/ui_open_chat.xml',encoding='utf-8',errors='ignore').read()
nodes=re.findall(r'<node [^>]*>', x)
box=lambda n: [int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()]
ei=[k for k,n in enumerate(nodes) if 'android.widget.EditText' in n]
out=""
if ei:
    a=box(nodes[ei[-1]]); out=f"{(a[0]+a[2])//2} {(a[1]+a[3])//2}"
    btn=[n for n in nodes[ei[-1]+1:] if 'clickable="true"' in n]
    if btn:
        b=box(btn[0]); out+=f" {(b[0]+b[2])//2} {(b[1]+b[3])//2}"
print(out)
PY
)"
if [ -n "$CX" ]; then adb shell input tap $CX $CY; sleep 1; adb shell input text "hello"; sleep 2
  # الكيبورد يرفع خانة الكتابة: نعيد قراءة الشاشة لنجد زر الإرسال في مكانه الجديد
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_open_chat_typed.xml 2>/dev/null
  read -r SX SY <<<"$(python3 - <<'PY'
import re
x=open('shots/ui_open_chat_typed.xml',encoding='utf-8',errors='ignore').read()
nodes=re.findall(r'<node [^>]*>', x)
box=lambda n: [int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()]
ei=[k for k,n in enumerate(nodes) if 'android.widget.EditText' in n]
btn=[n for n in nodes[ei[-1]+1:] if 'clickable="true"' in n] if ei else []
print(f"{(box(btn[0])[0]+box(btn[0])[2])//2} {(box(btn[0])[1]+box(btn[0])[3])//2}" if btn else "")
PY
)"
  [ -n "$SX" ] && adb shell input tap $SX $SY; fi
sleep 12; shot 45_open_signup_chat 1
timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_open_chat2.xml 2>/dev/null
{
  [ "${OPEN_USERS:-0}" -ge 1 ] && echo "PASS open signup: «ابدأ فورًا» أنشأ حسابًا على الخادم بلا أي إدخال" || echo "FAIL open signup (users=$OPEN_USERS)"
  [ "$OPEN_NAME" = "Tester" ] && echo "PASS name: المشترك سُجّل باسمه في لوحة التحكم ($OPEN_NAME)" || echo "FAIL name: الاسم المسجّل في الخادم «$OPEN_NAME» بدل Tester"
  if grep -q "Gemini\|تعذّر" shots/ui_open_chat2.xml; then echo "FAIL open chat: رفيق طلب مفتاحًا أو فشل الرد"; elif grep -q 'text="تم' shots/ui_open_chat2.xml; then echo "PASS open chat: رفيق ردّ بمفتاح الخادم"; else echo "FAIL open chat (no reply)"; fi
} | tee -a shots/shared_brain.txt
# ——— دعوة مجرّب: المدير ينشئ رابطًا ← صفحة التحميل تفتح رفيق (rafiq://join) ← المجرّب ينشئ حسابه ← يدخل الرئيسية
SCODE=$(curl -s -m 10 -b $J -H "$H" -H 'Content-Type: application/json' -d '{"days":7,"max_uses":5,"note":"CI"}' http://127.0.0.1:8787/api/admin/invites | python3 -c 'import sys,json;print(json.load(sys.stdin)["code"])')
adb shell pm clear $PKG >/dev/null; sleep 2
adb shell am start -W -a android.intent.action.VIEW -d "rafiq://join?u=http%3A%2F%2F127.0.0.1%3A8787%2Fjoin%2F$SCODE" $PKG; sleep 8
shot 42_invite_dialog 1
timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_invite.xml 2>/dev/null
read -r UXY PXY <<<"$(python3 - <<'PY'
import re
x=open('shots/ui_invite.xml',encoding='utf-8',errors='ignore').read()
pts=[((int(a)+int(c))//2,(int(b)+int(d))//2) for a,b,c,d in re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)]
print(f"{pts[2][0]},{pts[2][1]} {pts[3][0]},{pts[3][1]}" if len(pts)>=4 else "")
PY
)"
if [ -n "$UXY" ]; then
  # مثل المستخدم: يكتب الاسم، «التالي» في الكيبورد ينقله لكلمة المرور، ثم «تم» ينضم مباشرة
  adb shell input tap ${UXY/,/ }; sleep 1; adb shell input text "tester7"; sleep 1
  adb shell input keyevent 66; sleep 1; adb shell input text "tester-pass-7"; sleep 1
  adb shell input keyevent 66; sleep 1
fi
sleep 12; shot 43_invite_joined 1
curl -s -m 10 -b $J -H "$H" http://127.0.0.1:8787/api/users > shots/users.json
{
  grep -q "دعوة من" shots/ui_invite.xml && grep -q "tester7" shots/users.json \
    && echo "PASS tester invite: رابط الدعوة فتح رفيق معبّأ، والمجرّب أنشأ حسابه الخاص" || echo "FAIL tester invite (preview=$(grep -c 'دعوة من' shots/ui_invite.xml) user=$(grep -c tester7 shots/users.json))"
} | tee -a shots/shared_brain.txt
# ——— الإشعارات على الجوال: تذكير دقيق والتطبيق في الخلفية، ثم والتطبيق مقتول، لمس الإشعار يفتح المهام،
#     تذكير فائت يظهر بعد التشغيل، وإشعار حقيقي من الخادم يصل والتطبيق مغلق (WorkManager)
N() { timeout 20 adb shell dumpsys notification --noredact > "shots/notif_$1.txt" 2>/dev/null; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
{
  start --ei debug_reminder_in_s 25 --es debug_text CIREMONE; adb shell input keyevent 3; sleep 20; N rem1_early; sleep 14; N rem1
  if grep -q CIREMONE shots/notif_rem1_early.txt; then echo "FAIL reminder exact: ظهر قبل موعده"
  elif grep -q CIREMONE shots/notif_rem1.txt; then echo "PASS reminder: ظهر في موعده (±١٠ ثوانٍ) والتطبيق في الخلفية"
  else echo "FAIL reminder: لم يظهر بعد موعده"; fi
  start --ei debug_reminder_in_s 30 --es debug_text CIREMTWO; adb shell input keyevent 3; sleep 3; adb shell am kill $PKG; sleep 2
  P=$(adb shell pidof $PKG | tr -d '\r'); sleep 38; N rem2
  if [ -z "$P" ] && grep -q CIREMTWO shots/notif_rem2.txt; then echo "PASS reminder (app killed): ظهر والتطبيق مقتول"
  elif grep -q CIREMTWO shots/notif_rem2.txt; then echo "PASS reminder (background): ظهر (العملية لم تُقتل: pid=$P)"
  else echo "FAIL reminder (app killed): لم يظهر"; fi
  adb shell cmd statusbar expand-notifications; sleep 3; tap_text CIREMTWO; sleep 6
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > shots/ui_after_tap.xml 2>/dev/null
  grep -q "مفتوحة، و" shots/ui_after_tap.xml && echo "PASS notification tap: فتح شاشة المهام" || echo "FAIL notification tap: لم يفتح المهام"
  shot 50_after_notification_tap 1
  start --ez debug_missed_reminder true --es debug_text CIMISSED; sleep 5; N missed
  grep -q CIMISSED shots/notif_missed.txt && grep -q "تذكير فاتك" shots/notif_missed.txt && echo "PASS missed reminder: ظهر كتذكير فائت بدل أن يضيع" || echo "FAIL missed reminder"
  # إشعار من الخادم بحدث حقيقي (المدير يغيّر صلاحية tester7) ← التطبيق مغلق ← مهمة الخلفية تجلبه
  T7=$(python3 -c "import json;print([u['id'] for u in json.load(open('shots/users.json')) if u['username']=='tester7'][0])" 2>/dev/null)
  adb shell input keyevent 3; sleep 2; adb shell am kill $PKG; sleep 2
  curl -s -m 10 -b $J -H "$H" -H 'Content-Type: application/json' -X PATCH -d '{"role":"admin"}' "http://127.0.0.1:8787/api/users/$T7" >/dev/null
  sleep 8
  timeout 20 adb shell dumpsys jobscheduler > shots/jobs.txt 2>/dev/null
  JOB=$(python3 - <<'PY'
import re
x=open('shots/jobs.txt',encoding='utf-8',errors='ignore').read()
m=re.findall(r'JOB #u\d+a?\d*/(\d+):[^\n]*com\.alharith\.ai/androidx\.work\.impl\.background\.systemjob\.SystemJobService', x)
print(m[0] if m else "")
PY
)
  echo "work job id: $JOB" >> shots/progress.txt
  [ -n "$JOB" ] && timeout 20 adb shell cmd jobscheduler run -f $PKG $JOB
  sleep 20; N server
  if grep -q "تغيّرت صلاحيتك" shots/notif_server.txt; then echo "PASS server notice: إشعار حقيقي من الخادم ظهر على الجوال والتطبيق مغلق"
  else echo "FAIL server notice (job=$JOB)"; fi
  [ -n "$JOB" ] && timeout 20 adb shell cmd jobscheduler run -f $PKG $JOB; sleep 15; N server2
  C1=$(grep -c "تغيّرت صلاحيتك" shots/notif_server.txt); C2=$(grep -c "تغيّرت صلاحيتك" shots/notif_server2.txt)
  [ "$C2" -le "$C1" ] && echo "PASS no duplicate: تشغيل الجلب مرة ثانية لم يكرر الإشعار" || echo "FAIL duplicate server notice ($C1 → $C2)"
} | tee shots/notifications.txt
pkill -f "harith run" || true
pkill -f fake_openrouter || true
# الاستماع داخل التطبيق: الدائرة الذهبية الحية بدل نافذة Google
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -a com.alharith.ai.LISTEN -n $PKG/.ui.MainActivity; shot 17_listening_in_app 4
start --es open_screen home; shot 18_home_luxe 7
start --es open_screen onboarding; shot 19_onboarding 5
echo "== shared brain done $(date)" >> shots/progress.txt

# ——— زر الصوت في النهاية (يعيد تشغيل adb كـ root)
# زر النداء الخارجي: تفعيل الخدمة ثم ضغط مطوّل على رفع الصوت من الشاشة الرئيسية للنظام
# مهم: الإيقاف القسري يعطّل خدمات إمكانية الوصول، لذلك نوقف التطبيق أولًا ثم نفعّل الخدمة
adb shell am force-stop $PKG; adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.service.VolumeButtonService
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell dumpsys accessibility | grep -iE "alharith|Bound services" | head -5 > shots/a11y_state.txt
# محاكاة زر صوت فعلي عبر جهاز الإدخال في النواة (نفس مسار الزر الحقيقي في الجوال)
adb root >/dev/null 2>&1; sleep 4; adb reconnect offline >/dev/null 2>&1 || true; timeout 60 adb wait-for-device
DEV=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/add device/{d=$4} /KEY_VOLUMEUP/{print d; exit}')
echo "volume key device: $DEV" | tee shots/vol_dev.txt
press() { adb shell "sendevent $DEV 1 115 1; sendevent $DEV 0 0 0; sleep $1; sendevent $DEV 1 115 0; sendevent $DEV 0 0 0"; }
# ضغطة قصيرة: يجب أن يرتفع الصوت كالمعتاد
adb shell cmd media_session volume --stream 3 --get > shots/vol_before.txt 2>&1 || true
press 0.15; sleep 2
adb shell cmd media_session volume --stream 3 --get > shots/vol_after.txt 2>&1 || true
# ضغط مطوّل: يجب أن يفتح رفيق ويبدأ الاستماع
press 1.2
sleep 6; shot 11_volume_button 1
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > shots/volume_result.txt
adb shell dumpsys activity services $PKG > shots/service.txt || true
adb logcat -d > shots/logcat_full.txt
grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 shots/logcat_full.txt > shots/crashes.txt || true
grep -E "$PKG|AlHarith|ForegroundService" shots/logcat_full.txt | grep -iE "error|exception|denied" | head -80 > shots/errors.txt || true
echo "CRASH_LINES=$(wc -l < shots/crashes.txt)" | tee shots/summary.txt
echo "ERROR_LINES=$(wc -l < shots/errors.txt)" | tee -a shots/summary.txt
exit 0
