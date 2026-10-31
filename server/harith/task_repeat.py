"""Additive recurring web tasks using existing per-user settings, no schema changes."""
import calendar
from datetime import timedelta
from .db import iso, parse_iso, now_iso

REPEATS = ('none', 'daily', 'weekly', 'monthly', 'yearly')

def validate(value):
    if value not in REPEATS:
        raise ValueError('التكرار غير صالح')
    return value

def next_due(due, repeat):
    d = parse_iso(due)
    if repeat == 'daily': return iso(d + timedelta(days=1))
    if repeat == 'weekly': return iso(d + timedelta(days=7))
    year, month = d.year, d.month
    if repeat == 'monthly':
        month += 1
        if month == 13: year, month = year + 1, 1
    elif repeat == 'yearly': year += 1
    else: return due
    return iso(d.replace(year=year, month=month, day=min(d.day, calendar.monthrange(year, month)[1])))

def complete_next(app, uid, tid, previous_status):
    db = app.db
    repeat = db.get_setting(uid, 'task_repeat:' + str(tid), 'none')
    row = db.one('SELECT * FROM tasks WHERE id=? AND user_id=?', (tid,uid))
    if not row or row['status'] != 'done' or previous_status == 'done' or repeat == 'none': return
    key = 'task_repeat_next:' + str(tid)
    with db.tx():
        if db.get_setting(uid, key): return
        due = next_due(row['due_at'], repeat)
        nid = db.insert('INSERT INTO tasks(user_id,project_id,title,notes,priority,status,due_at,scope,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                        (uid,row['project_id'],row['title'],row['notes'],row['priority'],'open',due,row['scope'],'recurring',now_iso(),now_iso()))
        db.set_setting(uid,'task_repeat:'+str(nid),repeat)
        db.set_setting(uid,key,nid)
