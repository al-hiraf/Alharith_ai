package com.alharith.ai.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.alharith.ai.data.Prefs
import com.alharith.ai.service.HarithNotificationListener
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessagingTools {

    private fun installed(env: ToolEnv, pkg: String) = try {
        env.context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "read_app_messages", "يقرأ رسائل التطبيقات",
            "يقرأ الرسائل الواردة من واتساب وتيليجرام وسيجنال وغيرها كما وصلت في إشعارات الهاتف " +
                "(منذ تفعيل صلاحية الإشعارات). لا يصل إلى سجل المحادثات القديم داخل التطبيقات.",
            schema(
                "app" to prop("string", "التطبيق", listOf("all", "whatsapp", "telegram", "signal", "other")),
                "sender" to prop("string", "اسم المرسل أو المجموعة (اختياري)"),
                "since_hours" to prop("integer", "خلال آخر كم ساعة (اختياري)"),
                "limit" to prop("integer", "الحد الأقصى (افتراضي 15)")
            )
        ) { input ->
            if (!HarithNotificationListener.isEnabled(env.context)) {
                return@Tool ToolResult.error(
                    "صلاحية قراءة الإشعارات غير مفعّلة. اطلب من المستخدم تفعيلها من إعدادات الحارث ← الصلاحيات."
                )
            }
            val app = input.str("app")
            val sender = input.str("sender")
            val since = if (input.has("since_hours"))
                System.currentTimeMillis() - input.optLong("since_hours") * 3_600_000L else 0L
            val limit = input.intOr("limit", 15).coerceIn(1, 60)
            val fmt = SimpleDateFormat("h:mm a، d MMM", Locale("ar"))
            val list = HarithNotificationListener.messages()
                .filter { it.time >= since }
                .filter {
                    when (app) {
                        "whatsapp" -> it.packageName.startsWith("com.whatsapp")
                        "telegram" -> it.packageName.startsWith("org.telegram")
                        "signal" -> it.packageName == "org.thoughtcrime.securesms"
                        "other" -> !it.packageName.startsWith("com.whatsapp") &&
                            !it.packageName.startsWith("org.telegram") &&
                            it.packageName != "org.thoughtcrime.securesms"
                        else -> true
                    }
                }
                .filter { sender.isBlank() || Arabic.matches(it.sender, sender) }
                .takeLast(limit)
            if (list.isEmpty()) return@Tool ToolResult.ok("لا توجد رسائل مطابقة منذ تفعيل الإشعارات.")
            ToolResult.ok(list.joinToString("\n") {
                "[id=${it.id}] ${it.app} — ${it.sender} — ${fmt.format(Date(it.time))}" +
                    (if (it.replyAction != null) " (يمكن الرد)" else "") + ":\n${it.text.take(700)}"
            })
        },

        Tool(
            "reply_to_app_message", "يجهّز الرد",
            "يرد على رسالة واردة (واتساب/تيليجرام/…) عبر زر الرد في إشعارها. استخدم id من read_app_messages. " +
                "الأداة تطلب تأكيد المستخدم بنفسها إن كان مفعّلًا.",
            schema(
                "message_id" to prop("integer", "معرّف الرسالة"),
                "text" to prop("string", "نص الرد"),
                required = listOf("message_id", "text")
            )
        ) { input ->
            val id = input.optInt("message_id", -1)
            val text = input.str("text")
            val msg = HarithNotificationListener.messages().lastOrNull { it.id == id }
                ?: return@Tool ToolResult.error("لم أجد الرسالة. اقرأ الرسائل مجددًا.")
            if (msg.replyAction == null) {
                return@Tool ToolResult.error("إشعار هذه الرسالة لا يدعم الرد المباشر. استخدم open_chat لفتح المحادثة بنص جاهز.")
            }
            if (Prefs.confirmMessages &&
                !env.confirmer.confirm("أرد على ${msg.sender} في ${msg.app}: $text", "${msg.app} — ${msg.sender}\n\n$text")
            ) return@Tool ToolResult.ok("ألغى المستخدم الرد.")
            if (HarithNotificationListener.reply(env.context, msg, text))
                ToolResult.ok("تم إرسال الرد إلى ${msg.sender} عبر ${msg.app}.")
            else ToolResult.error("انتهت صلاحية الإشعار (ربما فُتحت المحادثة أو حُذف الإشعار). استخدم open_chat بدلًا من ذلك.")
        },

        Tool(
            "open_chat", "يفتح المحادثة",
            "يفتح محادثة مع شخص في واتساب أو تيليجرام أو سيجنال، مع تجهيز نص الرسالة إن وُجد. " +
                "في واتساب يظهر النص جاهزًا في خانة الكتابة ويضغط المستخدم إرسال بنفسه. " +
                "في تيليجرام وسيجنال يُنسخ النص للحافظة ليلصقه المستخدم.",
            schema(
                "app" to prop("string", "التطبيق", listOf("whatsapp", "telegram", "signal")),
                "recipient" to prop("string", "اسم الشخص أو رقمه"),
                "text" to prop("string", "نص الرسالة الجاهز (اختياري)"),
                required = listOf("app", "recipient")
            )
        ) { input ->
            val r = when (val res = resolveRecipient(env, input.str("recipient"))) {
                is Resolved.Problem -> return@Tool res.result
                is Resolved.One -> res
            }
            val num = internationalDigits(env.context, r.number)
            val text = input.str("text")
            val who = r.name ?: r.number
            when (input.str("app")) {
                "telegram" -> {
                    if (!installed(env, "org.telegram.messenger")) return@Tool ToolResult.error("تيليجرام غير مثبت.")
                    if (text.isNotBlank()) copy(env, text)
                    val res = env.launch(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$num")))
                    if (res.isError) res else ToolResult.ok(
                        "فُتحت محادثة $who في تيليجرام" + if (text.isNotBlank()) " ونُسخ النص، يلصقه المستخدم ويرسل." else "."
                    )
                }
                "signal" -> {
                    if (!installed(env, "org.thoughtcrime.securesms")) return@Tool ToolResult.error("سيجنال غير مثبت.")
                    if (text.isNotBlank()) copy(env, text)
                    val res = env.launch(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://signal.me/#p/+$num"))
                            .setPackage("org.thoughtcrime.securesms")
                    )
                    if (res.isError) res else ToolResult.ok(
                        "فُتحت محادثة $who في سيجنال" + if (text.isNotBlank()) " ونُسخ النص." else "."
                    )
                }
                else -> {
                    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(env, it) }
                        ?: return@Tool ToolResult.error("واتساب غير مثبت.")
                    val url = "https://wa.me/$num" +
                        if (text.isNotBlank()) "?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20") else ""
                    val res = env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
                    if (res.isError) res else ToolResult.ok(
                        "فُتحت محادثة $who في واتساب" +
                            if (text.isNotBlank()) " والرسالة جاهزة، يضغط المستخدم زر الإرسال." else "."
                    )
                }
            }
        }
    )

    private fun copy(env: ToolEnv, text: String) {
        runCatching {
            env.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("الحارث", text))
        }
    }
}
