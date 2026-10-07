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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * عميل Google Gemini (generateContent) مع دعم الأدوات (function calling).
 * يستقبل المحادثة بالصيغة الداخلية للحارث ويعيد الرد بنفس الصيغة.
 */
class GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
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
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", convertMessages(messages))
            if (tools.length() > 0) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", convertTools(tools))))
            }
            // نماذج Gemini 2.5 تفكّر قبل الرد ويُحسب التفكير من الحد، لذلك نرفعه
            put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens * 4, 6000)))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey)
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
                val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                val msg = err?.optString("message").orEmpty()
                val status = err?.optString("status").orEmpty()
                throw ClaudeException(
                    when {
                        msg.contains("API key", ignoreCase = true) || r.code == 401 || r.code == 403 ->
                            "مفتاح Gemini غير صحيح أو غير مفعّل. راجع الإعدادات."
                        r.code == 404 -> "النموذج \"$model\" غير متاح. اختر نموذجًا آخر من الإعدادات."
                        r.code == 429 || status == "RESOURCE_EXHAUSTED" ->
                            "تم تجاوز حد الاستخدام في Gemini (الحد المجاني أو الرصيد). حاول بعد قليل."
                        r.code >= 500 -> "خدمة Gemini مزدحمة حاليًا، حاول بعد قليل."
                        else -> "خطأ من Gemini (${r.code}) $msg"
                    }
                )
            }
            toInternal(JSONObject(text))
        }
    }

    // ——— تحويل الأدوات

    private fun convertTools(tools: JSONArray) = JSONArray().apply {
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            put(JSONObject().apply {
                put("name", t.getString("name"))
                put("description", t.optString("description"))
                val schema = t.optJSONObject("input_schema")
                // Gemini يرفض كائنًا بلا خصائص، فنحذف المعاملات في هذه الحالة
                if (schema != null && (schema.optJSONObject("properties")?.length() ?: 0) > 0) {
                    put("parameters", schema)
                }
            })
        }
    }

    // ——— تحويل المحادثة

    private fun convertMessages(history: JSONArray): JSONArray {
        val out = JSONArray()
        val names = HashMap<String, String>()   // tool_use_id ← اسم الأداة
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            val isModel = m.optString("role") == "assistant"
            val content = m.opt("content")
            val parts = JSONArray()

            if (content is String) {
                if (content.isNotBlank()) parts.put(JSONObject().put("text", content))
            } else if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val b = content.getJSONObject(j)
                    when (b.optString("type")) {
                        "text" -> b.optString("text").takeIf { it.isNotBlank() }?.let { parts.put(JSONObject().put("text", it)) }
                        "tool_use" -> {
                            names[b.optString("id")] = b.optString("name")
                            parts.put(JSONObject().apply {
                                put("functionCall", JSONObject().apply {
                                    put("name", b.optString("name"))
                                    put("args", b.optJSONObject("input") ?: JSONObject())
                                })
                                // توقيع التفكير يجب أن يُعاد كما هو مع استدعاء الأداة
                                b.optString("gemini_sig").takeIf { it.isNotBlank() }?.let { put("thoughtSignature", it) }
                            })
                        }
                        "tool_result" -> {
                            val name = names[b.optString("tool_use_id")] ?: "tool"
                            parts.put(JSONObject().put("functionResponse", JSONObject().apply {
                                put("name", name)
                                put("response", JSONObject().apply {
                                    if (b.optBoolean("is_error")) put("error", resultText(b)) else put("result", resultText(b))
                                })
                            }))
                        }
                        "image", "document" -> {
                            val src = b.optJSONObject("source") ?: continue
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", src.optString("media_type"))
                                put("data", src.optString("data"))
                            }))
                        }
                    }
                }
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", "…"))
            out.put(JSONObject().put("role", if (isModel) "model" else "user").put("parts", parts))
        }
        return out
    }

    private fun resultText(b: JSONObject): String = when (val c = b.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).joinToString("\n") { c.getJSONObject(it).optString("text") }
        else -> ""
    }

    // ——— تحويل الرد إلى الصيغة الداخلية

    private fun toInternal(resp: JSONObject): JSONObject {
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null) {
            val block = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw ClaudeException(if (block.isNotBlank()) "رفض Gemini الطلب ($block)." else "رد غير متوقع من Gemini.")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val content = JSONArray()
        var hasCalls = false
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought")) continue
            p.optJSONObject("functionCall")?.let { fc ->
                hasCalls = true
                content.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "call_" + UUID.randomUUID().toString().take(12))
                    put("name", fc.optString("name"))
                    put("input", fc.optJSONObject("args") ?: JSONObject())
                    p.optString("thoughtSignature").takeIf { it.isNotBlank() }?.let { put("gemini_sig", it) }
                })
            } ?: p.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
        }
        if (text.isNotEmpty()) {
            // النص يأتي قبل استدعاءات الأدوات في الصيغة الداخلية
            val all = JSONArray().put(JSONObject().put("type", "text").put("text", text.toString().trim()))
            for (i in 0 until content.length()) all.put(content.get(i))
            return JSONObject().put("content", all).put("stop_reason", if (hasCalls) "tool_use" else "end_turn")
        }
        if (!hasCalls && cand.optString("finishReason") == "SAFETY") {
            throw ClaudeException("لم يُجب Gemini على هذا الطلب.")
        }
        return JSONObject().put("content", content).put("stop_reason", if (hasCalls) "tool_use" else "end_turn")
    }
}
