package com.alharith.ai.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClaudeException(message: String) : Exception(message)

/** عميل بسيط لواجهة Claude Messages API مع دعم الأدوات (tool use). */
class ClaudeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        apiKey: String,
        model: String,
        system: String,
        tools: JSONArray,
        messages: JSONArray,
        maxTokens: Int = 1500
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", system)
            }))
            put("tools", tools)
            put("messages", messages)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        val resp = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(ClaudeException("تعذّر الاتصال بالإنترنت"))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }
                    .getOrNull().orEmpty()
                throw ClaudeException(
                    when (r.code) {
                        401 -> "مفتاح Claude غير صحيح. راجع الإعدادات."
                        429 -> "تم تجاوز حد الاستخدام مؤقتًا، حاول بعد قليل."
                        529, 503 -> "خدمة Claude مزدحمة حاليًا، حاول بعد قليل."
                        else -> "خطأ من Claude (${r.code}) $msg"
                    }
                )
            }
            JSONObject(text)
        }
    }
}
