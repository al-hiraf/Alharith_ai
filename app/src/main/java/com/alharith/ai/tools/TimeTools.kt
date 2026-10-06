package com.alharith.ai.tools

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.alharith.ai.service.ReminderReceiver
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeTools {

    private val zone get() = ZoneId.systemDefault()

    /** يقبل "2026-10-07T20:00" أو "2026-10-07" */
    private fun parseMillis(s: String, endOfDay: Boolean = false): Long? = runCatching {
        if (s.length <= 10) {
            val d = LocalDate.parse(s)
            (if (endOfDay) d.plusDays(1).atStartOfDay(zone) else d.atStartOfDay(zone)).toInstant().toEpochMilli()
        } else {
            LocalDateTime.parse(s.take(19).let { if (it.length == 16) "$it:00" else it })
                .atZone(zone).toInstant().toEpochMilli()
        }
    }.getOrNull()

    private val dayMap = mapOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY
    )

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "get_calendar_events", "يراجع التقويم",
            "يقرأ مواعيد التقويم بين تاريخين (لـ: ما عندي اليوم؟ ما مواعيد الأسبوع؟). التواريخ بالتوقيت المحلي.",
            schema(
                "start" to prop("string", "البداية: YYYY-MM-DD أو YYYY-MM-DDTHH:MM"),
                "end" to prop("string", "النهاية: YYYY-MM-DD (شاملة لليوم كاملًا) أو YYYY-MM-DDTHH:MM"),
                required = listOf("start", "end")
            )
        ) { input ->
            if (!env.has(Manifest.permission.READ_CALENDAR)) return@Tool env.missing("التقويم")
            val begin = parseMillis(input.str("start")) ?: return@Tool ToolResult.error("صيغة تاريخ البداية غير صحيحة.")
            val end = parseMillis(input.str("end"), endOfDay = true) ?: return@Tool ToolResult.error("صيغة تاريخ النهاية غير صحيحة.")
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
            }.build()
            val fmt = SimpleDateFormat("EEEE d MMM، h:mm a", Locale("ar"))
            val dayFmt = SimpleDateFormat("EEEE d MMM", Locale("ar")).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val out = mutableListOf<String>()
            env.context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 60) {
                    val title = c.getString(0).orEmpty().ifBlank { "(بدون عنوان)" }
                    val allDay = c.getInt(3) == 1
                    val whenTxt = if (allDay) "${dayFmt.format(Date(c.getLong(1)))} (طوال اليوم)"
                    else "${fmt.format(Date(c.getLong(1)))} حتى ${SimpleDateFormat("h:mm a", Locale("ar")).format(Date(c.getLong(2)))}"
                    val loc = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " — المكان: $it" }.orEmpty()
                    out += "$title — $whenTxt$loc"
                }
            }
            ToolResult.ok(if (out.isEmpty()) "لا توجد مواعيد في هذه الفترة." else out.joinToString("\n"))
        },

        Tool(
            "create_calendar_event", "يضيف موعدًا",
            "يضيف موعدًا إلى التقويم مع تنبيه قبل 15 دقيقة.",
            schema(
                "title" to prop("string", "عنوان الموعد"),
                "start" to prop("string", "YYYY-MM-DDTHH:MM"),
                "end" to prop("string", "YYYY-MM-DDTHH:MM (افتراضي بعد ساعة)"),
                "location" to prop("string", "المكان (اختياري)"),
                "notes" to prop("string", "ملاحظات (اختياري)"),
                required = listOf("title", "start")
            )
        ) { input ->
            if (!env.has(Manifest.permission.WRITE_CALENDAR)) return@Tool env.missing("التقويم")
            val start = parseMillis(input.str("start")) ?: return@Tool ToolResult.error("صيغة الوقت غير صحيحة.")
            val end = parseMillis(input.str("end")) ?: (start + 3_600_000L)
            val calId = primaryCalendar(env) ?: return@Tool ToolResult.error("لا يوجد تقويم قابل للكتابة على الجهاز.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, input.str("title"))
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                input.str("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                input.str("notes").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = env.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@Tool ToolResult.error("تعذّر إضافة الموعد.")
            val eventId = ContentUris.parseId(uri)
            env.context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 15)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
            val fmt = SimpleDateFormat("EEEE d MMM، h:mm a", Locale("ar"))
            ToolResult.ok("أُضيف \"${input.str("title")}\" في ${fmt.format(Date(start))}.")
        },

        Tool(
            "create_reminder", "يضبط التذكير",
            "ينشئ تذكيرًا يظهر كإشعار في وقت محدد (مثل: ذكرني الساعة 8 أتصل بأحمد).",
            schema(
                "time" to prop("string", "وقت التذكير YYYY-MM-DDTHH:MM"),
                "text" to prop("string", "نص التذكير"),
                required = listOf("time", "text")
            )
        ) { input ->
            val at = parseMillis(input.str("time")) ?: return@Tool ToolResult.error("صيغة الوقت غير صحيحة.")
            if (at <= System.currentTimeMillis()) return@Tool ToolResult.error("الوقت المطلوب مضى بالفعل.")
            val am = env.context.getSystemService(AlarmManager::class.java)
            val reqCode = (at / 1000 % Int.MAX_VALUE).toInt() xor input.str("text").hashCode()
            val pi = PendingIntent.getBroadcast(
                env.context, reqCode,
                Intent(env.context, ReminderReceiver::class.java)
                    .putExtra(ReminderReceiver.EXTRA_TEXT, input.str("text"))
                    .putExtra(ReminderReceiver.EXTRA_ID, reqCode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            val fmt = SimpleDateFormat("EEEE h:mm a", Locale("ar"))
            ToolResult.ok(
                "سأذكّرك ${fmt.format(Date(at))}: ${input.str("text")}" +
                    if (!exact) " (قد يتأخر دقائق قليلة لأن صلاحية المنبهات الدقيقة غير ممنوحة)" else ""
            )
        },

        Tool(
            "set_alarm", "يضبط المنبه",
            "يضبط منبهًا في تطبيق الساعة. إن لم يذكر المستخدم وقتًا فاسأله.",
            schema(
                "hour" to prop("integer", "الساعة بنظام 24 ساعة (0-23)"),
                "minute" to prop("integer", "الدقيقة"),
                "label" to prop("string", "اسم المنبه (اختياري)"),
                "days" to JSONObject().apply {
                    put("type", "array")
                    put("description", "أيام التكرار (اختياري)")
                    put("items", prop("string", "اليوم", dayMap.keys.toList()))
                },
                required = listOf("hour", "minute")
            )
        ) { input ->
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, input.optInt("hour"))
                putExtra(AlarmClock.EXTRA_MINUTES, input.optInt("minute"))
                input.str("label").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                input.optJSONArray("days")?.let { arr ->
                    val days = ArrayList<Int>()
                    for (k in 0 until arr.length()) dayMap[arr.optString(k)]?.let { days += it }
                    if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            val r = env.launch(i)
            if (r.isError) r else ToolResult.ok(
                "ضُبط المنبه على %02d:%02d.".format(Locale.US, input.optInt("hour"), input.optInt("minute"))
            )
        },

        Tool(
            "set_timer", "يضبط المؤقت",
            "يضبط مؤقتًا تنازليًا.",
            schema(
                "seconds" to prop("integer", "المدة بالثواني"),
                "label" to prop("string", "الاسم (اختياري)"),
                required = listOf("seconds")
            )
        ) { input ->
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, input.optInt("seconds").coerceIn(1, 86_400))
                input.str("label").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            val r = env.launch(i)
            if (r.isError) r else ToolResult.ok("بدأ المؤقت.")
        }
    )

    private fun primaryCalendar(env: ToolEnv): Long? {
        var fallback: Long? = null
        env.context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(3) != 1) continue
                if (c.getInt(1) == 1) return c.getLong(0)
                if (fallback == null) fallback = c.getLong(0)
            }
        }
        return fallback
    }
}
