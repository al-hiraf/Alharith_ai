#!/data/data/com.termux/files/usr/bin/bash
# ═══ تثبيت خادم الحارث على جوال أندرويد عبر Termux — أمر واحد:
#   curl -fsSL https://raw.githubusercontent.com/al-hiraf/Alharith_ai/main/server/install-termux.sh | bash
# يمكن إعادة تشغيله في أي وقت للتحديث؛ لا يمس بياناتك ولا ملف .env
set -euo pipefail

REPO="https://github.com/al-hiraf/Alharith_ai.git"
DIR="$HOME/alharith"
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

say "تسجيل الحارث كخدمة تعمل دائمًا وتُعاد تلقائيًا عند التوقف…"
SVDIR="$PREFIX/var/service/harith"
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
cat > "$HOME/.termux/boot/start-harith" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
. $PREFIX/etc/profile.d/start-services.sh
EOF
chmod +x "$HOME/.termux/boot/start-harith"

# تشغيل الآن
termux-wake-lock || true
. "$PREFIX/etc/profile.d/start-services.sh" 2>/dev/null || true
sleep 2
sv-enable harith 2>/dev/null || true
sv up harith 2>/dev/null || true

say "فحص الإعدادات…"
.venv/bin/python -m harith check || true

PORT=$(grep -E '^HARITH_PORT=' .env | cut -d= -f2 | awk '{print $1}')
PORT=${PORT:-8787}
cat <<EOF

✅ تم. الحارث يعمل الآن على هذا الجوال.

  لوحة التحكم:   http://127.0.0.1:$PORT   (افتحها من متصفح الجوال وأنشئ حساب المدير)
  الحالة:         sv status harith
  إيقاف/تشغيل:    sv down harith  /  sv up harith
  السجلات:        tail -f $SRV/data/logs/current
  التحديث:        أعد تشغيل نفس أمر التثبيت

مهم ليعمل 24/7:
  1) ثبّت تطبيق Termux:Boot من F-Droid وافتحه مرة واحدة.
  2) الإعدادات ← التطبيقات ← Termux و Termux:Boot ← البطارية ← «غير مقيّد».
  3) اترك الجوال على الشاحن ومتصلًا بالإنترنت.
EOF
