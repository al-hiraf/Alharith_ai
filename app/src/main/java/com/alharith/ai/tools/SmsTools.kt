package com.alharith.ai.tools

import android.Manifest
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import com.alharith.ai.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsTools {

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "read_sms", "يقرأ الرسائل القصيرة",
            "يقرأ الرسائل النصية SMS من الهاتف مع الفلترة. استخدمه لـ: اقرأ آخر رسالة، ما آخر رسالة من أحمد، لخص رسائل اليوم.",
            schema(
                "contact" to prop("string", "اسم أو رقم المرسل/المستقبل (اختياري)"),
                "contains" to prop("string", "نص يجب أن تحتويه الرسالة (اختياري)"),
                "since_hours" to prop("integer", "الرسائل خلال آخر كم ساعة (مثلاً 24 لرسائل اليوم)"),
                "box" to prop("string", "الوارد أو الصادر أو الكل", listOf("inbox", "sent", "all")),
                "unread_only" to prop("boolean", "غير المقروءة فقط"),
                "limit" to prop("integer", "الحد الأقصى (افتراضي 10)")
            )
        ) { input ->
            if (!env.has(Manifest.permission.READ_SMS)) return@Tool env.missing("قراءة الرسائل")
            val idx = if (env.has(Manifest.permission.READ_CONTACTS)) Contacts.numberIndex(env.context) else emptyMap()

            // مفاتيح أرقام الشخص المطلوب
            val contact = input.str("contact")
            val keys: Set<String>? = when {
                contact.isBlank() -> null
                looksLikeNumber(contact) -> setOf(phoneKey(contact))
                env.has(Manifest.permission.READ_CONTACTS) ->
                    Contacts.search(env.context, contact).map { phoneKey(it.number) }.toSet()
                else -> return@Tool env.missing("جهات الاتصال")
            }
            if (keys != null && keys.isEmpty()) return@Tool ToolResult.ok("لا يوجد في جهات الاتصال من يطابق \"$contact\".")

            val contains = Arabic.norm(input.str("contains"))
            val since = if (input.has("since_hours"))
                System.currentTimeMillis() - input.optLong("since_hours") * 3_600_000L else 0L
            val unread = input.optBoolean("unread_only", false)
            val limit = input.intOr("limit", 10).coerceIn(1, 60)
            val uri = when (input.str("box")) {
                "sent" -> Telephony.Sms.Sent.CONTENT_URI
                "all" -> Telephony.Sms.CONTENT_URI
                else -> Telephony.Sms.Inbox.CONTENT_URI
            }
            val fmt = SimpleDateFormat("EEEE d MMM، h:mm a", Locale("ar"))
            val out = mutableListOf<String>()
            env.context.contentResolver.query(
                uri,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val date = c.getLong(2)
                    if (date < since) break
                    val addr = c.getString(0).orEmpty()
                    if (keys != null && phoneKey(addr) !in keys) continue
                    if (unread && c.getInt(4) == 1) continue
                    val body = c.getString(1).orEmpty()
                    if (contains.isNotBlank() && !Arabic.norm(body).contains(contains)) continue
                    val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "إلى" else "من"
                    val who = idx[phoneKey(addr)] ?: addr
                    out += "[$dir $who — ${fmt.format(Date(date))}${if (c.getInt(4) == 0) " — غير مقروءة" else ""}]\n${body.take(700)}"
                }
            }
            ToolResult.ok(if (out.isEmpty()) "لا توجد رسائل مطابقة." else out.joinToString("\n\n"))
        },

        Tool(
            "send_sms", "يجهّز الرسالة",
            "يرسل رسالة SMS. اكتب نص الرسالة النهائي بصياغة مناسبة نيابة عن المستخدم. " +
                "الأداة تعرض الرسالة على المستخدم وتطلب تأكيده بنفسها إن كان التأكيد مفعّلًا.",
            schema(
                "recipient" to prop("string", "اسم الشخص أو رقمه"),
                "body" to prop("string", "نص الرسالة"),
                required = listOf("recipient", "body")
            )
        ) { input ->
            if (!env.has(Manifest.permission.SEND_SMS)) return@Tool env.missing("إرسال الرسائل")
            val body = input.str("body")
            if (body.isBlank()) return@Tool ToolResult.error("نص الرسالة فارغ.")
            when (val r = resolveRecipient(env, input.str("recipient"))) {
                is Resolved.Problem -> r.result
                is Resolved.One -> {
                    val who = r.name ?: r.number
                    if (Prefs.confirmMessages &&
                        !env.confirmer.confirm("أرسل لـ $who: $body", "إلى: $who (${r.number})\n\n$body")
                    ) return@Tool ToolResult.ok("ألغى المستخدم الإرسال. احتفظ بالنص إن أراد تعديله.")
                    try {
                        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31)
                            env.context.getSystemService(SmsManager::class.java)
                        else @Suppress("DEPRECATION") SmsManager.getDefault()
                        val parts = sms.divideMessage(body)
                        if (parts.size > 1) sms.sendMultipartTextMessage(r.number, null, parts, null, null)
                        else sms.sendTextMessage(r.number, null, body, null, null)
                        ToolResult.ok("أُرسلت الرسالة إلى $who.")
                    } catch (e: Exception) {
                        ToolResult.error("فشل الإرسال: ${e.message}")
                    }
                }
            }
        }
    )
}
