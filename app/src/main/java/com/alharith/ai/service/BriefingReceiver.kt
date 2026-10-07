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
 * يجهّز الحارث مواعيد اليوم والرسائل والإيميلات المهمة ويقرؤها لك.
 * يعيد أيضًا جدولة الموجز بعد إعادة تشغيل الهاتف.
 */
class BriefingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FIRE) showNotification(context)
        // في كل الحالات (التنبيه نفسه، إعادة التشغيل، تحديث التطبيق) نجدول الموعد القادم
        schedule(context)
    }

    private fun showNotification(context: Context) {
        if (!Prefs.briefingEnabled) return
        val open = PendingIntent.getActivity(
            context, 21,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_BRIEFING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_BRIEFING)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("صباح الخير ${Prefs.userName}")
            .setContentText("موجز يومك جاهز — اضغط ليقرأه لك الحارث")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "اقرأ الموجز", open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
            ActivityLog.record("الموجز الصباحي", "الموعد ${Prefs.briefingTime}", "تنبيه الموجز", "ظهر إشعار الموجز")
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_FIRE = "com.alharith.ai.BRIEFING"
        private const val NOTIF_ID = 31

        private fun pending(context: Context) = PendingIntent.getBroadcast(
            context, 20,
            Intent(context, BriefingReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** يجدول (أو يلغي) الموجز القادم حسب الإعدادات. */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(context)
            am.cancel(pi)
            if (!Prefs.briefingEnabled) return
            val parts = Prefs.briefingTime.split(":")
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

        /** نص الطلب الذي يُرسل للحارث عند فتح الموجز */
        const val BRIEFING_PROMPT =
            "أعطني موجز اليوم بصوت واضح ومختصر: أولًا مواعيد اليوم من التقويم، ثانيًا أهم الرسائل الواردة منذ أمس " +
                "(SMS ورسائل التطبيقات إن أمكن)، ثالثًا الإيميلات غير المقروءة المهمة إن كان البريد مُعدًّا. " +
                "تجاهل ما لا تستطيع الوصول إليه دون أن تذكر تفاصيل تقنية، واختم بأهم شيء يجب أن أنتبه له اليوم."
    }
}
