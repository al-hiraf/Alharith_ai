package com.alharith.ai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * سجل المحادثات السابقة: عند بدء محادثة جديدة تُحفظ السابقة هنا (على الجوال فقط، داخل مساحة التطبيق الخاصة)
 * لعرضها لاحقًا. آخر 30 محادثة، ويمكن حذف أي واحدة أو الكل.
 */
object ChatArchive {
    data class Conversation(val id: Long, val at: Long, val title: String, val messages: List<ChatMessage>)

    private var file: File? = null
    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "chat_archive.json")
        _items.value = runCatching {
            val a = JSONArray(file!!.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ms = o.getJSONArray("m")
                Conversation(o.getLong("id"), o.getLong("at"), o.optString("title"),
                    (0 until ms.length()).map { j -> ms.getJSONObject(j).let { m -> ChatMessage(id = j.toLong(), fromUser = m.optBoolean("u"), text = m.optString("t")) } })
            }
        }.getOrDefault(emptyList())
    }

    /** يحفظ المحادثة الحالية قبل مسحها (إن كان فيها طلب واحد على الأقل) */
    @Synchronized
    fun archive(messages: List<ChatMessage>) {
        val real = messages.filter { !it.isAction && it.text.isNotBlank() }
        val first = real.firstOrNull { it.fromUser } ?: return
        val c = Conversation(System.currentTimeMillis(), System.currentTimeMillis(), first.text.take(60), real.takeLast(200))
        _items.value = (listOf(c) + _items.value).take(30)
        save()
    }

    @Synchronized
    fun delete(id: Long) { _items.value = _items.value.filterNot { it.id == id }; save() }

    @Synchronized
    fun clear() { _items.value = emptyList(); save() }

    private fun save() {
        val f = file ?: return
        runCatching {
            val a = JSONArray()
            _items.value.forEach { c ->
                a.put(JSONObject().put("id", c.id).put("at", c.at).put("title", c.title).put("m", JSONArray().apply {
                    c.messages.forEach { m -> put(JSONObject().put("u", m.fromUser).put("t", m.text)) }
                }))
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        }
    }
}
