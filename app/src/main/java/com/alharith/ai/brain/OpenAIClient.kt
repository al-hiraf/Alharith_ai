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

/**
 * عميل OpenAI Chat Completions مع دعم الأدوات (function calling).
 * يستقبل المحادثة بنفس الصيغة الداخلية للحارث ويعيد الرد بنفس الصيغة،
 * فيعمل العقل والأدوات كما هي مع أي مزوّد.
 */
class OpenAIClient {

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
        maxTokens: Int = 1500,
        baseUrl: String = "https://api.openai.com/v1",
        providerName: String = "OpenAI"
    ): JSONObject = withContext(Dispatchers.IO) {
        val official = baseUrl.contains("api.openai.com")
        val body = JSONObject().apply {
            put("model", model)
            // نماذج الاستدلال تستهلك جزءًا من الحد في التفكير، لذلك نرفعه
            if (official) put("max_completion_tokens", maxOf(maxTokens * 3, 4000))
            else put("max_tokens", maxOf(maxTokens * 2, 3000))
            put("messages", convertMessages(system, messages, supportsFiles = official))
            if (tools.length() > 0) put("tools", convertTools(tools))
        }
        if (baseUrl.isBlank()) throw ClaudeException("ضع الرابط الأساسي للخدمة المخصصة في الإعدادات.")
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .header("content-type", "application/json")
            .header("HTTP-Referer", "https://github.com/al-hiraf/Alharith_ai")
            .header("X-Title", "AlHarith AI")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        val resp = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(
                        ClaudeException(if (official) "تعذّر الاتصال بالإنترنت" else "تعذّر الاتصال بـ $providerName — تحقق من الإنترنت أو الرابط.")
                    )
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
                val code = err?.optString("code").orEmpty()
                throw ClaudeException(
                    when {
                        r.code == 401 || r.code == 403 -> "مفتاح $providerName غير صحيح أو بلا صلاحية. راجع الإعدادات."
                        code == "insufficient_quota" || r.code == 402 -> "انتهى رصيد حساب $providerName. أضف رصيدًا من موقعهم."
                        code == "model_not_found" || r.code == 404 -> "النموذج \"$model\" غير متاح لدى $providerName. اختر أو اكتب نموذجًا آخر من الإعدادات."
                        r.code == 429 -> "تم تجاوز حد الاستخدام لدى $providerName مؤقتًا، حاول بعد قليل."
                        r.code >= 500 -> "خدمة $providerName مزدحمة حاليًا، حاول بعد قليل."
                        msg.contains("tool", ignoreCase = true) || msg.contains("function", ignoreCase = true) ->
                            "النموذج \"$model\" لا يدعم الأدوات (تنفيذ الأوامر). اختر نموذجًا آخر من الإعدادات."
                        else -> "خطأ من $providerName (${r.code}) $msg"
                    }
                )
            }
            toInternal(JSONObject(text))
        }
    }

    // ——— تحويل الطلب

    private fun convertTools(tools: JSONArray) = JSONArray().apply {
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.getString("name"))
                    put("description", t.optString("description"))
                    put("parameters", t.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
                })
            })
        }
    }

    private fun convertMessages(system: String, history: JSONArray, supportsFiles: Boolean): JSONArray {
        val out = JSONArray()
        out.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            val role = m.optString("role")
            val content = m.opt("content")
            if (role == "assistant") {
                out.put(assistantMessage(content))
                continue
            }
            // رسالة مستخدم: نص، أو كتل (نتائج أدوات، مرفقات، نصوص)
            if (content is String) {
                out.put(JSONObject().put("role", "user").put("content", content))
                continue
            }
            val blocks = content as? JSONArray ?: continue
            val parts = JSONArray()
            for (j in 0 until blocks.length()) {
                val b = blocks.getJSONObject(j)
                when (b.optString("type")) {
                    "tool_result" -> out.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", b.optString("tool_use_id"))
                        put("content", toolResultText(b))
                    })
                    "text" -> parts.put(JSONObject().put("type", "text").put("text", b.optString("text")))
                    "image" -> {
                        val src = b.optJSONObject("source") ?: continue
                        parts.put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:${src.optString("media_type")};base64,${src.optString("data")}"))
                        })
                    }
                    "document" -> {
                        val src = b.optJSONObject("source") ?: continue
                        if (!supportsFiles) {
                            parts.put(JSONObject().put("type", "text").put("text", "[ملف PDF مرفق لا يستطيع هذا المزوّد قراءته مباشرة. اقترح على المستخدم التبديل إلى Gemini أو OpenAI أو Claude لقراءة ملفات PDF.]"))
                            continue
                        }
                        parts.put(JSONObject().apply {
                            put("type", "file")
                            put("file", JSONObject().apply {
                                put("filename", "document.pdf")
                                put("file_data", "data:application/pdf;base64,${src.optString("data")}")
                            })
                        })
                    }
                }
            }
            // المرفقات تأتي بعد نتائج الأدوات في رسالة مستخدم منفصلة
            if (parts.length() > 0) out.put(JSONObject().put("role", "user").put("content", parts))
        }
        return out
    }

    private fun toolResultText(b: JSONObject): String {
        val prefix = if (b.optBoolean("is_error")) "خطأ: " else ""
        return when (val c = b.opt("content")) {
            is String -> prefix + c
            is JSONArray -> prefix + (0 until c.length()).joinToString("\n") { c.getJSONObject(it).optString("text") }
            else -> prefix
        }
    }

    private fun assistantMessage(content: Any?): JSONObject {
        val msg = JSONObject().put("role", "assistant")
        if (content is String) return msg.put("content", content)
        val blocks = content as? JSONArray ?: return msg.put("content", "")
        val text = StringBuilder()
        val calls = JSONArray()
        for (j in 0 until blocks.length()) {
            val b = blocks.getJSONObject(j)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls.put(JSONObject().apply {
                    put("id", b.optString("id"))
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", b.optString("name"))
                        put("arguments", (b.optJSONObject("input") ?: JSONObject()).toString())
                    })
                })
            }
        }
        msg.put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
        if (calls.length() > 0) msg.put("tool_calls", calls)
        return msg
    }

    // ——— تحويل الرد إلى الصيغة الداخلية: content[] + stop_reason

    private fun toInternal(resp: JSONObject): JSONObject {
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ClaudeException("رد غير متوقع من الخدمة.")
        val m = choice.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = m.optString("content", "").let { if (it == "null") "" else it }
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = m.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val f = c.optJSONObject("function") ?: continue
                val args = runCatching { JSONObject(f.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                content.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", c.optString("id"))
                    put("name", f.optString("name"))
                    put("input", args)
                })
            }
        }
        val hasCalls = calls != null && calls.length() > 0
        return JSONObject()
            .put("content", content)
            .put("stop_reason", if (hasCalls) "tool_use" else "end_turn")
    }
}

/** يوجّه الطلب للمزوّد المختار في الإعدادات (Gemini أو OpenAI أو Claude). */
object AI {
    private val gemini = GeminiClient()
    private val openai = OpenAIClient()
    private val claude = ClaudeClient()

    val providerName get() = com.alharith.ai.data.Prefs.providerLabel
    val apiKey get() = com.alharith.ai.data.Prefs.keyFor(com.alharith.ai.data.Prefs.provider)

    /** مفتاح فارغ مقبول للمزوّد المخصص (Ollama مثلًا) */
    val ready get() = !com.alharith.ai.data.Prefs.aiKeyMissing

    /** @param fast نموذج أسرع وأرخص للمهام الصغيرة (مثل تقييم أهمية رسالة) */
    suspend fun send(system: String, tools: JSONArray, messages: JSONArray, maxTokens: Int = 1500, fast: Boolean = false): JSONObject {
        val p = com.alharith.ai.data.Prefs
        val prov = p.currentProvider
        val model = (if (fast) prov.fastModel else null) ?: p.modelFor(prov.id)
        if (model.isBlank()) throw ClaudeException("اختر أو اكتب اسم النموذج في الإعدادات ← الذكاء الاصطناعي.")
        val key = p.keyFor(prov.id)
        return when (prov.kind) {
            "claude" -> claude.send(key, model, system, tools, withoutGeminiFields(messages), maxTokens)
            "gemini" -> gemini.send(key, model, system, tools, messages, maxTokens)
            else -> openai.send(
                key, model, system, tools, messages, maxTokens,
                baseUrl = if (prov.id == "custom") p.customBaseUrl else prov.baseUrl,
                providerName = p.providerLabel
            )
        }
    }

    /** Claude يرفض الحقول الإضافية، فنحذف توقيعات Gemini إن تغيّر المزوّد أثناء المحادثة */
    private fun withoutGeminiFields(messages: JSONArray): JSONArray {
        val out = JSONArray(messages.toString())
        for (i in 0 until out.length()) {
            val c = out.getJSONObject(i).opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) c.optJSONObject(j)?.remove("gemini_sig")
        }
        return out
    }
}
