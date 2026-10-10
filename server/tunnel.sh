#!/data/data/com.termux/files/usr/bin/sh
# نفق Cloudflare السريع: يعطي رابط HTTPS مجانيًا يتغير عند كل تشغيل،
# فننشر الرابط الجديد على GitHub ليجده تطبيق رفيق تلقائيًا.
cd "$(dirname "$0")"
PORT=$(grep -E '^HARITH_PORT=' .env 2>/dev/null | cut -d= -f2 | awk '{print $1}')
PORT=${PORT:-8787}
cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" 2>&1 | while IFS= read -r line; do
  echo "$line"
  u=$(echo "$line" | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | head -1)
  if [ -n "$u" ]; then
    .venv/bin/python -m harith publish-url "$u"
  fi
done
