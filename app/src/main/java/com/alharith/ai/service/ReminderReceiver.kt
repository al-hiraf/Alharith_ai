package com.alharith.ai.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.ui.MainActivity

/** يعرض إشعار التذكير في وقته. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val id = intent.getIntExtra(EXTRA_ID, text.hashCode())
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("تذكير من الحارث")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // صلاحية الإشعارات غير ممنوحة
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
