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
