/* إدارة المشاريع في لوحة رفيق: قوالب، لوحة كانبان بالسحب والإفلات، جدول زمني (Gantt)، مراحل، صحة المشروع، عبء الفريق. */
'use strict';

const HEALTH_TONE = { green: 'ok', amber: 'warn', red: 'bad' };
const PRIO_AR = { high: 'عالية', normal: 'عادية', low: 'منخفضة' };

function healthChip(level, label) { return el('span', { class: 'chip ' + (HEALTH_TONE[level] || '') }, label || level || '—'); }

VIEWS.projects = async function () {
  if (state.project) return pmProjectView(state.project);
  const [rows, work] = await Promise.all([api('GET', '/api/projects'), api('GET', '/api/workload')]);
  const grid = el('div', { class: 'grid grid-3' }, ...rows.map((p) => el('button', { class: 'card ws-card', onclick: () => { state.project = p.id; state.pmTab = 'board'; render(); } },
    el('div', { class: 'section-head', style: 'margin:0 0 6px' }, el('h3', {}, p.name), healthChip(p.health, p.health_ar)),
    p.workspace ? el('div', { class: 'small muted' }, p.workspace) : null,
    bar(p.progress ?? 0),
    el('div', { class: 'small muted' }, [`${arNum(p.progress ?? 0)}٪`, `${arNum(p.tasks_done)}/${arNum(p.tasks_total)}`,
      p.start_on && p.end_on ? `${p.start_on} ← ${p.end_on}` : '', p.owner].filter(Boolean).join(' · ')))));
  if (!rows.length) grid.append(empty(L('لا مشاريع بعد. ابدأ من قالب جاهز: مراحل ومهام ومواعيد تُنشأ تلقائيًا.', 'No projects yet.')));
  const wl = work.length ? el('div', { class: 'table-wrap card' }, el('table', {},
    el('thead', {}, el('tr', {}, ...[L('المسؤول', 'Assignee'), L('مفتوحة', 'Open'), L('متأخرة', 'Overdue'), L('التقدير (س)', 'Est. h'), L('الفعلي (س)', 'Spent h')].map((h) => el('th', {}, h)))),
    el('tbody', {}, ...work.map((w) => el('tr', {}, el('td', {}, w.assignee), el('td', { class: 'num' }, arNum(w.open)),
      el('td', { class: 'num ' + (w.overdue ? 'bad-text' : '') }, arNum(w.overdue)), el('td', { class: 'num' }, arNum(w.estimate_hours)), el('td', { class: 'num' }, arNum(w.spent_hours))))))) : empty('—');
  return el('div', {}, head(t('projects'), L('مراحل، لوحة مهام، جدول زمني، ومسؤولون — وصحة كل مشروع بنظرة.', 'Phases, board, timeline, owners.'),
    el('button', { class: 'btn primary', onclick: templateDialog }, L('+ مشروع من قالب', '+ From template')),
    el('button', { class: 'btn', onclick: blankProjectDialog }, L('+ مشروع فارغ', '+ Blank'))),
    el('section', { class: 'section' }, grid),
    el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, L('عبء العمل على الفريق', 'Team workload'))), wl));
};

async function templateDialog() {
  const [tpls, wss] = await Promise.all([api('GET', '/api/pm/templates'), api('GET', '/api/biz/workspaces')]);
  let pick = tpls[0].id;
  const cards = el('div', { class: 'grid', style: 'grid-template-columns:repeat(auto-fill,minmax(180px,1fr))' });
  const draw = () => cards.replaceChildren(...tpls.map((x) => el('button', { class: 'card ws-card' + (x.id === pick ? ' picked' : ''), onclick: () => { pick = x.id; draw(); } },
    el('b', {}, x.name), el('div', { class: 'small muted' }, L(`${arNum(x.phases.length)} مراحل · ${arNum(x.tasks)} مهمة · ${arNum(x.days)} يومًا`, `${x.phases.length} phases`)),
    el('div', { class: 'small' }, x.phases.join(' ← ')))));
  draw();
  const name = el('input', { placeholder: L('اسم المشروع', 'Name') });
  const start = el('input', { type: 'date', value: new Date().toISOString().slice(0, 10) });
  const ws = sel([['', L('— بدون جهة —', '— none —')], ...wss.map((w) => [w.id, w.name])]);
  const budget = el('input', { inputmode: 'decimal', placeholder: L('الميزانية (اختياري)', 'Budget') });
  const owner = el('input', { placeholder: L('المسؤول الافتراضي', 'Default owner') });
  modal(L('مشروع من قالب', 'Project from template'), el('div', { class: 'grid' }, cards, name,
    el('div', { class: 'form-row' }, field(L('تاريخ البداية', 'Start'), start), field(L('الجهة', 'Company'), ws)), el('div', { class: 'form-row' }, budget, owner)), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const r = await tool('project_from_template', { template: pick, name: name.value || undefined, start: start.value, workspace: ws.value || undefined, budget: budget.value || undefined, owner: owner.value || undefined });
      if (r.ok) { close(); state.project = r.data.id; state.pmTab = 'timeline'; render(); }
    } }, L('إنشاء', 'Create'))]);
}

function blankProjectDialog() {
  const name = el('input', { placeholder: L('اسم المشروع', 'Name') }), goal = el('input', { placeholder: L('الهدف', 'Goal') });
  const s = el('input', { type: 'date' }), e = el('input', { type: 'date' }), owner = el('input', { placeholder: L('مدير المشروع', 'Owner') });
  modal(L('مشروع جديد', 'New project'), el('div', { class: 'grid' }, name, goal, el('div', { class: 'form-row' }, field(L('البداية', 'Start'), s), field(L('النهاية', 'End'), e)), owner), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { if (!name.value.trim()) return;
      const r = await api('POST', '/api/projects', { name: name.value, goal: goal.value, start: s.value || undefined, end: e.value || undefined, owner: owner.value });
      close(); state.project = r.project.id; render(); } }, t('add'))]);
}

async function pmProjectView(id) {
  const tab = state.pmTab || 'board';
  const [d, hw, tl] = await Promise.all([api('GET', `/api/projects/${id}`), api('GET', `/api/projects/${id}/health`), api('GET', `/api/projects/${id}/timeline`)]);
  const h = hw.health;
  const tabs = el('div', { class: 'tabs' }, ...[['board', L('اللوحة', 'Board')], ['timeline', L('الجدول الزمني', 'Timeline')], ['list', L('القائمة', 'List')], ['log', L('السجل', 'Log')]].map(([k, l]) =>
    el('button', { class: tab === k ? 'active' : '', onclick: () => { state.pmTab = k; render(); } }, l)));
  let body;
  if (tab === 'board') body = await boardView(id, tl);
  else if (tab === 'timeline') body = ganttView(tl);
  else if (tab === 'list') body = listView(id, d, tl);
  else body = logView(id, d);
  const p = d.project;
  return el('div', {},
    el('button', { class: 'btn ghost', onclick: () => { state.project = null; render(); } }, L('→ كل المشاريع', '← All projects')),
    head(p.name, [p.goal, p.owner && L('المدير: ', 'Owner: ') + p.owner, p.start_on && p.end_on && `${p.start_on} ← ${p.end_on}`].filter(Boolean).join(' · '),
      el('button', { class: 'btn', onclick: () => projectEdit(p) }, t('edit'))),
    el('div', { class: 'card', style: 'display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:12px' }, healthChip(h.level, h.level_ar),
      el('div', { class: 'grow small' }, h.reasons.length ? h.reasons.join(' · ') : L('لا مشكلات مرصودة.', 'No issues detected.'))),
    el('div', { class: 'grid grid-4' }, tile(arNum(h.progress) + '٪', L('الإنجاز', 'Progress')),
      tile(h.time_elapsed == null ? '—' : arNum(h.time_elapsed) + '٪', L('الوقت المنقضي', 'Time elapsed'), h.time_elapsed != null && h.time_elapsed - h.progress >= 15 ? 'alert' : ''),
      tile(arNum(h.overdue), L('متأخرة', 'Overdue'), h.overdue ? 'alert' : ''),
      tile(h.budget_percent == null ? '—' : arNum(h.budget_percent) + '٪', L('من الميزانية', 'Of budget'), (h.budget_percent || 0) > 100 ? 'alert' : '')),
    el('section', { class: 'section' }, tabs, body));
}

function projectEdit(p) {
  const name = el('input', { value: p.name }), goal = el('input', { value: p.goal || '' }), owner = el('input', { value: p.owner || '' });
  const s = el('input', { type: 'date', value: p.start_on || '' }), e = el('input', { type: 'date', value: p.end_on || '' });
  const st = sel([['active', L('نشط', 'Active')], ['paused', L('متوقف', 'Paused')], ['done', L('منتهٍ', 'Done')], ['archived', L('مؤرشف', 'Archived')]], p.status);
  modal(t('edit'), el('div', { class: 'grid' }, field(L('الاسم', 'Name'), name), field(L('الهدف', 'Goal'), goal), field(L('المدير', 'Owner'), owner),
    el('div', { class: 'form-row' }, field(L('البداية', 'Start'), s), field(L('النهاية', 'End'), e)), field(t('status'), st)), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { await api('PATCH', `/api/projects/${p.id}`, { name: name.value, goal: goal.value, owner: owner.value, start_on: s.value, end_on: e.value, status: st.value }); close(); if (st.value === 'archived') state.project = null; render(); } }, t('save'))]);
}

// ——— لوحة كانبان (سحب وإفلات على الحاسوب، وأزرار نقل على الجوال)
async function boardView(id, tl) {
  const b = await api('GET', `/api/projects/${id}/board`);
  const order = ['todo', 'doing', 'waiting', 'done'];
  const ms = Object.fromEntries(tl.milestones.map((m) => [m.id, m.title]));
  const move = async (tid, col) => { await tool('task_move', { task: tid, column: col }); render(); };
  const quick = el('input', { placeholder: L('مهمة جديدة في «للتنفيذ»…', 'New task…') });
  const addForm = el('form', { class: 'form-row', style: 'margin-bottom:12px', onsubmit: async (e) => { e.preventDefault(); if (!quick.value.trim()) return;
    await api('POST', '/api/tasks', { title: quick.value, project: String(id) }); render(); } }, quick, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
  const board = el('div', { class: 'kanban' }, ...b.columns.map((c, ci) => {
    const col = el('div', { class: 'kcol', ondragover: (e) => { e.preventDefault(); col.classList.add('over'); }, ondragleave: () => col.classList.remove('over'),
      ondrop: (e) => { e.preventDefault(); col.classList.remove('over'); const tid = e.dataTransfer.getData('text/plain'); if (tid) move(Number(tid), c.id); } },
      el('div', { class: 'khead' }, el('b', {}, c.title), el('span', { class: 'chip' }, arNum(c.tasks.length))),
      ...c.tasks.map((x) => el('div', { class: 'kcard' + (x.overdue ? ' late' : '') + (x.blocked_by ? ' blocked' : ''), draggable: 'true',
        ondragstart: (e) => e.dataTransfer.setData('text/plain', String(x.id)), onclick: (e) => { if (!e.target.closest('.kmove')) taskDialog(x, id, tl); } },
        el('div', { class: 'title' }, x.title),
        el('div', { class: 'meta' }, [x.assignee && '👤 ' + x.assignee, x.due && '⏰ ' + x.due.split(' ').slice(-2).join(' '), ms[x.milestone_id]].filter(Boolean).join(' · ')),
        x.tags.length ? el('div', { class: 'ktags' }, ...x.tags.map((g) => el('span', { class: 'chip' }, g))) : null,
        x.blocked_by ? el('div', { class: 'small warn-text' }, L('⛓ ينتظر: ', '⛓ Waits for: ') + x.blocked_by.title) : null,
        x.estimate_min ? el('div', { class: 'small muted' }, L(`⏱ ${arNum(Math.round(x.spent_min / 6) / 10)} من ${arNum(Math.round(x.estimate_min / 6) / 10)} س`, `⏱ ${x.spent_min}/${x.estimate_min} min`)) : null,
        el('div', { class: 'kmove' }, ci > 0 ? el('button', { class: 'icon-btn', title: c.title, onclick: () => move(x.id, order[ci - 1]) }, '→') : el('span'),
          ci < 3 ? el('button', { class: 'icon-btn', onclick: () => move(x.id, order[ci + 1]) }, '←') : el('span')))));
    return col;
  }));
  return el('div', {}, addForm, board);
}

// ——— الجدول الزمني (Gantt) — SVG مرسوم من البيانات
function ganttView(tl) {
  if (!tl.milestones.length && !tl.tasks.length) return empty(L('لا مواعيد بعد. أضف تواريخ للمهام أو أنشئ المشروع من قالب.', 'No dates yet.'));
  const D = (s) => new Date(s + 'T00:00:00Z').getTime();
  const [r0, r1] = tl.range;
  const t0 = D(r0) - 2 * 864e5, t1 = D(r1) + 3 * 864e5;
  const W = 1000, LBL = 230, ROW = 26, x = (s) => LBL + (W - LBL - 10) * (D(s) - t0) / (t1 - t0);
  const rows = [];
  const byMs = {};
  tl.tasks.forEach((tk) => { (byMs[tk.milestone_id || 0] = byMs[tk.milestone_id || 0] || []).push(tk); });
  tl.milestones.forEach((m) => { rows.push({ kind: 'm', ...m }); (byMs[m.id] || []).forEach((tk) => rows.push({ kind: 't', ...tk })); });
  (byMs[0] || []).forEach((tk) => rows.push({ kind: 't', ...tk }));
  const H = rows.length * ROW + 40;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`); svg.setAttribute('class', 'gantt'); svg.setAttribute('direction', 'ltr');
  const add = (tag, attrs, text) => { const n = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v); if (text != null) n.textContent = text; svg.append(n); return n; };
  // شبكة الأشهر
  const d = new Date(t0); d.setUTCDate(1);
  while (d.getTime() < t1) {
    const s = d.toISOString().slice(0, 10);
    if (d.getTime() >= t0) { add('line', { x1: x(s), x2: x(s), y1: 18, y2: H, class: 'g-grid' }); add('text', { x: x(s) + 4, y: 13, class: 'g-month' }, s.slice(0, 7)); }
    d.setUTCMonth(d.getUTCMonth() + 1);
  }
  rows.forEach((r, k) => {
    const y = 26 + k * ROW;
    // نص عربي: مع direction=rtl تكون «البداية» هي الطرف الأيمن، فيمتد النص يسارًا من x
    const label = add('text', { x: LBL - (r.kind === 'm' ? 8 : 20), y: y + 13, class: r.kind === 'm' ? 'g-mlabel' : 'g-label', 'text-anchor': 'start', direction: 'rtl' },
      r.title.length > 28 ? r.title.slice(0, 27) + '…' : r.title);
    const s = r.kind === 'm' ? (r.start || r.due) : (r.start || r.end), e = r.kind === 'm' ? (r.due || r.start) : (r.end || r.start);
    if (!s) return;
    const x0 = x(s), x1 = Math.max(x(e), x0 + 4);
    if (r.kind === 'm') {
      add('rect', { x: x0, y: y + 4, width: x1 - x0, height: 14, rx: 3, class: 'g-mbar' + (r.late ? ' late' : '') });
      add('rect', { x: x0, y: y + 4, width: (x1 - x0) * (r.progress / 100), height: 14, rx: 3, class: 'g-mprog' });
    } else {
      add('rect', { x: x0, y: y + 6, width: x1 - x0, height: 10, rx: 5, class: 'g-bar ' + (r.status === 'done' ? 'done' : r.status === 'in_progress' ? 'doing' : '') });
    }
  });
  const today = x(tl.today);
  add('line', { x1: today, x2: today, y1: 18, y2: H, class: 'g-today' });
  add('text', { x: today + 3, y: H - 4, class: 'g-month' }, L('اليوم', 'today'));
  const late = tl.milestones.filter((m) => m.late).length;
  const wrap = el('div', { class: 'gantt-wrap' }, svg);
  // على الشاشات الضيقة: مرّر حتى يظهر «اليوم» مع بقاء أسماء المهام قريبة
  requestAnimationFrame(() => { if (wrap.scrollWidth > wrap.clientWidth) wrap.scrollLeft = Math.max(0, (today / W) * wrap.scrollWidth - wrap.clientWidth * 0.6); });
  return el('div', { class: 'card' }, wrap,
    el('p', { class: 'small muted', style: 'margin:8px 0 0' }, L(`${arNum(tl.milestones.length)} مراحل · ${arNum(tl.tasks.length)} مهمة لها مواعيد`, `${tl.milestones.length} phases`) +
      (late ? L(` · ${arNum(late)} مرحلة متأخرة (بالأحمر)`, ` · ${late} late`) : '') + L(' · الخط الذهبي = اليوم', ' · gold line = today')),
    el('div', { class: 'form-row', style: 'margin-top:10px' }, el('button', { class: 'btn sm', onclick: () => milestoneDialog(tl.project.id) }, L('+ مرحلة', '+ Phase')),
      ...tl.milestones.map((m) => el('button', { class: 'btn sm ghost', onclick: () => milestoneDialog(tl.project.id, m) }, m.title))));
}

function milestoneDialog(pid, m) {
  m = m || {};
  const title = el('input', { value: m.title || '' }), s = el('input', { type: 'date', value: m.start || '' }), d = el('input', { type: 'date', value: m.due || '' });
  const done = el('input', { type: 'checkbox', checked: m.status === 'done' });
  modal(m.id ? m.title : L('مرحلة جديدة', 'New phase'), el('div', { class: 'grid' }, title, el('div', { class: 'form-row' }, field(L('البداية', 'Start'), s), field(L('النهاية', 'Due'), d)),
    m.id ? el('label', { class: 'switch' }, L('مكتملة', 'Done'), done) : null), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const r = m.id ? await tool('milestone_update', { id: m.id, title: title.value, start: s.value, due: d.value, status: done.checked ? 'done' : 'open' })
        : await tool('milestone_add', { project: String(pid), title: title.value, start: s.value || undefined, due: d.value || undefined });
      if (r.ok) { close(); render(); } } }, t('save'))]);
}

// ——— تحرير مهمة كاملة
function taskDialog(x, pid, tl) {
  const title = el('input', { value: x.title }), assignee = el('input', { value: x.assignee || '', placeholder: L('المسؤول', 'Assignee') });
  const toLocal = (v) => (v ? v.split(' ').slice(-2).join('T') : '');
  const start = el('input', { type: 'datetime-local', value: toLocal(x.start) }), due = el('input', { type: 'datetime-local', value: toLocal(x.due) });
  const est = el('input', { type: 'number', step: '0.5', min: 0, value: x.estimate_min ? x.estimate_min / 60 : '', placeholder: L('ساعات', 'hours') });
  const tags = el('input', { value: (x.tags || []).join('، '), placeholder: L('وسوم، مفصولة بفواصل', 'tags') });
  const pr = sel(Object.entries(PRIO_AR), x.priority);
  const ms = sel([['', '—'], ...tl.milestones.map((m) => [m.id, m.title])], x.milestone_id || '');
  const dep = sel([['', L('— لا تنتظر شيئًا —', '— none —')], ...tl.tasks.filter((k) => k.id !== x.id).map((k) => [k.id, k.title])], x.depends_on || '');
  const logH = el('input', { type: 'number', step: '0.25', min: 0, placeholder: L('ساعات عمل الآن', 'hours worked') });
  const notes = el('textarea', {}, x.notes || '');
  modal(x.title, el('div', { class: 'grid' }, field(t('title'), title),
    el('div', { class: 'form-row' }, field(L('المسؤول', 'Assignee'), assignee), field(t('priority'), pr)),
    el('div', { class: 'form-row' }, field(L('البداية', 'Start'), start), field(t('due'), due)),
    el('div', { class: 'form-row' }, field(L('المرحلة', 'Phase'), ms), field(L('تنتظر انتهاء', 'Waits for'), dep)),
    el('div', { class: 'form-row' }, field(L('التقدير (ساعات)', 'Estimate (h)'), est), field(L('تسجيل وقت', 'Log time'), logH)),
    field(L('الوسوم', 'Tags'), tags), notes,
    el('div', { class: 'small muted' }, L(`الوقت الفعلي حتى الآن: ${arNum(Math.round((x.spent_min || 0) / 6) / 10)} ساعة`, `Spent: ${x.spent_min || 0} min`))), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      const r = await api('PATCH', `/api/tasks/${x.id}`, { title: title.value, assignee: assignee.value, priority: pr.value, start: start.value, due: due.value,
        milestone: ms.value, depends_on: dep.value || 0, estimate_hours: est.value === '' ? undefined : Number(est.value), tags: tags.value, notes: notes.value });
      if (logH.value) await tool('log_time', { task: x.id, hours: Number(logH.value) }, true);
      if (r.ok) { close(); render(); } } }, t('save')),
    el('button', { class: 'btn danger', onclick: async () => { if (confirm(L('حذف المهمة؟', 'Delete task?'))) { await api('DELETE', `/api/tasks/${x.id}`); close(); render(); } } }, t('delete'))]);
}

function listView(id, d, tl) {
  const tt = el('input', { placeholder: L('مهمة في المشروع…', 'Task…') });
  const asg = el('input', { placeholder: L('المسؤول', 'Assignee'), style: 'width:140px' });
  const ms = sel([['', L('المرحلة', 'Phase')], ...tl.milestones.map((m) => [m.id, m.title])]);
  return el('div', {}, el('form', { class: 'form-row', style: 'margin:10px 0', onsubmit: async (e) => { e.preventDefault(); if (!tt.value.trim()) return;
    await api('POST', '/api/tasks', { title: tt.value, project: String(id), assignee: asg.value || undefined, milestone: ms.value || undefined }); render(); } },
    tt, asg, ms, el('button', { class: 'btn primary', type: 'submit' }, t('add'))),
    el('div', { class: 'list' }, ...d.open_tasks.map((x) => taskRow(x, render))),
    d.open_tasks.length ? null : empty(t('noTasks')),
    el('div', { class: 'form-row', style: 'margin-top:10px' }, el('button', { class: 'btn sm ghost', onclick: async () => {
      const r = await tool('export_report', { kind: 'tasks', format: 'xlsx' }, true); if (r.ok) location.href = `/api/files/${r.data.file_id}`; } }, '⬇ Excel')));
}

function logView(id, d) {
  const KIND = { decision: 'قرار', risk: 'خطر', blocker: 'عائق', note: 'ملاحظة', meeting: 'اجتماع', contract: 'عقد', deadline: 'موعد نهائي', assignment: 'تكليف', lead: 'فرصة/عميل' };
  const kind = sel(Object.entries(KIND));
  const content = el('input', { placeholder: L('المحتوى', 'Content') }), owner = el('input', { placeholder: L('المسؤول', 'Owner'), style: 'width:140px' });
  const groups = {};
  d.entries.forEach((e) => { (groups[e.kind] = groups[e.kind] || []).push(e); });
  return el('div', {}, el('form', { class: 'form-row card', style: 'margin:10px 0', onsubmit: async (e) => { e.preventDefault(); if (!content.value.trim()) return;
    await api('POST', `/api/projects/${id}/entries`, { kind: kind.value, content: content.value, owner: owner.value }); render(); } },
    kind, content, owner, el('button', { class: 'btn primary', type: 'submit' }, t('add'))),
    ...Object.entries(groups).map(([k, items]) => el('div', { style: 'margin-top:14px' }, el('h3', { class: 'gold' }, KIND[k] || k),
      el('div', { class: 'list' }, ...items.map((e) => el('div', { class: 'row' }, el('div', { class: 'grow title' }, e.content,
        el('div', { class: 'meta' }, [e.owner, e.due_at, e.created_at].filter(Boolean).join(' · ')))))))),
    Object.keys(groups).length ? null : empty(L('لا قرارات أو مخاطر مسجلة.', 'Nothing logged.')));
}
