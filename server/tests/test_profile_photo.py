import base64
import pytest
from harith.profile_photo import validate_photo
from test_acceptance import client
from test_testers import anon
from test_team import bearer
from harith.security import create_user, new_api_token

PHOTO = 'data:image/jpeg;base64,' + base64.b64encode(b'\xff\xd8\xfffixture\xff\xd9').decode()

def test_validation():
    assert validate_photo('') == ''
    assert validate_photo(PHOTO) == PHOTO
    for bad in [None, {}, 'https://example.com/image.jpg', 'data:image/svg+xml;base64,aaaa', PHOTO + '!', 'x' * 180001, 'data:image/jpeg;base64,YQ==']:
        with pytest.raises(ValueError): validate_photo(bad)

def test_save_remove_and_account_isolation(app, user):
    a = client(app)
    assert a.put('/api/settings', json={'profile_photo': PHOTO}).status_code == 200
    assert a.get('/api/settings').json()['settings']['profile_photo'] == PHOTO
    uid = create_user(app.db, 'photo_other', 'password123', display_name='other')
    token = new_api_token(app.db, uid, 'test')
    b = bearer(app, token)
    assert b.get('/api/settings').json()['settings']['profile_photo'] == ''
    assert anon(app).put('/api/settings', json={'profile_photo': PHOTO}).status_code == 401
    assert a.put('/api/settings', json={'profile_photo': 'javascript:bad'}).status_code == 400
    assert a.get('/api/settings').json()['settings']['profile_photo'] == PHOTO
    assert a.put('/api/settings', json={'profile_photo': ''}).status_code == 200
    assert a.get('/api/settings').json()['settings']['profile_photo'] == ''
