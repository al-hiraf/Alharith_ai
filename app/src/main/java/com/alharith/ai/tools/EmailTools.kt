package com.alharith.ai.tools

import com.alharith.ai.data.Prefs
import java.text.SimpleDateFormat
import java.util.Locale
import javax.mail.AuthenticationFailedException

object EmailTools {

    private fun notConfigured() = ToolResult.error(
        "البريد غير مُعدّ. اطلب من المستخدم إضافة بريده وكلمة مرور التطبيقات من إعدادات الحارث ← البريد."
    )

    private fun friendly(e: Exception): ToolResult = when (e) {
        is AuthenticationFailedException -> ToolResult.error(
            "رفض خادم البريد الدخول. تأكد من كلمة مرور التطبيقات (وليس كلمة المرور العادية) ومن تفعيل IMAP."
        )
        else -> ToolResult.error("تعذّر الوصول للبريد: ${e.message ?: e.javaClass.simpleName}")
    }

    private val folders = listOf("inbox", "sent", "drafts", "important", "starred", "all")

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "email_list", "يفتح البريد",
            "يعرض قائمة رسائل البريد مع المرسل والعنوان والتاريخ ومقتطف. " +
                "لإيميلات اليوم استخدم since_days=1. للبحث استخدم query (كلمة من العنوان أو المرسل أو المحتوى).",
            schema(
                "folder" to prop("string", "المجلد (افتراضي inbox)", folders),
                "query" to prop("string", "كلمة بحث (اختياري)"),
                "unread_only" to prop("boolean", "غير المقروءة فقط"),
                "since_days" to prop("integer", "خلال آخر كم يوم"),
                "limit" to prop("integer", "العدد (افتراضي 10، أقصى 30)")
            )
        ) { input ->
            if (!Prefs.emailConfigured) return@Tool notConfigured()
            try {
                val list = EmailClient.list(
                    folderKind = input.str("folder").ifBlank { "inbox" },
                    query = input.str("query"),
                    unreadOnly = input.optBoolean("unread_only", false),
                    sinceDays = if (input.has("since_days")) input.optInt("since_days") else null,
                    limit = input.intOr("limit", 10).coerceIn(1, 30)
                )
                if (list.isEmpty()) return@Tool ToolResult.ok("لا توجد رسائل مطابقة.")
                val fmt = SimpleDateFormat("d MMM h:mm a", Locale("ar"))
                ToolResult.ok(list.joinToString("\n\n") { s ->
                    "[uid=${s.uid}]${if (s.unread) " غير مقروءة" else ""} — ${s.date?.let { fmt.format(it) }.orEmpty()}\n" +
                        "من: ${s.from}\nالعنوان: ${s.subject}" +
                        if (s.snippet.isNotBlank()) "\nمقتطف: ${s.snippet.replace('\n', ' ')}" else ""
                })
            } catch (e: Exception) { friendly(e) }
        },

        Tool(
            "email_read", "يقرأ الإيميل",
            "يقرأ رسالة بريد كاملة باستخدام uid من email_list.",
            schema(
                "uid" to prop("integer", "معرّف الرسالة"),
                "folder" to prop("string", "المجلد الذي جاءت منه (افتراضي inbox)", folders),
                required = listOf("uid")
            )
        ) { input ->
            if (!Prefs.emailConfigured) return@Tool notConfigured()
            try {
                ToolResult.ok(EmailClient.read(input.str("folder").ifBlank { "inbox" }, input.optLong("uid")))
            } catch (e: Exception) { friendly(e) }
        },

        Tool(
            "email_send", "يجهّز الإيميل",
            "يرسل بريدًا إلكترونيًا (أو ردًا على رسالة بتمرير reply_to_uid من صندوق الوارد). " +
                "الأداة تعرض المحتوى وتطلب تأكيد المستخدم بنفسها إن كان مفعّلًا.",
            schema(
                "to" to prop("string", "عنوان البريد المستلم (أو عدة عناوين مفصولة بفاصلة)"),
                "subject" to prop("string", "العنوان (يمكن تركه فارغًا عند الرد)"),
                "body" to prop("string", "نص الرسالة كاملًا"),
                "reply_to_uid" to prop("integer", "uid الرسالة التي نرد عليها (اختياري)"),
                required = listOf("to", "body")
            )
        ) { input ->
            if (!Prefs.emailConfigured) return@Tool notConfigured()
            val to = input.str("to")
            if (!to.contains("@")) return@Tool ToolResult.error("عنوان المستلم غير صالح: $to")
            val subject = input.str("subject")
            val body = input.str("body")
            val replyUid = if (input.has("reply_to_uid")) input.optLong("reply_to_uid") else null
            if (Prefs.confirmEmails &&
                !env.confirmer.confirm(
                    "أرسل الإيميل إلى $to بعنوان ${subject.ifBlank { "الرد" }}؟",
                    "إلى: $to\nالعنوان: ${subject.ifBlank { "(رد)" }}\n\n$body"
                )
            ) return@Tool ToolResult.ok("ألغى المستخدم الإرسال. يمكن حفظه كمسودة إن أراد.")
            try {
                EmailClient.send(to, subject, body, replyUid)
                ToolResult.ok("أُرسل الإيميل إلى $to.")
            } catch (e: Exception) { friendly(e) }
        },

        Tool(
            "email_create_draft", "يحفظ مسودة",
            "يحفظ بريدًا كمسودة في مجلد المسودات دون إرسال.",
            schema(
                "to" to prop("string", "المستلم"),
                "subject" to prop("string", "العنوان"),
                "body" to prop("string", "النص"),
                "reply_to_uid" to prop("integer", "uid الرسالة التي نرد عليها (اختياري)"),
                required = listOf("to", "body")
            )
        ) { input ->
            if (!Prefs.emailConfigured) return@Tool notConfigured()
            try {
                EmailClient.saveDraft(
                    input.str("to"), input.str("subject"), input.str("body"),
                    if (input.has("reply_to_uid")) input.optLong("reply_to_uid") else null
                )
                ToolResult.ok("حُفظت المسودة في مجلد المسودات.")
            } catch (e: Exception) { friendly(e) }
        }
    )
}
