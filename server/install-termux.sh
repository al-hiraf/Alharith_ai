#!/data/data/com.termux/files/usr/bin/bash
# ═══ تثبيت خادم رفيق على جوال أندرويد عبر Termux — أمر واحد:
#   curl -fsSL https://raw.githubusercontent.com/al-hiraf/Alharith_ai/main/server/install-termux.sh | bash
# يمكن إعادة تشغيله في أي وقت للتحديث؛ لا يمس بياناتك ولا ملف .env
set -euo pipefail

REPO="https://github.com/al-hiraf/Alharith_ai.git"
DIR="$HOME/rafiq"
SRV="$DIR/server"
say() { printf '\n\033[1;33m▶ %s\033[0m\n' "$1"; }

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ]; then
  echo "هذا السكربت مخصص لـ Termux على أندرويد. للخوادم العادية استخدم Docker (راجع README)."; exit 1
fi

say "تثبيت الحزم الأساسية (python, git, termux-services)…"
pkg update -y >/dev/null || true
pkg install -y python git termux-services termux-api ffmpeg >/dev/null

say "تنزيل/تحديث الشيفرة…"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only
else
  git clone --depth 1 "$REPO" "$DIR"
fi

say "إنشاء بيئة Python وتثبيت المكتبات…"
cd "$SRV"
[ -d .venv ] || python -m venv .venv
.venv/bin/pip install --upgrade pip >/dev/null
.venv/bin/pip install -r requirements.txt

if [ ! -f .env ]; then
  say "الإعداد الأول"
  cp .env.example .env
  chmod 600 .env
  read -r -p "مفتاح Gemini API (من aistudio.google.com/apikey) — أو اتركه فارغًا: " GK </dev/tty || GK=""
  read -r -p "رمز بوت تيليجرام (من @BotFather) — أو اتركه فارغًا: " TG </dev/tty || TG=""
  [ -n "$GK" ] && sed -i "s|^GEMINI_API_KEY=.*|GEMINI_API_KEY=$GK|" .env
  [ -n "$TG" ] && sed -i "s|^TELEGRAM_BOT_TOKEN=.*|TELEGRAM_BOT_TOKEN=$TG|" .env
  echo "حُفظت الإعدادات في $SRV/.env (يمكنك تعديلها لاحقًا: nano $SRV/.env)"
fi

# ——— توزيع رفيق على مستخدمين آخرين (اختياري): نفق Cloudflare مجاني + نشر الرابط على GitHub
if ! grep -q '^OPENROUTER_PROVISIONING_KEY=.\+' .env 2>/dev/null; then
  read -r -p "هل تريد توزيع رفيق على مستخدمين آخرين بلا إعداد؟ (y/N): " DIST </dev/tty || DIST=""
  if [ "$DIST" = "y" ] || [ "$DIST" = "Y" ]; then
    read -r -p "مفتاح إدارة OpenRouter (Provisioning key): " PK </dev/tty || PK=""
    read -r -p "مفتاح GitHub (Fine-grained، صلاحية Contents: Read and write على مستودع Alharith_ai): " GT </dev/tty || GT=""
    if [ -n "$PK" ]; then
      sed -i '/^OPENROUTER_PROVISIONING_KEY=/d' .env
      echo "OPENROUTER_PROVISIONING_KEY=$PK" >> .env
    fi
    if [ -n "$GT" ]; then
      sed -i '/^GITHUB_TOKEN=/d;/^GITHUB_REPO=/d' .env
      printf 'GITHUB_TOKEN=%s\nGITHUB_REPO=al-hiraf/Alharith_ai\n' "$GT" >> .env
    fi
  fi
fi
TUNNEL=0
if grep -q '^OPENROUTER_PROVISIONING_KEY=.\+' .env 2>/dev/null; then
  say "تشغيل نفق Cloudflare ليصل المستخدمون لخادمك…"
  pkg install -y cloudflared >/dev/null
  TDIR="$PREFIX/var/service/rafiq-tunnel"
  mkdir -p "$TDIR/log"
  {
    echo '#!/data/data/com.termux/files/usr/bin/sh'
    echo "cd \"$SRV\""
    echo "exec sh \"$SRV/tunnel.sh\""
  } > "$TDIR/run"
  {
    echo '#!/data/data/com.termux/files/usr/bin/sh'
    echo "mkdir -p \"$SRV/data/logs/tunnel\""
    echo "exec svlogd -tt \"$SRV/data/logs/tunnel\""
  } > "$TDIR/log/run"
  chmod +x "$TDIR/run" "$TDIR/log/run"
  TUNNEL=1
fi

say "تسجيل رفيق كخدمة تعمل دائمًا وتُعاد تلقائيًا عند التوقف…"
SVDIR="$PREFIX/var/service/rafiq"
mkdir -p "$SVDIR/log"
cat > "$SVDIR/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
cd "$SRV"
exec .venv/bin/python -m harith run 2>&1
EOF
cat > "$SVDIR/log/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
mkdir -p "$SRV/data/logs"
exec svlogd -tt "$SRV/data/logs"
EOF
chmod +x "$SVDIR/run" "$SVDIR/log/run"

say "التشغيل التلقائي بعد إعادة تشغيل الجوال (يتطلب تطبيق Termux:Boot)…"
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/start-rafiq" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
. $PREFIX/etc/profile.d/start-services.sh
EOF
chmod +x "$HOME/.termux/boot/start-rafiq"

# تشغيل الآن
termux-wake-lock || true
. "$PREFIX/etc/profile.d/start-services.sh" 2>/dev/null || true
sleep 2
sv-enable rafiq 2>/dev/null || true
sv up rafiq 2>/dev/null || true
if [ "$TUNNEL" = "1" ]; then sv-enable rafiq-tunnel 2>/dev/null || true; sv up rafiq-tunnel 2>/dev/null || true; fi

# أمر مختصر لربط تطبيق الجوال برمز من 6 أرقام
printf '#!/data/data/com.termux/files/usr/bin/sh\ncd "%s" && exec .venv/bin/python -m harith pair\n' "$SRV" > "$PREFIX/bin/rafiq-pair"
chmod +x "$PREFIX/bin/rafiq-pair"

say "فحص الإعدادات…"
.venv/bin/python -m harith check || true

PORT=$(grep -E '^HARITH_PORT=' .env | cut -d= -f2 | awk '{print $1}')
PORT=${PORT:-8787}
cat <<EOF

✅ تم. رفيق يعمل الآن على هذا الجوال.

  لوحة التحكم:   http://127.0.0.1:$PORT   (افتحها من متصفح الجوال وأنشئ حساب المدير)
  الحالة:         sv status rafiq
  إيقاف/تشغيل:    sv down rafiq  /  sv up rafiq
  السجلات:        tail -f $SRV/data/logs/current
  التحديث:        أعد تشغيل نفس أمر التثبيت

التوزيع على مستخدمين آخرين: $( [ "$TUNNEL" = "1" ] && echo "مفعّل — حالة النفق: sv status rafiq-tunnel" || echo "غير مفعّل (أعد أمر التثبيت واختر y لتفعيله)")

ربط تطبيق رفيق على الجوال: اكتب رمز الربط الظاهر في الأسفل داخل التطبيق ← الأعمال ← «اربط برمز».
  (لرمز جديد في أي وقت اكتب:  rafiq-pair)

مهم ليعمل 24/7:
  1) ثبّت تطبيق Termux:Boot من F-Droid وافتحه مرة واحدة.
  2) الإعدادات ← التطبيقات ← Termux و Termux:Boot ← البطارية ← «غير مقيّد».
  3) اترك الجوال على الشاحن ومتصلًا بالإنترنت.
EOF
sleep 2
.venv/bin/python -m harith pair || true
