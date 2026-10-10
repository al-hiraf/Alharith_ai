/* لوحة تحكم رفيق — JavaScript بلا مكتبات. كل نص من المستخدم يُعرض عبر textContent (لا innerHTML). */
'use strict';

// ——— الترجمة
const I18N = {
  ar: {
    home: 'الرئيسية', chat: 'المحادثة', tasks: 'المهام', projects: 'المشاريع', business: 'الشركات', finance: 'المالية',
    invoices: 'الفواتير والعروض', contacts: 'العملاء والموردون', meetings: 'الاجتماعات', memory: 'الذاكرة', files: 'الملفات',
    scheduled: 'المجدولة', log: 'سجل العمليات', alerts: 'الأخطاء والتنبيهات', usage: 'الاستخدام والتكلفة',
    integrations: 'التكاملات', settings: 'الإعدادات', users: 'المستخدمون', devices: 'مستخدمو التطبيق', more: 'المزيد', logout: 'خروج',
    morning: 'صباح الخير', evening: 'مساء الخير', ask: 'اطلب من رفيق أي شيء…', send: 'إرسال',
    overdue: 'متأخرة', today: 'اليوم', open: 'مفتوحة', done: 'مكتملة', week: 'هذا الأسبوع', all: 'الكل',
    approvals: 'بانتظار موافقتك', approve: 'موافقة', deny: 'رفض', reminders: 'التذكيرات', habits: 'العادات',
    noTasks: 'لا توجد مهام هنا.', add: 'إضافة', save: 'حفظ', cancel: 'إلغاء', delete: 'حذف', edit: 'تعديل',
    title: 'العنوان', due: 'الموعد', priority: 'الأولوية', high: 'عالية', normal: 'عادية', low: 'منخفضة',
    killOn: 'التنفيذ موقوف — لن يُنفّذ أي إجراء حتى الاستئناف.', killOff: 'المساعد يعمل. زر الإيقاف الطارئ يوقف كل التنفيذ فورًا.',
    stopAll: 'إيقاف طارئ', resume: 'استئناف', login: 'تسجيل الدخول', username: 'اسم المستخدم', password: 'كلمة المرور',
    setupTitle: 'إعداد رفيق لأول مرة', setupHint: 'أنشئ حساب المدير. كلمة المرور 8 أحرف على الأقل.', create: 'إنشاء',
    search: 'بحث', status: 'الحالة', when: 'الوقت', text: 'النص', recur: 'التكرار', none: 'بلا', daily: 'يوميًا',
    weekdays: 'أيام العمل', weekly: 'أسبوعيًا', monthly: 'شهريًا', thinking: 'رفيق يعمل على طلبك…',
    language: 'English', theme: 'المظهر',
  },
  en: {
    home: 'Home', chat: 'Chat', tasks: 'Tasks', projects: 'Projects', business: 'Companies', finance: 'Finance',
    invoices: 'Invoices & quotes', contacts: 'Clients & suppliers', meetings: 'Meetings', memory: 'Memory', files: 'Files',
    scheduled: 'Scheduled', log: 'Activity log', alerts: 'Errors & alerts', usage: 'Usage & cost',
    integrations: 'Integrations', settings: 'Settings', users: 'Users', devices: 'App users', more: 'More', logout: 'Sign out',
    morning: 'Good morning', evening: 'Good evening', ask: 'Ask Rafiq anything…', send: 'Send',
    overdue: 'Overdue', today: 'Today', open: 'Open', done: 'Done', week: 'This week', all: 'All',
    approvals: 'Awaiting your approval', approve: 'Approve', deny: 'Deny', reminders: 'Reminders', habits: 'Habits',
    noTasks: 'Nothing here.', add: 'Add', save: 'Save', cancel: 'Cancel', delete: 'Delete', edit: 'Edit',
    title: 'Title', due: 'Due', priority: 'Priority', high: 'High', normal: 'Normal', low: 'Low',
    killOn: 'Execution is paused — nothing runs until you resume.', killOff: 'Assistant is running. Emergency stop halts all execution.',
    stopAll: 'Emergency stop', resume: 'Resume', login: 'Sign in', username: 'Username', password: 'Password',
    setupTitle: 'First-time setup', setupHint: 'Create the admin account. Password: 8+ characters.', create: 'Create',
    search: 'Search', status: 'Status', when: 'When', text: 'Text', recur: 'Repeat', none: 'None', daily: 'Daily',
    weekdays: 'Weekdays', weekly: 'Weekly', monthly: 'Monthly', thinking: 'Working on it…',
    language: 'العربية', theme: 'Theme',
  },
};
let LANG = 'ar';
try { LANG = localStorage.getItem('harith_lang') || 'ar'; } catch (e) {}
const t = (k) => (I18N[LANG] && I18N[LANG][k]) || I18N.ar[k] || k;
function applyLang() {
  document.documentElement.lang = LANG;
  document.documentElement.dir = LANG === 'ar' ? 'rtl' : 'ltr';
}
applyLang();
try { const th = localStorage.getItem('harith_theme'); if (th) document.documentElement.dataset.theme = th; } catch (e) {}

// ——— أدوات DOM
function el(tag, attrs, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (k === 'html') n.innerHTML = v; // للأيقونات الثابتة فقط
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return n;
}
const ICONS = {
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  chat: 'M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z',
  tasks: 'M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11 2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z',
  projects: 'M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z',
  memory: 'M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7a2 2 0 0 0-2-2h-2V3h-2v2h-2V3H9v2H7a2 2 0 0 0-2 2v2H3v2h2v2H3v2h2v2a2 2 0 0 0 2 2h2v2h2v-2h2v2h2v-2h2a2 2 0 0 0 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z',
  files: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z',
  scheduled: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.2 14.2L11 13V7h1.5v5.2l4.5 2.7-.8 1.3z',
  log: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 2.05 4.95l-1.42 1.42A9 9 0 1 0 13 3zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
  alerts: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  usage: 'M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z',
  integrations: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  settings: 'M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58a.5.5 0 0 0 .12-.61l-1.92-3.32a.5.5 0 0 0-.59-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54a.5.5 0 0 0-.5-.42h-3.84a.5.5 0 0 0-.49.42l-.36 2.54a7.3 7.3 0 0 0-1.62.94l-2.39-.96a.5.5 0 0 0-.59.22L2.7 8.87a.5.5 0 0 0 .12.61l2.03 1.58a7 7 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.04.7 1.62.94l.36 2.54c.05.24.25.42.49.42h3.84c.24 0 .44-.18.49-.42l.36-2.54a7 7 0 0 0 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.5.5 0 0 0-.12-.61l-2.01-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z',
  users: 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  more: 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  trash: 'M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  pen: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  down: 'M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z',
  business: 'M12 7V3H2v18h20V7H12zM6 19H4v-2h2v2zm0-4H4v-2h2v2zm0-4H4V9h2v2zm0-4H4V5h2v2zm4 12H8v-2h2v2zm0-4H8v-2h2v2zm0-4H8V9h2v2zm0-4H8V5h2v2zm10 12h-8v-2h2v-2h-2v-2h2v-2h-2V9h8v10zm-2-8h-2v2h2v-2zm0 4h-2v2h2v-2z',
  finance: 'M21 18v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v1h-9a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h9zm-9-2h10V8H12v8zm4-2.5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z',
  invoices: 'M18 17H6v-2h12v2zm0-4H6v-2h12v2zm0-4H6V7h12v2zM3 22l1.5-1.5L6 22l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5L21 22V2l-1.5 1.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2 4.5 3.5 3 2v20z',
  contacts: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  meetings: 'M19 3h-1V1h-2v2H8V1H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H5V8h14v11zM7 10h5v5H7z',
  search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
};
const icon = (name) => el('span', { 'aria-hidden': 'true', html: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="${ICONS[name]}"/></svg>` });

function toast(msg) {
  const n = document.getElementById('toast');
  n.textContent = msg; n.classList.add('show');
  clearTimeout(toast._t); toast._t = setTimeout(() => n.classList.remove('show'), 2800);
}
const arNum = (v) => LANG === 'ar' ? String(v).replace(/\d/g, (d) => '٠١٢٣٤٥٦٧٨٩'[d]) : String(v);

async function api(method, path, body, raw) {
  const opts = { method, credentials: 'same-origin', headers: { 'X-Harith': '1' } };
  if (body !== undefined && !raw) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  if (raw) opts.body = body;
  const r = await fetch(path, opts);
  if (r.status === 401 && !path.startsWith('/api/login')) { state.me = null; render(); throw new Error('401'); }
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : await r.text();
  if (!r.ok) { const m = (data && data.error) || `HTTP ${r.status}`; toast(m); throw new Error(m); }
  return data;
}

function modal(title, content, actions) {
  const back = el('div', { class: 'modal-back', onclick: (e) => { if (e.target === back) back.remove(); } });
  const close = () => back.remove();
  back.append(el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' },
    el('h2', {}, title), content, el('div', { class: 'form-row' }, ...(actions ? actions(close) : []),
      el('button', { class: 'btn ghost', onclick: close }, t('cancel')))));
  document.body.append(back);
  const f = back.querySelector('input,textarea,select'); if (f) f.focus();
  return close;
}

// ——— الحالة والتوجيه
const state = { me: null, page: 'home', navOpen: false };
const NAV = [
  ['home', 'home'], ['chat', 'chat'], ['tasks', 'tasks'], ['business', 'business'], ['finance', 'finance'],
  ['invoices', 'invoices'], ['contacts', 'contacts'], ['meetings', 'meetings'], ['projects', 'projects'],
  ['memory', 'memory'], ['files', 'files'], ['scheduled', 'scheduled'], ['log', 'log'], ['usage', 'usage'],
  ['integrations', 'integrations'], ['settings', 'settings'],
];
const NAV_SPLIT = 9;
const ADMIN_NAV = [['devices', 'users'], ['alerts', 'alerts'], ['users', 'users']];
const BOTTOM = ['home', 'chat', 'tasks', 'finance', 'more'];

window.addEventListener('hashchange', () => { state.page = location.hash.slice(1) || 'home'; state.navOpen = false; render(); });

async function boot() {
  state.page = location.hash.slice(1) || 'home';
  try { state.me = (await api('GET', '/api/me')).user; } catch (e) { state.me = null; }
  render();
  pollNotifications();
}

function go(page) { if (location.hash.slice(1) === page) render(); else location.hash = page; }

async function render() {
  const root = document.getElementById('app');
  root.replaceChildren();
  if (!state.me) { root.append(await loginView()); return; }
  const isAdmin = state.me.role === 'admin';
  const navItems = NAV.concat(isAdmin ? ADMIN_NAV : []);
  const link = ([id, ic]) => el('button', { class: 'nav-link' + (state.page === id ? ' active' : ''), onclick: () => go(id) }, icon(ic), t(id));
  const side = el('nav', { class: 'side' + (state.navOpen ? ' open' : ''), 'aria-label': 'main' },
    el('div', { class: 'brand' }, el('img', { class: 'brand-mark', src: '/static/logo-mark.svg', alt: 'رفيق', width: 46, height: 46, style: 'width:46px;height:46px' }), el('div', {}, el('b', { class: 'wordmark' }, LANG === 'ar' ? 'رفيق' : 'Rafiq'), el('div', { class: 'small muted brand-sub' }, LANG === 'ar' ? 'مساعدك التنفيذي' : 'Executive assistant')),
      state.navOpen ? el('button', { class: 'icon-btn', style: 'margin-inline-start:auto', onclick: () => { state.navOpen = false; render(); } }, icon('close')) : null),
    el('button', { class: 'nav-link search-link', onclick: () => openSearch() }, icon('search'), LANG === 'ar' ? 'بحث شامل…' : 'Search everything…'),
    ...navItems.slice(0, NAV_SPLIT).map(link), el('div', { class: 'nav-sep' }), ...navItems.slice(NAV_SPLIT).map(link),
    el('div', { class: 'side-foot' },
      el('div', { class: 'small muted' }, state.me.display_name || state.me.username),
      el('div', { class: 'form-row' },
        el('button', { class: 'btn sm', onclick: () => { LANG = LANG === 'ar' ? 'en' : 'ar'; try { localStorage.setItem('harith_lang', LANG); } catch (e) {} applyLang(); render(); } }, t('language')),
        el('button', { class: 'btn sm', onclick: toggleTheme }, t('theme')),
        el('button', { class: 'btn sm ghost', onclick: async () => { await api('POST', '/api/logout', {}); state.me = null; render(); } }, t('logout')))));
  const main = el('main', { class: 'main' });
  const top = el('header', { class: 'topbar' },
    el('div', { class: 'brand', style: 'padding:0' }, el('img', { class: 'brand-mark', src: '/static/logo-mark.svg', alt: 'رفيق', width: 36, height: 36, style: 'width:36px;height:36px' }), el('b', {}, t(state.page))),
    el('div', { style: 'display:flex;gap:4px' },
      el('button', { class: 'icon-btn', 'aria-label': t('search'), onclick: () => openSearch() }, icon('search')),
      el('button', { class: 'icon-btn', 'aria-label': t('more'), onclick: () => { state.navOpen = true; render(); } }, icon('more'))));
  const bottom = el('nav', { class: 'bottom-nav' }, ...BOTTOM.map((id) => el('button', {
    class: state.page === id ? 'active' : '', onclick: () => { if (id === 'more') { state.navOpen = true; render(); } else go(id); },
  }, icon(id === 'more' ? 'more' : id), t(id))));
  root.append(el('div', { class: 'shell' }, side, el('div', {}, top, main)), bottom);
  const view = VIEWS[state.page] || VIEWS.home;
  main.append(el('p', { class: 'muted' }, '…'));
  try { main.replaceChildren(await view()); } catch (e) { if (e.message !== '401') main.replaceChildren(el('p', { class: 'muted' }, e.message)); }
}

function toggleTheme() {
  const cur = document.documentElement.dataset.theme;
  const next = cur === 'light' ? 'dark' : cur === 'dark' ? '' : (matchMedia('(prefers-color-scheme: light)').matches ? 'dark' : 'light');
  if (next) document.documentElement.dataset.theme = next; else delete document.documentElement.dataset.theme;
  try { next ? localStorage.setItem('harith_theme', next) : localStorage.removeItem('harith_theme'); } catch (e) {}
}

let lastNotice = 0;
async function pollNotifications() {
  setInterval(async () => {
    if (!state.me) return;
    try {
      const rows = await api('GET', `/api/notifications?after=${lastNotice}`);
      if (lastNotice && rows.length) toast(rows[rows.length - 1].content.slice(0, 120));
      if (rows.length) lastNotice = rows[rows.length - 1].id;
      if (!lastNotice) lastNotice = -1;
    } catch (e) {}
  }, 20000);
}

// ——— الدخول والإعداد الأول
async function loginView() {
  const st = await (await fetch('/api/setup-state')).json();
  const u = el('input', { autocomplete: 'username', value: st.needs_setup ? 'admin' : '', dir: 'ltr' });
  const p = el('input', { type: 'password', autocomplete: st.needs_setup ? 'new-password' : 'current-password', dir: 'ltr' });
  const n = el('input', { placeholder: LANG === 'ar' ? 'اسمك (يظهر في التحية)' : 'Your name' });
  const submit = async (e) => {
    e.preventDefault();
    try {
      if (st.needs_setup) await api('POST', '/api/setup', { username: u.value, password: p.value, display_name: n.value });
      else await api('POST', '/api/login', { username: u.value, password: p.value });
      state.me = (await api('GET', '/api/me')).user; render();
    } catch (err) {}
  };
  return el('div', { class: 'login' }, el('div', { class: 'login-art', role: 'img', 'aria-label': 'زخرفة' }),
    el('form', { class: 'login-form', onsubmit: submit },
      el('img', { src: '/static/logo.svg', alt: 'رفيق — إدارة مهام ومساعد شخصي بالذكاء الاصطناعي', class: 'login-logo' }),
      el('h1', {}, st.needs_setup ? t('setupTitle') : t('login')),
      st.needs_setup ? el('p', { class: 'muted' }, t('setupHint')) : null,
      st.needs_setup ? el('label', { class: 'field' }, LANG === 'ar' ? 'الاسم' : 'Name', n) : null,
      el('label', { class: 'field' }, t('username'), u), el('label', { class: 'field' }, t('password'), p),
      el('button', { class: 'btn primary', type: 'submit' }, st.needs_setup ? t('create') : t('login'))));
}

// ——— عناصر مشتركة
const PRIO = { high: 'high', normal: 'normal', low: 'low' };
function taskRow(tk, onChange) {
  const isDone = tk.status === 'done';
  return el('div', { class: 'row' },
    el('button', { class: `check ${PRIO[tk.priority] || ''} ${isDone ? 'done' : ''}`, title: t('done'), 'aria-label': t('done'),
      onclick: async () => { await api('PATCH', `/api/tasks/${tk.id}`, { status: isDone ? 'open' : 'done' }); onChange(); } }),
    el('div', { class: 'grow' }, el('div', { class: 'title' + (isDone ? ' done-text' : '') }, tk.title),
      el('div', { class: 'meta' }, [tk.due, tk.priority === 'high' ? t('high') : ''].filter(Boolean).join(' · '))),
    tk.due && !isDone && isPast(tk) ? el('span', { class: 'chip bad' }, t('overdue')) : null,
    el('button', { class: 'icon-btn', title: t('edit'), onclick: () => editTask(tk, onChange) }, icon('pen')));
}
function isPast(tk) { return tk.overdue || tk._overdue; }

function editTask(tk, onChange) {
  const title = el('input', { value: tk.title });
  const due = el('input', { type: 'datetime-local', value: tk.due ? tk.due.split(' ').slice(-2).join('T') : '' });
  const pr = el('select', {}, ...['high', 'normal', 'low'].map((p) => el('option', { value: p, selected: p === tk.priority }, t(p))));
  const notes = el('textarea', {}, tk.notes || '');
  modal(t('edit'), el('div', { class: 'grid' }, el('label', { class: 'field' }, t('title'), title),
    el('label', { class: 'field' }, t('due'), due), el('label', { class: 'field' }, t('priority'), pr), notes), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { await api('PATCH', `/api/tasks/${tk.id}`, { title: title.value, due: due.value, priority: pr.value, notes: notes.value }); close(); onChange(); } }, t('save')),
    el('button', { class: 'btn danger', onclick: async () => { if (confirm('حذف المهمة؟')) { await api('DELETE', `/api/tasks/${tk.id}`); close(); onChange(); } } }, t('delete')),
  ]);
}

function statusChip(s) {
  const map = { success: 'ok', executed: 'ok', done: 'ok', ok: 'ok', sent: 'ok', approved: 'ok', failed: 'bad', error: 'bad', denied: 'bad',
    blocked: 'bad', needs_approval: 'warn', pending: 'warn', queued: 'warn', running: 'warn', partial: 'warn', setup: 'warn' };
  const ar = { success: 'نجح', failed: 'فشل', blocked: 'محظور', needs_approval: 'ينتظر موافقة', pending: 'قيد الانتظار',
    running: 'قيد التنفيذ', queued: 'مؤجل', partial: 'جزئي', cancelled: 'أُلغي', executed: 'نُفّذ', denied: 'مرفوض',
    approved: 'موافق', expired: 'انتهى', done: 'مكتمل', sent: 'أُرسل', ok: 'يعمل', setup: 'يحتاج إعداد', error: 'خطأ', app: 'في التطبيق', planned: 'لاحقًا' };
  return el('span', { class: 'chip ' + (map[s] || '') }, LANG === 'ar' ? (ar[s] || s) : s);
}

function chatBox(onSent, placeholder) {
  const input = el('input', { placeholder: placeholder || t('ask'), 'aria-label': t('ask') });
  const btn = el('button', { class: 'btn primary', type: 'submit' }, t('send'));
  const form = el('form', { class: 'ask', onsubmit: async (e) => {
    e.preventDefault(); const text = input.value.trim(); if (!text) return;
    btn.disabled = true; input.value = ''; toast(t('thinking'));
    try { const r = await api('POST', '/api/chat', { text }); onSent && onSent(r); } finally { btn.disabled = false; }
  } }, input, btn);
  return form;
}

// ——— الصفحات
const VIEWS = {
  async home() {
    const [o, appr] = await Promise.all([api('GET', '/api/overview'), api('GET', '/api/approvals')]);
    const d = o.day, h = new Date().getHours();
    const name = state.me.display_name || state.me.username;
    const overdue = d.overdue.map((x) => ({ ...x, _overdue: true }));
    const sum = el('p', { class: 'sum' });
    const part = (n, label) => [el('b', {}, n === 0 ? (LANG === 'ar' ? 'لا' : 'no') : arNum(n)), ' ', label];
    sum.append(...(LANG === 'ar'
      ? [t('today'), ' ', ...part(d.due_today.length, 'مهام'), '، و', ...part(d.overdue.length, 'متأخرة'), '، و', ...part(d.reminders_today.length, 'تذكيرات'), '.']
      : [...part(d.due_today.length, 'due today'), ', ', ...part(d.overdue.length, 'overdue'), ', ', ...part(d.reminders_today.length, 'reminders'), '.']));
    const result = el('div', { class: 'card', style: 'display:none;margin-top:12px;white-space:pre-wrap' });
    const box = chatBox((r) => { orb.classList.remove('busy'); result.style.display = 'block'; result.textContent = r.text; refreshLater(); });
    box.addEventListener('submit', () => orb.classList.add('busy'));
    const orb = el('button', { class: 'orb', 'aria-label': t('ask'), onclick: () => box.querySelector('input').focus() },
      el('span', { class: 'aura' }), el('span', { class: 'ring r2' }), el('span', { class: 'ring' }),
      el('span', { class: 'core', html: '<svg viewBox="0 0 24 24"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/></svg>' }));
    const hero = el('section', { class: 'hero' }, el('div', { class: 'text-col' },
      el('div', { class: 'date' }, new Date().toLocaleDateString(LANG === 'ar' ? 'ar-SA-u-ca-gregory' : 'en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) +
        '  ·  ' + new Date().toLocaleDateString('ar-SA-u-ca-islamic-umalqura', { day: 'numeric', month: 'long', year: 'numeric' })),
      el('h1', {}, `${h >= 4 && h < 12 ? t('morning') : t('evening')}، `, el('span', { class: 'name' }, name)), sum, box));
    hero.append(orb);
    const paused = o.health.paused || state.me && (await api('GET', '/api/me')).paused;
    const kill = el('div', { class: 'card kill' + (paused ? ' on' : '') },
      el('div', {}, el('h3', {}, paused ? '⏸️ ' + t('killOn') : t('killOff'))),
      el('button', { class: 'btn ' + (paused ? 'primary' : 'danger solid'), onclick: async () => {
        await api('POST', '/api/pause', { paused: !paused }); toast(paused ? t('resume') : t('stopAll')); render();
      } }, paused ? t('resume') : t('stopAll')));
    const stats = el('div', { class: 'grid grid-4' },
      stat(o.counts.open, t('open') + ' — ' + t('tasks')), stat(d.overdue.length, t('overdue'), d.overdue.length > 0),
      stat(o.counts.approvals, t('approvals'), o.counts.approvals > 0), stat('$' + o.cost_today, LANG === 'ar' ? 'تكلفة اليوم' : 'Cost today'));
    const pend = appr.filter((a) => a.status === 'pending');
    const tasksList = el('div', { class: 'list' }, ...[...overdue, ...d.due_today, ...d.high_priority_undated].slice(0, 10).map((x) => taskRow(x, render)));
    if (!tasksList.children.length) tasksList.append(el('div', { class: 'empty' }, t('noTasks')));
    const remList = el('div', { class: 'list' }, ...d.reminders_today.map((r) => el('div', { class: 'row' }, el('span', { class: 'gold num' }, r.when.slice(-5)), el('div', { class: 'grow' }, r.text))));
    if (!remList.children.length) remList.append(el('div', { class: 'empty' }, LANG === 'ar' ? 'لا تذكيرات اليوم.' : 'No reminders today.'));
    const habits = el('div', { class: 'list' }, ...d.habits.map((hb) => el('div', { class: 'row' },
      el('button', { class: 'check ' + (hb.done_today ? 'done' : 'normal'), onclick: async () => { await api('POST', `/api/habits/${hb.id}/check`); render(); } }),
      el('div', { class: 'grow' }, hb.name, el('div', { class: 'meta' }, `${arNum(hb.this_week)}/${arNum(hb.target_per_week)} · 🔥 ${arNum(hb.streak)}`)))));
    habits.append(el('div', { class: 'row' }, el('button', { class: 'btn sm', onclick: () => {
      const n = el('input'); modal(LANG === 'ar' ? 'عادة جديدة' : 'New habit', n, (c) => [el('button', { class: 'btn primary', onclick: async () => { await api('POST', '/api/habits', { name: n.value }); c(); render(); } }, t('add'))]);
    } }, '+ ' + t('add'))));
    const healthChips = el('div', { style: 'display:flex;gap:6px;flex-wrap:wrap' },
      el('span', { class: 'chip ' + (o.health.ai.configured ? 'ok' : 'bad') }, `AI: ${o.health.ai.provider}`),
      ...Object.entries(o.health.channels).map(([k, v]) => el('span', { class: 'chip ' + (v === 'متصل' ? 'ok' : 'bad') }, `${k}: ${v}`)),
      el('span', { class: 'chip ' + (o.health.jobs.failed ? 'warn' : '') }, `${LANG === 'ar' ? 'مجدولة' : 'jobs'}: ${o.health.jobs.pending}`));
    let ri = 0; const rv = (n) => { if (n) { n.classList.add('reveal'); n.style.setProperty('--i', ri++); } return n; };
    const bizHome = await api('GET', '/api/biz/home').catch(() => null);
    const hidden = new Set((bizHome && bizHome.widgets_hidden) || []);
    const W = (id, node) => (hidden.has(id) ? null : node);
    const bizW = bizHome ? homeBusinessWidgets(bizHome) : {};
    return el('div', {}, rv(hero), result,
      rv(el('div', { class: 'form-row', style: 'justify-content:flex-end;margin-top:-6px' },
        el('button', { class: 'btn sm ghost', onclick: () => customizeHome(hidden) }, LANG === 'ar' ? '⚙︎ تخصيص اللوحة' : 'Customize'))),
      pend.length ? el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, t('approvals'))), el('div', { class: 'grid' }, ...pend.map(approvalCard))) : null,
      W('alerts', rv(bizW.alerts)),
      W('stats', rv(el('section', { class: 'section' }, stats))),
      W('tasks', rv(el('section', { class: 'section grid grid-2' },
        el('div', {}, el('div', { class: 'section-head' }, el('h2', {}, t('tasks')), el('a', { href: '#tasks' }, LANG === 'ar' ? 'عرض الكل' : 'View all')), tasksList),
        el('div', {}, el('div', { class: 'section-head' }, el('h2', {}, t('reminders'))), remList,
          el('div', { class: 'section-head', style: 'margin-top:20px' }, el('h2', {}, t('habits'))), habits)))),
      W('finance', rv(bizW.finance)), W('meetings', rv(bizW.meetings)),
      W('kill', rv(el('section', { class: 'section' }, kill))),
      W('health', rv(el('section', { class: 'section' }, healthChips))));
  },

  async chat() {
    const msgs = await api('GET', '/api/messages');
    const list = el('div', { class: 'chat' });
    const add = (m) => list.append(el('div', { class: 'msg ' + m.role }, m.content, el('span', { class: 'meta' }, (m.created_local || '') + (m.channel && m.role !== 'notice' ? ' · ' + m.channel : ''))));
    msgs.forEach(add);
    if (!msgs.length) list.append(el('div', { class: 'empty' }, LANG === 'ar' ? 'ابدأ المحادثة. جرّب: «ذكرني غدًا الساعة التاسعة بالاتصال بالعميل».' : 'Start chatting.'));
    const input = el('textarea', { rows: 1, placeholder: t('ask'), style: 'min-height:44px', onkeydown: (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); } } });
    const typing = el('div', { class: 'typing', style: 'display:none' }, t('thinking'));
    const btn = el('button', { class: 'btn primary', type: 'submit' }, t('send'));
    const stop = el('button', { class: 'btn danger', type: 'button', style: 'display:none', onclick: () => api('POST', '/api/runs/cancel', {}) }, LANG === 'ar' ? 'إيقاف' : 'Stop');
    const form = el('form', { class: 'composer', onsubmit: async (e) => {
      e.preventDefault(); const text = input.value.trim(); if (!text) return;
      add({ role: 'user', content: text, created_local: '' }); input.value = ''; typing.style.display = 'block'; btn.disabled = true; stop.style.display = '';
      window.scrollTo(0, document.body.scrollHeight);
      try { const r = await api('POST', '/api/chat', { text }); add({ role: 'assistant', content: r.text, created_local: r.status !== 'success' ? r.status : '' }); }
      finally { typing.style.display = 'none'; btn.disabled = false; stop.style.display = 'none'; window.scrollTo(0, document.body.scrollHeight); }
    } }, input, stop, btn);
    const q = el('input', { placeholder: t('search') + '…', onchange: async () => {
      const rows = q.value ? await api('GET', '/api/messages?q=' + encodeURIComponent(q.value)) : await api('GET', '/api/messages');
      list.replaceChildren(); rows.forEach(add);
    } });
    setTimeout(() => window.scrollTo(0, document.body.scrollHeight), 50);
    const mini = el('div', { class: 'orb mini' + (msgs.length ? '' : ''), 'aria-hidden': 'true' }, el('span', { class: 'aura' }), el('span', { class: 'ring' }), el('span', { class: 'core' }));
    form.addEventListener('submit', () => mini.classList.add('busy'));
    const obs = new MutationObserver(() => { if (typing.style.display === 'none') mini.classList.remove('busy'); });
    obs.observe(typing, { attributes: true });
    return el('div', { class: 'chat-page' }, el('div', { class: 'page-head' },
      el('div', { style: 'display:flex;align-items:center;gap:16px' }, mini, el('div', {}, el('h1', { class: 'wordmark', style: 'font-size:44px' }, LANG === 'ar' ? 'رفيق' : 'Rafiq'),
        el('p', { class: 'muted' }, LANG === 'ar' ? 'محادثة واحدة مع تيليجرام والتطبيق' : 'One conversation across Telegram and the app'))),
      el('div', { style: 'width:240px;max-width:100%' }, q)), el('div', { class: 'card chat-card' }, list, typing), form);
  },

  async tasks() {
    const f = state.taskFilter || 'open';
    const [rows, rems] = await Promise.all([api('GET', '/api/tasks?filter=' + f), api('GET', '/api/reminders')]);
    const now = new Date();
    const tabs = el('div', { class: 'tabs' }, ...['open', 'today', 'overdue', 'week', 'done'].map((k) =>
      el('button', { class: f === k ? 'active' : '', onclick: () => { state.taskFilter = k; render(); } }, t(k))));
    const title = el('input', { placeholder: LANG === 'ar' ? 'مهمة جديدة…' : 'New task…' });
    const due = el('input', { type: 'datetime-local' });
    const pr = el('select', {}, ...['normal', 'high', 'low'].map((p) => el('option', { value: p }, t(p))));
    const addForm = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault(); if (!title.value.trim()) return;
      await api('POST', '/api/tasks', { title: title.value, due: due.value || undefined, priority: pr.value }); toast('✓'); render(); } },
      title, due, pr, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
    const list = el('div', { class: 'list' }, ...rows.map((x) => taskRow({ ...x, _overdue: f === 'overdue' }, render)));
    if (!rows.length) list.append(el('div', { class: 'empty' }, t('noTasks')));
    const rt = el('input', { placeholder: t('text') });
    const rw = el('input', { type: 'datetime-local' });
    const rr = el('select', {}, ...['none', 'daily', 'weekdays', 'weekly', 'monthly'].map((p) => el('option', { value: p }, t(p))));
    const remForm = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault();
      const r = await api('POST', '/api/reminders', { text: rt.value, when: rw.value, recur: rr.value }); toast(r.message); render(); } },
      rt, rw, rr, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
    const remList = el('div', { class: 'list' }, ...rems.filter((r) => r.status === 'pending').map((r) => el('div', { class: 'row' },
      el('span', { class: 'gold num small' }, r.when), el('div', { class: 'grow' }, r.text, r.recur !== 'none' ? el('div', { class: 'meta' }, t(r.recur)) : null),
      el('button', { class: 'icon-btn', title: t('cancel'), onclick: async () => { await api('DELETE', `/api/reminders/${r.id}`); render(); } }, icon('close')))));
    if (!remList.children.length) remList.append(el('div', { class: 'empty' }, '—'));
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('tasks'))), addForm, el('div', { class: 'section' }, tabs, list),
      el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, t('reminders'))), remForm, remList));
  },

  async projects() {
    if (state.project) return projectView(state.project);
    const rows = await api('GET', '/api/projects');
    const name = el('input', { placeholder: LANG === 'ar' ? 'اسم المشروع' : 'Project name' });
    const goal = el('input', { placeholder: LANG === 'ar' ? 'الهدف' : 'Goal' });
    const form = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault(); if (!name.value.trim()) return;
      await api('POST', '/api/projects', { name: name.value, goal: goal.value }); render(); } }, name, goal, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
    const grid = el('div', { class: 'grid grid-3' }, ...rows.map((p) => {
      const pct = p.tasks_total ? Math.round(100 * p.tasks_done / p.tasks_total) : 0;
      return el('button', { class: 'card', style: 'text-align:start;cursor:pointer;border:0', onclick: () => { state.project = p.id; render(); } },
        el('h3', {}, p.name), el('p', { class: 'muted small', style: 'margin:4px 0 12px' }, p.goal || '—'),
        el('div', { class: 'bar-track' }, el('div', { class: 'bar-fill', style: `width:${pct}%` })),
        el('div', { class: 'small muted', style: 'margin-top:6px' }, `${arNum(pct)}٪ · ${arNum(p.tasks_done)}/${arNum(p.tasks_total)}`));
    }));
    if (!rows.length) grid.append(el('div', { class: 'empty' }, LANG === 'ar' ? 'لا مشاريع بعد.' : 'No projects yet.'));
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('projects'))), form, el('div', { class: 'section' }, grid));
  },

  async memory() {
    const [rows, st] = await Promise.all([api('GET', '/api/memory' + (state.memQ ? '?q=' + encodeURIComponent(state.memQ) : '')), api('GET', '/api/settings')]);
    const q = el('input', { placeholder: t('search') + '…', value: state.memQ || '', onchange: () => { state.memQ = q.value; render(); } });
    const content = el('input', { placeholder: LANG === 'ar' ? 'معلومة تريد أن يتذكرها رفيق' : 'Something to remember' });
    const kind = el('select', {}, ...[['fact', 'حقيقة'], ['preference', 'تفضيل'], ['project', 'مشروع'], ['decision', 'قرار']].map(([v, l]) => el('option', { value: v }, LANG === 'ar' ? l : v)));
    const form = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault();
      const r = await api('POST', '/api/memory', { content: content.value, kind: kind.value }); toast(r.message); render(); } },
      content, kind, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
    const enabled = el('input', { type: 'checkbox', checked: st.settings.memory_enabled, onchange: async () => { await api('PUT', '/api/settings', { memory_enabled: enabled.checked }); toast('✓'); } });
    const list = el('div', { class: 'list' }, ...rows.map((m) => el('div', { class: 'row' },
      el('span', { class: 'chip' }, m.kind), el('div', { class: 'grow title' }, m.content,
        m.expires_at ? el('div', { class: 'meta' }, (LANG === 'ar' ? 'ينتهي ' : 'expires ') + m.expires_at.slice(0, 10)) : null),
      el('button', { class: 'icon-btn', title: t('edit'), onclick: () => {
        const ta = el('textarea', {}, m.content);
        modal(t('edit'), ta, (c) => [el('button', { class: 'btn primary', onclick: async () => { await api('PATCH', `/api/memory/${m.id}`, { content: ta.value }); c(); render(); } }, t('save'))]);
      } }, icon('pen')),
      el('button', { class: 'icon-btn', title: t('delete'), onclick: async () => { if (confirm(LANG === 'ar' ? 'حذف هذه المعلومة نهائيًا؟' : 'Delete permanently?')) { await api('DELETE', `/api/memory/${m.id}`); render(); } } }, icon('trash')))));
    if (!rows.length) list.append(el('div', { class: 'empty' }, LANG === 'ar' ? 'لا شيء محفوظ. رفيق لا يحفظ إلا ما تطلبه أو توافق عليه.' : 'Nothing saved.'));
    return el('div', {}, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, t('memory')),
      el('p', { class: 'muted' }, LANG === 'ar' ? 'كل ما يتذكره رفيق عنك. عدّل أو احذف أي شيء.' : 'Everything Rafiq remembers.')), el('div', { style: 'width:260px;max-width:100%' }, q)),
      el('label', { class: 'switch' }, LANG === 'ar' ? 'الذاكرة طويلة المدى مفعّلة' : 'Long-term memory enabled', enabled),
      el('div', { class: 'section' }, form), el('div', { class: 'section' }, list));
  },

  async files() {
    const rows = await api('GET', '/api/files');
    const inp = el('input', { type: 'file', multiple: true, style: 'display:none', onchange: async () => {
      for (const f of inp.files) await api('POST', '/api/files?name=' + encodeURIComponent(f.name), f, true);
      toast('✓'); render();
    } });
    const list = el('div', { class: 'list' }, ...rows.map((f) => el('div', { class: 'row' },
      el('div', { class: 'grow' }, el('div', { class: 'title' }, f.name), el('div', { class: 'meta' }, `${(f.size / 1024).toFixed(1)} KB · ${f.created_at.slice(0, 10)}`)),
      el('a', { class: 'icon-btn', href: `/api/files/${f.id}`, title: 'download' }, icon('down')),
      el('button', { class: 'icon-btn', title: t('delete'), onclick: async () => { if (confirm(LANG === 'ar' ? 'حذف الملف نهائيًا؟' : 'Delete file?')) { await api('DELETE', `/api/files/${f.id}`); render(); } } }, icon('trash')))));
    if (!rows.length) list.append(el('div', { class: 'empty' }, '—'));
    return el('div', {}, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, t('files')),
      el('p', { class: 'muted' }, LANG === 'ar' ? 'رفيق يقرأ ويلخّص هذه الملفات فقط (txt, md, csv, pdf, docx). حدّ الملف ٢٠ ميجابايت.' : 'Files Rafiq can read.')),
      el('label', { class: 'btn primary' }, LANG === 'ar' ? 'رفع ملفات' : 'Upload', inp)), list);
  },

  async scheduled() {
    const rows = await api('GET', '/api/jobs');
    const kinds = { reminder: 'تذكير', briefing_morning: 'ملخص صباحي', briefing_evening: 'خلاصة مسائية', agent_prompt: 'أمر مجدول', notify: 'تنبيه', backup: 'نسخ احتياطي', cleanup: 'تنظيف', agent_run: 'طلب مؤجل' };
    const tb = el('tbody', {}, ...rows.map((j) => el('tr', {},
      el('td', {}, LANG === 'ar' ? (kinds[j.kind] || j.kind) : j.kind, j.label ? el('div', { class: 'meta small muted' }, j.label.slice(0, 80)) : null),
      el('td', { class: 'num small' }, j.when), el('td', {}, statusChip(j.status), j.last_error ? el('div', { class: 'small muted' }, j.last_error.slice(0, 100)) : null),
      el('td', {}, j.status === 'pending' && ['agent_prompt', 'notify', 'briefing_morning', 'briefing_evening'].includes(j.kind) ?
        el('button', { class: 'btn sm danger', onclick: async () => { await api('DELETE', `/api/jobs/${j.id}`); render(); } }, t('cancel')) : null))));
    return el('div', {}, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, t('scheduled')),
      el('p', { class: 'muted' }, LANG === 'ar' ? 'كل ما سيُنفَّذ لاحقًا، محفوظ في قاعدة البيانات ويُستأنف بعد أي انقطاع.' : 'Everything queued; survives restarts.'))),
      rows.length ? el('div', { class: 'table-wrap' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, LANG === 'ar' ? 'المهمة' : 'Job'), el('th', {}, t('when')), el('th', {}, t('status')), el('th', {}))), tb))
        : el('div', { class: 'empty' }, '—'));
  },

  async log() {
    const tab = state.logTab || 'runs';
    const tabs = el('div', { class: 'tabs' }, ...[['runs', LANG === 'ar' ? 'الطلبات' : 'Requests'], ['ops', LANG === 'ar' ? 'العمليات' : 'Tool calls'], ['appr', LANG === 'ar' ? 'الموافقات' : 'Approvals']].map(([k, l]) =>
      el('button', { class: tab === k ? 'active' : '', onclick: () => { state.logTab = k; render(); } }, l)));
    let body;
    if (tab === 'runs') {
      const rows = await api('GET', '/api/runs');
      body = el('div', { class: 'list' }, ...rows.map((r) => el('div', { class: 'row' },
        el('div', { class: 'grow' }, el('div', { class: 'title' }, r.input.slice(0, 160)), el('div', { class: 'meta' }, `${r.created_local} · ${r.channel}`),
          r.output ? el('div', { class: 'small', style: 'margin-top:4px;white-space:pre-wrap' }, r.output.slice(0, 300)) : null),
        statusChip(r.running ? 'running' : r.status),
        r.running ? el('button', { class: 'btn sm danger', onclick: async () => { await api('POST', `/api/runs/${r.id}/cancel`, {}); render(); } }, LANG === 'ar' ? 'إيقاف' : 'Stop') : null)));
    } else if (tab === 'ops') {
      const rows = await api('GET', '/api/operations');
      body = el('div', { class: 'table-wrap' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, LANG === 'ar' ? 'الأداة' : 'Tool'), el('th', {}, t('status')), el('th', {}, LANG === 'ar' ? 'النتيجة' : 'Result'), el('th', {}, t('when')))),
        el('tbody', {}, ...rows.map((o) => el('tr', {}, el('td', {}, el('code', {}, o.tool), el('div', { class: 'small muted' }, 'L' + o.level)), el('td', {}, statusChip(o.status)),
          el('td', { class: 'small' }, (o.error || o.result || '').slice(0, 200)), el('td', { class: 'small num muted' }, o.created_local))))));
    } else {
      const rows = await api('GET', '/api/approvals');
      body = el('div', { class: 'grid' }, ...rows.map(approvalCard));
      if (!rows.length) body.append(el('div', { class: 'empty' }, '—'));
    }
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('log'))), tabs, body);
  },

  async alerts() {
    const [rows, backups] = await Promise.all([api('GET', '/api/events'), api('GET', '/api/backups')]);
    const lv = { error: 'bad', warning: 'warn', info: 'ok' };
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('alerts')),
      el('button', { class: 'btn', onclick: async () => { const r = await api('POST', '/api/backups', {}); toast('✓ ' + r.name); render(); } }, LANG === 'ar' ? 'نسخة احتياطية الآن' : 'Backup now')),
      el('div', { class: 'list' }, ...rows.map((e) => el('div', { class: 'row' }, el('span', { class: 'chip ' + (lv[e.level] || '') }, e.level),
        el('div', { class: 'grow' }, el('div', { class: 'title small' }, e.message), el('div', { class: 'meta' }, `${e.source} · ${e.created_local}`))))),
      el('section', { class: 'section' }, el('h2', {}, LANG === 'ar' ? 'النسخ الاحتياطية' : 'Backups'),
        el('div', { class: 'list' }, ...backups.map((b) => el('div', { class: 'row' }, el('code', { class: 'grow' }, b.name), el('span', { class: 'muted small' }, (b.size / 1024).toFixed(0) + ' KB')))),
        el('p', { class: 'muted small' }, LANG === 'ar' ? 'للاستعادة: أوقف الخادم ثم نفّذ  python -m harith restore اسم-النسخة' : 'Restore: stop server, run python -m harith restore NAME')));
  },

  async usage() {
    const u = await api('GET', '/api/usage');
    const max = Math.max(0.0001, ...u.by_day.map((d) => d.cost));
    const bars = el('div', { style: 'display:flex;align-items:flex-end;gap:4px;height:140px;padding-top:10px', role: 'img', 'aria-label': 'cost per day' },
      ...u.by_day.map((d) => el('div', { title: `${d.day}: $${d.cost} · ${d.calls}`, style: `flex:1;min-width:6px;background:var(--gold);border-radius:4px 4px 0 0;height:${Math.max(2, 130 * d.cost / max)}px` })));
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('usage'))),
      el('div', { class: 'grid grid-3' }, stat('$' + u.today, LANG === 'ar' ? 'تكلفة اليوم (تقديرية)' : 'Today (est.)'), stat('$' + u.limit, LANG === 'ar' ? 'الحد اليومي' : 'Daily limit'),
        stat(u.by_day.reduce((a, d) => a + d.calls, 0), LANG === 'ar' ? 'طلبات آخر ٣٠ يومًا' : 'Calls, 30 days')),
      el('section', { class: 'section card' }, el('h3', {}, LANG === 'ar' ? 'التكلفة اليومية — آخر ٣٠ يومًا' : 'Daily cost — 30 days'), u.by_day.length ? bars : el('div', { class: 'empty' }, '—')),
      el('section', { class: 'section table-wrap' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, LANG === 'ar' ? 'النموذج' : 'Model'), el('th', {}, 'in'), el('th', {}, 'out'), el('th', {}, '$'), el('th', {}, '#'))),
        el('tbody', {}, ...u.by_model.map((m) => el('tr', {}, el('td', {}, `${m.provider} / ${m.model}`), el('td', { class: 'num' }, m.tin), el('td', { class: 'num' }, m.tout), el('td', { class: 'num' }, m.cost), el('td', { class: 'num' }, m.calls)))))),
      el('p', { class: 'muted small' }, LANG === 'ar' ? 'الأسعار تقديرية حسب جدول أسعار عام؛ راجع فاتورة المزوّد للأرقام الدقيقة.' : 'Estimates only.'));
  },

  async integrations() {
    const [items, tools] = await Promise.all([api('GET', '/api/integrations'), api('GET', '/api/tools')]);
    const lvl = { 1: ['ok', LANG === 'ar' ? 'تلقائي' : 'auto'], 2: ['warn', LANG === 'ar' ? 'يتطلب موافقة' : 'approval'], 3: ['bad', LANG === 'ar' ? 'محظور' : 'blocked'] };
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('integrations'))),
      el('div', { class: 'list' }, ...items.map((i) => el('div', { class: 'row' }, el('div', { class: 'grow' }, el('h3', {}, i.name), el('div', { class: 'meta' }, i.detail),
        i.status === 'setup' && i.setup ? el('div', { class: 'small gold' }, (LANG === 'ar' ? 'للإعداد أضف في ‎.env: ' : 'Set in .env: ') + i.setup) : null), statusChip(i.status)))),
      el('section', { class: 'section' }, el('h2', {}, LANG === 'ar' ? 'الأدوات ومستويات الموافقة' : 'Tools & approval levels'),
        el('p', { class: 'muted small' }, LANG === 'ar' ? 'المستوى ١ ينفذ تلقائيًا، المستوى ٢ ينتظر موافقتك، المستوى ٣ لا يُنفّذ أبدًا من المساعد.' : ''),
        el('div', { class: 'table-wrap' }, el('table', {}, el('tbody', {}, ...tools.map((x) => el('tr', {}, el('td', {}, el('code', {}, x.name)), el('td', { class: 'small' }, x.description), el('td', { class: 'small muted' }, x.category),
          el('td', {}, el('span', { class: 'chip ' + lvl[x.level][0] }, lvl[x.level][1])))))))));
  },

  async settings() {
    const st = await api('GET', '/api/settings');
    const s = st.settings;
    const inp = (k, type, extra) => el('input', { type, value: s[k], ...extra, onchange: (e) => save({ [k]: type === 'number' ? Number(e.target.value) : e.target.value }) });
    const sw = (k, label) => el('label', { class: 'switch' }, label, el('input', { type: 'checkbox', checked: s[k], onchange: (e) => save({ [k]: e.target.checked }) }));
    const save = async (patch) => { await api('PUT', '/api/settings', patch); toast('✓'); };
    const codeBox = el('div');
    const tokenBox = el('div');
    const name = el('input', { value: st.user.display_name, onchange: () => save({ display_name: name.value }) });
    const tz = el('input', { value: st.user.timezone, dir: 'ltr', onchange: () => save({ timezone: tz.value }) });
    const cur = el('input', { type: 'password' }), nw = el('input', { type: 'password' });
    const wipePw = el('input', { type: 'password', placeholder: t('password') });
    const L = (ar, en) => LANG === 'ar' ? ar : en;
    return el('div', {}, el('div', { class: 'page-head' }, el('h1', {}, t('settings'))),
      el('div', { class: 'grid grid-2' }, el('label', { class: 'field' }, L('الاسم', 'Name'), name), el('label', { class: 'field' }, L('المنطقة الزمنية', 'Time zone'), tz)),
      el('section', { class: 'section' }, el('h2', {}, L('الملخصات اليومية', 'Daily briefings')),
        sw('briefing_morning_enabled', L('الملخص الصباحي', 'Morning briefing')), el('div', { class: 'grid grid-2', style: 'margin:10px 0' },
          el('label', { class: 'field' }, L('وقت الملخص الصباحي', 'Morning time'), inp('briefing_time', 'time')), el('label', { class: 'field' }, L('وقت الخلاصة المسائية', 'Evening time'), inp('evening_time', 'time'))),
        sw('briefing_evening_enabled', L('الخلاصة المسائية', 'Evening review')), sw('voice_replies', L('الرد الصوتي في تيليجرام', 'Voice replies in Telegram'))),
      el('section', { class: 'section' }, el('h2', {}, L('تيليجرام', 'Telegram')),
        el('div', { class: 'list' }, ...st.telegram.map((c) => el('div', { class: 'row' }, el('div', { class: 'grow' }, '@' + (c.tg_username || c.chat_id)),
          el('button', { class: 'btn sm danger', onclick: async () => { await api('DELETE', `/api/telegram/${c.chat_id}`); render(); } }, L('فك الربط', 'Unlink'))))),
        el('button', { class: 'btn', onclick: async () => { const r = await api('POST', '/api/telegram/link-code', {});
          codeBox.replaceChildren(el('p', {}, L(`أرسل إلى البوت${st.telegram_bot ? ' @' + st.telegram_bot : ''} خلال ${r.expires_minutes} دقائق:`, 'Send to the bot:')), el('div', { class: 'code' }, '/link ' + r.code)); } }, L('ربط تيليجرام', 'Link Telegram')), codeBox),
      el('section', { class: 'section' }, el('h2', {}, L('تطبيق رفيق على الجوال', 'Android app')),
        el('p', { class: 'muted small' }, L('أنشئ مفتاح وصول والصقه في إعدادات التطبيق ← «العقل المشترك». لا يظهر المفتاح إلا مرة واحدة.', 'Create an access token for the Android app.')),
        el('div', { class: 'list' }, ...st.tokens.map((k) => el('div', { class: 'row' }, el('div', { class: 'grow' }, k.name, el('div', { class: 'meta' }, (k.last_used || '—').slice(0, 16))),
          el('button', { class: 'btn sm danger', onclick: async () => { await api('DELETE', `/api/tokens/${k.id}`); render(); } }, L('إلغاء', 'Revoke'))))),
        el('button', { class: 'btn', onclick: async () => { const r = await api('POST', '/api/tokens', { name: L('تطبيق الجوال', 'Android') }); tokenBox.replaceChildren(el('div', { class: 'code' }, r.token)); } }, L('إنشاء مفتاح وصول', 'Create token')), tokenBox),
      el('section', { class: 'section' }, el('h2', {}, L('الخصوصية والاحتفاظ', 'Privacy & retention')),
        el('div', { class: 'grid grid-3' }, el('label', { class: 'field' }, L('حذف المحادثات بعد (يوم، ٠ = أبدًا)', 'Delete chats after (days)'), inp('history_retention_days', 'number', { min: 0 })),
          el('label', { class: 'field' }, L('مدة الذاكرة الافتراضية (يوم، ٠ = دائم)', 'Memory retention (days)'), inp('memory_retention_days', 'number', { min: 0 })),
          el('label', { class: 'field' }, L('حد التكلفة اليومي ($)', 'Daily cost limit ($)'), inp('daily_cost_limit_usd', 'number', { min: 0, step: 0.5 }))),
        el('div', { class: 'form-row', style: 'margin-top:14px' }, el('a', { class: 'btn', href: '/api/export' }, L('تصدير كل بياناتي', 'Export my data'))),
        el('div', { class: 'card', style: 'margin-top:14px' }, el('h3', {}, L('حذف البيانات', 'Delete data')),
          el('div', { class: 'form-row', style: 'margin-top:10px' }, wipePw, ...[['history', L('المحادثات', 'Chats')], ['memories', L('الذاكرة', 'Memory')], ['all', L('كل شيء', 'Everything')]].map(([k, l]) =>
            el('button', { class: 'btn danger', onclick: async () => { if (!confirm(L('لا يمكن التراجع. متابعة؟', 'Cannot be undone. Continue?'))) return;
              const r = await fetch('/api/data', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-Harith': '1', 'Content-Type': 'application/json' }, body: JSON.stringify({ scope: k, password: wipePw.value }) });
              const d = await r.json(); toast(r.ok ? `✓ ${d.deleted}` : d.error); } }, l))))),
      el('section', { class: 'section' }, el('h2', {}, L('كلمة المرور', 'Password')), el('div', { class: 'form-row' },
        el('label', { class: 'field' }, L('الحالية', 'Current'), cur), el('label', { class: 'field' }, L('الجديدة', 'New'), nw),
        el('button', { class: 'btn', onclick: async () => { await api('POST', '/api/password', { current: cur.value, new: nw.value }); toast('✓'); state.me = null; render(); } }, t('save')))));
  },

  async devices() {
    const d = await api('GET', '/api/devices');
    const L = (ar, en) => LANG === 'ar' ? ar : en;
    const head = el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, t('devices')),
      el('p', { class: 'muted' }, L('كل من ثبّت رفيق وأخذ مفتاحًا تلقائيًا من حسابك في OpenRouter، مع استهلاكه الشهري.', 'Everyone who installed Rafiq and got an automatic OpenRouter sub-key.'))));
    if (!d.enabled) return el('div', {}, head, el('div', { class: 'card' }, el('h3', {}, L('التسجيل التلقائي غير مفعّل', 'Provisioning disabled')),
      el('p', { class: 'muted' }, L('أضف OPENROUTER_PROVISIONING_KEY (مفتاح إدارة من openrouter.ai ← Settings ← Provisioning keys) في ملف ‎.env ثم أعد التشغيل.', 'Set OPENROUTER_PROVISIONING_KEY in .env'))));
    const total = d.devices.reduce((a, x) => a + (x.usage_monthly || 0), 0);
    const stats = el('div', { class: 'grid grid-3' }, stat(d.devices.length, L(`مستخدم (الحد ${d.max})`, `users (max ${d.max})`)),
      stat('$' + total.toFixed(2), L('استهلاك هذا الشهر', 'Spent this month')), stat('$' + d.limit_default, L('الحد الشهري الافتراضي لكل مستخدم', 'Default monthly limit')));
    const rows = d.devices.map((x, i) => {
      const pct = x.limit ? Math.min(100, Math.round(100 * (x.usage_monthly || 0) / x.limit)) : 0;
      return el('div', { class: 'card reveal', style: `--i:${i}` },
        el('div', { class: 'section-head' }, el('div', {}, el('h3', {}, x.name || L('بلا اسم', 'Unnamed')),
          el('div', { class: 'small muted' }, `${x.device} · ${x.app_version || ''} · ${L('آخر ظهور', 'last seen')} ${(x.last_seen || '').slice(0, 10)}`)),
          x.disabled ? el('span', { class: 'chip bad' }, L('موقوف', 'disabled')) : el('span', { class: 'chip ok' }, L('نشط', 'active'))),
        el('div', { class: 'bar-track' }, el('div', { class: 'bar-fill', style: `width:${pct}%` })),
        el('div', { class: 'small muted', style: 'margin:6px 0 12px' }, `$${(x.usage_monthly || 0).toFixed(2)} / $${x.limit ?? '—'} ${L('هذا الشهر', 'this month')}`),
        el('div', { class: 'form-row' },
          el('button', { class: 'btn sm', onclick: async () => { const v = prompt(L('الحد الشهري بالدولار', 'Monthly limit $'), x.limit); if (v) { await api('PATCH', `/api/devices/${x.id}`, { limit: Number(v) }); render(); } } }, L('تعديل الحد', 'Edit limit')),
          el('button', { class: 'btn sm', onclick: async () => { await api('PATCH', `/api/devices/${x.id}`, { disabled: !x.disabled }); render(); } }, x.disabled ? L('تفعيل', 'Enable') : L('إيقاف', 'Disable')),
          el('button', { class: 'btn sm danger', onclick: async () => { if (confirm(L('حذف المستخدم وإلغاء مفتاحه نهائيًا؟', 'Revoke key?'))) { await api('DELETE', `/api/devices/${x.id}`); render(); } } }, L('إلغاء المفتاح', 'Revoke'))));
    });
    return el('div', {}, head, stats, el('section', { class: 'section grid grid-2' }, ...(rows.length ? rows : [el('div', { class: 'empty' }, L('لم يسجّل أحد بعد.', 'No users yet.'))])));
  },

  async users() {
    const rows = await api('GET', '/api/users');
    const u = el('input', { placeholder: t('username'), dir: 'ltr' }), p = el('input', { type: 'password', placeholder: t('password') });
    const role = el('select', {}, el('option', { value: 'user' }, 'user'), el('option', { value: 'admin' }, 'admin'));
    return el('div', {}, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, t('users')), el('p', { class: 'muted' }, LANG === 'ar' ? 'بيانات كل مستخدم منفصلة تمامًا عن غيره.' : 'Each user’s data is fully separated.'))),
      el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault(); await api('POST', '/api/users', { username: u.value, password: p.value, role: role.value }); render(); } }, u, p, role, el('button', { class: 'btn primary', type: 'submit' }, t('add'))),
      el('div', { class: 'list section' }, ...rows.map((x) => el('div', { class: 'row' }, el('div', { class: 'grow' }, x.display_name, el('div', { class: 'meta' }, `${x.username} · ${x.role}`)),
        x.disabled ? el('span', { class: 'chip bad' }, LANG === 'ar' ? 'معطّل' : 'disabled') : null,
        x.id !== state.me.id ? el('button', { class: 'btn sm', onclick: async () => { await api('PATCH', `/api/users/${x.id}`, { disabled: !x.disabled }); render(); } }, x.disabled ? (LANG === 'ar' ? 'تفعيل' : 'Enable') : (LANG === 'ar' ? 'تعطيل' : 'Disable')) : null))));
  },
};

function stat(v, label, alert) { return el('div', { class: 'card stat' + (alert ? ' alert' : '') }, el('b', { class: 'num' }, typeof v === 'number' ? arNum(v) : v), el('span', {}, label)); }

function approvalCard(a) {
  return el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, `#${a.id} · ${a.tool}`), statusChip(a.status)),
    el('pre', { style: 'white-space:pre-wrap;margin:0 0 10px;font-family:inherit' }, a.summary),
    a.result ? el('p', { class: 'small muted' }, a.result) : null,
    a.status === 'pending' ? el('div', { class: 'form-row' },
      el('button', { class: 'btn primary', onclick: async () => { const r = await api('POST', `/api/approvals/${a.id}`, { approve: true }); toast(r.text); render(); } }, t('approve')),
      el('button', { class: 'btn danger', onclick: async () => { const r = await api('POST', `/api/approvals/${a.id}`, { approve: false }); toast(r.text); render(); } }, t('deny'))) : null);
}

async function projectView(id) {
  const d = await api('GET', `/api/projects/${id}`);
  const KIND = { decision: 'قرار', risk: 'خطر', blocker: 'عائق', note: 'ملاحظة', meeting: 'اجتماع', contract: 'عقد', deadline: 'موعد نهائي', assignment: 'تكليف', lead: 'فرصة/عميل' };
  const kind = el('select', {}, ...Object.entries(KIND).map(([k, l]) => el('option', { value: k }, LANG === 'ar' ? l : k)));
  const content = el('input', { placeholder: LANG === 'ar' ? 'المحتوى' : 'Content' });
  const owner = el('input', { placeholder: LANG === 'ar' ? 'المسؤول' : 'Owner' });
  const due = el('input', { type: 'datetime-local' });
  const groups = {};
  d.entries.forEach((e) => { (groups[e.kind] = groups[e.kind] || []).push(e); });
  const tt = el('input', { placeholder: LANG === 'ar' ? 'مهمة في المشروع…' : 'Task…' });
  return el('div', {},
    el('button', { class: 'btn ghost', onclick: () => { state.project = null; render(); } }, LANG === 'ar' ? '→ كل المشاريع' : '← All projects'),
    el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, d.project.name), el('p', { class: 'muted' }, d.project.goal || ''))),
    el('div', { class: 'grid grid-3' }, stat(arNum(d.progress_percent) + (LANG === 'ar' ? '٪' : '%'), LANG === 'ar' ? 'نسبة الإنجاز' : 'Progress'), stat(`${arNum(d.tasks_done)}/${arNum(d.tasks_total)}`, t('tasks')), stat(d.overdue.length, t('overdue'), d.overdue.length > 0)),
    el('section', { class: 'section' }, el('h2', {}, t('tasks')),
      el('form', { class: 'form-row', style: 'margin:10px 0', onsubmit: async (e) => { e.preventDefault(); await api('POST', '/api/tasks', { title: tt.value, project: String(id) }); render(); } }, tt, el('button', { class: 'btn primary', type: 'submit' }, t('add'))),
      el('div', { class: 'list' }, ...d.open_tasks.map((x) => taskRow(x, render)))),
    el('section', { class: 'section' }, el('h2', {}, LANG === 'ar' ? 'السجل: قرارات، مخاطر، اجتماعات…' : 'Log'),
      el('form', { class: 'form-row card', style: 'margin:10px 0', onsubmit: async (e) => { e.preventDefault(); await api('POST', `/api/projects/${id}/entries`, { kind: kind.value, content: content.value, owner: owner.value, due: due.value || undefined }); render(); } },
        kind, content, owner, due, el('button', { class: 'btn primary', type: 'submit' }, t('add'))),
      ...Object.entries(groups).map(([k, items]) => el('div', { style: 'margin-top:16px' }, el('h3', { class: 'gold' }, LANG === 'ar' ? KIND[k] || k : k),
        el('div', { class: 'list' }, ...items.map((e) => el('div', { class: 'row' }, el('div', { class: 'grow title' }, e.content,
          el('div', { class: 'meta' }, [e.owner, e.due_at, e.created_at].filter(Boolean).join(' · '))))))))));
}

let refreshTimer;
function refreshLater() { clearTimeout(refreshTimer); refreshTimer = setTimeout(() => { if (state.page === 'home') render(); }, 4000); }

boot();
