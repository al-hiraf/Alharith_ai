package com.alharith.ai.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * العقل المشترك: يربط التطبيق بخادم رفيق (على Termux في نفس الجوال أو على خادم).
 * - مزامنة ثنائية للمهام والذاكرة (الأحدث يفوز)
 * - إشعارات الخادم (تذكيرات تيليجرام، الملخصات، طلبات الموافقة) تظهر كإشعارات على الجوال
 * - تحويل طلبات المشاريع والجدولة والتقارير إلى وكيل الخادم
 */
object SharedBrain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var debounce: Job? = null
    private var loop: Job? = null
    private lateinit var appCtx: Context

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    class ServerException(message: String) : Exception(message)

    fun start(context: Context) {
        appCtx = context.applicationContext
        _status.value = Prefs.lastSyncStatus
        LocalStore.onLocalChange = { requestSync() }
        loop?.cancel()
        loop = scope.launch {
            delay(4_000)
            while (isActive) {
                if (Prefs.sharedBrainReady) runCatching { syncNow() }
                delay(5 * 60_000L)
            }
        }
    }

    /** مزامنة بعد تعديل محلي (تأخير قصير لتجميع التعديلات المتتالية) */
    fun requestSync() {
        if (!Prefs.sharedBrainReady) return
        debounce?.cancel()
        debounce = scope.launch { delay(2_500); runCatching { syncNow() } }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val url = Prefs.serverUrl + path
        val b = Request.Builder().url(url).header("Authorization", "Bearer ${Prefs.serverToken}")
        if (body != null) b.method(method, body.toString().toRequestBody(JSON_TYPE)) else b.method(method, null)
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw ServerException("مفتاح الوصول غير صالح — أنشئ مفتاحًا جديدًا من لوحة التحكم")
            if (!r.isSuccessful) throw ServerException(runCatching { JSONObject(text).optString("error") }.getOrNull()
                ?.ifBlank { null } ?: "الخادم أعاد ${r.code}")
            return if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text.ifBlank { "{}" })
        }
    }

    /** فحص الاتصال: يعيد اسم المستخدم على الخادم */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val me = request("GET", "/api/me").getJSONObject("user")
        me.optString("display_name").ifBlank { me.optString("username") }
    }

    suspend fun syncNow(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                val since = Prefs.lastPushMs
                val tasks = JSONArray()
                LocalStore.tasks.value.filter { since == 0L || it.updatedAt > since }.forEach { t ->
                    tasks.put(JSONObject().put("ref", t.syncRef).put("title", t.title)
                        .put("notes", t.notes)
                        .put("priority", t.priority).put("status", t.status).put("due", t.due)
                        .put("updated_ms", if (t.updatedAt > 0) t.updatedAt else t.createdAt))
                }
                val mems = JSONArray()
                LocalStore.memories.value.filter { since == 0L || it.updatedAt > since }.forEach { m ->
                    mems.put(JSONObject().put("ref", m.syncRef).put("text", m.text)
                        .put("updated_ms", if (m.updatedAt > 0) m.updatedAt else m.createdAt).put("expires_ms", m.expiresAt))
                }
                val tombs = LocalStore.pendingTombstones()
                tombs.forEach { tb ->
                    val o = JSONObject().put("ref", tb.ref).put("deleted", true).put("updated_ms", tb.at)
                    if (tb.kind == "task") tasks.put(o) else mems.put(o)
                }
                val body = JSONObject().put("tasks", tasks).put("memories", mems)
                if (Prefs.syncCursor.isNotBlank()) body.put("since", Prefs.syncCursor)
                val r = request("POST", "/api/sync", body)

                val inTasks = r.optJSONArray("tasks") ?: JSONArray()
                val remoteTasks = (0 until inTasks.length()).map { i ->
                    val o = inTasks.getJSONObject(i)
                    TaskItem(
                        id = 0, ref = o.getString("ref"), title = o.optString("title"), notes = o.optString("notes"),
                        priority = when (o.optString("priority")) { "high" -> "high"; "low" -> "low"; else -> "medium" },
                        status = when (o.optString("status")) { "open" -> "new"; "in_progress" -> "in_progress"; "done" -> "done"; else -> "cancelled" },
                        due = o.optString("due"), updatedAt = o.optLong("updated_ms"),
                        completedAt = if (o.optString("status") == "done") System.currentTimeMillis() else 0L
                    )
                }
                val inMem = r.optJSONArray("memories") ?: JSONArray()
                val remoteMems = (0 until inMem.length()).map { i ->
                    val o = inMem.getJSONObject(i)
                    MemoryItem(id = 0, ref = o.getString("ref"), text = o.optString("text"),
                        kind = if (o.optLong("expires_ms") > 0) "temp" else "long", expiresAt = o.optLong("expires_ms"),
                        updatedAt = o.optLong("updated_ms"))
                }
                val del = r.optJSONArray("deleted") ?: JSONArray()
                val deleted = (0 until del.length()).map { del.getJSONObject(it).let { d -> d.optString("kind") to d.optString("ref") } }
                LocalStore.applyRemote(remoteTasks, remoteMems, deleted)
                LocalStore.clearTombstones(tombs)
                Prefs.syncCursor = r.optString("cursor")
                Prefs.lastPushMs = started
                pullNotifications()
                val msg = "تمت المزامنة ${java.text.SimpleDateFormat("h:mm a", java.util.Locale("ar")).format(java.util.Date())} — " +
                    "أُرسل ${tasks.length() + mems.length()} واستُقبل ${remoteTasks.size + remoteMems.size + deleted.size}"
                Prefs.lastSyncStatus = msg; _status.value = msg
                msg
            } catch (e: Exception) {
                val msg = "تعذّرت المزامنة: ${e.message ?: e.javaClass.simpleName}"
                Prefs.lastSyncStatus = msg; _status.value = msg
                throw e
            }
        }
    }

    /** إشعارات الخادم (تذكير من تيليجرام، ملخص، طلب موافقة…) تظهر على الجوال */
    private fun pullNotifications() {
        val first = Prefs.lastNoticeId < 0
        val r = request("GET", "/api/notifications?after=${maxOf(0, Prefs.lastNoticeId)}")
        val items = r.optJSONArray("items") ?: return
        var last = Prefs.lastNoticeId
        for (i in 0 until items.length()) {
            val n = items.getJSONObject(i)
            last = maxOf(last, n.optLong("id"))
            if (first) continue // أول ربط: لا نُغرق المستخدم بالإشعارات القديمة
            show(n.optLong("id"), n.optString("kind"), n.optString("content"))
        }
        Prefs.lastNoticeId = maxOf(last, 0)
    }

    private fun show(id: Long, kind: String, text: String) {
        if (!::appCtx.isInitialized) return
        val ctx = appCtx
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (kind) {
            "reminder" -> "تذكير من رفيق"; "approval" -> "طلب موافقة"; "briefing" -> "ملخص رفيق"; else -> "رفيق"
        }
        val n = NotificationCompat.Builder(ctx, if (kind == "briefing") AlHarithApp.CHANNEL_BRIEFING else AlHarithApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_harith).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(ctx).notify(900_000 + (id % 90_000).toInt(), n) } catch (_: SecurityException) { }
    }

    /** يمرر طلبًا لوكيل الخادم (مشاريع، مهام مجدولة، تقارير، بريد بموافقة…) ويعيد رده */
    suspend fun ask(text: String): JSONObject = withContext(Dispatchers.IO) {
        val r = request("POST", "/api/chat", JSONObject().put("text", text).put("channel", "android"))
        requestSync()
        r
    }
}
