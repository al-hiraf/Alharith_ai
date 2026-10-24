package com.alharith.ai.service

import com.alharith.ai.data.Prefs
import com.alharith.ai.data.Push
import com.alharith.ai.data.SharedBrain
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** رسالة الإيقاظ من الخادم (data: t=sync). لا تحمل نصًا؛ نجلب الإشعارات من الخادم بمفتاح المستخدم. */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "sync" || !Prefs.sharedBrainReady) return
        // نافذة المعالجة قصيرة (~20 ثانية): إن لم تكتمل يكمل الجلب الدوري
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { runCatching { SharedBrain.syncNow() } }
        }
    }

    override fun onNewToken(token: String) {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { runCatching { Push.onNewToken(token) } }
        }
    }
}
