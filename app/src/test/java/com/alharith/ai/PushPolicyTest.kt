package com.alharith.ai

import com.alharith.ai.data.PushPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * منطق الإشعارات الفورية: تدوير الرمز، تبديل الحساب، الخروج، الإيقاظ، عدم التكرار.
 * ملاحظة: كُتبت دون القدرة على تشغيلها محليًا (لا Android SDK عند الكاتب)؛ يؤكدها أول تشغيل لـ CI.
 */
class PushPolicyTest {
    private val now = 10_000_000_000L

    @Test fun tokenRotationTriggersReRegistration() {
        val k1 = PushPolicy.regKey("https://s", "hrt_a", "fcm1")
        val k2 = PushPolicy.regKey("https://s", "hrt_a", "fcm2")
        assertNotEquals(k1, k2)
        assertFalse(PushPolicy.needsRegister(k1, now - 1000, now, k1))
        assertTrue(PushPolicy.needsRegister(k1, now - 1000, now, k2))
    }

    @Test fun accountSwitchChangesKeyAndNeverReusesOldRegistration() {
        val a = PushPolicy.regKey("https://s", "hrt_a", "fcm1")
        val b = PushPolicy.regKey("https://s", "hrt_b", "fcm1")
        assertNotEquals(a, b)
        assertTrue(PushPolicy.needsRegister(a, now - 1000, now, b))
    }

    @Test fun clearedStateAfterLogoutForcesFreshRegistration() {
        val k = PushPolicy.regKey("https://s", "hrt_a", "fcm1")
        assertTrue(PushPolicy.needsRegister("", 0L, now, k)) // ما تُصفّره Push.release()
    }

    @Test fun weeklyRenewal() {
        val k = PushPolicy.regKey("https://s", "hrt_a", "fcm1")
        assertFalse(PushPolicy.needsRegister(k, now - PushPolicy.REREGISTER_MS + 1, now, k))
        assertTrue(PushPolicy.needsRegister(k, now - PushPolicy.REREGISTER_MS, now, k))
    }

    @Test fun wakeOnlyHandledForSyncWhileLinked() {
        assertTrue(PushPolicy.shouldSyncOnWake("sync", true))
        assertFalse(PushPolicy.shouldSyncOnWake("sync", false)) // بعد الخروج/فك الربط
        assertFalse(PushPolicy.shouldSyncOnWake("other", true))
        assertFalse(PushPolicy.shouldSyncOnWake(null, true))
    }

    private fun items(vararg n: Triple<Long, Boolean, String>) = JSONArray().also { a ->
        n.forEach { a.put(JSONObject().put("id", it.first).put("read", it.second).put("content", it.third)) }
    }

    @Test fun wakeAndPollingDeliverTheSameNoticeOnce() {
        val all = items(Triple(5, false, "a"), Triple(6, false, "b"))
        val first = PushPolicy.selectNotices(all, 4, false)      // الإيقاظ يجلب أولًا
        assertEquals(listOf(5L, 6L), first.fresh.map { it.getLong("id") })
        val second = PushPolicy.selectNotices(all, first.last, false) // ثم الاستطلاع الدوري بالمؤشر المتقدم
        assertTrue(second.fresh.isEmpty())
        assertEquals(6L, second.last)
    }

    @Test fun firstLinkAndReadNoticesAreNotShownButCursorAdvances() {
        val all = items(Triple(1, false, "old"), Triple(2, true, "read"))
        val r = PushPolicy.selectNotices(all, -1, true)
        assertTrue(r.fresh.isEmpty()); assertEquals(2L, r.last)
        val r2 = PushPolicy.selectNotices(items(Triple(3, true, "x"), Triple(4, false, "y")), 2, false)
        assertEquals(listOf(4L), r2.fresh.map { it.getLong("id") })
    }
}
