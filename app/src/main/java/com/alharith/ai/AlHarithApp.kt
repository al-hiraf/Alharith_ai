package com.alharith.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.alharith.ai.data.Prefs

class AlHarithApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.alharith.ai.data.Health.init(this)
        Prefs.init(this)
        com.alharith.ai.data.ActivityLog.init(this)
        com.alharith.ai.data.LocalStore.init(this)
        com.alharith.ai.service.Reminders.rescheduleAll(this)
        createChannels()
        com.alharith.ai.service.BriefingReceiver.schedule(this)
        com.alharith.ai.data.SharedBrain.start(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "حالة المساعد", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "يظهر أثناء استماع الحارث لكلمة التنبيه" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "تذكيرات أنشأها الحارث" }
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
    }

    companion object {
        const val CHANNEL_ALERTS = "harith_alerts"
        const val CHANNEL_BRIEFING = "harith_briefing"
        const val CHANNEL_SERVICE = "harith_service"
        const val CHANNEL_REMINDERS = "harith_reminders"
    }
}
