import pytest
@pytest.fixture(autouse=True)
def personal_fixture(app, user):
    app.db.execute("UPDATE users SET id=54 WHERE id=?", (user["id"],))
    user["id"] = 54

from test_acceptance import client
from harith.task_repeat import next_due

def test_repeat_create_complete_once(app):
    c=client(app)
    r=c.post('/api/tasks',json={'title':'fixture recurring','due':'2026-10-31T08:00','repeat':'daily'})
    assert r.status_code == 200, r.text
    tid=r.json()['task']['id']
    assert c.patch('/api/tasks/'+str(tid),json={'status':'done'}).status_code==200
    assert c.patch('/api/tasks/'+str(tid),json={'status':'done'}).status_code==200
    rows=c.get('/api/tasks').json()
    copies=[x for x in rows if x['title']=='fixture recurring']
    assert len(copies)==1
    assert copies[0]['repeat']=='daily'
    assert '2026-11-01' in copies[0]['due']

def test_repeat_dates_and_validation(app):
    assert next_due('2026-01-31T08:00:00Z','monthly').startswith('2026-02-28')
    assert next_due('2028-02-29T08:00:00Z','yearly').startswith('2029-02-28')
    c=client(app)
    assert c.post('/api/tasks',json={'title':'bad','repeat':'daily'}).status_code==400
    assert c.post('/api/tasks',json={'title':'bad','repeat':'wrong','due':'2026-11-01'}).status_code==400

def test_scheduled_alarm_label_private(app, user):
    c=client(app)
    app.reminders.create(user['id'],'الخروج','2026-10-31T05:00:00Z','daily_except_friday')
    rows=c.get('/api/jobs').json()
    assert any(x['kind']=='reminder' and x['label']=='الخروج' for x in rows)

def test_personal_repeat_denied_to_other_account(app):
    from harith.security import create_user, new_api_token
    from test_team import bearer
    uid=create_user(app.db,'repeat_other','password123')
    b=bearer(app,new_api_token(app.db,uid,'fixture'))
    assert b.post('/api/tasks',json={'title':'normal still works'}).status_code==200
    assert b.post('/api/tasks',json={'title':'private','due':'2026-11-01','repeat':'daily'}).status_code==403
    assert all('repeat' not in x for x in b.get('/api/tasks').json())

def test_empty_cache_full_snapshot(app, user):
    c=client(app)
    for i in range(3): c.post('/api/tasks',json={'title':f'fixture open {i}'})
    # A stale/future cursor excludes preexisting rows; omitting it recovers intact rows.
    assert len(c.post('/api/sync',json={'tasks':[],'since':'2099-01-01T00:00:00Z'}).json()['tasks'])==3
    recovered=c.post('/api/sync',json={'tasks':[]}).json()['tasks']
    assert len(recovered)==3 and all(x['status']=='open' for x in recovered)

def test_personal_sync_includes_pending_alarms_only(app, user):
    c=client(app)
    app.reminders.create(54,'الخروج','2026-10-31T05:00:00Z','daily_except_friday')
    rows=c.post('/api/sync',json={}).json()['reminders']
    assert len(rows)==1 and rows[0]['recur']=='daily_except_friday' and rows[0]['text']=='الخروج'
    from harith.security import create_user,new_api_token
    from test_team import bearer
    uid=create_user(app.db,'alarm_other','password123')
    assert 'reminders' not in bearer(app,new_api_token(app.db,uid,'fixture')).post('/api/sync',json={}).json()
