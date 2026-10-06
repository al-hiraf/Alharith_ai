package com.alharith.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.alharith.ai.data.Prefs

class AlHarithApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannels()
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
    }

    companion object {
        const val CHANNEL_SERVICE = "harith_service"
        const val CHANNEL_REMINDERS = "harith_reminders"
    }
}
