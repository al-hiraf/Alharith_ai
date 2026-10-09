package com.alharith.ai.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** سجل شفاف لكل ما نفّذه رفيق: الوقت، المصدر، الأمر، الإجراء، النتيجة. */
object ActivityLog {

    data class Entry(
        val time: Long,
        val source: String,
        val command: String,
        val action: String,
        val result: String,
        val ok: Boolean
    )

    private lateinit var sp: SharedPreferences
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences("harith_log", Context.MODE_PRIVATE)
        _entries.value = runCatching {
            val a = JSONArray(sp.getString("log", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(o.optLong("t"), o.optString("s"), o.optString("c"), o.optString("a"), o.optString("r"), o.optBoolean("ok", true))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(source: String, command: String, action: String, result: String, ok: Boolean = true) {
        if (!::sp.isInitialized) return
        val e = Entry(System.currentTimeMillis(), source, command.take(200), action.take(120), result.take(300), ok)
        val list = (listOf(e) + _entries.value).take(300)
        _entries.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("t", it.time); put("s", it.source); put("c", it.command)
                put("a", it.action); put("r", it.result); put("ok", it.ok)
            })
        }
        sp.edit().putString("log", arr.toString()).apply()
    }

    fun clear() {
        _entries.value = emptyList()
        if (::sp.isInitialized) sp.edit().putString("log", "[]").apply()
    }
}
