#!/usr/bin/env bash
# ═══ تثبيت خادم رفيق على سيرفر Ubuntu (مثل Oracle Cloud المجاني) — أمر واحد:
#   curl -fsSL https://raw.githubusercontent.com/al-hiraf/Alharith_ai/main/server/install-vps.sh | sudo bash
# (للتشغيل بلا أسئلة: RAFIQ_NAME و DUCKDNS_TOKEN و AI_KEY كمتغيرات بيئة)
# يجهّز: رفيق كخدمة دائمة، ورابطًا ثابتًا مع HTTPS مجاني (DuckDNS أو دومينك)، ونسخًا احتياطيًا يوميًا.
# إعادة تشغيله في أي وقت = تحديث، ولا يمس بياناتك ولا ملف .env
set -euo pipefail

REPO="https://github.com/al-hiraf/Alharith_ai.git"
DIR=/opt/rafiq
SRV="$DIR/server"
DATA=/var/lib/rafiq
say() { printf '\n\033[1;33m▶ %s\033[0m\n' "$1"; }
ask() { local v; read -r -p "$1" v </dev/tty || v=""; printf '%s' "$v"; }
asks() { local v; read -r -s -p "$1" v </dev/tty || v=""; echo >&2; printf '%s' "$v" | tr -d '[:space:]'; }
setenv() {  # setenv KEY VALUE — يضيف أو يستبدل سطرًا في .env
  sed -i "/^$1=/d" "$SRV/.env"; printf '%s=%s\n' "$1" "$2" >> "$SRV/.env"
}

[ "$(id -u)" = 0 ] || { echo "شغّل الأمر مع sudo (انسخه كما هو)."; exit 1; }
command -v apt-get >/dev/null || { echo "هذا الأمر لسيرفرات Ubuntu أو Debian."; exit 1; }

say "تثبيت الحزم الأساسية…"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-venv python3-pip git curl cron sqlite3 debian-keyring debian-archive-keyring apt-transport-https gnupg
apt-get install -y ffmpeg || echo "تنبيه: لم يُثبت ffmpeg (الرسائل الصوتية في تيليجرام فقط)."

say "مستخدم خاص بالخدمة (رفيق لا يعمل كمدير للنظام)…"
id rafiq >/dev/null 2>&1 || useradd --system --home "$DATA" --create-home --shell /usr/sbin/nologin rafiq
mkdir -p "$DATA" && chown -R rafiq:rafiq "$DATA" && chmod 750 "$DATA"

say "تنزيل/تحديث الشيفرة…"
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull --ff-only; else git clone --depth 1 "$REPO" "$DIR"; fi

say "بيئة Python والمكتبات…"
cd "$SRV"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install --upgrade pip >/dev/null
.venv/bin/pip install -r requirements.txt

FIRST=0
if [ ! -f .env ]; then
  FIRST=1
  cp .env.example .env
fi
chown rafiq:rafiq .env && chmod 600 .env

# ——— الرابط الثابت
if ! grep -q '^RAFIQ_PUBLIC_URL=https://' .env; then
  say "الرابط الثابت لرفيق"
  echo "اكتب اسمك في DuckDNS (مثل rafiq-mohand) — أو دومينًا كاملًا تملكه ووجّهته لهذا السيرفر (مثل rafiq.example.com):"
  NAME="${RAFIQ_NAME:-$(ask 'الاسم: ')}"
  NAME="$(printf '%s' "$NAME" | tr -d '[:space:]' | tr 'A-Z' 'a-z')"
  [ -n "$NAME" ] || { echo "الاسم مطلوب."; exit 1; }
  if [[ "$NAME" == *.* ]]; then
    DOMAIN="$NAME"
  else
    DOMAIN="$NAME.duckdns.org"
    TOKEN="${DUCKDNS_TOKEN:-$(asks 'رمز DuckDNS (token من duckdns.org — لن يظهر وهذا طبيعي): ')}"
    [ -n "$TOKEN" ] || { echo "رمز DuckDNS مطلوب."; exit 1; }
    echo "*/5 * * * * root curl -fsS 'https://www.duckdns.org/update?domains=$NAME&token=$TOKEN&ip=' >/dev/null 2>&1" > /etc/cron.d/rafiq-duckdns
    chmod 600 /etc/cron.d/rafiq-duckdns
    R="$(curl -fsS "https://www.duckdns.org/update?domains=$NAME&token=$TOKEN&ip=" || true)"
    [ "$R" = "OK" ] && echo "✓ ربطت $DOMAIN بعنوان هذا السيرفر." || echo "تنبيه: DuckDNS رد بـ «$R» — تأكد من الاسم والرمز."
  fi
  setenv RAFIQ_PUBLIC_URL "https://$DOMAIN"
fi
DOMAIN="$(grep '^RAFIQ_PUBLIC_URL=' .env | cut -d= -f2- | sed 's#https://##')"

# ——— مفتاح الذكاء الاصطناعي (يُكتشف نوعه تلقائيًا)
if ! grep -Eq '^(GEMINI|OPENROUTER|OPENAI|ANTHROPIC)_API_KEY=[^[:space:]#]' .env; then
  say "مفتاح الذكاء الاصطناعي (بدونه لا يستطيع رفيق الرد)"
  AK="${AI_KEY:-$(asks 'الصق مفتاح OpenRouter (sk-or-…) أو Gemini (AIza…) — لن يظهر وهذا طبيعي: ')}"
  case "$AK" in
    sk-or-*) setenv AI_PROVIDER openrouter; setenv OPENROUTER_API_KEY "$AK" ;;
    AIza*)   setenv AI_PROVIDER gemini;     setenv GEMINI_API_KEY "$AK" ;;
    sk-ant-*) setenv AI_PROVIDER anthropic; setenv ANTHROPIC_API_KEY "$AK" ;;
    sk-*)    setenv AI_PROVIDER openai;     setenv OPENAI_API_KEY "$AK" ;;
    "")      echo "تخطّيت المفتاح — أضفه لاحقًا ثم أعد تشغيل هذا الأمر." ;;
    *)       echo "لم أتعرف على نوع المفتاح — تخطّيته." ;;
  esac
fi

setenv HARITH_HOST 127.0.0.1
setenv HARITH_DATA_DIR "$DATA"
grep -q '^RAFIQ_SETUP_TOKEN=.\+' .env || setenv RAFIQ_SETUP_TOKEN "$(python3 -c 'import secrets;print(secrets.token_urlsafe(18))')"
SETUP_TOKEN="$(grep '^RAFIQ_SETUP_TOKEN=' .env | cut -d= -f2-)"
chown rafiq:rafiq .env && chmod 600 .env
chown -R rafiq:rafiq "$SRV"

say "تشغيل رفيق كخدمة دائمة (يرجع لحاله بعد أي إعادة تشغيل)…"
cat > /etc/systemd/system/rafiq.service <<EOF
[Unit]
Description=Rafiq server
After=network-online.target
Wants=network-online.target

[Service]
User=rafiq
WorkingDirectory=$SRV
Environment=HARITH_HOME=$SRV
ExecStart=$SRV/.venv/bin/python -m harith run
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=$DATA $SRV

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable rafiq >/dev/null
systemctl restart rafiq

say "HTTPS مجاني (Caddy يجدّد الشهادة لحاله)…"
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y && apt-get install -y caddy
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:8787
}
EOF
systemctl enable caddy >/dev/null
systemctl reload caddy 2>/dev/null || systemctl restart caddy

say "فتح منافذ الويب في جدار الحماية…"
# صور Oracle Cloud تغلق كل المنافذ عدا SSH داخل السيرفر نفسه
if command -v iptables >/dev/null; then
  for p in 80 443; do
    iptables -C INPUT -p tcp --dport $p -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport $p -j ACCEPT
  done
  command -v netfilter-persistent >/dev/null && netfilter-persistent save >/dev/null 2>&1 || true
fi
command -v ufw >/dev/null && ufw status | grep -q active && ufw allow 80,443/tcp >/dev/null || true

say "التحقق…"
ok=0
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8787/api/health >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
[ $ok = 1 ] && echo "✓ رفيق يعمل على السيرفر." || { echo "✗ رفيق لم يبدأ. السجل:"; journalctl -u rafiq -n 30 --no-pager; exit 1; }
if curl -fsS --max-time 20 "https://$DOMAIN/api/health" >/dev/null 2>&1; then
  echo "✓ الرابط الثابت يعمل: https://$DOMAIN"
else
  echo "⚠ الرابط https://$DOMAIN لم يفتح بعد. في Oracle Cloud افتح المنفذين 80 و443:"
  echo "  الشبكة (VCN) ← Security Lists ← Add Ingress Rules ← المصدر 0.0.0.0/0، المنافذ 80 و443"
  echo "  ثم انتظر دقيقة وجرّب الرابط."
fi

NEEDS="$(curl -fsS http://127.0.0.1:8787/api/setup-state 2>/dev/null | grep -o 'true' || true)"
echo
echo "════════════════════════════════════════"
if [ -n "$NEEDS" ]; then
  echo "افتح هذا الرابط لإنشاء حسابك أو استيراد بياناتك من خادمك القديم:"
  echo "  https://$DOMAIN/?setup=$SETUP_TOKEN"
else
  echo "لوحة التحكم:   https://$DOMAIN"
fi
echo "صفحة التحميل:  https://$DOMAIN/download"
echo "التحديث لاحقًا: نفس الأمر مرة ثانية."
echo "════════════════════════════════════════"
