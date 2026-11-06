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
 * نافذة «فوري»: أوامر سريعة تُرسل لخادم رفيق (مشروع «فوري») وتُنفَّذ خلال دقائق.
 * إضافة فقط — لا تغيّر أي سلوك قائم. تستعمل نفس ارتباط «العقل المشترك» (serverUrl + serverToken).
 */
object Fawri {

    data class Msg(
        val id: Long,
        val text: String,
        val reply: String,
        val state: String, // received | working | done
        val created: String,
        val updated: String
    )

    sealed class Load {
        data class Ok(val messages: List<Msg>) : Load()
        /** الحساب غير مرتبط بخادم رفيق بعد */
        object NoServer : Load()
        /** «فوري» غير متاح لهذا الحساب (ليس عضوًا في مشروع «فوري») */
        object Unavailable : Load()
        data class Error(val message: String) : Load()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun req(path: String): Request.Builder? {
        val base = Prefs.serverUrl.trim().trimEnd('/')
        val tok = Prefs.serverToken
        if (!base.startsWith("https://") || !tok.startsWith("hrt_")) return null
        return Request.Builder().url(base + path).header("Authorization", "Bearer $tok")
    }

    suspend fun list(): Load = withContext(Dispatchers.IO) {
        val b = req("/api/fawri/messages") ?: return@withContext Load.NoServer
        try {
            http.newCall(b.build()).execute().use { r ->
                when (r.code) {
                    401 -> Load.NoServer
                    404 -> Load.Unavailable
                    else -> {
                        if (!r.isSuccessful) return@withContext Load.Error("تعذّر جلب الرسائل (${r.code})")
                        val arr = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }).optJSONArray("messages")
                        val out = mutableListOf<Msg>()
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            out += Msg(
                                o.optLong("id"), o.optString("text"), o.optString("reply"),
                                o.optString("state", "received"), o.optString("created"), o.optString("updated")
                            )
                        }
                        Load.Ok(out)
                    }
                }
            }
        } catch (e: Exception) {
            Load.Error("تعذّر الوصول للخادم: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** يرسل أمرًا. يعيد null عند النجاح أو رسالة خطأ عربية. */
    suspend fun send(text: String): String? = withContext(Dispatchers.IO) {
        val b = req("/api/fawri/messages") ?: return@withContext "اربط حسابك بخادم رفيق أولًا"
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
        try {
            http.newCall(b.post(body).build()).execute().use { r ->
                if (r.isSuccessful) null
                else JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                    .optString("error").ifBlank { "تعذّر الإرسال (${r.code})" }
            }
        } catch (e: Exception) {
            "تعذّر الوصول للخادم: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
