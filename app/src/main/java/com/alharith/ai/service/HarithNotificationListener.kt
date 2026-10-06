package com.alharith.ai.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * قارئ إشعارات المراسلة (واتساب، تيليجرام، سيجنال…) عبر NotificationListenerService الرسمي.
 * هذه هي الطريقة التي تعتمدها Android Auto والساعات الذكية: قراءة الرسائل الواردة
 * والرد عليها من خلال زر "رد" الموجود في الإشعار نفسه — دون تجاوز حماية أي تطبيق.
 */
class HarithNotificationListener : NotificationListenerService() {

    data class StoredMessage(
        val id: Int,
        val packageName: String,
        val app: String,
        val sender: String,
        val text: String,
        val time: Long,
        val replyAction: Notification.Action?
    )

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        // الرسائل بنمط المحادثة (MessagingStyle) إن توفرت
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val parsed = mutableListOf<Pair<String, String>>()
        if (msgs != null) {
            for (p in msgs) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString() ?: continue
                val sender = b.getCharSequence("sender")?.toString() ?: title
                parsed += sender to text
            }
        }
        if (parsed.isEmpty()) {
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: return
            parsed += title to text
        }

        synchronized(store) {
            for ((sender, text) in parsed.takeLast(5)) {
                // تجنّب التكرار عند تحديث الإشعار نفسه
                if (store.any {
                        it.packageName == sbn.packageName && it.sender == sender && it.text == text &&
                            kotlin.math.abs(it.time - sbn.postTime) < 6 * 3_600_000L
                    }) continue
                store += StoredMessage(nextId++, sbn.packageName, app, sender, text, sbn.postTime, reply)
            }
            // تحديث زر الرد لأحدث الرسائل من نفس المحادثة
            if (reply != null) {
                for (i in store.indices) {
                    val m = store[i]
                    if (m.packageName == sbn.packageName && parsed.any { it.first == m.sender }) {
                        store[i] = m.copy(replyAction = reply)
                    }
                }
            }
            while (store.size > 300) store.removeAt(0)
        }
    }

    companion object {
        @Volatile var instance: HarithNotificationListener? = null
        private val store = mutableListOf<StoredMessage>()
        private var nextId = 1

        val APPS = mapOf(
            "com.whatsapp" to "واتساب",
            "com.whatsapp.w4b" to "واتساب للأعمال",
            "org.telegram.messenger" to "تيليجرام",
            "org.telegram.messenger.web" to "تيليجرام",
            "org.thoughtcrime.securesms" to "سيجنال",
            "com.google.android.apps.messaging" to "رسائل Google",
            "com.samsung.android.messaging" to "رسائل سامسونج",
            "com.facebook.orca" to "ماسنجر",
            "com.instagram.android" to "انستقرام",
            "com.snapchat.android" to "سناب شات",
            "com.microsoft.teams" to "تيمز",
            "com.Slack" to "سلاك"
        )

        fun messages(): List<StoredMessage> = synchronized(store) { store.toList() }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, HarithNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** يرد عبر زر "رد" في الإشعار (RemoteInput) — الآلية الرسمية في Android. */
        fun reply(context: Context, msg: StoredMessage, text: String): Boolean {
            val action = msg.replyAction ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()
            inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
