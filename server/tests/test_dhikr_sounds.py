from unittest.mock import AsyncMock
import pytest
from harith.db import now_iso

@pytest.mark.asyncio
async def test_dhikr_uses_sync_and_distinct_notice_kinds(app, user):
    app.notify = AsyncMock(return_value={'inbox': True})
    for slot in range(5):
        await app.scheduler.handlers['dhikr']({'id': 9900+slot, 'user_id': user['id'], 'run_at': now_iso()}, {'slot': slot})
    calls = app.notify.call_args_list
    assert {c.kwargs['kind'] for c in calls} == {'dhikr_salawat', 'dhikr_istighfar'}
    for call in calls:
        assert call.kwargs['push_data'] == {'t': 'sync'}
        assert ('أستغفر' in call.args[1]) == (call.kwargs['kind'] == 'dhikr_istighfar')

@pytest.mark.asyncio
async def test_disabled_dhikr_stays_silent(app, user):
    app.db.set_setting(user['id'], 'dhikr_enabled', False)
    app.notify = AsyncMock()
    await app.scheduler.handlers['dhikr']({'id': 99, 'user_id': user['id'], 'run_at': now_iso()}, {'slot': 0})
    app.notify.assert_not_called()
