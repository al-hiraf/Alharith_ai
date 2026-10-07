package com.alharith.ai.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.brain.ClaudeClient
import com.alharith.ai.data.ActivityLog
import com.alharith.ai.data.Prefs
import com.alharith.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * تنبيه الرسائل المهمة: عند وصول رسالة يقيّم الحارث أهميتها، وإن كانت مهمة
 * يعرض إشعارًا بملخصها مع رد مقترح. الرد لا يُرسل إلا إذا ضغط المستخدم "أرسل الرد".
 */
object ImportantAlerts {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ClaudeClient()
    private val lastBySender = ConcurrentHashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    /** الردود المقترحة بانتظار ضغطة المستخدم: رقم الرسالة ← النص */
    private val suggestions = ConcurrentHashMap<Int, String>()

    private const val CLASSIFIER_MODEL = "claude-haiku-4-5-20251001"

    fun consider(context: Context, msg: HarithNotificationListener.StoredMessage) {
        if (!Prefs.importantAlerts || Prefs.claudeApiKey.isBlank()) return
        val key = msg.packageName + "|" + msg.sender
        val now = System.currentTimeMillis()
        // لا أكثر من تقييم لكل مرسل كل 3 دقائق، ولا أكثر من 40 تقييمًا في الساعة (للتكلفة)
        if (now - (lastBySender[key] ?: 0L) < 3 * 60_000L) return
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > 3_600_000L) recent.removeFirst()
            if (recent.size >= 40) return
            recent.addLast(now)
        }
        lastBySender[key] = now
        val app = context.applicationContext
        scope.launch { runCatching { evaluate(app, msg) } }
    }

    private suspend fun evaluate(context: Context, msg: HarithNotificationListener.StoredMessage) {
        val system = """
أنت مساعد يقيّم رسائل ${Prefs.userName} الواردة. أعد JSON فقط بدون أي نص آخر:
{"important": true أو false, "summary": "ملخص عربي في جملة", "reply": "رد مقترح قصير بصيغة المتكلم نيابة عن ${Prefs.userName}، أو نص فارغ إن لم يلزم رد"}
المهم: طلب عاجل، موعد، مال أو عمل، سؤال مباشر ينتظر جوابًا، خبر عائلي مهم. غير المهم: إعلانات، تحيات عامة، رسائل مجموعات عامة، رموز تحقق.
""".trim()
        val user = "التطبيق: ${msg.app}\nالمرسل: ${msg.sender}\nالرسالة: ${msg.text.take(1500)}"
        val resp = client.send(
            Prefs.claudeApiKey, CLASSIFIER_MODEL, system, JSONArray(),
            JSONArray().put(JSONObject().put("role", "user").put("content", user)),
            maxTokens = 400
        )
        val text = resp.optJSONArray("content")?.let { c ->
            (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
        }.orEmpty()
        val json = runCatching { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }.getOrNull() ?: return
        if (!json.optBoolean("important")) return
        val summary = json.optString("summary").ifBlank { msg.text.take(120) }
        val reply = json.optString("reply").trim()
        ActivityLog.record(msg.app, "رسالة من ${msg.sender}", "تقييم الأهمية", "مهمة: $summary")
        notify(context, msg, summary, reply)
    }

    private fun notify(context: Context, msg: HarithNotificationListener.StoredMessage, summary: String, reply: String) {
        val open = PendingIntent.getActivity(
            context, 40, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("رسالة مهمة من ${msg.sender} (${msg.app})")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (reply.isNotBlank() && msg.replyAction != null) {
            suggestions[msg.id] = reply
            b.setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n\nرد مقترح: \"$reply\""))
            b.addAction(0, "أرسل الرد المقترح", action(context, ACTION_SEND, msg.id))
            b.addAction(0, "تجاهل", action(context, ACTION_DISMISS, msg.id))
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(summary))
        }
        try {
            NotificationManagerCompat.from(context).notify(notifId(msg.id), b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun notifId(msgId: Int) = 5000 + (msgId % 1000)

    private fun action(context: Context, act: String, msgId: Int) = PendingIntent.getBroadcast(
        context, msgId * 2 + if (act == ACTION_SEND) 0 else 1,
        Intent(context, AlertActionReceiver::class.java).setAction(act).putExtra(EXTRA_ID, msgId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    const val ACTION_SEND = "com.alharith.ai.ALERT_SEND"
    const val ACTION_DISMISS = "com.alharith.ai.ALERT_DISMISS"
    const val EXTRA_ID = "msg_id"

    /** ينفذ ضغطة المستخدم على أزرار الإشعار */
    fun handleAction(context: Context, act: String, msgId: Int) {
        val nm = NotificationManagerCompat.from(context)
        val text = suggestions.remove(msgId)
        if (act != ACTION_SEND || text == null) {
            nm.cancel(notifId(msgId))
            return
        }
        val msg = HarithNotificationListener.messages().lastOrNull { it.id == msgId }
        val ok = msg != null && HarithNotificationListener.reply(context, msg, text)
        ActivityLog.record(
            msg?.app ?: "رسالة", "رد مقترح وافقت عليه", "إرسال الرد",
            if (ok) "أُرسل إلى ${msg?.sender}: $text" else "تعذّر الإرسال (انتهى إشعار الرسالة)", ok
        )
        val done = NotificationCompat.Builder(context, AlHarithApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle(if (ok) "أُرسل الرد إلى ${msg?.sender}" else "تعذّر إرسال الرد")
            .setContentText(if (ok) text else "افتح المحادثة وأرسله يدويًا.")
            .setTimeoutAfter(if (ok) 6_000 else 0)
            .setAutoCancel(true)
            .build()
        try { nm.notify(notifId(msgId), done) } catch (_: SecurityException) {}
    }
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ImportantAlerts.EXTRA_ID, -1)
        if (id >= 0) ImportantAlerts.handleAction(context, intent.action.orEmpty(), id)
    }
}
