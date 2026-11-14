#!/usr/bin/env bash
set -euo pipefail
mkdir -p shots
PKG=com.alharith.ai
adb install -r -g app/build/outputs/apk/full/debug/app-full-debug.apk
adb shell settings put global hide_error_dialogs 1
for kind in salawat istighfar; do
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.ui.MainActivity --es open_screen home --ez seed_demo true --es debug_dhikr dhikr_$kind
  sleep 4
  adb shell dumpsys notification --noredact > shots/$kind-first.txt
  adb shell am start -W -n $PKG/.ui.MainActivity --es debug_dhikr dhikr_$kind
  sleep 5
  adb shell dumpsys notification --noredact > shots/$kind-duplicate.txt
  adb shell dumpsys activity services $PKG > shots/$kind-services.txt
  adb shell cmd statusbar expand-notifications
  sleep 2
  adb exec-out screencap -p > shots/$kind.png
  adb shell cmd statusbar collapse
 done
python3 - <<'PY' | tee shots/once-summary.txt
import re,pathlib
for kind,ident in [('salawat',900701),('istighfar',900702)]:
    s=pathlib.Path(f'shots/{kind}-duplicate.txt').read_text()
    # Inspect the active notification record, not old historical log entries.
    matches=re.findall(r'NotificationRecord\([^\n]*pkg=com.alharith.ai[^\n]*id='+str(ident)+r'\b[^\n]*',s)
    assert len(matches)==1,(kind,'one active notification',matches)
    flags=re.search(r'flags=0x([0-9a-fA-F]+)',matches[0])
    assert flags,(kind,'flags absent',matches[0])
    n=int(flags[1],16)
    assert n & 0x8,(kind,'FLAG_ONLY_ALERT_ONCE missing')
    assert not n & 0x4,(kind,'FLAG_INSISTENT must not loop')
    assert f'rafiq_dhikr_{kind}_v1' in matches[0],matches[0]
    assert not re.search(r'ServiceRecord.*AlarmRingService',pathlib.Path(f'shots/{kind}-services.txt').read_text())
    print('PASS',kind,': one active notification after duplicate, ONLY_ALERT_ONCE set, no INSISTENT loop, no alarm service')
PY
