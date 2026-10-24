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

    fun healthy(base: String): Boolean = runCatching {
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
                if (Prefs.serverToken != token) runCatching { Push.release() }
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

    /**
     * الانضمام لفريق مشروع برابط دعوة (https://…/join/CODE): ينشئ حسابًا على خادم صاحب المشروع
     * ويربط التطبيق به. @return (رسالة الخطأ أو null عند النجاح، رقم المشروع)
     */
    suspend fun join(link: String, name: String, username: String, password: String): Pair<String?, Long> = withContext(Dispatchers.IO) {
        val m = Regex("""(https?://[^\s/]+)/join/([A-Za-z0-9-]+)""").find(link)
        val base: String; val code: String
        if (m != null) { base = m.groupValues[1]; code = m.groupValues[2] }
        else {
            code = link.filter { it.isLetterOrDigit() }
            base = (if (Prefs.serverUrl.isNotBlank()) Prefs.serverUrl else discover()) ?: return@withContext ("الصق الرابط كاملًا كما وصلك (يبدأ بـ https://)" to 0L)
        }
        try {
            val body = JSONObject().put("code", code).put("name", name).put("username", username).put("password", password)
                .put("app", true).put("device_name", name.ifBlank { "جوالي" })
            http.newCall(Request.Builder().url("$base/api/public/join")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) return@withContext (o.optString("error").ifBlank { "تعذّر الانضمام (${r.code})" } to 0L)
                val token = o.optString("token")
                if (!token.startsWith("hrt_")) return@withContext ("رد غير متوقع من الخادم" to 0L)
                if (Prefs.serverToken != token) runCatching { Push.release() }
                Prefs.serverUrl = base
                Prefs.serverToken = token
                Prefs.syncCursor = ""
                Prefs.lastPushMs = 0L
                Prefs.sharedBrain = true
                val proj = o.optString("project")
                ActivityLog.record("الإعداد", if (proj.isBlank()) "إنشاء حساب" else "انضمام لفريق", "العقل المشترك",
                    if (proj.isBlank()) "أنشأت حسابك على خادم رفيق باسم ${o.optString("user")}" else "انضممت إلى «$proj» كـ ${o.optString("user")}", ok = true)
                SharedBrain.requestSync()
                null to o.optLong("project_id")
            }
        } catch (e: Exception) {
            ("تعذّر الوصول لخادم الفريق: ${e.message ?: e.javaClass.simpleName} — تأكد أن الرابط حديث (يتغيّر عند إعادة تشغيل خادم صاحب المشروع)" to 0L)
        }
    }

    /** معاينة رابط الدعوة قبل الانضمام: من أرسلها، ولأي مشروع (أو حساب جديد). يعيد null إن كان الرابط ناقصًا. */
    suspend fun peek(link: String): Result<String>? = withContext(Dispatchers.IO) {
        val m = Regex("""(https?://[^\s/]+)/join/([A-Za-z0-9-]+)""").find(link) ?: return@withContext null
        try {
            http.newCall(Request.Builder().url("${m.groupValues[1]}/api/public/invite/${m.groupValues[2]}").build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) return@withContext Result.failure(Exception(o.optString("error").ifBlank { "الدعوة غير صحيحة" }))
                val from = o.optString("from").ifBlank { "صاحب الخادم" }
                val proj = o.optString("project")
                Result.success(if (proj.isNotBlank() && proj != "null") "دعوة من $from للانضمام إلى «$proj»" else "دعوة من $from لتجربة رفيق")
            }
        } catch (e: Exception) { Result.failure(Exception("تعذّر الوصول للخادم — تأكد من الإنترنت")) }
    }

    /** عند تغيّر رابط النفق (يتغيّر مع كل تشغيل للخادم): ابحث عن الرابط الجديد */
    fun refreshUrl(): Boolean {
        val found = discover() ?: return false
        if (found == Prefs.serverUrl) return false
        Prefs.serverUrl = found
        return true
    }
}
