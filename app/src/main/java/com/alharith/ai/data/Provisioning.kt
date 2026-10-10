package com.alharith.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** بدء رفيق بلا إعداد: يأخذ الجهاز مفتاحًا محدودًا من خادم رفيق (مفتاح الإدارة لا يغادر الخادم). */
object Provisioning {
    data class Info(val available: Boolean, val inviteRequired: Boolean)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    /** رابط الخادم: مباشر، أو يُقرأ من ملف .txt ينشر فيه الخادم رابط نفقه الحالي */
    internal fun base(): String? {
        val u = Prefs.provisionUrl
        if (!u.startsWith("https://") && !u.startsWith("http://127.0.0.1")) return null
        if (!u.endsWith(".txt")) return u
        return runCatching {
            http.newCall(Request.Builder().url(u).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.trim()?.lineSequence()?.firstOrNull()?.trim()?.trimEnd('/')
            }
        }.getOrNull()?.takeIf { it.startsWith("https://") }
    }

    suspend fun info(): Info = withContext(Dispatchers.IO) {
        val url = base() ?: return@withContext Info(false, false)
        runCatching {
            http.newCall(Request.Builder().url("$url/api/public/info").build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                Info(o.optBoolean("provisioning"), o.optBoolean("invite_required"))
            }
        }.getOrDefault(Info(false, false))
    }

    /** يسجل الجهاز ويضبط المفتاح والنموذج. يعيد null عند النجاح أو رسالة خطأ. */
    suspend fun register(invite: String = ""): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", Prefs.deviceId).put("name", Prefs.userName)
            .put("invite", invite).put("app_version", com.alharith.ai.BuildConfig.VERSION_NAME)
        val url = base() ?: return@withContext "خادم رفيق غير متاح الآن، حاول بعد قليل"
        try {
            http.newCall(
                Request.Builder().url("$url/api/public/register")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            ).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) return@withContext o.optString("error").ifBlank { "تعذّر التسجيل (${r.code})" }
                val key = o.optString("key")
                if (!key.startsWith("sk-")) return@withContext "رد غير متوقع من الخادم"
                val provider = o.optString("provider", "openrouter")
                Prefs.setKeyFor(provider, key)
                o.optString("model").takeIf { it.isNotBlank() }?.let { Prefs.setModelFor(provider, it) }
                Prefs.provider = provider
                ActivityLog.record("الإعداد", "تسجيل تلقائي", "مفتاح من خادم رفيق",
                    "حد شهري ${o.optDouble("limit_usd")}$", ok = true)
                null
            }
        } catch (e: Exception) {
            "تعذّر الوصول لخادم رفيق: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
