/* صفحات الأعمال في لوحة رفيق: الشركات، المالية، الفواتير والعروض، العملاء والموردون، الاجتماعات، البحث الشامل.
   كل تعديل يمر عبر /api/tool/* (سجل العمليات + الموافقات + الإيقاف الطارئ). كل نص يُعرض عبر textContent. */
'use strict';

const AR = () => LANG === 'ar';
const L = (ar, en) => (AR() ? ar : en);
const nf = (v) => Number(v || 0).toLocaleString(AR() ? 'ar-SA' : 'en-US', { maximumFractionDigits: 2 });
const CUR = { SAR: 'ر.س', USD: '$', AED: 'د.إ', EUR: '€', KWD: 'د.ك', QAR: 'ر.ق', BHD: 'د.ب', OMR: 'ر.ع', EGP: 'ج.م' };
const fm = (v, c) => `${nf(v)} ${CUR[c] || c || ''}`.trim();
const thisMonth = () => new Date().toISOString().slice(0, 7);
const CATS = ['مبيعات', 'خدمات', 'رواتب', 'إيجار', 'مواد', 'مقاولو باطن', 'معدات', 'وقود', 'نقل', 'تسويق', 'اشتراكات', 'كهرباء وماء', 'اتصالات', 'رسوم حكومية', 'صيانة', 'مطاعم', 'مشتريات', 'عام'];

async function tool(name, args, quiet) {
  const r = await api('POST', `/api/tool/${name}`, args || {});
  if (!quiet) toast(r.text);
  return r;
}
function field(label, input) { return el('label', { class: 'field' }, label, input); }
function sel(options, value) { return el('select', {}, ...options.map(([v, l]) => el('option', { value: v, selected: String(v) === String(value) }, l))); }
function tile(value, label, tone) { return el('div', { class: 'card stat' + (tone ? ' ' + tone : '') }, el('b', { class: 'num' }, value), el('span', {}, label)); }
function bar(pct, over) {
  return el('div', { class: 'bar-track' }, el('div', { class: 'bar-fill' + (over ? ' over' : ''), style: `width:${Math.max(0, Math.min(100, pct || 0))}%` }));
}
function head(title, sub, ...right) {
  return el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, title), sub ? el('p', { class: 'muted' }, sub) : null),
    right.length ? el('div', { class: 'form-row' }, ...right) : null);
}
function empty(msg) { return el('div', { class: 'empty' }, msg); }
const NOTE = () => L('الأرقام من تسجيلك فقط — لا يوجد ربط بنكي، ولا تُنفَّذ أي مدفوعات من هنا.', 'Figures come only from what you record — no bank link, no payments executed.');

// ——— اختيار الجهة (مشترك بين الصفحات)
async function wsPicker(onChange) {
  const list = await api('GET', '/api/biz/workspaces');
  if (!state.ws || !list.find((w) => String(w.id) === String(state.ws))) state.ws = (list[0] || {}).id;
  const s = sel(list.map((w) => [w.id, `${w.name}${w.kind === 'personal' ? '' : ' · ' + w.kind_ar}`]), state.ws);
  s.style.minWidth = '180px';
  s.addEventListener('change', () => { state.ws = s.value; onChange ? onChange() : render(); });
  return { node: s, list, cur: list.find((w) => String(w.id) === String(state.ws)) };
}

// ——— البحث الشامل
function openSearch() {
  const q = el('input', { placeholder: L('ابحث في المهام والعملاء والفواتير والاجتماعات والملفات…', 'Search…'), autofocus: true });
  const out = el('div', { class: 'list', style: 'max-height:60vh;overflow:auto;margin-top:10px' });
  let tm;
  q.addEventListener('input', () => { clearTimeout(tm); tm = setTimeout(async () => {
    const rows = q.value.trim().length < 2 ? [] : await api('GET', '/api/search?q=' + encodeURIComponent(q.value.trim()));
    out.replaceChildren(...rows.map((r) => el('button', { class: 'row', style: 'width:100%;text-align:start;background:none;border:0;cursor:pointer', onclick: () => { close(); go(r.page); } },
      el('span', { class: 'chip' }, r.type_ar), el('div', { class: 'grow' }, el('div', { class: 'title' }, r.title), r.sub ? el('div', { class: 'meta' }, r.sub) : null))));
    if (q.value.trim().length >= 2 && !rows.length) out.append(empty(L('لا نتائج.', 'No results.')));
  }, 250); });
  const close = modal(L('بحث شامل', 'Search'), el('div', {}, q, out));
}

// ——— عناصر الرئيسية
function homeBusinessWidgets(d) {
  const a = d.alerts || {};
  const items = [
    ...(a.overdue_invoices || []).map((x) => ['bad', `${x.kind_ar} ${x.number} — ${x.contact || '—'}`, L(`متأخرة منذ ${x.due_on} · المتبقي ${x.outstanding_text}`, `overdue since ${x.due_on}`), 'invoices']),
    ...(a.recurring_next_7_days || []).map((x) => ['warn', `${x.category} ${x.amount_text}`, `${x.workspace} · ${x.date}${x.note ? ' · ' + x.note : ''}`, 'finance']),
    ...(a.due_within_7_days || []).map((x) => ['warn', `${x.kind_ar} ${x.number} — ${x.contact || '—'}`, L(`تستحق ${x.due_on} · ${x.outstanding_text}`, `due ${x.due_on}`), 'invoices']),
    ...(a.contracts_ending_30_days || []).map((x) => ['warn', L(`عقد ${x.kind_ar}: ${x.name}`, `Contract: ${x.name}`), L(`ينتهي ${x.contract_end}`, `ends ${x.contract_end}`), 'contacts']),
  ];
  const alerts = items.length ? el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, L('التنبيهات والالتزامات', 'Alerts & obligations'))),
    el('div', { class: 'list' }, ...items.slice(0, 8).map(([tone, t1, t2, page]) => el('button', { class: 'row', style: 'width:100%;text-align:start;background:none;border:0;cursor:pointer', onclick: () => go(page) },
      el('span', { class: 'chip ' + tone }, tone === 'bad' ? L('متأخر', 'Overdue') : L('قريبًا', 'Soon')), el('div', { class: 'grow' }, el('div', { class: 'title' }, t1), el('div', { class: 'meta' }, t2)))))) : null;
  const wss = (d.workspaces || []).filter((w) => w.totals.length || w.receivables.length || w.kind !== 'شخصي');
  const finance = wss.length ? el('section', { class: 'section' },
    el('div', { class: 'section-head' }, el('h2', {}, L('ملخص مالي — هذا الشهر', 'Finance — this month')), el('a', { href: '#finance' }, L('التفاصيل', 'Details'))),
    el('div', { class: 'grid grid-3' }, ...wss.map((w) => el('button', { class: 'card ws-card', onclick: () => { state.ws = w.id; go('finance'); } },
      el('div', { class: 'section-head', style: 'margin:0 0 8px' }, el('h3', {}, w.workspace), el('span', { class: 'chip' }, w.kind)),
      ...(w.totals.length ? w.totals.map((x) => el('div', { class: 'fin-line' },
        el('span', { class: 'muted small' }, x.currency), el('span', { class: 'ok-text' }, '+' + x.income_text), el('span', { class: 'bad-text' }, '−' + x.expense_text),
        el('b', { class: x.net < 0 ? 'bad-text' : 'gold' }, x.net_text))) : [el('div', { class: 'muted small' }, L('لا قيود هذا الشهر', 'No entries yet'))]),
      w.receivables.length ? el('div', { class: 'small', style: 'margin-top:6px' }, L('مستحق لك: ', 'Receivable: '), w.receivables.map((r) => r.amount_text).join('، ')) : null,
      w.overdue_count ? el('div', { class: 'chip bad', style: 'margin-top:6px' }, L(`${arNum(w.overdue_count)} فاتورة متأخرة`, `${w.overdue_count} overdue`)) : null))),
    el('p', { class: 'small muted', style: 'margin-top:8px' }, NOTE())) : null;
  const ms = a.upcoming_meetings || [];
  const meetings = ms.length ? el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, t('meetings')), el('a', { href: '#meetings' }, L('عرض الكل', 'View all'))),
    el('div', { class: 'list' }, ...ms.map((m) => el('div', { class: 'row' }, el('span', { class: 'gold num small' }, m.when), el('div', { class: 'grow title' }, m.title))))) : null;
  return { alerts, finance, meetings };
}

function customizeHome(hidden) {
  const opts = [['alerts', L('التنبيهات والالتزامات', 'Alerts')], ['stats', L('الأرقام السريعة', 'Quick stats')], ['tasks', L('المهام والتذكيرات والعادات', 'Tasks & reminders')],
    ['finance', L('الملخص المالي', 'Finance summary')], ['meetings', L('الاجتماعات القادمة', 'Upcoming meetings')], ['kill', L('زر الإيقاف الطارئ', 'Emergency stop')], ['health', L('حالة النظام', 'System health')]];
  const boxes = opts.map(([id, label]) => { const c = el('input', { type: 'checkbox', checked: !hidden.has(id) }); c.dataset.id = id; return [c, el('label', { class: 'switch' }, label, c)]; });
  modal(L('تخصيص الرئيسية', 'Customize home'), el('div', { class: 'grid' }, ...boxes.map((b) => b[1])), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      await api('PUT', '/api/settings', { home_widgets: boxes.filter(([c]) => !c.checked).map(([c]) => c.dataset.id) }); close(); render();
    } }, t('save'))]);
}

Object.assign(VIEWS, {
  // ═══════════ الشركات والمساحات
  async business() {
    if (state.bizWs) return workspaceView(state.bizWs);
    const home = await api('GET', '/api/biz/home');
    const list = await api('GET', '/api/biz/workspaces');
    const name = el('input', { placeholder: L('اسم الشركة أو النشاط', 'Company name') });
    const kind = sel([['company', L('شركة', 'Company')], ['activity', L('نشاط/مشروع تجاري', 'Activity')]]);
    const cur = el('input', { value: 'SAR', dir: 'ltr', style: 'width:90px', maxlength: 3 });
    const vat = el('input', { type: 'number', value: '15', style: 'width:90px', min: 0, max: 100 });
    const form = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault(); if (!name.value.trim()) return;
      await tool('workspace_create', { name: name.value, kind: kind.value, currency: cur.value, vat_rate: vat.value }); render(); } },
      name, kind, field(L('العملة', 'Currency'), cur), field(L('الضريبة٪', 'VAT%'), vat), el('button', { class: 'btn primary', type: 'submit' }, t('add')));
    const byName = Object.fromEntries((home.workspaces || []).map((w) => [w.workspace, w]));
    const grid = el('div', { class: 'grid grid-3' }, ...list.map((w) => {
      const s = byName[w.name] || { totals: [], receivables: [] };
      return el('button', { class: 'card ws-card', onclick: () => { state.bizWs = w.id; render(); } },
        el('div', { class: 'section-head', style: 'margin:0 0 6px' }, el('h3', {}, w.name), el('span', { class: 'chip' }, w.kind_ar)),
        el('div', { class: 'small muted' }, `${w.currency}${w.vat_rate ? ' · ' + L('ضريبة', 'VAT') + ' ' + w.vat_rate + '٪' : ''}`),
        ...s.totals.map((x) => el('div', { class: 'fin-line' }, el('span', { class: 'muted small' }, L('صافي الشهر', 'Net')), el('b', { class: x.net < 0 ? 'bad-text' : 'gold' }, x.net_text))),
        s.receivables && s.receivables.length ? el('div', { class: 'small' }, L('مستحق لك: ', 'Receivable: '), s.receivables.map((r) => r.amount_text).join('، ')) : null,
        s.overdue_count ? el('span', { class: 'chip bad' }, L(`${arNum(s.overdue_count)} متأخرة`, `${s.overdue_count} overdue`)) : null);
    }));
    return el('div', {}, head(t('business'), L('مساحة مستقلة لكل شركة أو نشاط: أموالها ومشاريعها وعملاؤها ومؤشراتها — منفصلة تمامًا عن حسابك الشخصي.', 'One separate space per company.')),
      form, el('div', { class: 'section' }, grid));
  },

  // ═══════════ المالية
  async finance() {
    const wp = await wsPicker();
    const month = state.finMonth || thisMonth();
    const mIn = el('input', { type: 'month', value: month, style: 'width:160px', onchange: () => { state.finMonth = mIn.value; render(); } });
    const q = `workspace=${encodeURIComponent(state.ws)}`;
    const [rep, ledger, recs] = await Promise.all([api('GET', `/api/biz/report?${q}&month=${month}`), api('GET', `/api/biz/ledger?${q}&month=${month}`), api('GET', '/api/biz/recurring')]);
    const w = wp.cur;
    const sm = rep.summary;
    const tiles = sm.totals.length ? sm.totals.flatMap((x) => [tile('+' + x.income_text, L('الدخل', 'Income') + ' · ' + x.currency), tile('−' + x.expense_text, L('المصروف', 'Expenses') + ' · ' + x.currency),
      tile(x.net_text, L('الصافي', 'Net') + ' · ' + x.currency, x.net < 0 ? 'alert' : '')]) : [tile('—', L('لا قيود لهذا الشهر', 'No entries'))];
    if (sm.receivables.length) tiles.push(tile(sm.receivables.map((r) => r.amount_text).join(' + '), L('مستحق لك', 'Receivable')));
    if (sm.payables.length) tiles.push(tile(sm.payables.map((r) => r.amount_text).join(' + '), L('مستحق عليك للموردين', 'Payable')));

    // إضافة قيد
    const kind = sel([['expense', L('مصروف', 'Expense')], ['income', L('دخل', 'Income')]]);
    const amt = el('input', { placeholder: L('المبلغ', 'Amount'), inputmode: 'decimal', style: 'width:130px' });
    const cur = el('input', { value: w.currency, dir: 'ltr', style: 'width:76px', maxlength: 3 });
    const dl = el('datalist', { id: 'cats' }, ...CATS.map((c) => el('option', { value: c })));
    const cat = el('input', { placeholder: L('البند', 'Category'), list: 'cats', style: 'width:150px' });
    const day = el('input', { type: 'date', value: new Date().toISOString().slice(0, 10), style: 'width:160px' });
    const note = el('input', { placeholder: L('وصف / الجهة', 'Note') });
    const addForm = el('form', { class: 'form-row card', onsubmit: async (e) => { e.preventDefault(); if (!amt.value) return;
      await tool('finance_record', { kind: kind.value, amount: amt.value, currency: cur.value, category: cat.value || 'عام', date: day.value, note: note.value, workspace: state.ws }); render(); } },
      kind, amt, cur, cat, dl, day, note, el('button', { class: 'btn primary', type: 'submit' }, L('تسجيل', 'Record')));

    // الميزانية مقابل الفعلي
    const bCat = el('input', { placeholder: L('البند', 'Category'), list: 'cats', style: 'width:150px' });
    const bAmt = el('input', { placeholder: L('الميزانية الشهرية', 'Monthly budget'), inputmode: 'decimal', style: 'width:150px' });
    const budgets = el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('الميزانية مقابل الفعلي', 'Budget vs actual'))),
      ...(sm.budgets.length ? sm.budgets.map((b) => el('div', { style: 'margin-bottom:12px' },
        el('div', { class: 'fin-line' }, el('span', {}, b.category), el('span', { class: 'small muted' }, `${b.spent_text} / ${b.budget_text}`), el('b', { class: b.percent > 100 ? 'bad-text' : '' }, arNum(b.percent) + '٪')),
        bar(b.percent, b.percent > 100))) : [el('p', { class: 'muted small' }, L('لم تحدد ميزانيات بعد.', 'No budgets yet.'))]),
      el('form', { class: 'form-row', onsubmit: async (e) => { e.preventDefault(); if (!bCat.value || !bAmt.value) return;
        await tool('budget_set', { workspace: state.ws, category: bCat.value, amount: bAmt.value }); render(); } }, bCat, bAmt, el('button', { class: 'btn sm', type: 'submit' }, L('حفظ', 'Save'))));

    // التدفق النقدي (آخر 6 أشهر)
    const flow = rep.cash_flow.filter((c) => c.currency === (sm.totals[0] || {}).currency || rep.cash_flow.length <= 6);
    const maxV = Math.max(1, ...flow.map((c) => Math.max(c.income, c.expense)));
    const chart = el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('التدفق النقدي', 'Cash flow'))),
      el('div', { class: 'flow' }, ...flow.map((c) => el('div', { class: 'flow-col', title: `${c.month}: +${nf(c.income)} / −${nf(c.expense)}` },
        el('div', { class: 'flow-bars' }, el('i', { class: 'in', style: `height:${(100 * c.income / maxV).toFixed(1)}%` }), el('i', { class: 'out', style: `height:${(100 * c.expense / maxV).toFixed(1)}%` })),
        el('span', { class: 'small muted num' }, c.month.slice(5))))),
      el('div', { class: 'small muted' }, el('span', { class: 'ok-text' }, '■ ' + L('دخل', 'income')), '  ', el('span', { class: 'gold' }, '■ ' + L('مصروف', 'expense'))),
      ...(rep.forecast || []).map((f) => el('p', { class: 'small', style: 'margin:8px 0 0' }, L('توقع صافي شهري: ', 'Forecast monthly net: '), el('b', {}, f.expected_monthly_net_text),
        ` — ${f.basis} · ${L('الثقة', 'confidence')}: ${f.confidence}`)));

    // القيود
    const rows = el('div', { class: 'table-wrap card' }, ledger.length ? el('table', {}, el('thead', {}, el('tr', {}, ...[L('التاريخ', 'Date'), L('البند', 'Category'), L('الوصف', 'Note'), L('المبلغ', 'Amount'), ''].map((h) => el('th', {}, h)))),
      el('tbody', {}, ...ledger.map((r) => el('tr', {}, el('td', { class: 'num small' }, r.date), el('td', {}, r.category), el('td', { class: 'small' }, r.note || '—'),
        el('td', { class: 'num ' + (r.kind === 'income' ? 'ok-text' : 'bad-text') }, (r.kind === 'income' ? '+' : '−') + r.amount_text),
        el('td', {}, el('button', { class: 'icon-btn', title: L('حذف (يحتاج موافقة)', 'Delete (needs approval)'), onclick: async () => {
          if (confirm(L('حذف هذا القيد؟ سيُطلب منك التأكيد في الموافقات.', 'Delete? You will be asked to approve.'))) { await tool('finance_delete', { id: r.id }); render(); } } }, icon('trash'))))))) : empty(L('لا قيود لهذا الشهر. سجّل من النموذج أعلاه، أو قل لرفيق: «سجل مصروف وقود ٢٥٠».', 'No entries.')));

    // الالتزامات المتكررة
    const rAmt = el('input', { placeholder: L('المبلغ', 'Amount'), inputmode: 'decimal', style: 'width:120px' });
    const rCat = el('input', { placeholder: L('البند (إيجار، رواتب…)', 'Category'), list: 'cats', style: 'width:170px' });
    const rDay = el('input', { type: 'number', min: 1, max: 28, value: 1, style: 'width:80px' });
    const recBox = el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('الالتزامات الشهرية المتكررة', 'Recurring obligations'))),
      el('p', { class: 'small muted', style: 'margin-top:0' }, L('أنبّهك في يوم الاستحقاق، ولا أسجّلها مصروفًا إلا بعد تأكيدك.', 'Reminded on the due day; never auto-posted.')),
      el('div', { class: 'list' }, ...recs.filter((r) => r.workspace === w.name).map((r) => el('div', { class: 'row' }, el('span', { class: 'chip' }, L('يوم ', 'day ') + arNum(r.day_of_month)),
        el('div', { class: 'grow' }, `${r.category} — ${r.amount_text}`, r.note ? el('div', { class: 'meta' }, r.note) : null),
        el('button', { class: 'icon-btn', onclick: async () => { await tool('recurring_cancel', { id: r.id }); render(); } }, icon('close'))))),
      el('form', { class: 'form-row', onsubmit: async (e) => { e.preventDefault(); if (!rAmt.value) return;
        await tool('recurring_add', { workspace: state.ws, amount: rAmt.value, category: rCat.value || 'عام', day_of_month: rDay.value }); render(); } },
      rAmt, rCat, field(L('يوم', 'Day'), rDay), el('button', { class: 'btn sm', type: 'submit' }, t('add'))));

    const exportX = el('button', { class: 'btn', onclick: async () => {
      const r = await tool('export_report', { kind: 'report', workspace: state.ws, month, format: 'xlsx' }, true);
      if (r.ok) location.href = `/api/files/${r.data.file_id}`; } }, '⬇ Excel');
    const exportL = el('button', { class: 'btn', onclick: async () => {
      const r = await tool('export_report', { kind: 'ledger', workspace: state.ws, month, format: 'xlsx' }, true);
      if (r.ok) location.href = `/api/files/${r.data.file_id}`; } }, L('⬇ القيود Excel', '⬇ Ledger'));
    const pdf = el('a', { class: 'btn', target: '_blank', href: `/api/biz/report/print?${q}&month=${month}` }, L('🖨 PDF', '🖨 PDF'));
    return el('div', {}, head(t('finance'), NOTE(), wp.node, mIn),
      el('div', { class: 'grid grid-3 section' }, ...tiles), el('section', { class: 'section' }, addForm),
      el('section', { class: 'section grid grid-2' }, budgets, chart),
      el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, L('القيود', 'Entries')), el('div', { class: 'form-row' }, exportL, exportX, pdf)), rows),
      el('section', { class: 'section' }, recBox));
  },

  // ═══════════ الفواتير والعروض
  async invoices() {
    const f = state.invFilter || 'all';
    const rows = await api('GET', '/api/biz/invoices?filter=' + f);
    const tabs = el('div', { class: 'tabs' }, ...[['all', L('الكل', 'All')], ['unpaid', L('غير مدفوعة', 'Unpaid')], ['overdue', L('متأخرة', 'Overdue')], ['open_quotes', L('عروض مفتوحة', 'Open quotes')]].map(([k, l]) =>
      el('button', { class: f === k ? 'active' : '', onclick: () => { state.invFilter = k; render(); } }, l)));
    const tone = { paid: 'ok', accepted: 'ok', overdue: 'bad', rejected: 'bad', cancelled: '', partial: 'warn', sent: 'warn', draft: '' };
    const list = el('div', { class: 'list' }, ...rows.map((x) => el('div', { class: 'row inv-row' },
      el('span', { class: 'chip' }, x.kind_ar),
      el('div', { class: 'grow' }, el('div', { class: 'title' }, `${x.number}${x.title ? ' — ' + x.title : ''}`),
        el('div', { class: 'meta' }, [x.contact || '—', x.workspace, x.issued_on, x.due_on ? L('الاستحقاق ', 'due ') + x.due_on : ''].filter(Boolean).join(' · '))),
      el('div', { style: 'text-align:end' }, el('b', { class: 'num' }, x.total_text), x.paid && x.outstanding ? el('div', { class: 'small muted' }, L('المتبقي ', 'left ') + x.outstanding_text) : null),
      el('span', { class: 'chip ' + (tone[x.status] || '') }, x.status_ar),
      el('button', { class: 'btn sm', onclick: () => invoiceActions(x) }, L('إجراء', 'Actions')))));
    if (!rows.length) list.append(empty(L('لا مستندات. أنشئ فاتورة أو عرض سعر، أو قل لرفيق: «اعمل عرض سعر لأبو خالد ٢٠٠٠ ريال صيانة».', 'Nothing here.')));
    return el('div', {}, head(t('invoices'), L('مسودات تجهزها هنا ثم ترسلها أنت. ليست فواتير إلكترونية معتمدة لدى هيئة الزكاة (فاتورة).', 'Drafts you send yourself. Not ZATCA e-invoices.'),
      el('button', { class: 'btn primary', onclick: () => invoiceForm('invoice') }, L('+ فاتورة', '+ Invoice')),
      el('button', { class: 'btn', onclick: () => invoiceForm('quote') }, L('+ عرض سعر', '+ Quote')),
      el('button', { class: 'btn ghost', onclick: () => invoiceForm('bill') }, L('+ فاتورة مورد', '+ Supplier bill')),
      el('button', { class: 'btn ghost', onclick: async () => { const r = await tool('export_report', { kind: 'invoices', format: 'xlsx' }, true); if (r.ok) location.href = `/api/files/${r.data.file_id}`; } }, '⬇ Excel')),
      tabs, list);
  },

  // ═══════════ العملاء والموردون
  async contacts() {
    const k = state.ctKind || '';
    const rows = await api('GET', `/api/biz/contacts?kind=${k}&q=${encodeURIComponent(state.ctQ || '')}`);
    const kinds = [['', L('الكل', 'All')], ['client', L('العملاء', 'Clients')], ['lead', L('عملاء محتملون', 'Leads')], ['supplier', L('الموردون', 'Suppliers')], ['partner', L('الشركاء', 'Partners')], ['employee', L('الموظفون', 'Employees')]];
    const tabs = el('div', { class: 'tabs' }, ...kinds.map(([v, l]) => el('button', { class: k === v ? 'active' : '', onclick: () => { state.ctKind = v; render(); } }, l)));
    const q = el('input', { placeholder: t('search') + '…', value: state.ctQ || '', style: 'width:220px', onchange: () => { state.ctQ = q.value; render(); } });
    const STG = { new: L('جديد', 'New'), contacted: L('تم التواصل', 'Contacted'), proposal: L('عرض مقدم', 'Proposal'), negotiation: L('تفاوض', 'Negotiation'), won: L('تم الفوز', 'Won'), lost: L('خسارة', 'Lost') };
    const list = el('div', { class: 'list' }, ...rows.map((c) => el('div', { class: 'row' },
      el('span', { class: 'chip' }, c.kind_ar),
      el('div', { class: 'grow' }, el('div', { class: 'title' }, c.name, c.company ? el('span', { class: 'muted small' }, ' · ' + c.company) : null),
        el('div', { class: 'meta' }, [c.role, c.phone, c.email, c.contract_end ? L('ينتهي العقد ', 'contract ends ') + c.contract_end : ''].filter(Boolean).join(' · '))),
      c.owes_you ? el('span', { class: 'chip warn' }, L('مستحق: ', 'Owes: ') + c.owes_you) : null,
      c.kind === 'lead' ? (() => { const s = sel(Object.entries(STG), c.stage || 'new'); s.style.width = '130px';
        s.addEventListener('change', async () => { await tool('contact_update', { contact: c.id, stage: s.value }); }); return s; })() : null,
      el('button', { class: 'icon-btn', title: t('edit'), onclick: () => contactForm(c) }, icon('pen')))));
    if (!rows.length) list.append(empty(L('لا جهات بعد.', 'No contacts yet.')));
    return el('div', {}, head(t('contacts'), k === 'employee' ? L('بيانات الموظفين سرية؛ لا قرارات توظيف أو فصل آلية — للمراجعة البشرية فقط.', 'Employee data is private.') : '',
      q, el('button', { class: 'btn primary', onclick: () => contactForm({ kind: k || 'client' }) }, '+ ' + t('add'))), tabs, list);
  },

  // ═══════════ الاجتماعات
  async meetings() {
    const f = state.mtFilter || 'upcoming';
    const rows = await api('GET', '/api/biz/meetings?filter=' + f);
    const tabs = el('div', { class: 'tabs' }, ...[['upcoming', L('القادمة', 'Upcoming')], ['all', L('الكل', 'All')]].map(([v, l]) =>
      el('button', { class: f === v ? 'active' : '', onclick: () => { state.mtFilter = v; render(); } }, l)));
    const list = el('div', { class: 'grid grid-2' }, ...rows.map((m) => el('div', { class: 'card' },
      el('div', { class: 'section-head', style: 'margin:0 0 6px' }, el('h3', {}, m.title), statusChip(m.status === 'done' ? 'done' : 'pending')),
      el('div', { class: 'small gold num' }, m.when || L('بلا موعد', 'No time')),
      m.workspace ? el('div', { class: 'small muted' }, m.workspace) : null,
      m.attendees ? el('div', { class: 'small' }, L('الحضور: ', 'Attendees: ') + m.attendees) : null,
      m.agenda ? el('pre', { class: 'small', style: 'white-space:pre-wrap;font-family:inherit;margin:8px 0' }, m.agenda) : null,
      el('div', { class: 'form-row' }, el('button', { class: 'btn sm primary', onclick: () => minutesForm(m) }, m.has_minutes ? L('المحضر', 'Minutes') : L('سجّل المحضر', 'Record minutes'))))));
    if (!rows.length) list.append(empty(L('لا اجتماعات.', 'No meetings.')));
    return el('div', {}, head(t('meetings'), L('جدول الأعمال، ثم المحضر: كل إجراء يتحول لمهمة فعلية بمسؤول وموعد.', 'Agenda, then minutes → real tasks.'),
      el('button', { class: 'btn primary', onclick: meetingForm }, L('+ اجتماع', '+ Meeting'))), tabs, list);
  },
});

// ——— لوحة جهة واحدة
async function workspaceView(id) {
  const d = await api('GET', `/api/biz/overview?workspace=${id}`);
  const w = d.workspace, fin = d.finance;
  const back = el('button', { class: 'btn ghost', onclick: () => { state.bizWs = null; render(); } }, L('→ كل الجهات', '← All'));
  const tiles = fin.totals.flatMap((x) => [tile('+' + x.income_text, L('دخل الشهر', 'Income')), tile('−' + x.expense_text, L('مصروف الشهر', 'Expenses')), tile(x.net_text, L('الصافي', 'Net'), x.net < 0 ? 'alert' : '')]);
  if (!tiles.length) tiles.push(tile('—', L('لا قيود هذا الشهر', 'No entries this month')));
  tiles.push(tile(arNum(d.contacts.clients || 0), L('عملاء', 'Clients')), tile(arNum(d.contacts.suppliers || 0), L('موردون', 'Suppliers')));
  if (fin.receivables.length) tiles.push(tile(fin.receivables.map((r) => r.amount_text).join(' + '), L('مستحق لك', 'Receivable')));
  const projects = el('div', { class: 'list' }, ...d.projects.map((p) => el('div', { class: 'row' },
    el('div', { class: 'grow' }, el('div', { class: 'title' }, p.name), bar(p.progress),
      el('div', { class: 'meta' }, `${arNum(p.progress)}٪ · ${arNum(p.done)}/${arNum(p.total)}` + (p.budget_text ? ` · ${L('المصروف', 'spent')} ${p.spent_text} / ${p.budget_text}` : ''))),
    p.over_budget ? el('span', { class: 'chip bad' }, L('تجاوز الميزانية', 'Over budget')) : null)));
  if (!d.projects.length) projects.append(empty(L('لا مشاريع لهذه الجهة.', 'No projects.')));
  const pName = el('input', { placeholder: L('مشروع جديد', 'New project') });
  const pBud = el('input', { placeholder: L('الميزانية', 'Budget'), inputmode: 'decimal', style: 'width:130px' });
  const pForm = el('form', { class: 'form-row', onsubmit: async (e) => { e.preventDefault(); if (!pName.value.trim()) return;
    await api('POST', '/api/projects', { name: pName.value, workspace: id, budget: pBud.value || undefined }); render(); } }, pName, pBud, el('button', { class: 'btn sm', type: 'submit' }, t('add')));
  const kpis = el('div', { class: 'list' }, ...d.kpis.map((k) => el('div', { class: 'row' },
    el('div', { class: 'grow' }, el('div', { class: 'fin-line' }, el('span', { class: 'title' }, k.name), el('span', { class: 'num small' }, `${k.current ?? '—'} / ${k.target ?? '—'} ${k.unit}`)), k.percent != null ? bar(k.percent, false) : null),
    el('button', { class: 'icon-btn', onclick: () => kpiForm(id, k) }, icon('pen')))));
  if (!d.kpis.length) kpis.append(empty(L('لا مؤشرات بعد.', 'No KPIs.')));
  const log = el('div', { class: 'list' }, ...d.log.map((x) => el('div', { class: 'row' }, el('span', { class: 'small muted num' }, x.created_at.slice(-16)), el('div', { class: 'grow small' }, x.detail || `${x.entity} ${x.action}`))));
  const risks = el('div', { class: 'list' }, ...d.risks.map((r) => el('div', { class: 'row' }, el('span', { class: 'chip bad' }, L('خطر', 'Risk')), el('div', { class: 'grow' }, r.content, el('div', { class: 'meta' }, r.project)))));
  if (!d.risks.length) risks.append(empty(L('لا مخاطر مسجلة.', 'No open risks.')));
  return el('div', {}, head(w.name, `${w.kind_ar} · ${w.currency}${w.vat_rate ? ' · ' + L('ضريبة', 'VAT') + ' ' + w.vat_rate + '٪' : ''}${w.goals ? ' · ' + w.goals : ''}`, back,
    el('button', { class: 'btn', onclick: () => { state.ws = id; go('finance'); } }, t('finance')),
    el('button', { class: 'btn ghost', onclick: () => wsEdit(w) }, t('edit'))),
    el('div', { class: 'grid grid-3 section' }, ...tiles),
    fin.overdue_invoices.length ? el('section', { class: 'section card kill on' }, el('h3', {}, L('فواتير متأخرة', 'Overdue invoices')),
      ...fin.overdue_invoices.map((x) => el('div', { class: 'small' }, `${x.number} — ${x.contact || '—'} — ${x.outstanding_text} (${x.due_on})`))) : null,
    el('section', { class: 'section grid grid-2' },
      el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, t('projects'))), projects, pForm),
      el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('مؤشرات الأداء KPI', 'KPIs')), el('button', { class: 'btn sm', onclick: () => kpiForm(id) }, '+')), kpis)),
    el('section', { class: 'section grid grid-2' },
      el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('المخاطر والمشكلات', 'Risks'))), risks),
      el('div', { class: 'card' }, el('div', { class: 'section-head' }, el('h3', {}, L('سجل التعديلات', 'Change log'))), log.children.length ? log : empty('—'))));
}

function wsEdit(w) {
  const name = el('input', { value: w.name }), goals = el('textarea', {}, w.goals || ''), vat = el('input', { type: 'number', value: w.vat_rate });
  modal(t('edit'), el('div', { class: 'grid' }, field(L('الاسم', 'Name'), name), field(L('الأهداف', 'Goals'), goals), field(L('الضريبة٪', 'VAT%'), vat)), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { await tool('workspace_update', { workspace: w.id, name: name.value, goals: goals.value, vat_rate: vat.value }); close(); render(); } }, t('save')),
    w.kind !== 'personal' ? el('button', { class: 'btn danger', onclick: async () => { if (confirm(L('أرشفة هذه الجهة؟ البيانات تبقى محفوظة.', 'Archive?'))) { await tool('workspace_update', { workspace: w.id, status: 'archived' }); close(); state.bizWs = null; render(); } } }, L('أرشفة', 'Archive')) : null]);
}

function kpiForm(wsId, k) {
  k = k || {};
  const name = el('input', { value: k.name || '' }), target = el('input', { type: 'number', value: k.target ?? '' }), current = el('input', { type: 'number', value: k.current ?? '' });
  const unit = el('input', { value: k.unit || '', placeholder: L('ريال، عميل، ٪…', 'unit') });
  const dir = sel([['up', L('الأعلى أفضل', 'Higher is better')], ['down', L('الأقل أفضل', 'Lower is better')]], k.direction || 'up');
  modal(L('مؤشر أداء', 'KPI'), el('div', { class: 'grid' }, field(L('الاسم', 'Name'), name), field(L('الهدف', 'Target'), target), field(L('الحالي', 'Current'), current), field(L('الوحدة', 'Unit'), unit), dir), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { await tool('kpi_set', { workspace: wsId, name: name.value, target: target.value, current: current.value, unit: unit.value, direction: dir.value }); close(); render(); } }, t('save'))]);
}

// ——— نماذج الفواتير
async function invoiceForm(kind) {
  const wss = await api('GET', '/api/biz/workspaces');
  const ws = sel(wss.map((w) => [w.id, w.name]), state.ws || (wss.find((w) => w.kind !== 'personal') || wss[0] || {}).id);
  const contact = el('input', { placeholder: kind === 'bill' ? L('اسم المورد', 'Supplier') : L('اسم العميل', 'Client') });
  const title = el('input', { placeholder: L('الموضوع', 'Subject') });
  const due = el('input', { type: 'date' });
  const vat = el('input', { type: 'number', placeholder: L('حسب الجهة', 'per company'), style: 'width:120px' });
  const notes = el('textarea', { placeholder: L('الشروط والملاحظات', 'Terms') });
  const lines = el('div', { class: 'grid' });
  const addLine = () => lines.append(el('div', { class: 'form-row line' }, el('input', { placeholder: L('الوصف', 'Description'), class: 'd' }),
    el('input', { type: 'number', value: 1, min: 0, step: 'any', class: 'q', style: 'width:80px' }), el('input', { inputmode: 'decimal', placeholder: L('السعر', 'Price'), class: 'p', style: 'width:120px' })));
  addLine();
  const label = { invoice: L('فاتورة جديدة', 'New invoice'), quote: L('عرض سعر جديد', 'New quote'), bill: L('فاتورة مورد', 'Supplier bill') }[kind];
  modal(label, el('div', { class: 'grid' }, field(L('الجهة', 'Company'), ws), contact, title, el('b', { class: 'small' }, L('البنود', 'Items')), lines,
    el('button', { class: 'btn sm ghost', type: 'button', onclick: addLine }, L('+ بند', '+ line')),
    el('div', { class: 'form-row' }, field(kind === 'quote' ? L('صالح حتى', 'Valid until') : L('الاستحقاق', 'Due'), due), field(L('الضريبة٪', 'VAT%'), vat)), notes), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const items = [...lines.querySelectorAll('.line')].map((r) => ({ description: r.querySelector('.d').value, qty: Number(r.querySelector('.q').value || 1), price: r.querySelector('.p').value }))
        .filter((x) => x.description && x.price);
      if (!items.length) { toast(L('أضف بندًا بسعر', 'Add a priced line')); return; }
      const r = await tool('invoice_create', { kind, workspace: ws.value, contact: contact.value, title: title.value, items, due_on: due.value || undefined, vat_rate: vat.value === '' ? undefined : vat.value, notes: notes.value });
      if (r.ok) { close(); render(); }
    } }, L('إنشاء مسودة', 'Create draft'))]);
}

function invoiceActions(x) {
  const pay = el('input', { inputmode: 'decimal', placeholder: L('مبلغ الدفعة', 'Amount'), value: x.outstanding || '' });
  const day = el('input', { type: 'date', value: new Date().toISOString().slice(0, 10) });
  const body = el('div', { class: 'grid' },
    el('div', { class: 'small muted' }, `${x.kind_ar} · ${x.contact || '—'} · ${x.total_text} · ${x.status_ar}`),
    el('div', { class: 'form-row' },
      el('a', { class: 'btn', target: '_blank', href: `/api/biz/invoices/${x.id}/print` }, L('🖨 عرض / PDF', '🖨 View / PDF')),
      x.status === 'draft' ? el('button', { class: 'btn', onclick: async () => { await tool('invoice_update', { invoice: x.number, status: 'sent' }); document.querySelector('.modal-back').remove(); render(); } }, L('أرسلتُها للعميل', 'I sent it')) : null,
      x.kind === 'quote' && ['draft', 'sent'].includes(x.status) ? el('button', { class: 'btn primary', onclick: async () => { await tool('invoice_from_quote', { quote: x.number }); document.querySelector('.modal-back').remove(); render(); } }, L('قُبل ← حوّله لفاتورة', 'Accepted → invoice')) : null,
      x.kind === 'quote' && ['draft', 'sent'].includes(x.status) ? el('button', { class: 'btn ghost', onclick: async () => { await tool('invoice_update', { invoice: x.number, status: 'rejected' }); document.querySelector('.modal-back').remove(); render(); } }, L('رُفض', 'Rejected')) : null),
    x.kind !== 'quote' && x.outstanding > 0 && x.status !== 'cancelled' ? el('div', { class: 'card' }, el('b', { class: 'small' }, x.kind === 'bill' ? L('سجّل دفعة دفعتها للمورد', 'Record payment made') : L('سجّل دفعة استلمتها', 'Record payment received')),
      el('div', { class: 'form-row' }, pay, day, el('button', { class: 'btn primary', onclick: async () => {
        const r = await tool('invoice_update', { invoice: x.number, payment: pay.value, date: day.value }); if (r.ok) { document.querySelector('.modal-back').remove(); render(); } } }, L('تسجيل', 'Record'))),
      el('div', { class: 'small muted' }, L('تسجيل فقط — رفيق لا يحوّل أموالًا.', 'Record only — no money is moved.'))) : null,
    x.status !== 'cancelled' && x.status !== 'paid' ? el('button', { class: 'btn sm danger', onclick: async () => { if (confirm(L('إلغاء المستند؟', 'Cancel document?'))) { await tool('invoice_update', { invoice: x.number, status: 'cancelled' }); document.querySelector('.modal-back').remove(); render(); } } }, L('إلغاء المستند', 'Cancel document')) : null);
  modal(x.number, body);
}

function contactForm(c) {
  const isNew = !c.id;
  const kind = sel([['client', L('عميل', 'Client')], ['lead', L('عميل محتمل', 'Lead')], ['supplier', L('مورد', 'Supplier')], ['partner', L('شريك', 'Partner')], ['employee', L('موظف', 'Employee')]], c.kind || 'client');
  const f = {}; for (const k of ['name', 'company', 'phone', 'email', 'role']) f[k] = el('input', { value: c[k] || '', dir: ['phone', 'email'].includes(k) ? 'ltr' : null });
  const ce = el('input', { type: 'date', value: c.contract_end || '' });
  const notes = el('textarea', {}, c.notes || '');
  modal(isNew ? L('جهة جديدة', 'New contact') : c.name, el('div', { class: 'grid' }, kind, field(L('الاسم', 'Name'), f.name), field(L('الشركة', 'Company'), f.company),
    field(L('الجوال', 'Phone'), f.phone), field(L('البريد', 'Email'), f.email), field(L('المسمى/التخصص', 'Role'), f.role), field(L('انتهاء العقد', 'Contract end'), ce), notes), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const args = { kind: kind.value, name: f.name.value, company: f.company.value, phone: f.phone.value, email: f.email.value, role: f.role.value, notes: notes.value, contract_end: ce.value || undefined };
      const r = isNew ? await tool('contact_add', args) : await tool('contact_update', { contact: c.id, ...args });
      if (r.ok) { close(); render(); }
    } }, t('save')),
    !isNew ? el('button', { class: 'btn danger', onclick: async () => { if (confirm(L('أرشفة هذه الجهة؟', 'Archive?'))) { await tool('contact_update', { contact: c.id, status: 'archived' }); close(); render(); } } }, L('أرشفة', 'Archive')) : null]);
}

async function meetingForm() {
  const wss = await api('GET', '/api/biz/workspaces');
  const title = el('input', { placeholder: L('العنوان', 'Title') }), when = el('input', { type: 'datetime-local' });
  const att = el('input', { placeholder: L('الحضور', 'Attendees') }), agenda = el('textarea', { placeholder: L('جدول الأعمال — بند في كل سطر', 'Agenda') });
  const ws = sel([['', '—'], ...wss.map((w) => [w.id, w.name])]);
  modal(L('اجتماع جديد', 'New meeting'), el('div', { class: 'grid' }, title, field(L('الموعد', 'When'), when), att, field(L('الجهة', 'Company'), ws), agenda,
    el('div', { class: 'small muted' }, L('سأذكّرك قبله بنصف ساعة. لا أرسل دعوات لأحد.', 'Reminder 30 min before. No invites are sent.'))), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { if (!title.value.trim()) return;
      const r = await tool('meeting_add', { title: title.value, when: when.value || undefined, attendees: att.value, agenda: agenda.value, workspace: ws.value || undefined }); if (r.ok) { close(); render(); } } }, t('add'))]);
}

function minutesForm(m) {
  const minutes = el('textarea', { rows: 6, placeholder: L('الصق ملاحظات الاجتماع أو المحضر كما هو…', 'Paste notes…') });
  const dec = el('textarea', { rows: 3, placeholder: L('القرارات — قرار في كل سطر', 'Decisions — one per line') });
  const acts = el('textarea', { rows: 4, placeholder: L('الإجراءات — سطر لكل إجراء: المهمة | المسؤول | 2026-10-20', 'Actions — task | owner | YYYY-MM-DD') });
  modal(L('محضر: ', 'Minutes: ') + m.title, el('div', { class: 'grid' }, minutes, dec, acts,
    el('div', { class: 'small muted' }, L('أو اضغط «استخرج بالذكاء»: يقرأ رفيق المحضر ويستخرج القرارات والمهام بنفسه.', 'Or let Rafiq extract them.'))), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const actions = acts.value.split('\n').map((l) => l.split('|').map((s) => s.trim())).filter((p) => p[0]).map(([title, owner, due]) => ({ title, owner, due: due ? (due.length === 10 ? due + 'T09:00' : due) : undefined }));
      const r = await tool('meeting_record', { meeting: m.id, minutes: minutes.value, decisions: dec.value.split('\n').map((s) => s.trim()).filter(Boolean), actions });
      if (r.ok) { close(); render(); } } }, L('حفظ وتحويل لمهام', 'Save & create tasks')),
    el('button', { class: 'btn', onclick: async () => { if (!minutes.value.trim()) { toast(L('الصق المحضر أولًا', 'Paste minutes first')); return; }
      close(); toast(t('thinking'));
      const r = await api('POST', '/api/chat', { text: `هذا محضر الاجتماع رقم ${m.id} «${m.title}». استخرج القرارات وكل إجراء مطلوب مع المسؤول والموعد، واحفظها بأداة meeting_record:\n\n${minutes.value}` });
      toast(r.text.slice(0, 160)); render(); } }, L('✨ استخرج بالذكاء', '✨ Extract with AI'))]);
}
