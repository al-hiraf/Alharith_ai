package com.alharith.ai.tools

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.alharith.ai.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Contact(val name: String, val number: String, val typeLabel: String, val isMobile: Boolean)

object Contacts {

    fun all(context: Context): List<Contact> {
        val out = mutableListOf<Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val type = c.getInt(2)
                val label = Phone.getTypeLabel(context.resources, type, c.getString(3)).toString()
                out += Contact(name, num, label, type == Phone.TYPE_MOBILE)
            }
        }
        return out.distinctBy { it.name + "|" + phoneKey(it.number) }
    }

    fun search(context: Context, query: String): List<Contact> {
        val q = Arabic.norm(query)
        return all(context)
            .filter { Arabic.matches(it.name, query) }
            .sortedWith(compareBy<Contact>(
                { if (Arabic.norm(it.name) == q) 0 else if (Arabic.norm(it.name).startsWith(q)) 1 else 2 },
                { it.name.length }
            ))
    }

    /** خريطة: آخر 9 أرقام ← الاسم */
    fun numberIndex(context: Context): Map<String, String> =
        all(context).associate { phoneKey(it.number) to it.name }

    fun nameFor(context: Context, number: String, index: Map<String, String>? = null): String? =
        (index ?: numberIndex(context))[phoneKey(number)]
}

/** تحويل الرقم المحلي إلى دولي (مثل 05… ← 9665…) لروابط واتساب وغيرها. */
fun internationalDigits(context: Context, number: String): String {
    var d = number.trim()
    if (d.startsWith("+")) return digitsOnly(d)
    d = digitsOnly(d)
    if (d.startsWith("00")) return d.drop(2)
    if (d.startsWith("0")) {
        val iso = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.let { it.networkCountryIso.ifBlank { it.simCountryIso } }?.lowercase().orEmpty()
        val cc = mapOf(
            "sa" to "966", "ae" to "971", "kw" to "965", "qa" to "974", "bh" to "973",
            "om" to "968", "eg" to "20", "jo" to "962", "sd" to "249", "ye" to "967",
            "iq" to "964", "lb" to "961", "sy" to "963", "ma" to "212", "dz" to "213", "tn" to "216"
        )[iso] ?: "966"
        return cc + d.drop(1)
    }
    return d
}

/**
 * يحوّل اسمًا أو رقمًا إلى رقم واحد، أو يعيد رسالة توضّح الغموض ليسأل Claude المستخدم.
 */
sealed class Resolved {
    data class One(val name: String?, val number: String) : Resolved()
    data class Problem(val result: ToolResult) : Resolved()
}

fun resolveRecipient(env: ToolEnv, target: String): Resolved {
    if (target.isBlank()) return Resolved.Problem(ToolResult.error("لم يُحدَّد الشخص."))
    if (looksLikeNumber(target)) {
        val name = if (env.has(Manifest.permission.READ_CONTACTS)) Contacts.nameFor(env.context, target) else null
        return Resolved.One(name, target.filter { it.isDigit() || it == '+' })
    }
    if (!env.has(Manifest.permission.READ_CONTACTS)) return Resolved.Problem(env.missing("جهات الاتصال"))
    val found = Contacts.search(env.context, target)
    if (found.isEmpty()) {
        return Resolved.Problem(ToolResult.error("لا يوجد في جهات الاتصال اسم يطابق \"$target\"."))
    }
    val names = found.map { it.name }.distinct()
    val exact = found.filter { Arabic.norm(it.name) == Arabic.norm(target) }
    val pool = when {
        exact.isNotEmpty() -> exact
        names.size == 1 -> found
        else -> return Resolved.Problem(
            ToolResult.error(
                "وجدت أكثر من شخص بهذا الاسم، اسأل المستخدم أيهم يقصد:\n" +
                    found.take(8).joinToString("\n") { "- ${it.name} (${it.typeLabel}: ${it.number})" }
            )
        )
    }
    val pick = pool.firstOrNull { it.isMobile } ?: pool.first()
    return Resolved.One(pick.name, pick.number)
}

object PhoneTools {

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "search_contacts", "يبحث في جهات الاتصال",
            "يبحث في جهات اتصال الهاتف بالاسم (يدعم العربية والإنجليزية) ويعيد الأسماء والأرقام.",
            schema("query" to prop("string", "الاسم أو جزء منه"), required = listOf("query"))
        ) { input ->
            if (!env.has(Manifest.permission.READ_CONTACTS)) return@Tool env.missing("جهات الاتصال")
            val res = Contacts.search(env.context, input.str("query"))
            if (res.isEmpty()) ToolResult.ok("لا توجد نتائج.")
            else ToolResult.ok(res.take(15).joinToString("\n") { "${it.name} — ${it.typeLabel}: ${it.number}" })
        },

        Tool(
            "make_call", "يجهّز المكالمة",
            "يجري مكالمة هاتفية عادية. مرّر الاسم كما قاله المستخدم أو الرقم. " +
                "الأداة تطلب تأكيد المستخدم بنفسها إن كان التأكيد مفعّلًا، فلا تطلب تأكيدًا مسبقًا.",
            schema(
                "target" to prop("string", "اسم الشخص أو رقم الهاتف"),
                required = listOf("target")
            )
        ) { input ->
            if (!env.has(Manifest.permission.CALL_PHONE)) return@Tool env.missing("إجراء المكالمات")
            when (val r = resolveRecipient(env, input.str("target"))) {
                is Resolved.Problem -> r.result
                is Resolved.One -> {
                    val who = r.name ?: r.number
                    if (Prefs.confirmCalls &&
                        !env.confirmer.confirm("أتصل بـ $who؟", "${r.name ?: ""} ${r.number}".trim())
                    ) return@Tool ToolResult.ok("ألغى المستخدم المكالمة.")
                    try {
                        val tm = env.context.getSystemService(TelecomManager::class.java)
                        tm.placeCall(Uri.fromParts("tel", r.number, null), Bundle())
                        ToolResult.ok("جارٍ الاتصال بـ $who (${r.number}).")
                    } catch (e: SecurityException) {
                        env.missing("إجراء المكالمات")
                    }
                }
            }
        },

        Tool(
            "get_call_log", "يراجع سجل المكالمات",
            "يقرأ سجل المكالمات الأخيرة (للأوامر مثل: اتصل بآخر شخص اتصل بي، من اتصل علي؟).",
            schema(
                "type" to prop("string", "نوع المكالمات", listOf("all", "incoming", "outgoing", "missed")),
                "limit" to prop("integer", "عدد المكالمات (افتراضي 10)")
            )
        ) { input ->
            if (!env.has(Manifest.permission.READ_CALL_LOG)) return@Tool env.missing("سجل المكالمات")
            val want = when (input.str("type")) {
                "incoming" -> CallLog.Calls.INCOMING_TYPE
                "outgoing" -> CallLog.Calls.OUTGOING_TYPE
                "missed" -> CallLog.Calls.MISSED_TYPE
                else -> null
            }
            val limit = input.intOr("limit", 10).coerceIn(1, 50)
            val fmt = SimpleDateFormat("EEEE d MMM، h:mm a", Locale("ar"))
            val idx = if (env.has(Manifest.permission.READ_CONTACTS)) Contacts.numberIndex(env.context) else emptyMap()
            val lines = mutableListOf<String>()
            env.context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && lines.size < limit) {
                    val type = c.getInt(2)
                    if (want != null && type != want) continue
                    val num = c.getString(0).orEmpty()
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: idx[phoneKey(num)] ?: "رقم غير محفوظ"
                    val t = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "واردة"
                        CallLog.Calls.OUTGOING_TYPE -> "صادرة"
                        CallLog.Calls.MISSED_TYPE -> "فائتة"
                        CallLog.Calls.REJECTED_TYPE -> "مرفوضة"
                        else -> "أخرى"
                    }
                    lines += "$name ($num) — $t — ${fmt.format(Date(c.getLong(3)))} — ${c.getLong(4)} ثانية"
                }
            }
            ToolResult.ok(if (lines.isEmpty()) "السجل فارغ." else lines.joinToString("\n"))
        }
    )
}
