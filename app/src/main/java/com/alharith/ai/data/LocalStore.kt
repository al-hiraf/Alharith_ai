package com.alharith.ai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// ———————————————————————— النماذج

data class SubTask(val title: String, val done: Boolean = false)

data class TaskItem(
    val id: Long,
    val title: String,
    val description: String = "",
    val project: String = "",
    val category: String = "",
    val priority: String = "medium",   // low | medium | high | urgent
    val status: String = "new",        // new | in_progress | done | postponed | cancelled
    val due: String = "",              // YYYY-MM-DD أو YYYY-MM-DDTHH:MM
    val repeat: String = "none",       // none | daily | weekly | monthly | yearly
    val person: String = "",
    val notes: String = "",
    val links: String = "",
    val subtasks: List<SubTask> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L
) {
    val isOpen get() = status == "new" || status == "in_progress" || status == "postponed"

    /** وقت الاستحقاق بالمللي ثانية (نهاية اليوم إن لم يُحدد وقت) */
    val dueMillis: Long?
        get() = runCatching {
            val z = ZoneId.systemDefault()
            if (due.length <= 10) LocalDate.parse(due).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
            else LocalDateTime.parse(due.take(16)).atZone(z).toInstant().toEpochMilli()
        }.getOrNull()

    val isOverdue get() = isOpen && (dueMillis?.let { it < System.currentTimeMillis() } ?: false)

    val dueDate: LocalDate? get() = runCatching { LocalDate.parse(due.take(10)) }.getOrNull()

    val priorityRank get() = when (priority) { "urgent" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("description", description); put("project", project)
        put("category", category); put("priority", priority); put("status", status); put("due", due)
        put("repeat", repeat); put("person", person); put("notes", notes); put("links", links)
        put("subtasks", JSONArray().apply { subtasks.forEach { put(JSONObject().put("t", it.title).put("d", it.done)) } })
        put("created", createdAt); put("completed", completedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = TaskItem(
            id = o.optLong("id"), title = o.optString("title"), description = o.optString("description"),
            project = o.optString("project"), category = o.optString("category"),
            priority = o.optString("priority", "medium"), status = o.optString("status", "new"),
            due = o.optString("due"), repeat = o.optString("repeat", "none"), person = o.optString("person"),
            notes = o.optString("notes"), links = o.optString("links"),
            subtasks = o.optJSONArray("subtasks")?.let { a ->
                (0 until a.length()).map { val s = a.getJSONObject(it); SubTask(s.optString("t"), s.optBoolean("d")) }
            } ?: emptyList(),
            createdAt = o.optLong("created"), completedAt = o.optLong("completed")
        )

        val PRIORITY_AR = mapOf("low" to "منخفضة", "medium" to "متوسطة", "high" to "عالية", "urgent" to "عاجلة")
        val STATUS_AR = mapOf(
            "new" to "جديدة", "in_progress" to "قيد التنفيذ", "done" to "مكتملة",
            "postponed" to "مؤجلة", "cancelled" to "ملغاة"
        )
    }
}

data class NoteItem(val id: Long, val title: String, val body: String, val tags: String = "", val createdAt: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("body", body).put("tags", tags).put("created", createdAt)
    companion object {
        fun fromJson(o: JSONObject) = NoteItem(o.optLong("id"), o.optString("title"), o.optString("body"), o.optString("tags"), o.optLong("created"))
    }
}

/** معلومة في الذاكرة الشخصية. kind: long (دائمة) | temp (مؤقتة تنتهي تلقائيًا) */
data class MemoryItem(val id: Long, val text: String, val kind: String = "long", val createdAt: Long = System.currentTimeMillis(), val expiresAt: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("kind", kind).put("created", createdAt).put("expires", expiresAt)
    companion object {
        fun fromJson(o: JSONObject) = MemoryItem(o.optLong("id"), o.optString("text"), o.optString("kind", "long"), o.optLong("created"), o.optLong("expires"))
    }
}

data class ReminderItem(val id: Long, val text: String, val at: Long, val repeat: String = "none", val taskId: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("at", at).put("repeat", repeat).put("task", taskId)
    companion object {
        fun fromJson(o: JSONObject) = ReminderItem(o.optLong("id"), o.optString("text"), o.optLong("at"), o.optString("repeat", "none"), o.optLong("task"))
    }
}

// ———————————————————————— المخزن

/**
 * بيانات الحارث المحلية (المهام، الملاحظات، الذاكرة، التذكيرات) في ملف JSON على الجهاز.
 * تعمل بدون إنترنت بالكامل.
 */
object LocalStore {
    private lateinit var file: File

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()
    private val _notes = MutableStateFlow<List<NoteItem>>(emptyList())
    val notes: StateFlow<List<NoteItem>> = _notes.asStateFlow()
    private val _memories = MutableStateFlow<List<MemoryItem>>(emptyList())
    val memories: StateFlow<List<MemoryItem>> = _memories.asStateFlow()
    private val _reminders = MutableStateFlow<List<ReminderItem>>(emptyList())
    val reminders: StateFlow<List<ReminderItem>> = _reminders.asStateFlow()

    private var lastId = 0L

    @Synchronized
    fun newId(): Long {
        val t = System.currentTimeMillis()
        lastId = if (t > lastId) t else lastId + 1
        return lastId
    }

    fun init(context: Context) {
        file = File(context.filesDir, "harith_data.json")
        // إن تلف الملف الأساسي لأي سبب نستعيد آخر نسخة احتياطية سليمة
        val backup = File(file.parentFile, file.name + ".bak")
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: runCatching { JSONObject(backup.readText()) }.getOrNull()?.also {
                if (file.exists()) ActivityLog.record("الاعتمادية", "ملف البيانات", "استعادة نسخة احتياطية", "استُعيدت البيانات بعد تلف الملف الأساسي", ok = false)
            }
            ?: JSONObject()
        fun <T> list(key: String, f: (JSONObject) -> T): List<T> =
            o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
        _tasks.value = list("tasks", TaskItem::fromJson)
        _notes.value = list("notes", NoteItem::fromJson)
        _memories.value = list("memories", MemoryItem::fromJson)
            .filter { it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis() }
        _reminders.value = list("reminders", ReminderItem::fromJson)
    }

    @Synchronized
    private fun save() {
        if (!::file.isInitialized) return
        val o = JSONObject().apply {
            put("tasks", JSONArray().apply { _tasks.value.forEach { put(it.toJson()) } })
            put("notes", JSONArray().apply { _notes.value.forEach { put(it.toJson()) } })
            put("memories", JSONArray().apply { _memories.value.forEach { put(it.toJson()) } })
            put("reminders", JSONArray().apply { _reminders.value.forEach { put(it.toJson()) } })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        // نسخة احتياطية من آخر حالة سليمة، ثم استبدال ذري للملف
        if (file.exists()) runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ——— المهام
    fun task(id: Long) = _tasks.value.firstOrNull { it.id == id }
    fun addTask(t: TaskItem) { _tasks.value = _tasks.value + t; save() }
    fun updateTask(id: Long, f: (TaskItem) -> TaskItem): TaskItem? {
        var out: TaskItem? = null
        _tasks.value = _tasks.value.map { if (it.id == id) f(it).also { n -> out = n } else it }
        save(); return out
    }
    fun deleteTask(id: Long) { _tasks.value = _tasks.value.filterNot { it.id == id }; save() }

    // ——— الملاحظات
    fun addNote(n: NoteItem) { _notes.value = _notes.value + n; save() }
    fun deleteNote(id: Long) { _notes.value = _notes.value.filterNot { it.id == id }; save() }

    // ——— الذاكرة
    fun addMemory(m: MemoryItem) { _memories.value = _memories.value + m; save() }
    fun updateMemory(id: Long, text: String) { _memories.value = _memories.value.map { if (it.id == id) it.copy(text = text) else it }; save() }
    fun deleteMemory(id: Long) { _memories.value = _memories.value.filterNot { it.id == id }; save() }
    fun clearMemories() { _memories.value = emptyList(); save() }
    fun activeMemories() = _memories.value.filter { it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis() }

    // ——— التذكيرات
    fun reminder(id: Long) = _reminders.value.firstOrNull { it.id == id }
    fun upsertReminder(r: ReminderItem) { _reminders.value = _reminders.value.filterNot { it.id == r.id } + r; save() }
    fun deleteReminder(id: Long) { _reminders.value = _reminders.value.filterNot { it.id == id }; save() }

    // ——— التصدير والمسح
    fun exportJson(): String = JSONObject().apply {
        put("exported_at", System.currentTimeMillis())
        put("tasks", JSONArray().apply { _tasks.value.forEach { put(it.toJson()) } })
        put("notes", JSONArray().apply { _notes.value.forEach { put(it.toJson()) } })
        put("memories", JSONArray().apply { _memories.value.forEach { put(it.toJson()) } })
        put("reminders", JSONArray().apply { _reminders.value.forEach { put(it.toJson()) } })
    }.toString(2)

    fun wipeAll() {
        _tasks.value = emptyList(); _notes.value = emptyList(); _memories.value = emptyList(); _reminders.value = emptyList()
        save()
    }
}
