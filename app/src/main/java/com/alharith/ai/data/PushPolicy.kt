package com.alharith.ai.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** منطق الإشعارات الفورية الخالص (بلا Android) ليُختبر بوحدات JVM في CI. */
object PushPolicy {
    const val REREGISTER_MS = 7L * 24 * 3600 * 1000
    const val MAX_SHOWN = 5

    /** مفتاح تسجيل: يتغير بتغير الخادم أو الحساب (مفتاحه) أو رمز FCM، فيُعاد التسجيل تلقائيًا */
    fun regKey(serverUrl: String, serverToken: String, fcmToken: String): String =
        MessageDigest.getInstance("SHA-256").digest("$serverUrl|$serverToken|$fcmToken".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun needsRegister(storedKey: String, storedAt: Long, now: Long, newKey: String): Boolean =
        storedKey.isBlank() || storedKey != newKey || now - storedAt >= REREGISTER_MS

    /** رسالة الإيقاظ تُعالج فقط إن كانت من نوع sync والحساب مربوط الآن (بعد الخروج لا شيء يُجلب) */
    fun shouldSyncOnWake(dataType: String?, ready: Boolean): Boolean = dataType == "sync" && ready

    class Notices(val fresh: List<JSONObject>, val last: Long)

    /** اختيار الإشعارات الجديدة: لا تكرار (id <= last)، أول ربط/المقروء يُتخطى، ويتقدم المؤشر دائمًا */
    fun selectNotices(items: JSONArray, lastNoticeId: Long, first: Boolean): Notices {
        var last = lastNoticeId
        val fresh = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            val n = items.getJSONObject(i)
            if (n.optLong("id") <= last) continue
            last = maxOf(last, n.optLong("id"))
            if (first || n.optBoolean("read")) continue
            fresh += n
        }
        return Notices(fresh, last)
    }
}
