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
adb logcat -c

shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }
start() { adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n $PKG/.ui.MainActivity "$@"; }

start --es open_screen home --ez seed_demo true;  shot 01_home 8
start --es open_screen tasks;    shot 02_tasks 5
start --es open_screen settings; shot 03_settings 5
adb shell input swipe 540 1800 540 500 300; shot 04_settings_ai 3
start --es open_screen memory;   shot 05_memory 4
start --es open_screen chat --es ask "ذكرني بكرة الساعة 9 أتصل بمحمد"; shot 06_chat_command 12
start --es open_screen log;      shot 07_activity_log 4

adb shell dumpsys activity services $PKG > shots/service.txt || true
adb logcat -d > shots/logcat_full.txt
grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 shots/logcat_full.txt > shots/crashes.txt || true
grep -E "$PKG|AlHarith|ForegroundService" shots/logcat_full.txt | grep -iE "error|exception|denied" | head -80 > shots/errors.txt || true
echo "CRASH_LINES=$(wc -l < shots/crashes.txt)" | tee shots/summary.txt
echo "ERROR_LINES=$(wc -l < shots/errors.txt)" | tee -a shots/summary.txt
exit 0
