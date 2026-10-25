// عامل الخدمة: يجعل لوحة رفيق قابلة للتثبيت كتطبيق، ويفتح الواجهة بسرعة. لا يخزّن بيانات /api أبدًا.
const CACHE = 'rafiq-shell-v4';
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

// الإشعارات الفورية (Web Push): الخادم يرسل «إيقاظًا» فارغًا بلا أي نص.
// نعرض دائمًا نصًّا عامًّا — لا يظهر أي محتوى إشعار على شاشة القفل، والمحتوى يُقرأ بعد فتح رفيق.
// iOS قد يسحب إذن الدفع إن وصل إيقاظ ولم يظهر إشعار، لذا يُعرض الإشعار دائمًا.
self.addEventListener('push', e => {
  e.waitUntil(self.registration.showNotification('رفيق', {
    body: 'عندك إشعار جديد في رفيق',
    icon: '/static/media/icon-192.png', tag: 'rafiq-notice', renotify: true,
    data: { url: '/' },
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = (e.notification.data && e.notification.data.url) || '/';
  e.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    for (const c of cs) { if (c.url.startsWith(self.location.origin) && 'focus' in c) return c.focus(); }
    return clients.openWindow(url);
  }));
});
