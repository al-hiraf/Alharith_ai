#!/usr/bin/env bash
set -euo pipefail
PKG=com.alharith.ai
# Clean first run, network disabled. No registration or external messages.
adb shell svc wifi disable
adb shell svc data disable
for flavor in full lite; do
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "signed/Rafiq-$flavor.apk" | tee "shots/install-$flavor.txt"
  adb logcat -c
  adb shell am start -W -n "$PKG/.ui.MainActivity" | tee "shots/start-$flavor.txt"
  sleep 7
  adb exec-out screencap -p > "shots/first-run-$flavor.png"
  adb shell dumpsys package "$PKG" | grep -E 'versionCode=|versionName=' > "shots/version-$flavor.txt"
  adb logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.alharith.ai' > "shots/crash-$flavor.txt" || true
  test ! -s "shots/crash-$flavor.txt"
  grep -q 'versionCode=56' "shots/version-$flavor.txt"
  grep -q 'Status: ok' "shots/start-$flavor.txt"
  echo "PASS signed $flavor install and first run offline" >> shots/signed-summary.txt
 done
