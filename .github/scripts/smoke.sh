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
# إخفاء نوافذ "لا يستجيب" الخاصة بالمحاكي نفسه (ليست من الحارث) حتى تكون اللقطات واضحة
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
adb logcat -c

shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }
start() { adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n $PKG/.ui.MainActivity "$@"; }

start --es open_screen home --ez seed_demo true;  shot 01_home 8
start --es open_screen tasks;    shot 02_tasks 5
start --es open_screen settings; shot 03_settings 5
adb shell input swipe 540 1900 540 400 400; sleep 1; adb shell input swipe 540 1900 540 900 400; shot 04_settings_ai 3
start --es open_screen memory;   shot 05_memory 4
start --es open_screen chat --es ask "'ذكرني بكرة الساعة 9 أتصل بمحمد'"; shot 06_chat_command 12
# اختبار الطابور: بدون إنترنت يُحفظ الأمر، وعند عودة الاتصال يُنفَّذ تلقائيًا
adb shell svc wifi disable; adb shell svc data disable; sleep 4
start --es open_screen chat --es ask "'رتب لي يومي'"; shot 08_offline_queued 6
adb shell svc wifi enable; adb shell svc data enable; sleep 15; shot 09_back_online 1
# زر النداء الخارجي: تفعيل الخدمة ثم ضغط مطوّل على رفع الصوت من الشاشة الرئيسية للنظام
# مهم: الإيقاف القسري يعطّل خدمات إمكانية الوصول، لذلك نوقف التطبيق أولًا ثم نفعّل الخدمة
adb shell am force-stop $PKG; adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.service.VolumeButtonService
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell dumpsys accessibility | grep -iE "alharith|Bound services" | head -5 > shots/a11y_state.txt
# محاكاة زر صوت فعلي عبر جهاز الإدخال في النواة (نفس مسار الزر الحقيقي في الجوال)
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
DEV=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/add device/{d=$4} /KEY_VOLUMEUP/{print d; exit}')
echo "volume key device: $DEV" | tee shots/vol_dev.txt
press() { adb shell "sendevent $DEV 1 115 1; sendevent $DEV 0 0 0; sleep $1; sendevent $DEV 1 115 0; sendevent $DEV 0 0 0"; }
# ضغطة قصيرة: يجب أن يرتفع الصوت كالمعتاد
adb shell cmd media_session volume --stream 3 --get > shots/vol_before.txt 2>&1 || true
press 0.15; sleep 2
adb shell cmd media_session volume --stream 3 --get > shots/vol_after.txt 2>&1 || true
# ضغط مطوّل: يجب أن يفتح الحارث ويبدأ الاستماع
press 1.2
sleep 6; shot 11_volume_button 1
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > shots/volume_result.txt
start --es open_screen diagnostics; shot 10_diagnostics 10
# الوضع الفاتح
adb shell cmd uimode night no; sleep 2
start --es open_screen home; shot 12_home_light 6
start --es open_screen chat; shot 13_chat_light 4
adb shell cmd uimode night yes; sleep 2
start --es open_screen home; shot 14_home_dark 6
start --es open_screen log;      shot 07_activity_log 4

# ——— العقل المشترك: خادم الحارث الحقيقي على المضيف، والتطبيق يصل له عبر 127.0.0.1 (adb reverse)
echo "== shared brain start $(date)" >> shots/progress.txt
timeout 180 bash -c 'python3 -m venv /tmp/hv && /tmp/hv/bin/pip install -q -r server/requirements.txt' </dev/null
(cd server && HARITH_DATA_DIR=/tmp/hsrv HARITH_HOME=/tmp/hsrv AI_PROVIDER=gemini GEMINI_API_KEY= TELEGRAM_BOT_TOKEN= \
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
pkill -f "harith run" || true
echo "== shared brain done $(date)" >> shots/progress.txt

adb shell dumpsys activity services $PKG > shots/service.txt || true
adb logcat -d > shots/logcat_full.txt
grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 shots/logcat_full.txt > shots/crashes.txt || true
grep -E "$PKG|AlHarith|ForegroundService" shots/logcat_full.txt | grep -iE "error|exception|denied" | head -80 > shots/errors.txt || true
echo "CRASH_LINES=$(wc -l < shots/crashes.txt)" | tee shots/summary.txt
echo "ERROR_LINES=$(wc -l < shots/errors.txt)" | tee -a shots/summary.txt
exit 0
