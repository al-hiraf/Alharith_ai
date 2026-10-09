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
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.service.VolumeButtonService
adb shell settings put secure accessibility_enabled 1
sleep 4
adb shell am force-stop $PKG; adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell input keyevent --longpress KEYCODE_VOLUME_UP; sleep 6; shot 11_volume_button 1
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > shots/volume_result.txt
start --es open_screen diagnostics; shot 10_diagnostics 10
# الوضع الفاتح
adb shell cmd uimode night no; sleep 2
start --es open_screen home; shot 12_home_light 6
start --es open_screen chat; shot 13_chat_light 4
adb shell cmd uimode night yes; sleep 2
start --es open_screen home; shot 14_home_dark 6
start --es open_screen log;      shot 07_activity_log 4

adb shell dumpsys activity services $PKG > shots/service.txt || true
adb logcat -d > shots/logcat_full.txt
grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 shots/logcat_full.txt > shots/crashes.txt || true
grep -E "$PKG|AlHarith|ForegroundService" shots/logcat_full.txt | grep -iE "error|exception|denied" | head -80 > shots/errors.txt || true
echo "CRASH_LINES=$(wc -l < shots/crashes.txt)" | tee shots/summary.txt
echo "ERROR_LINES=$(wc -l < shots/errors.txt)" | tee -a shots/summary.txt
exit 0
