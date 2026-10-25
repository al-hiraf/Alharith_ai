package com.alharith.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import com.alharith.ai.data.Prefs

class AlHarithApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.alharith.ai.data.Health.init(this)
        Prefs.init(this)
        com.alharith.ai.data.ActivityLog.init(this)
        com.alharith.ai.data.LocalStore.init(this)
        com.alharith.ai.data.ChatArchive.init(this)
        com.alharith.ai.ui.ThemeState.init()
        runCatching { com.alharith.ai.ui.HeadFonts.init(this) }
        com.alharith.ai.service.HarithNotificationListener.load(this)
        com.alharith.ai.service.Reminders.rescheduleAll(this)
        createChannels()
        com.alharith.ai.service.BriefingReceiver.schedule(this)
        com.alharith.ai.data.Push.initFromCache(this)
        com.alharith.ai.data.SharedBrain.start(this)
        com.alharith.ai.service.NoticeWorker.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "حالة المساعد", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "يظهر أثناء استماع رفيق لكلمة التنبيه" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "تذكيرات أنشأها رفيق" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "الرسائل المهمة", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "ملخص الرسائل المهمة مع رد مقترح" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIEFING, "الموجز الصباحي", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "تنبيه يومي بموجز مواعيدك ورسائلك" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER_ALARM, "منبّه التذكيرات الصوتي", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نغمة عالية متكررة للتذكيرات حتى إيقافها"
                setSound(
                    Uri.parse("android.resource://$packageName/${R.raw.rafiq_alarm}"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setBypassDnd(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "harith_alerts"
        const val CHANNEL_BRIEFING = "harith_briefing"
        const val CHANNEL_SERVICE = "harith_service"
        const val CHANNEL_REMINDERS = "harith_reminders"
        const val CHANNEL_REMINDER_ALARM = "harith_reminder_alarm"
    }
}
