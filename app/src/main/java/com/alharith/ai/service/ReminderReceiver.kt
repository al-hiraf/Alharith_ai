package com.alharith.ai.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.data.ActivityLog
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.ReminderItem
import com.alharith.ai.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/** يعرض إشعار التذكير في وقته، ويجدول الموعد التالي للتذكيرات المتكررة. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // أزرار الإشعار: أجّل 10 دقائق / تم
        when (intent.action) {
            ACTION_SNOOZE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
                val r = ReminderItem(LocalStore.newId(), text, System.currentTimeMillis() + 10 * 60_000L, "none",
                    intent.getLongExtra(EXTRA_TASK, 0L))
                LocalStore.upsertReminder(r)
                Reminders.schedule(context, r)
                ActivityLog.record("التذكيرات", text, "تأجيل 10 دقائق", "سيُعاد التنبيه")
                return
            }
            ACTION_DONE -> {
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
                val tid = intent.getLongExtra(EXTRA_TASK, 0L)
                if (tid != 0L) LocalStore.updateTask(tid) { it.copy(status = "done", completedAt = System.currentTimeMillis()) }
                ActivityLog.record("التذكيرات", intent.getStringExtra(EXTRA_TEXT).orEmpty(), "تم", if (tid != 0L) "أُكملت المهمة المرتبطة" else "أُغلق التذكير")
                return
            }
        }
        val rid = intent.getLongExtra(EXTRA_RID, 0L)
        val stored = if (rid != 0L) LocalStore.reminder(rid) else null
        val text = stored?.text ?: intent.getStringExtra(EXTRA_TEXT) ?: return
        val notifId = (rid % Int.MAX_VALUE).toInt().takeIf { it != 0 } ?: intent.getIntExtra(EXTRA_ID, text.hashCode())

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("تذكير من رفيق")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "أجّل 10 دقائق", action(context, ACTION_SNOOZE, notifId, text, stored?.taskId ?: 0L))
            .addAction(0, if ((stored?.taskId ?: 0L) != 0L) "تم ✓ (أكمل المهمة)" else "تم ✓",
                action(context, ACTION_DONE, notifId, text, stored?.taskId ?: 0L))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, n)
        } catch (_: SecurityException) {
            // صلاحية الإشعارات غير ممنوحة
        }
        ActivityLog.record("التذكيرات", text, "تنبيه تذكير", "ظهر الإشعار")

        if (stored != null) {
            val next = Reminders.nextTime(stored)
            if (next != null) {
                val r = stored.copy(at = next)
                LocalStore.upsertReminder(r)
                Reminders.schedule(context, r)
            } else {
                LocalStore.deleteReminder(stored.id)
            }
        }
    }

    private fun action(context: Context, act: String, notifId: Int, text: String, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, (act.hashCode() * 31 + notifId),
            Intent(context, ReminderReceiver::class.java).setAction(act)
                .putExtra(EXTRA_ID, notifId).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_TASK, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val ACTION_SNOOZE = "com.alharith.ai.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.alharith.ai.REMINDER_DONE"
        const val EXTRA_TASK = "task"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        const val EXTRA_RID = "rid"
    }
}

/** جدولة التذكيرات (لمرة واحدة أو متكررة) عبر AlarmManager. */
object Reminders {

    private fun pending(context: Context, r: ReminderItem, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, (r.id % Int.MAX_VALUE).toInt(),
            Intent(context, ReminderReceiver::class.java).setAction("reminder_${r.id}")
                .putExtra(ReminderReceiver.EXTRA_RID, r.id)
                .putExtra(ReminderReceiver.EXTRA_TEXT, r.text),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    /** @return true إذا جُدول بدقة */
    fun schedule(context: Context, r: ReminderItem): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pending(context, r) ?: return false
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        return exact
    }

    fun cancel(context: Context, r: ReminderItem) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, r)?.let { am.cancel(it); it.cancel() }
    }

    /** يعيد جدولة كل التذكيرات (بعد إعادة تشغيل الهاتف). التذكيرات الفائتة تُقدَّم لموعدها التالي أو تُحذف. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (r in LocalStore.reminders.value) {
            var cur = r
            while (cur.at <= now) {
                val n = nextTime(cur) ?: break
                cur = cur.copy(at = n)
            }
            if (cur.at <= now) {
                LocalStore.deleteReminder(r.id)
                continue
            }
            if (cur != r) LocalStore.upsertReminder(cur)
            schedule(context, cur)
        }
    }

    fun nextTime(r: ReminderItem): Long? {
        val z = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(r.at).atZone(z)
        val n = when (r.repeat) {
            "daily" -> t.plusDays(1)
            "weekdays" -> { var d = t.plusDays(1); while (d.dayOfWeek.value == 5 || d.dayOfWeek.value == 6) d = d.plusDays(1); d }
            "weekly" -> t.plusWeeks(1)
            "monthly" -> t.plusMonths(1)
            "yearly" -> t.plusYears(1)
            else -> return null
        }
        return n.toInstant().toEpochMilli()
    }

    val REPEAT_AR = mapOf(
        "none" to "مرة واحدة", "daily" to "يوميًا", "weekdays" to "أيام العمل (الأحد–الخميس)",
        "weekly" to "أسبوعيًا", "monthly" to "شهريًا", "yearly" to "سنويًا"
    )
}
