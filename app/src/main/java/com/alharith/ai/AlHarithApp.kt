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
        com.alharith.ai.data.ProfilePhoto.init(this)
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
        // نغمات رفيق الهادئة: لكل نوع نغمة قصيرة ناعمة بدل صوت النظام الحاد.
        // صوت القناة لا يتغير بعد إنشائها في أندرويد، لذلك قنوات بمعرّفات جديدة (v2) وتُزال القديمة
        listOf("harith_reminders", "harith_alerts", "harith_briefing").forEach { runCatching { nm.deleteNotificationChannel(it) } }
        fun soft(raw: Int) = Uri.parse("android.resource://$packageName/$raw")
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val gentleBuzz = longArrayOf(0, 120, 90, 120)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تذكيرات أنشأها رفيق — نغمة هادئة"
                setSound(soft(R.raw.rafiq_chime), notifAttrs); enableVibration(true); vibrationPattern = gentleBuzz
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "الرسائل المهمة", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "ملخص الرسائل المهمة مع رد مقترح"
                setSound(soft(R.raw.rafiq_alert), notifAttrs); enableVibration(true); vibrationPattern = gentleBuzz
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                // الموجز ليس عاجلًا: يصل بنغمة صباحية ناعمة بلا نافذة منبثقة فوق الشاشة
                CHANNEL_BRIEFING, "الموجز الصباحي", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "تنبيه يومي بموجز مواعيدك ورسائلك"
                setSound(soft(R.raw.rafiq_morning), notifAttrs); enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER_ALARM, "منبّه التذكيرات الصوتي", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نغمة هادئة تتكرر وتعلو تدريجيًا حتى إيقافها"
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
        const val CHANNEL_ALERTS = "rafiq_alerts_v2"
        const val CHANNEL_BRIEFING = "rafiq_briefing_v2"
        const val CHANNEL_SERVICE = "harith_service"
        const val CHANNEL_REMINDERS = "rafiq_reminders_v2"
        const val CHANNEL_REMINDER_ALARM = "harith_reminder_alarm"
    }
}
