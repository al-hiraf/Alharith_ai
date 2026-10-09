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
import com.alharith.ai.data.Prefs
import com.alharith.ai.ui.MainActivity
import java.util.Calendar

/**
 * الموجز الصباحي: في الوقت المحدد يظهر إشعار "موجزك جاهز"، وعند الضغط عليه
 * يجهّز رفيق مواعيد اليوم والرسائل والإيميلات المهمة ويقرؤها لك.
 * يعيد أيضًا جدولة الموجز بعد إعادة تشغيل الهاتف.
 */
class BriefingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FIRE) showNotification(context, intent.getStringExtra(EXTRA_KIND) ?: KIND_MORNING)
        else Reminders.rescheduleAll(context)   // بعد إعادة التشغيل أو تحديث التطبيق
        // في كل الحالات نجدول الموعد القادم
        schedule(context)
    }

    private fun showNotification(context: Context, kind: String) {
        val evening = kind == KIND_EVENING
        if (if (evening) !Prefs.eveningEnabled else !Prefs.briefingEnabled) return
        val open = PendingIntent.getActivity(
            context, if (evening) 23 else 21,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_BRIEFING)
                .putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_BRIEFING)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle(if (evening) "مساء الخير ${Prefs.userName}" else "صباح الخير ${Prefs.userName}")
            .setContentText(if (evening) "مراجعة يومك جاهزة — ماذا أنجزت اليوم؟" else "موجز يومك جاهز — اضغط ليقرأه لك رفيق")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, if (evening) "راجع يومي" else "اقرأ الموجز", open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(if (evening) NOTIF_ID + 1 else NOTIF_ID, n)
            ActivityLog.record(if (evening) "المراجعة المسائية" else "الموجز الصباحي", "", "تنبيه", "ظهر الإشعار")
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_FIRE = "com.alharith.ai.BRIEFING"
        private const val NOTIF_ID = 31

        const val EXTRA_KIND = "kind"
        const val KIND_MORNING = "morning"
        const val KIND_EVENING = "evening"

        private fun pending(context: Context, kind: String) = PendingIntent.getBroadcast(
            context, if (kind == KIND_EVENING) 22 else 20,
            Intent(context, BriefingReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** يجدول (أو يلغي) الموجز الصباحي والمراجعة المسائية حسب الإعدادات. */
        fun schedule(context: Context) {
            scheduleOne(context, KIND_MORNING, Prefs.briefingEnabled, Prefs.briefingTime)
            scheduleOne(context, KIND_EVENING, Prefs.eveningEnabled, Prefs.eveningTime)
        }

        private fun scheduleOne(context: Context, kind: String, enabled: Boolean, time: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(context, kind)
            am.cancel(pi)
            if (!enabled) return
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 5_000) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }

        const val EVENING_PROMPT =
            "ماذا أنجزت اليوم؟ راجع يومي: المهام التي أكملتها اليوم، المهام المتأخرة، ما تأجل، مواعيد اليوم التي مرت، " +
                "ثم اقترح أهم 3 أشياء للغد مع مواعيد الغد. باختصار وبصوت واضح."

        /** نص الطلب الذي يُرسل لرفيق عند فتح الموجز */
        const val BRIEFING_PROMPT =
            "أعطني موجز اليوم بصوت واضح ومختصر: أولًا أهم 3 مهام والمهام المتأخرة، ثانيًا مواعيد اليوم من التقويم وأقربها، " +
                "ثالثًا التذكيرات، رابعًا أهم الرسائل الواردة منذ أمس إن أمكن، خامسًا الإيميلات المهمة إن كان البريد مُعدًّا، ثم اقترح خطة لليوم. " +
                "تجاهل ما لا تستطيع الوصول إليه دون أن تذكر تفاصيل تقنية، واختم بأهم شيء يجب أن أنتبه له اليوم."
    }
}
