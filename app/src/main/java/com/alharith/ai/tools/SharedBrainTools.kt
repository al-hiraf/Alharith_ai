package com.alharith.ai.tools

import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain

/** أداة تمرير الطلبات إلى خادم رفيق (العقل المشترك) — تظهر فقط عند ربط الخادم */
object SharedBrainTools {
    fun tools(@Suppress("UNUSED_PARAMETER") env: ToolEnv): List<Tool> = if (!Prefs.sharedBrainReady) emptyList() else listOf(
        Tool(
            "harith_server", "يسأل خادم رفيق",
            "يمرر طلبًا إلى خادم رفيق الذي يعمل على مدار الساعة ويشترك مع تيليجرام ولوحة التحكم. استخدمه لـ: " +
                "الأعمال والمالية (تسجيل دخل أو مصروف شخصي أو لشركة، الميزانيات، الالتزامات الشهرية، التقارير المالية وتصديرها Excel)، " +
                "الفواتير وعروض الأسعار وفواتير الموردين وتسجيل الدفعات، العملاء والموردين والعملاء المحتملين والموظفين، " +
                "مؤشرات الأداء، الاجتماعات ومحاضرها وتحويلها لمهام، " +
                "المشاريع (القرارات، المخاطر، المحاضر، التقارير ونسب الإنجاز)، المهام المجدولة المتكررة " +
                "(مثل: كل صباح لخّص…)، تذكيرات تصل عبر تيليجرام، الملفات المحفوظة على الخادم، العادات، " +
                "وإرسال البريد عبر الخادم بموافقة. المهام والذاكرة تُزامن تلقائيًا فلا تحتاج هذه الأداة لها. " +
                "اكتب الطلب كاملًا وواضحًا بالعربية.",
            schema("request" to prop("string", "الطلب كاملًا كما يجب أن ينفذه الخادم"), required = listOf("request"))
        ) { input ->
            val text = input.str("request")
            if (text.isBlank()) return@Tool ToolResult.error("الطلب فارغ.")
            val r = try { SharedBrain.ask(text) } catch (e: Exception) {
                return@Tool ToolResult.error("تعذّر الوصول لخادم رفيق (${e.message}). تأكد أن الخادم يعمل في Termux.")
            }
            val status = r.optString("status")
            val reply = r.optString("text")
            when (status) {
                "success", "partial" -> ToolResult.ok("رد الخادم ($status): $reply")
                "queued" -> ToolResult.ok("الخادم حفظ الطلب وسينفذه لاحقًا: $reply")
                else -> ToolResult.error("الخادم لم ينفذ الطلب ($status): $reply")
            }
        }
    )
}
