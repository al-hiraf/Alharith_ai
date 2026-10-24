#!/usr/bin/env bash
# اختبار المرشح 3.4.7 على محاكي: ترقية من 3.4.4 العامة، ثم رسالة ورد فعلي، ورفض التزوير والرجوع
set -u
API="$1"; R=results/api$API; mkdir -p $R
PKG=com.alharith.ai
log(){ echo "$*" | tee -a $R/report.txt; }
dump(){ timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml > "$R/$1.xml" 2>/dev/null; }
shot(){ adb exec-out screencap -p > "$R/$1.png"; }
tap_text(){ dump _t; XY=$(python3 - "$1" "$R/_t.xml" <<'PY'
import re,sys
t,f=sys.argv[1],sys.argv[2]; x=open(f,encoding='utf-8',errors='ignore').read()
for n in re.findall(r'<node [^>]*>',x):
    if f'text="{t}"' in n or f'content-desc="{t}"' in n:
        a=[int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()]; print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
); [ -n "$XY" ] && adb shell input tap $XY; }
launch(){ adb shell am force-stop $PKG; sleep 1; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep "$1"; }
install(){ adb install -r "$@" 2>&1 | grep -v "^Performing" | tr "\n" " " | sed "s/  */ /g"; }

chat_test(){  # $1 = اسم
  launch 8; shot "$1_home"; dump "$1_home"
  if grep -q 'text="ابدأ"' "$R/$1_home.xml"; then sleep 4; shot "$1_home2"; dump "$1_home2"
    if grep -q 'text="ابدأ"' "$R/$1_home2.xml"; then log "FAIL $1: التطبيق رجع لشاشة البداية (ضاعت حالة التسجيل)"; else log "   $1: لقطة الواجهة الأولى كانت قديمة، وإعادة الفحص أظهرت الرئيسية"; fi; fi
  tap_text "رفيق AI"; sleep 4; dump "$1_chat0"
  grep -q 'اكتب أمرًا لرفيق' "$R/$1_chat0.xml" || { log "FAIL $1: لم تفتح شاشة الدردشة"; return; }
  read -r CX CY <<<"$(python3 - "$R/$1_chat0.xml" <<'PY'
import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
m=re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[-1][0])+int(m[-1][2]))//2} {(int(m[-1][1])+int(m[-1][3]))//2}" if m else "")
PY
)"
  [ -z "$CX" ] && { log "FAIL $1: لم أجد خانة الكتابة"; return; }
  adb shell input tap $CX $CY; sleep 1; adb shell input text "say%sOK%sin%sone%sword"; sleep 2; dump "$1_typed"
  read -r SX SY <<<"$(python3 - "$R/$1_typed.xml" <<'PY'
import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
nodes=re.findall(r'<node [^>]*>',x); box=lambda n:[int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()]
ei=[k for k,n in enumerate(nodes) if 'android.widget.EditText' in n]
b=[n for n in nodes[ei[-1]+1:] if 'clickable="true"' in n] if ei else []
print(f"{(box(b[0])[0]+box(b[0])[2])//2} {(box(b[0])[1]+box(b[0])[3])//2}" if b else "")
PY
)"
  adb shell input tap $SX $SY
  for i in $(seq 1 12); do sleep 5; dump "$1_chat"; python3 - "$R/$1_chat.xml" > "$R/$1_reply.txt" <<'PY'
import re,sys,html
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
t=[html.unescape(v) for v in re.findall(r'text="([^"]*)"',x)]
skip={'رفيق AI','جاهز','نسخ','إلى مهمة','اكتب أمرًا لرفيق…',''}
i=[k for k,v in enumerate(t) if 'say OK in one word' in v]
# الرد = النصوص التي تأتي بعد رسالتي مباشرة (لا الأزرار المقترحة قبلها)
ans=[v for v in (t[i[-1]+1:] if i else []) if v not in skip and 'say OK' not in v]
sys.stdout.write("\n".join(a for a in ans if a.strip()))
PY
    grep -q '[^[:space:]]' "$R/$1_reply.txt" && break; done
  shot "$1_chat"
  if grep -q "تعذّر\|Gemini\|مفتاح\|خطأ\|مزدحمة\|حاول بعد قليل\|حد استخدامك" "$R/$1_reply.txt"; then log "FAIL $1: رد بخطأ: $(head -c 200 $R/$1_reply.txt)"
  elif grep -q '[^[:space:]]' "$R/$1_reply.txt" && grep -q "say OK in one word" "$R/$1_chat.xml"; then log "PASS $1: أُرسلت الرسالة وجاء رد: $(head -c 120 $R/$1_reply.txt | tr '\n' ' ')"
  else log "FAIL $1: لا رد بعد 60 ثانية"; fi
}

for ED in lite full; do
  adb uninstall $PKG >/dev/null 2>&1
  log "== $ED: تثبيت 3.4.4 العامة: $(install -g old/Rafiq-$ED.apk)"
  launch 8; OK=0
  for k in 1 2 3 4 5; do tap_text "ابدأ"; sleep 6; dump "${ED}_old_ob"; grep -q 'بماذا أناديك' "$R/${ED}_old_ob.xml" || { OK=1; break; }; done
  sleep 6; shot "${ED}_old_onboard"
  [ $OK = 1 ] && log "   3.4.4: اكتمل التسجيل في النسخة القديمة قبل الترقية" || log "FAIL $ED: تعذّر إكمال التسجيل في 3.4.4 قبل الترقية (إعداد الاختبار)"
  log "== $ED: ترقية إلى 3.4.7 فوقها: $(install new/Rafiq-$ED.apk)"
  V=$(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r'); log "   النسخة المثبتة: $V"
  echo "$V" | grep -q "3.4.7" && log "PASS $ED upgrade: الترقية من 3.4.4 نجحت دون حذف" || log "FAIL $ED upgrade"
  chat_test "$ED"
  R1=$(install forged/Rafiq-$ED-oldkey.apk); log "   تحديث مزور بالمفتاح القديم: $R1"
  if echo "$R1" | grep -q "INSTALL_FAILED"; then log "PASS $ED forged: رُفض التحديث بالمفتاح القديم ($(echo "$R1" | grep -o 'INSTALL_FAILED[A-Z_]*' | head -1))"; else log "FAIL $ED forged: $R1"; fi
  R2=$(install old/Rafiq-$ED.apk); log "   رجوع لـ 3.4.4: $R2"
  if echo "$R2" | grep -q "INSTALL_FAILED"; then log "PASS $ED downgrade: رُفض الرجوع لنسخة أقدم ($(echo "$R2" | grep -o 'INSTALL_FAILED[A-Z_]*' | head -1))"; else log "FAIL $ED downgrade: $R2"; fi
done
exit 0
