package com.alharith.ai.brain

import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.tools.ToolRegistry
import com.alharith.ai.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * عقل رفيق: يرسل الطلب إلى Claude مع قائمة الأدوات، وينفّذ الأدوات التي يطلبها
 * على الهاتف، ثم يعيد النتائج إليه حتى يصل إلى الرد النهائي.
 */
class Brain(private val registry: ToolRegistry) {

    /** تعليمات المحادثة الصوتية المباشرة */
    fun liveSystemPrompt(): String {
        val now = java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", java.util.Locale("ar")).format(java.util.Date())
        return systemPrompt() + """

أنت الآن في مكالمة صوتية مباشرة مع ${Prefs.userName}. الوقت الآن: $now.
- تكلم كإنسان: جمل قصيرة دافئة بلهجة سعودية بيضاء، صوت رجل، بلا قوائم طويلة.
- إذا قاطعك فتوقف واستمع. إذا لم تفهم فاسأل باختصار.
- عند تنفيذ أداة قل جملة قصيرة مثل "لحظة" ثم أعطه النتيجة.
- الإجراءات الحساسة تظهر لها بطاقة تأكيد، ويكفي أن يقول المستخدم "نعم" أو "لا".
""".trimEnd()
    }

    private var history = JSONArray()
    private var lastActivity = 0L

    fun reset() { history = JSONArray() }

    /**
     * @param attachments كتل محتوى إضافية مع رسالة المستخدم (ملف مشارك مثلًا)
     * @return نص الرد للنطق والعرض
     */
    suspend fun handle(userText: String, attachments: List<JSONObject> = emptyList(), attachmentNote: String? = null): String {
        val key = AI.apiKey
        if (!AI.ready) return "لم يُضَف مفتاح ${AI.providerName} بعد. افتح الإعدادات وأضف المفتاح لأبدأ العمل."

        // بداية جلسة جديدة بعد 15 دقيقة من الخمول
        if (System.currentTimeMillis() - lastActivity > 15 * 60_000L) reset()
        lastActivity = System.currentTimeMillis()

        val snapshot = history.length()
        // تعليمات النظام ثابتة طوال الطلب؛ الوقت الحالي يُرسل مع رسالة المستخدم
        val system = systemPrompt()
        val stamped = "[${nowLine()}]\n$userText"
        val userContent: Any = if (attachments.isEmpty() && attachmentNote == null) stamped else JSONArray().apply {
            attachments.forEach { put(it) }
            attachmentNote?.let { put(JSONObject().put("type", "text").put("text", it)) }
            put(JSONObject().put("type", "text").put("text", stamped))
        }
        history.put(JSONObject().put("role", "user").put("content", userContent))

        try {
            val spoken = StringBuilder()
            // حماية من الحلقات: نفس الأداة بنفس المدخلات أكثر من مرتين في الطلب الواحد
            val seen = HashMap<String, Int>()
            repeat(MAX_STEPS) {
                val resp = sendWithRecovery(key, system)
                val content = resp.getJSONArray("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))

                val toolUses = mutableListOf<JSONObject>()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    when (b.optString("type")) {
                        "text" -> b.optString("text").trim().takeIf { it.isNotEmpty() }?.let {
                            if (spoken.isNotEmpty()) spoken.append(' ')
                            spoken.append(it)
                        }
                        "tool_use" -> toolUses += b
                    }
                }

                if (resp.optString("stop_reason") != "tool_use" || toolUses.isEmpty()) {
                    stripThinking()
                    trim()
                    lastActivity = System.currentTimeMillis()
                    return spoken.toString().ifBlank { "تم." }
                }

                // نص مرحلي قبل الأدوات (مثل "لحظة أبحث لك") يظهر ولا يُكرر لاحقًا
                if (spoken.isNotEmpty()) {
                    ConversationStore.action(spoken.toString())
                    spoken.clear()
                }

                val results = JSONArray()
                val extra = mutableListOf<JSONObject>()
                for (tu in toolUses) {
                    val name = tu.getString("name")
                    ConversationStore.action(registry.label(name) + "…")
                    val input = tu.optJSONObject("input") ?: JSONObject()
                    val sig = name + input.toString()
                    val n = (seen[sig] ?: 0) + 1
                    seen[sig] = n
                    val r: ToolResult = if (n > 2) {
                        ToolResult.error("نفّذت هذا الإجراء بنفس المدخلات مرتين بالفعل. لا تكرره؛ أجب المستخدم بما لديك أو اسأله.")
                    } else registry.run(name, input)
                    results.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", tu.getString("id"))
                        put("content", r.text)
                        if (r.isError) put("is_error", true)
                    })
                    extra += r.attachments
                }
                extra.forEach { results.put(it) }   // مرفقات PDF/صور بعد نتائج الأدوات
                history.put(JSONObject().put("role", "user").put("content", results))
            }
            trim()
            return "استغرق الطلب خطوات كثيرة، فتوقفت. جرّب صياغته بشكل أبسط."
        } catch (e: Throwable) {
            // إعادة السجل لحالته قبل هذا الطلب حتى لا يبقى طلب أداة بلا نتيجة
            truncate(snapshot)
            if (e is kotlinx.coroutines.CancellationException) throw e
            return when (e) {
                is ClaudeException -> e.message ?: "حدث خطأ."
                else -> "حدث خطأ غير متوقع: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * يرسل الطلب، وإذا رفض Claude كتل التفكير القديمة (توقيع لا يطابق) يحذفها ويعيد المحاولة مرة واحدة.
     */
    private suspend fun sendWithRecovery(key: String, system: String): JSONObject = try {
        AI.send(system, registry.definitions(), history)
    } catch (e: ClaudeException) {
        val m = e.message.orEmpty()
        if (m.contains("thinking", ignoreCase = true) || m.contains("signature", ignoreCase = true)) {
            stripThinking(all = true)
            AI.send(system, registry.definitions(), history)
        } else throw e
    }

    /**
     * يحذف كتل التفكير من ردود الأدوار المنتهية (لا يحتاجها Claude بعد انتهاء الدور).
     * all = true يحذفها من كل السجل، بما فيه الدور الحالي.
     */
    private fun stripThinking(all: Boolean = true) {
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            if (msg.optString("role") != "assistant") continue
            val c = msg.opt("content") as? JSONArray ?: continue
            val kept = JSONArray()
            for (j in 0 until c.length()) {
                val b = c.getJSONObject(j)
                val t = b.optString("type")
                if (t != "thinking" && t != "redacted_thinking") kept.put(b)
            }
            if (kept.length() == 0) kept.put(JSONObject().put("type", "text").put("text", "…"))
            msg.put("content", kept)
        }
    }

    private fun nowLine(): String {
        val now = Date()
        val ar = SimpleDateFormat("EEEE d MMMM yyyy، الساعة h:mm a", Locale("ar"))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        return "الوقت الآن: ${ar.format(now)} (${iso.format(now)}، ${TimeZone.getDefault().id})"
    }

    private fun truncate(size: Int) {
        val n = JSONArray()
        for (i in 0 until minOf(size, history.length())) n.put(history.get(i))
        history = n
    }

    /** يحافظ على آخر جزء من المحادثة ويبدأ دائمًا برسالة مستخدم حقيقية (ليست نتيجة أداة). */
    private fun trim() {
        if (history.length() <= MAX_MESSAGES) return
        var start = history.length() - MAX_MESSAGES
        while (start < history.length()) {
            val m = history.getJSONObject(start)
            if (m.optString("role") == "user" && isRealUserTurn(m)) break
            start++
        }
        val n = JSONArray()
        for (i in start until history.length()) n.put(history.get(i))
        history = n
        stripOldAttachments()
    }

    private fun isRealUserTurn(m: JSONObject): Boolean {
        val c = m.opt("content")
        if (c is String) return true
        if (c is JSONArray) {
            for (i in 0 until c.length()) if (c.getJSONObject(i).optString("type") == "tool_result") return false
            return true
        }
        return false
    }

    /** المرفقات الكبيرة (PDF/صور) تبقى فقط في آخر 4 رسائل لتقليل الحجم والتكلفة. */
    private fun stripOldAttachments() {
        val cutoff = history.length() - 4
        for (i in 0 until cutoff) {
            val c = history.getJSONObject(i).opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) {
                val b = c.getJSONObject(j)
                val t = b.optString("type")
                if (t == "document" || t == "image") {
                    c.put(j, JSONObject().put("type", "text").put("text", "[مرفق سابق أُزيل لتوفير المساحة]"))
                }
            }
        }
    }

    internal fun systemPrompt(): String {
        val name = Prefs.userName.ifBlank { "المستخدم" }
        return """
أنت "رفيق"، مساعد شخصي صوتي ذكي يعمل على هاتف Android الخاص بـ $name. يناديك بقوله "يا رفيق".

الوقت الحالي مكتوب بين قوسين مربعين في بداية كل رسالة من المستخدم؛ اعتمد عليه في التواريخ والمواعيد.

أسلوبك:
- ردودك تُقرأ بصوت عالٍ، فاجعلها قصيرة وطبيعية (جملة إلى ثلاث جمل غالبًا)، بالعربية الواضحة القريبة من اللهجة السعودية.
- لا تستخدم Markdown ولا رموز ولا جداول ولا روابط. عند الحاجة لقائمة قل: أولًا… ثانيًا…
- افهم جميع اللهجات العربية والأخطاء الإملائية الناتجة عن التعرف الصوتي (مثل "اتصل باحمد" = "اتصل بأحمد").
- خاطب $name بلطف ومباشرة، بدون مقدمات طويلة.

طريقة العمل:
- نفّذ الطلبات باستخدام الأدوات المتاحة. لا تدّعِ أبدًا أنك نفّذت شيئًا لم تؤكده نتيجة أداة.
- الإجراءات الحساسة (اتصال، إرسال SMS، رد على رسالة، إرسال بريد) تطلب الأداة نفسها تأكيد المستخدم، فاستدعها مباشرة ولا تسأل "هل أرسل؟" قبلها، إلا إذا كان هناك غموض حقيقي (عدة أشخاص بنفس الاسم، أو محتوى غير واضح).
- عندما يقول المستخدم "اكتب لفلان أن…" أو "أرسل لفلان…" فهو يريد الإرسال: صُغ الرسالة بصيغة المتكلم نيابة عنه ومرّرها لأداة الإرسال. أما "اقترح ردًا" أو "صُغ ردًا" فاكتب الاقتراح فقط واسأله إن كان يريد إرساله.
- إذا قال "أرسل الرسالة" أو "أرسلها" بعد مسودة سابقة، فأرسل آخر مسودة اتفقتما عليها.
- "رسالة" بدون تحديد تعني SMS، إلا إذا ذكر واتساب أو تيليجرام أو سياق المحادثة يدل على غير ذلك.
- واتساب وتيليجرام وسيجنال: تستطيع قراءة الرسائل التي وصلت كإشعارات والرد عليها مباشرة إن كان الإشعار يدعم الرد؛ وإلا افتح المحادثة بنص جاهز ويضغط المستخدم إرسال. لا يمكن الإرسال الخفي في هذه التطبيقات.
- للأوقات استخدم الصيغة YYYY-MM-DDTHH:MM بالتوقيت المحلي. "الساعة 8" بدون تحديد: اختر أقرب 8 قادمة منطقيًا حسب الوقت الحالي.
- عند تلخيص الرسائل أو البريد: ابدأ بالأهم، اذكر المرسل والمضمون والمطلوب والمواعيد والأرقام المهمة باختصار.
- "هذا الملف" أو "هذه الرسالة" تعني آخر ملف أو رسالة في المحادثة أو المرفق مع الطلب.
- أوامر سريعة: "رد عليه" أو "قل له…" تعني آخر رسالة واردة تحدثتما عنها؛ صُغ الرد بصيغة المتكلم ومرّره لأداة الرد مباشرة (الأداة تطلب موافقته).
- "موجز اليوم": مواعيد اليوم، ثم أهم الرسائل، ثم الإيميلات المهمة، باختصار ودون تفاصيل تقنية عمّا لا تصل إليه.
- المهام متعددة الخطوات (مثل: اقرأ الرسائل ثم لخّص ثم ذكّرني) نفّذها كاملة بالتتابع دون أن تسأله بين الخطوات، إلا عند الإجراءات الحساسة التي تطلب أدواتها موافقته.
- المهام: "ذكرني/لازم/عندي شغلة" قد تعني تذكيرًا أو مهمة؛ إن ذكر وقتًا محددًا للتنبيه فأنشئ تذكيرًا (ويمكن ربطه بمهمة)، وإن كان عملًا يجب إنجازه فأنشئ مهمة. أكّد بجملة واحدة فيها الوقت المفهوم بوضوح (مثل: غدًا الخميس الساعة 9:00 صباحًا).
- "رتب لي يومي" أو "جهز لي يومي بكرة": اقرأ مواعيد ذلك اليوم والمهام (المتأخرة ثم المستحقة ثم الأهم) والتذكيرات، ثم قدّم خطة زمنية مرتبة (الوقت — البند) تراعي المواعيد الثابتة، واسأله إن كان يريد تعديلها أو جدولة المهام كتذكيرات.
- "ماذا أنجزت اليوم؟": المهام المكتملة اليوم، المتأخرة، المؤجلة، واقتراحات للغد.
- "ما الأشياء المهمة التي تحتاج متابعة؟": المهام المتأخرة والعاجلة، المواعيد القريبة، الرسائل والإيميلات التي تحتاج ردًا إن أمكن الوصول إليها.
- الاجتماع أو الموعد: أنشئه في التقويم؛ وإذا طلب تنبيهًا قبله أنشئ تذكيرًا في الوقت المحسوب. قبل الإضافة تحقق من التعارض بقراءة مواعيد ذلك اليوم ونبّهه إن وُجد.
- المعلومات الحديثة (أسعار، أخبار، شركات، أي شيء قد يتغير): استخدم search_web واذكر المصدر.
- اسم شخص أو شركة أو موضوع بدون سياق: استخدم search_everything.
- الذاكرة: إذا قال "تذكر أن…" أو "احفظ…" احفظه بـ save_memory. لا تحفظ كل ما يُقال، ولا تحفظ معلومات حساسة إلا بطلب صريح. "انسَ…" = forget_memory.
- لا تدّعِ نجاح أي عملية إلا إذا أكدته نتيجة الأداة؛ إن فشلت فقل السبب والحل.
- إذا نقصت صلاحية أو إعداد، أخبره باختصار ماذا يفعل.
- إذا كان الطلب خارج قدرات الهاتف أو يتطلب تجاوز حماية النظام أو التطبيقات، اعتذر باختصار واقترح البديل الرسمي.
- الأعمال والمالية (دخل ومصروف شخصي أو لشركة، ميزانيات، فواتير وعروض أسعار، دفعات، عملاء وموردون، مؤشرات أداء، اجتماعات ومحاضرها، تقارير وتصدير Excel): مرّرها لأداة harith_server إن كانت متاحة. إن لم تكن متاحة فقل إنها تحتاج ربط خادم رفيق من الإعدادات. لا تخلط أموال جهة بأخرى، ولا تخترع أرصدة، والتسجيل ليس دفعًا: لا تحويلات ولا مدفوعات أبدًا.
- تحليل القرار: إذا طرح مشكلة أو قرارًا مهمًا فأجب باختصار بهذا الترتيب: المشكلة، الحقائق المتاحة، الناقص، البدائل، مقارنة التكلفة والفائدة والمخاطر، التوصية، خطة التنفيذ، المسؤوليات والمواعيد، قياس النجاح. افصل المؤكد عن التقدير، ولا توافقه تلقائيًا: إن كان في افتراضه خطأ واضح فقل ذلك بلطف ووضوح.
""".trim() + memorySection()
    }

    /** الذاكرة الشخصية تُضاف لتعليمات النظام (معلومات وافق المستخدم على حفظها) */
    private fun memorySection(): String {
        if (!Prefs.memoryEnabled) return "\n\nالذاكرة الشخصية متوقفة: لا تحفظ شيئًا."
        val mem = com.alharith.ai.data.LocalStore.activeMemories().takeLast(80)
        if (mem.isEmpty()) return ""
        return "\n\nما تعرفه عن ${Prefs.userName} (من ذاكرته الشخصية):\n" +
            mem.joinToString("\n") { "- [memory_id=${it.id}] ${it.text}" }
    }

    companion object {
        private const val MAX_STEPS = 16
        private const val MAX_MESSAGES = 30
    }
}
