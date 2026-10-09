package com.alharith.ai.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الاعتمادية: تسجيل الأعطال المفاجئة، حالة الشبكة، وطابور الأوامر المؤجلة لحين عودة الإنترنت.
 */
object Health {
    private lateinit var appContext: Context
    private fun crashFile() = File(appContext.filesDir, "last_crash.txt")
    private fun queueFile() = File(appContext.filesDir, "pending_commands.json")

    fun init(context: Context) {
        appContext = context.applicationContext
        // أي عطل مفاجئ يُحفظ تفصيله ليظهر في شاشة "فحص الحارث" ويمكن مشاركته
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { recordCrash("thread ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    fun recordCrash(where: String, e: Throwable) {
        if (!::appContext.isInitialized) return
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        crashFile().writeText("$ts — $where\n${Log.getStackTraceString(e)}".take(20_000))
    }

    fun lastCrash(): String? = if (!::appContext.isInitialized) null
    else crashFile().takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clearCrash() { if (::appContext.isInitialized) crashFile().delete() }

    fun isOnline(context: Context = appContext): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ——— طابور الأوامر عند انقطاع الإنترنت (يبقى محفوظًا حتى بعد إغلاق التطبيق)

    @Synchronized
    fun enqueue(text: String) {
        val a = pending().toMutableList().apply { add(text) }.takeLast(20)
        queueFile().writeText(JSONArray(a).toString())
    }

    @Synchronized
    fun pending(): List<String> = runCatching {
        val a = JSONArray(queueFile().readText())
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun takeAll(): List<String> = pending().also { queueFile().delete() }
}
