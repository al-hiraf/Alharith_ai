/* إدارة المشاريع في لوحة رفيق: قوالب، لوحة كانبان بالسحب والإفلات، جدول زمني (Gantt)، مراحل، صحة المشروع، عبء الفريق. */
'use strict';

const HEALTH_TONE = { green: 'ok', amber: 'warn', red: 'bad' };
const PRIO_AR = { high: 'عالية', normal: 'عادية', low: 'منخفضة' };

function healthChip(level, label) { return el('span', { class: 'chip ' + (HEALTH_TONE[level] || '') }, label || level || '—'); }

VIEWS.projects = async function () {
  if (state.project) return pmProjectView(state.project);
  const [rows, work, mine] = await Promise.all([api('GET', '/api/projects'), api('GET', '/api/workload'), api('GET', '/api/team/my-tasks')]);
  const grid = el('div', { class: 'grid grid-3' }, ...rows.map((p) => el('button', { class: 'card ws-card', onclick: () => { state.project = p.id; state.pmTab = 'board'; render(); } },
    el('div', { class: 'section-head', style: 'margin:0 0 6px' }, el('h3', {}, p.name), healthChip(p.health, p.health_ar)),
    p.shared ? el('div', { class: 'small gold' }, L('👥 فريق — صاحب المشروع: ', '👥 Team — owner: ') + (p.owner_name || ''))
      : p.members ? el('div', { class: 'small muted' }, L(`👥 ${arNum(p.members)} في الفريق`, `👥 ${p.members} members`)) : null,
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
    mine.length ? el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, L('مهامي في الفريق', 'My team tasks'))),
      el('div', { class: 'list' }, ...mine.map((x) => el('button', { class: 'row row-btn', type: 'button', onclick: () => memberTaskDialog(x) },
        el('div', { class: 'grow title' }, x.title, el('div', { class: 'meta' + (x.overdue ? ' bad-text' : '') },
          [x.project, x.column_ar, x.due && '⏰ ' + x.due].filter(Boolean).join(' · '))))))) : null,
    el('section', { class: 'section' }, grid),
    work.length ? el('section', { class: 'section' }, el('div', { class: 'section-head' }, el('h2', {}, L('عبء العمل على الفريق', 'Team workload'))), wl) : null);
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
  const [d, hw, tl, team] = await Promise.all([api('GET', `/api/projects/${id}`), api('GET', `/api/projects/${id}/health`), api('GET', `/api/projects/${id}/timeline`),
    api('GET', `/api/projects/${id}/team`)]);
  const h = hw.health;
  const owner = d.role !== 'member';
  state.pmTeam = team;
  const TABS = [['board', L('اللوحة', 'Board')], ['timeline', L('الجدول الزمني', 'Timeline')]].concat(
    owner ? [['list', L('القائمة', 'List')], ['log', L('السجل', 'Log')]] : [], [['team', L(`الفريق (${arNum(team.members.length)})`, `Team (${team.members.length})`)]]);
  const cur = TABS.some(([k]) => k === tab) ? tab : 'board';
  const tabs = el('div', { class: 'tabs' }, ...TABS.map(([k, l]) =>
    el('button', { class: cur === k ? 'active' : '', onclick: () => { state.pmTab = k; render(); } }, l)));
  let body;
  if (cur === 'board') body = await boardView(id, tl, team);
  else if (cur === 'timeline') body = ganttView(tl);
  else if (cur === 'list') body = listView(id, d, tl);
  else if (cur === 'team') body = teamView(id, team);
  else body = logView(id, d);
  const p = d.project;
  return el('div', {},
    el('button', { class: 'btn ghost', onclick: () => { state.project = null; render(); } }, L('→ كل المشاريع', '← All projects')),
    head(p.name, [p.goal, p.owner && L('المدير: ', 'Owner: ') + p.owner, !owner && p.owner_name && L('صاحب المشروع: ', 'Owner: ') + p.owner_name,
      p.start_on && p.end_on && `${p.start_on} ← ${p.end_on}`].filter(Boolean).join(' · '),
      owner ? el('button', { class: 'btn', onclick: () => projectEdit(p) }, t('edit')) : el('span', { class: 'chip' }, L('عضو في الفريق', 'Team member'))),
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
async function boardView(id, tl, team) {
  const b = await api('GET', `/api/projects/${id}/board`);
  const order = ['todo', 'doing', 'waiting', 'done'];
  const ms = Object.fromEntries(tl.milestones.map((m) => [m.id, m.title]));
  const owner = b.role !== 'member';
  const canEdit = (x) => owner || x.assignee_user_id === b.me;
  const move = async (tid, col) => {
    if (owner) await tool('task_move', { task: tid, column: col });
    else await api('POST', `/api/team/tasks/${tid}/move`, { column: col });
    render();
  };
  const quick = el('input', { placeholder: L('مهمة جديدة في «للتنفيذ»…', 'New task…') });
  const addForm = el('form', { class: 'form-row', style: 'margin-bottom:12px', onsubmit: async (e) => { e.preventDefault(); if (!quick.value.trim()) return;
    await api('POST', '/api/tasks', { title: quick.value, project: String(id) }); render(); } }, quick, el('button', { class: 'btn primary', type: 'submit' }, t('add')));
  const board = el('div', { class: 'kanban' }, ...b.columns.map((c, ci) => {
    const col = el('div', { class: 'kcol', ondragover: (e) => { e.preventDefault(); col.classList.add('over'); }, ondragleave: () => col.classList.remove('over'),
      ondrop: (e) => { e.preventDefault(); col.classList.remove('over'); const tid = e.dataTransfer.getData('text/plain'); if (tid) move(Number(tid), c.id); } },
      el('div', { class: 'khead' }, el('b', {}, c.title), el('span', { class: 'chip' }, arNum(c.tasks.length))),
      ...c.tasks.map((x) => el('div', { class: 'kcard' + (x.overdue ? ' late' : '') + (x.blocked_by ? ' blocked' : '') + (x.assignee_user_id && x.assignee_user_id === b.me ? ' mine' : '') + (canEdit(x) ? '' : ' readonly'),
        draggable: canEdit(x) ? 'true' : 'false',
        ondragstart: (e) => e.dataTransfer.setData('text/plain', String(x.id)),
        onclick: (e) => { if (e.target.closest('.kmove')) return; if (owner) taskDialog(x, id, tl); else if (canEdit(x)) memberTaskDialog(x); } },
        el('div', { class: 'title' }, x.title),
        el('div', { class: 'meta' }, [x.assignee && '👤 ' + x.assignee, x.due && '⏰ ' + x.due.split(' ').slice(-2).join(' '), ms[x.milestone_id]].filter(Boolean).join(' · ')),
        x.tags.length ? el('div', { class: 'ktags' }, ...x.tags.map((g) => el('span', { class: 'chip' }, g))) : null,
        x.blocked_by ? el('div', { class: 'small warn-text' }, L('⛓ ينتظر: ', '⛓ Waits for: ') + x.blocked_by.title) : null,
        x.estimate_min ? el('div', { class: 'small muted' }, L(`⏱ ${arNum(Math.round(x.spent_min / 6) / 10)} من ${arNum(Math.round(x.estimate_min / 6) / 10)} س`, `⏱ ${x.spent_min}/${x.estimate_min} min`)) : null,
        canEdit(x) ? el('div', { class: 'kmove' }, ci > 0 ? el('button', { class: 'icon-btn', title: L('إلى «', 'To ') + b.columns[ci - 1].title + L('»', ''), onclick: () => move(x.id, order[ci - 1]) }, '→') : el('span'),
          ci < 3 ? el('button', { class: 'icon-btn', title: L('إلى «', 'To ') + b.columns[ci + 1].title + L('»', ''), onclick: () => move(x.id, order[ci + 1]) }, '←') : el('span')) : null)));
    return col;
  }));
  const hint = owner ? null : el('p', { class: 'small muted' }, L('مهامك محاطة بالذهبي — تنقلها بين الأعمدة وتسجل وقتك. بقية المهام للاطلاع.', 'Your tasks are outlined in gold.'));
  return el('div', {}, owner ? addForm : hint, board);
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
  const title = el('input', { value: x.title }), assignee = el('input', { value: x.assignee || '', placeholder: L('المسؤول (اختر من الفريق أو اكتب اسمًا)', 'Assignee'), list: 'pm-team-list' });
  const teamList = el('datalist', { id: 'pm-team-list' }, ...((state.pmTeam && state.pmTeam.members) || []).map((m) => el('option', { value: m.name })));
  const toLocal = (v) => (v ? v.split(' ').slice(-2).join('T') : '');
  const start = el('input', { type: 'datetime-local', value: toLocal(x.start) }), due = el('input', { type: 'datetime-local', value: toLocal(x.due) });
  const est = el('input', { type: 'number', step: '0.5', min: 0, value: x.estimate_min ? x.estimate_min / 60 : '', placeholder: L('ساعات', 'hours') });
  const tags = el('input', { value: (x.tags || []).join('، '), placeholder: L('وسوم، مفصولة بفواصل', 'tags') });
  const pr = sel(Object.entries(PRIO_AR), x.priority);
  const ms = sel([['', '—'], ...tl.milestones.map((m) => [m.id, m.title])], x.milestone_id || '');
  const dep = sel([['', L('— لا تنتظر شيئًا —', '— none —')], ...tl.tasks.filter((k) => k.id !== x.id).map((k) => [k.id, k.title])], x.depends_on || '');
  const logH = el('input', { type: 'number', step: '0.25', min: 0, placeholder: L('ساعات عمل الآن', 'hours worked') });
  const notes = el('textarea', {}, x.notes || '');
  modal(x.title, el('div', { class: 'grid' }, field(t('title'), title), teamList,
    el('div', { class: 'form-row' }, field(L('المسؤول', 'Assignee'), assignee), field(t('priority'), pr)),
    el('div', { class: 'small muted' }, L('إن كان المسؤول عضوًا في فريق المشروع تصله المهمة وإشعارها في تطبيقه.', 'Team members get the task in their app.')),
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

// ——— العضو: نافذة مهمته (نقلها وتسجيل وقته)
function memberTaskDialog(x) {
  const COLS = [['todo', 'للتنفيذ'], ['doing', 'قيد التنفيذ'], ['waiting', 'بانتظار'], ['done', 'مكتملة']];
  const col = sel(COLS, x.column), logH = el('input', { type: 'number', step: '0.25', min: 0, placeholder: L('ساعات عمل الآن', 'hours worked') });
  modal(x.title, el('div', { class: 'grid' },
    el('div', { class: 'small muted' }, [x.due && L('الموعد: ', 'Due: ') + x.due, x.start && L('البداية: ', 'Start: ') + x.start].filter(Boolean).join(' · ') || '—'),
    x.notes ? el('p', {}, x.notes) : null,
    x.blocked_by ? el('div', { class: 'small warn-text' }, L('⛓ تنتظر: ', '⛓ Waits for: ') + x.blocked_by.title) : null,
    el('div', { class: 'form-row' }, field(L('الحالة', 'Status'), col), field(L('تسجيل وقت', 'Log time'), logH)),
    el('div', { class: 'small muted' }, L(`وقتك المسجل: ${arNum(Math.round((x.spent_min || 0) / 6) / 10)} ساعة`, `Spent: ${x.spent_min || 0} min`))), (close) => [
    el('button', { class: 'btn primary', onclick: async () => {
      if (logH.value) await api('POST', `/api/team/tasks/${x.id}/log`, { hours: Number(logH.value) });
      if (col.value !== x.column) await api('POST', `/api/team/tasks/${x.id}/move`, { column: col.value });
      close(); render(); } }, t('save'))]);
}

// ——— تبويب الفريق: الأعضاء، الدعوة، الإضافة، النشاط
function teamView(id, team) {
  const owner = team.role === 'owner';
  const rows = team.members.map((m) => el('tr', {},
    el('td', {}, m.name, el('div', { class: 'small muted', dir: 'ltr' }, '@' + m.username)),
    el('td', {}, el('span', { class: 'chip' + (m.role === 'owner' ? ' ok' : '') }, m.role_ar)),
    el('td', { class: 'num' }, arNum(m.open)), el('td', { class: 'num ' + (m.overdue ? 'bad-text' : '') }, arNum(m.overdue)),
    el('td', {}, owner && m.role !== 'owner' ? el('button', { class: 'btn sm ghost', onclick: async () => {
      if (!confirm(L(`إزالة ${m.name} من الفريق؟ مهامه تبقى لكن تُفك عن حسابه.`, `Remove ${m.name}?`))) return;
      await api('DELETE', `/api/projects/${id}/members/${m.user_id}`); render(); } }, L('إزالة', 'Remove'))
      : !owner && m.user_id === team.me ? el('button', { class: 'btn sm ghost', onclick: async () => {
        if (!confirm(L('مغادرة فريق هذا المشروع؟', 'Leave this team?'))) return;
        await api('DELETE', `/api/projects/${id}/members/${m.user_id}`); state.project = null; render(); } }, L('مغادرة', 'Leave')) : null)));
  const table = el('div', { class: 'table-wrap card' }, el('table', {},
    el('thead', {}, el('tr', {}, ...[L('العضو', 'Member'), L('الدور', 'Role'), L('مفتوحة', 'Open'), L('متأخرة', 'Overdue'), ''].map((h) => el('th', {}, h)))),
    el('tbody', {}, ...rows)));
  const uname = el('input', { placeholder: L('اسم مستخدم موجود على الخادم', 'existing username'), dir: 'ltr' });
  const actions = owner ? el('div', { class: 'card', style: 'margin:12px 0' },
    el('h3', {}, L('أضف أعضاء', 'Add members')),
    el('p', { class: 'small muted' }, L('أرسل رابط الدعوة لأي شخص: يفتحه وينشئ حسابه وينضم مباشرة (صالح 7 أيام، حتى 10 أشخاص). العضو يرى المشروع كاملًا ويعدّل مهامه فقط، ولا يرى المالية.',
      'Share the invite link; members see the project and edit their own tasks only.')),
    el('div', { class: 'form-row' }, el('button', { class: 'btn primary', onclick: () => inviteDialog(id) }, L('🔗 رابط دعوة', '🔗 Invite link'))),
    el('form', { class: 'form-row', style: 'margin-top:10px', onsubmit: async (e) => { e.preventDefault(); if (!uname.value.trim()) return;
      const r = await api('POST', `/api/projects/${id}/members`, { username: uname.value.trim() }); toast(r.message); render(); } },
      uname, el('button', { class: 'btn', type: 'submit' }, L('إضافة', 'Add')))) : null;
  const acts = team.activity.length ? el('div', { class: 'list' }, ...team.activity.map((a) => el('div', { class: 'row' },
    el('div', { class: 'grow' }, el('b', {}, a.actor), ' ', a.action, el('div', { class: 'meta' }, a.when)))))
    : empty(L('لا نشاط بعد.', 'No activity yet.'));
  return el('div', {}, actions, table, el('h3', { style: 'margin-top:16px' }, L('نشاط المشروع', 'Activity')), acts);
}

async function inviteDialog(id) {
  const inv = await api('POST', `/api/projects/${id}/invite`);
  const link = el('input', { value: inv.link, readonly: true, dir: 'ltr', onclick: (e) => e.target.select() });
  const msg = L(`انضم لفريق مشروعي في رفيق: ${inv.link}`, `Join my project team on Rafiq: ${inv.link}`);
  modal(L('رابط دعوة الفريق', 'Team invite link'), el('div', { class: 'grid' }, link,
    el('div', { class: 'small muted' }, L(`الرمز: ${inv.code} · صالح حتى ${inv.expires}`, `Code ${inv.code} · until ${inv.expires}`)),
    inv.local_only ? el('div', { class: 'small warn-text' }, L('تنبيه: الوصول من الإنترنت غير مفعّل، فالرابط يعمل فقط على نفس الشبكة. فعّله من أمر التثبيت.', 'Internet access is off; link works locally only.')) : null,
    el('div', { class: 'small muted' }, L('في تطبيق رفيق: المشاريع ← «انضم لفريق» ثم الصق الرابط.', 'In the app: Projects → Join a team.'))), (close) => [
    el('button', { class: 'btn primary', onclick: async () => { try { await navigator.clipboard.writeText(msg); toast(L('نُسخ — أرسله لعضو الفريق', 'Copied')); } catch (e) { link.select(); } } }, L('نسخ الرسالة', 'Copy message')),
    el('a', { class: 'btn', href: 'https://wa.me/?text=' + encodeURIComponent(msg), target: '_blank', rel: 'noopener' }, L('واتساب', 'WhatsApp'))]);
}

// ——— صفحة الانضمام (من رابط الدعوة)
async function joinView() {
  const code = state.joinCode;
  let inv = null, err = '';
  try { const r = await fetch(`/api/public/invite/${encodeURIComponent(code)}`); const d = await r.json(); if (r.ok) inv = d; else err = d.error; } catch (e) { err = e.message; }
  const name = el('input', { placeholder: L('اسمك', 'Your name'), autocomplete: 'name' });
  const user = el('input', { dir: 'ltr', autocomplete: 'username', placeholder: 'username', value: state.me ? state.me.username : '' });
  const pass = el('input', { type: 'password', dir: 'ltr', autocomplete: 'new-password', placeholder: L('٨ أحرف على الأقل', 'min 8 chars') });
  const submit = async (e) => {
    e.preventDefault();
    try {
      const d = await api('POST', '/api/public/join', { code, name: name.value, username: user.value, password: pass.value });
      state.joinCode = null; state.me = (await api('GET', '/api/me')).user;
      if (d.project) {
        state.page = 'projects'; state.project = d.project_id; state.pmTab = 'board';
        history.replaceState(null, '', '/#projects'); toast(L(`انضممت إلى «${d.project}»`, `Joined ${d.project}`));
      } else {
        state.page = 'home'; history.replaceState(null, '', '/#home'); toast(L('أهلًا بك في رفيق! حسابك جاهز', 'Welcome to Rafiq'));
      }
      render();
    } catch (x) {}
  };
  return el('div', { class: 'login' }, el('div', { class: 'login-art', role: 'img', 'aria-label': 'زخرفة' }),
    el('form', { class: 'login-form', onsubmit: submit },
      el('img', { src: '/static/logo.svg', alt: 'رفيق', class: 'login-logo' }),
      inv ? el('h1', {}, inv.project ? L(`دعوة للانضمام إلى «${inv.project}»`, `Join "${inv.project}"`) : L('وصلتك دعوة لتجربة رفيق', 'Try Rafiq')) : el('h1', {}, L('رابط الدعوة', 'Invite')),
      inv ? el('p', { class: 'muted' }, inv.project
          ? L(`دعاك ${inv.from} لفريق المشروع. أنشئ حسابك (أو اكتب بيانات حسابك إن كان لديك حساب على هذا الخادم):`, `${inv.from} invited you.`)
          : L(`دعاك ${inv.from}. أنشئ حسابك الخاص، وبياناتك ما يشوفها أحد غيرك:`, `${inv.from} invited you. Create your private account:`))
        : el('p', { class: 'bad-text' }, err || L('الدعوة غير صحيحة', 'Invalid invite')),
      inv ? field(L('الاسم', 'Name'), name) : null, inv ? field(L('اسم المستخدم (بالإنجليزية)', 'Username'), user) : null,
      inv ? field(L('كلمة المرور', 'Password'), pass) : null,
      inv ? el('button', { class: 'btn primary', type: 'submit' }, inv.project ? L('انضم للفريق', 'Join team') : L('أنشئ حسابي', 'Create my account')) : null,
      el('button', { class: 'btn ghost', type: 'button', onclick: () => { state.joinCode = null; render(); } }, L('إلغاء', 'Cancel'))));
}
