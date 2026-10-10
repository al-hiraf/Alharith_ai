package com.alharith.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ربط التطبيق بخادم رفيق برمز من 6 أرقام — بلا عنوان ولا مفتاح يدوي.
 * يبحث عن الخادم على نفس الجوال أولًا، ثم رابط النفق المنشور.
 */
object Pairing {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    const val LOCAL = "http://127.0.0.1:8787"

    private fun healthy(base: String): Boolean = runCatching {
        http.newCall(Request.Builder().url("$base/api/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** يجد خادم رفيق: على نفس الجوال، ثم آخر عنوان معروف، ثم رابط النفق المنشور */
    fun discover(): String? {
        if (healthy(LOCAL)) return LOCAL
        Prefs.serverUrl.takeIf { it.startsWith("https://") && healthy(it) }?.let { return it }
        return Provisioning.base()?.takeIf { healthy(it) }
    }

    /** @return null عند النجاح، أو رسالة الخطأ */
    suspend fun pair(code: String): String? = withContext(Dispatchers.IO) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) return@withContext "الرمز 6 أرقام"
        val base = discover() ?: return@withContext "لم أجد خادم رفيق. تأكد أنه يعمل في Termux (اكتب: sv up rafiq) وأن الإنترنت متصل."
        try {
            val body = JSONObject().put("code", digits).put("device_name", Prefs.userName.ifBlank { "جوالي" })
            http.newCall(Request.Builder().url("$base/api/public/pair")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) return@withContext o.optString("error").ifBlank { "تعذّر الربط (${r.code})" }
                val token = o.optString("token")
                if (!token.startsWith("hrt_")) return@withContext "رد غير متوقع من الخادم"
                Prefs.serverUrl = base
                Prefs.serverToken = token
                Prefs.syncCursor = ""
                Prefs.lastPushMs = 0L
                Prefs.sharedBrain = true
                ActivityLog.record("الإعداد", "ربط برمز", "العقل المشترك", "متصل بخادم رفيق كـ ${o.optString("user")}", ok = true)
                SharedBrain.requestSync()
                null
            }
        } catch (e: Exception) {
            "تعذّر الوصول للخادم: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** عند تغيّر رابط النفق (يتغيّر مع كل تشغيل للخادم): ابحث عن الرابط الجديد */
    fun refreshUrl(): Boolean {
        val found = discover() ?: return false
        if (found == Prefs.serverUrl) return false
        Prefs.serverUrl = found
        return true
    }
}
