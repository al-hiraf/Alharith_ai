// عامل الخدمة: يجعل لوحة رفيق قابلة للتثبيت كتطبيق، ويفتح الواجهة بسرعة. لا يخزّن بيانات /api أبدًا.
const CACHE = 'rafiq-shell-v2';
const SHELL = ['/', '/static/style.css', '/static/app.js', '/static/biz.js', '/static/pm.js',
  '/static/fonts/plex_regular.ttf', '/static/media/icon-192.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).catch(() => {})); self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin || u.pathname.startsWith('/api/')) return;
  // الشبكة أولًا (أحدث نسخة دائمًا)، والمخزّن فقط عند انقطاع الإنترنت
  e.respondWith(fetch(e.request, { cache: 'no-cache' }).then(r => {
    if (r.ok && (u.pathname === '/' || u.pathname.startsWith('/static/'))) {
      const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy));
    }
    return r;
  }).catch(() => caches.match(e.request).then(m => m || caches.match('/'))));
});
