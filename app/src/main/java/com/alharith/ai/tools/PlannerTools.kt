package com.alharith.ai.tools

import android.Manifest
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.MemoryItem
import com.alharith.ai.data.NoteItem
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SubTask
import com.alharith.ai.data.TaskItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** المهام والملاحظات والذاكرة والبحث الشامل — كلها محلية على الجهاز وتعمل بدون إنترنت. */
object PlannerTools {

    private val PRIORITIES = listOf("low", "medium", "high", "urgent")
    private val STATUSES = listOf("new", "in_progress", "done", "postponed", "cancelled")
    private val REPEATS = listOf("none", "daily", "weekly", "monthly", "yearly")

    fun describe(t: TaskItem): String = buildString {
        append("[task_id=${t.id}] ${t.title}")
        append(" — ${TaskItem.STATUS_AR[t.status] ?: t.status}، أولوية ${TaskItem.PRIORITY_AR[t.priority] ?: t.priority}")
        if (t.due.isNotBlank()) append("، الاستحقاق ${t.due}")
        if (t.isOverdue) append(" (متأخرة)")
        if (t.project.isNotBlank()) append("، مشروع: ${t.project}")
        if (t.person.isNotBlank()) append("، مع: ${t.person}")
        if (t.repeat != "none") append("، تتكرر: ${t.repeat}")
        if (t.description.isNotBlank()) append("\n  الوصف: ${t.description.take(300)}")
        if (t.notes.isNotBlank()) append("\n  ملاحظات: ${t.notes.take(300)}")
        if (t.subtasks.isNotEmpty()) append("\n  مهام فرعية: " + t.subtasks.joinToString("، ") { (if (it.done) "✓ " else "○ ") + it.title })
    }

    private fun strList(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

    private fun arr(desc: String) = JSONObject().apply {
        put("type", "array"); put("description", desc); put("items", JSONObject().put("type", "string"))
    }

    /** يحسب الاستحقاق التالي للمهمة المتكررة */
    private fun nextDue(t: TaskItem): String? {
        if (t.repeat == "none" || t.due.isBlank()) return null
        return runCatching {
            if (t.due.length <= 10) {
                val d = LocalDate.parse(t.due)
                when (t.repeat) { "daily" -> d.plusDays(1); "weekly" -> d.plusWeeks(1); "monthly" -> d.plusMonths(1); else -> d.plusYears(1) }.toString()
            } else {
                val d = LocalDateTime.parse(t.due.take(16))
                val n = when (t.repeat) { "daily" -> d.plusDays(1); "weekly" -> d.plusWeeks(1); "monthly" -> d.plusMonths(1); else -> d.plusYears(1) }
                n.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            }
        }.getOrNull()
    }

    fun tools(env: ToolEnv): List<Tool> = listOf(

        // ——————————————— المهام

        Tool(
            "create_task", "ينشئ مهمة",
            "ينشئ مهمة في قائمة مهام المستخدم. استخرج من كلامه العنوان والموعد والأولوية والمشروع والشخص المرتبط.",
            schema(
                "title" to prop("string", "عنوان المهمة"),
                "description" to prop("string", "الوصف (اختياري)"),
                "due" to prop("string", "الاستحقاق YYYY-MM-DD أو YYYY-MM-DDTHH:MM (اختياري)"),
                "priority" to prop("string", "الأولوية (افتراضي medium)", PRIORITIES),
                "project" to prop("string", "المشروع (اختياري)"),
                "category" to prop("string", "التصنيف (اختياري)"),
                "person" to prop("string", "الشخص المرتبط (اختياري)"),
                "repeat" to prop("string", "التكرار (افتراضي none)", REPEATS),
                "notes" to prop("string", "ملاحظات (اختياري)"),
                "links" to prop("string", "روابط (اختياري)"),
                "subtasks" to arr("مهام فرعية (اختياري)"),
                required = listOf("title")
            )
        ) { input ->
            val t = TaskItem(
                id = LocalStore.newId(), title = input.str("title"), description = input.str("description"),
                due = input.str("due"), priority = input.str("priority").takeIf { it in PRIORITIES } ?: "medium",
                project = input.str("project"), category = input.str("category"), person = input.str("person"),
                repeat = input.str("repeat").takeIf { it in REPEATS } ?: "none", notes = input.str("notes"),
                links = input.str("links"), subtasks = strList(input, "subtasks").map { SubTask(it) }
            )
            if (t.title.isBlank()) return@Tool ToolResult.error("عنوان المهمة فارغ.")
            LocalStore.addTask(t)
            ToolResult.ok("أُنشئت المهمة: " + describe(t))
        },

        Tool(
            "list_tasks", "يراجع المهام",
            "يعرض مهام المستخدم مع الفلترة. استخدمه لـ: ما مهامي اليوم؟ المهام المتأخرة؟ مهام مشروع كذا؟ رتب لي يومي.",
            schema(
                "filter" to prop("string", "open=المفتوحة (افتراضي)، today=اليوم، overdue=المتأخرة، week=هذا الأسبوع، done=المكتملة، all=الكل",
                    listOf("open", "today", "overdue", "week", "done", "all")),
                "project" to prop("string", "مشروع محدد (اختياري)"),
                "query" to prop("string", "كلمة بحث (اختياري)")
            )
        ) { input ->
            val today = LocalDate.now()
            val project = input.str("project")
            val q = input.str("query")
            val list = LocalStore.tasks.value.filter { t ->
                when (input.str("filter").ifBlank { "open" }) {
                    "today" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today) } ?: false)
                    "overdue" -> t.isOverdue
                    "week" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today.plusDays(7)) } ?: false)
                    "done" -> t.status == "done"
                    "all" -> true
                    else -> t.isOpen
                }
            }.filter { project.isBlank() || Arabic.matches(it.project, project) }
                .filter { q.isBlank() || Arabic.matches(it.title + " " + it.description + " " + it.notes + " " + it.person, q) }
                .sortedWith(compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE }))
            if (list.isEmpty()) return@Tool ToolResult.ok("لا توجد مهام مطابقة.")
            ToolResult.ok("عدد المهام: ${list.size}\n" + list.take(40).joinToString("\n") { describe(it) })
        },

        Tool(
            "update_task", "يعدّل المهمة",
            "يعدّل مهمة: الحالة، الأولوية، الموعد (تأجيل)، العنوان، الملاحظات، المشروع، أو يضيف/يُنجز مهام فرعية. استخدم task_id.",
            schema(
                "task_id" to prop("integer", "معرّف المهمة"),
                "title" to prop("string", "عنوان جديد"),
                "description" to prop("string", "وصف جديد"),
                "status" to prop("string", "الحالة", STATUSES),
                "priority" to prop("string", "الأولوية", PRIORITIES),
                "due" to prop("string", "موعد جديد YYYY-MM-DD أو YYYY-MM-DDTHH:MM"),
                "project" to prop("string", "المشروع"),
                "person" to prop("string", "الشخص المرتبط"),
                "notes" to prop("string", "ملاحظات (تُضاف للملاحظات الحالية)"),
                "add_subtasks" to arr("مهام فرعية جديدة"),
                "complete_subtasks" to arr("عناوين مهام فرعية أُنجزت"),
                required = listOf("task_id")
            )
        ) { input ->
            val id = input.optLong("task_id")
            LocalStore.task(id) ?: return@Tool ToolResult.error("لم أجد المهمة. استخدم list_tasks للحصول على task_id.")
            val done = strList(input, "complete_subtasks")
            val updated = LocalStore.updateTask(id) { t ->
                val status = input.str("status").takeIf { it in STATUSES } ?: t.status
                t.copy(
                    title = input.str("title").ifBlank { t.title },
                    description = input.str("description").ifBlank { t.description },
                    status = status,
                    priority = input.str("priority").takeIf { it in PRIORITIES } ?: t.priority,
                    due = input.str("due").ifBlank { t.due },
                    project = input.str("project").ifBlank { t.project },
                    person = input.str("person").ifBlank { t.person },
                    notes = listOf(t.notes, input.str("notes")).filter { it.isNotBlank() }.joinToString("\n"),
                    subtasks = t.subtasks.map { s -> if (done.any { Arabic.matches(s.title, it) }) s.copy(done = true) else s } +
                        strList(input, "add_subtasks").map { SubTask(it) },
                    completedAt = if (status == "done" && t.status != "done") System.currentTimeMillis() else t.completedAt
                )
            }!!
            ToolResult.ok("عُدّلت المهمة: " + describe(updated))
        },

        Tool(
            "complete_task", "يُنجز المهمة",
            "يعلّم مهمة كمكتملة. المهام المتكررة تُنشأ نسختها التالية تلقائيًا.",
            schema("task_id" to prop("integer", "معرّف المهمة"), required = listOf("task_id"))
        ) { input ->
            val t = LocalStore.task(input.optLong("task_id")) ?: return@Tool ToolResult.error("لم أجد المهمة.")
            LocalStore.updateTask(t.id) { it.copy(status = "done", completedAt = System.currentTimeMillis()) }
            val next = nextDue(t)
            if (next != null) {
                LocalStore.addTask(t.copy(id = LocalStore.newId(), status = "new", due = next, completedAt = 0L,
                    subtasks = t.subtasks.map { it.copy(done = false) }, createdAt = System.currentTimeMillis()))
                ToolResult.ok("أُنجزت \"${t.title}\"، والنسخة التالية مستحقة $next.")
            } else ToolResult.ok("أُنجزت المهمة \"${t.title}\".")
        },

        Tool(
            "delete_task", "يحذف المهمة",
            "يحذف مهمة نهائيًا (الأداة تطلب تأكيد المستخدم). للإلغاء دون حذف استخدم update_task بالحالة cancelled.",
            schema("task_id" to prop("integer", "معرّف المهمة"), required = listOf("task_id"))
        ) { input ->
            val t = LocalStore.task(input.optLong("task_id")) ?: return@Tool ToolResult.error("لم أجد المهمة.")
            if (!env.confirmer.confirm("أحذف مهمة \"${t.title}\"؟", t.title)) return@Tool ToolResult.ok("ألغى المستخدم الحذف.")
            LocalStore.deleteTask(t.id)
            ToolResult.ok("حُذفت المهمة \"${t.title}\".")
        },

        // ——————————————— الملاحظات

        Tool(
            "create_note", "يحفظ ملاحظة",
            "يحفظ ملاحظة نصية للمستخدم (فكرة، معلومة، محضر اجتماع…).",
            schema(
                "title" to prop("string", "عنوان قصير"),
                "body" to prop("string", "نص الملاحظة"),
                "tags" to prop("string", "وسوم مفصولة بفاصلة (اختياري)"),
                required = listOf("body")
            )
        ) { input ->
            val body = input.str("body")
            val n = NoteItem(LocalStore.newId(), input.str("title").ifBlank { body.take(40) }, body, input.str("tags"))
            LocalStore.addNote(n)
            ToolResult.ok("حُفظت الملاحظة [note_id=${n.id}] \"${n.title}\".")
        },

        Tool(
            "search_notes", "يبحث في الملاحظات",
            "يبحث في ملاحظات المستخدم أو يعرض أحدثها.",
            schema("query" to prop("string", "كلمة البحث (فارغة = أحدث الملاحظات)"))
        ) { input ->
            val q = input.str("query")
            val list = LocalStore.notes.value.filter { q.isBlank() || Arabic.matches(it.title + " " + it.body + " " + it.tags, q) }
                .sortedByDescending { it.createdAt }.take(15)
            if (list.isEmpty()) return@Tool ToolResult.ok("لا توجد ملاحظات مطابقة.")
            ToolResult.ok(list.joinToString("\n\n") { "[note_id=${it.id}] ${it.title}\n${it.body.take(1500)}" })
        },

        Tool(
            "update_note", "يعدّل الملاحظة",
            "يعدّل عنوان ملاحظة أو نصها، أو يضيف إليها (append=true يضيف النص لنهايتها).",
            schema(
                "note_id" to prop("integer", "معرّف الملاحظة"),
                "title" to prop("string", "العنوان الجديد (اختياري)"),
                "body" to prop("string", "النص الجديد أو الإضافة"),
                "append" to prop("boolean", "أضف للنص بدل الاستبدال"),
                required = listOf("note_id")
            )
        ) { input ->
            val n = LocalStore.notes.value.firstOrNull { it.id == input.optLong("note_id") }
                ?: return@Tool ToolResult.error("لم أجد الملاحظة.")
            val body = input.optString("body")
            val newBody = when {
                body.isBlank() -> n.body
                input.optBoolean("append") -> (n.body + "\n" + body).trim()
                else -> body
            }
            val title = input.optString("title").ifBlank { n.title }
            val saved = LocalStore.updateNote(n.id, title, newBody) ?: return@Tool ToolResult.error("لم تُحفظ.")
            ToolResult.ok("عُدّلت الملاحظة \"${saved.title}\".")
        },

        Tool(
            "delete_note", "يحذف الملاحظة",
            "يحذف ملاحظة (الأداة تطلب التأكيد).",
            schema("note_id" to prop("integer", "معرّف الملاحظة"), required = listOf("note_id"))
        ) { input ->
            val n = LocalStore.notes.value.firstOrNull { it.id == input.optLong("note_id") }
                ?: return@Tool ToolResult.error("لم أجد الملاحظة.")
            if (!env.confirmer.confirm("أحذف ملاحظة \"${n.title}\"؟", n.body.take(200))) return@Tool ToolResult.ok("ألغى المستخدم الحذف.")
            LocalStore.deleteNote(n.id)
            ToolResult.ok("حُذفت الملاحظة.")
        },

        // ——————————————— الذاكرة الشخصية

        Tool(
            "save_memory", "يحفظ في الذاكرة",
            "يحفظ معلومة دائمة عن المستخدم يستفيد منها رفيق لاحقًا (تفضيل، طريقة عمل، مشروع مستمر، اسم عميل، معلومة طلب حفظها). " +
                "لا تحفظ كل ما يقال: احفظ فقط ما طلب المستخدم حفظه أو ما هو تفضيل دائم واضح. " +
                "لا تحفظ معلومات حساسة (صحة، مال، كلمات مرور، أرقام هوية أو بطاقات) إلا إذا طلب المستخدم ذلك صراحة.",
            schema(
                "text" to prop("string", "المعلومة بصياغة قصيرة واضحة"),
                "kind" to prop("string", "long=دائمة، temp=مؤقتة لمدة أسبوع", listOf("long", "temp")),
                required = listOf("text")
            )
        ) { input ->
            if (!Prefs.memoryEnabled) return@Tool ToolResult.error("الذاكرة متوقفة من الإعدادات، لم أحفظ شيئًا.")
            val text = input.str("text")
            if (text.isBlank()) return@Tool ToolResult.error("المعلومة فارغة.")
            val temp = input.str("kind") == "temp"
            val m = MemoryItem(LocalStore.newId(), text, if (temp) "temp" else "long",
                expiresAt = if (temp) System.currentTimeMillis() + 7 * 86_400_000L else 0L)
            LocalStore.addMemory(m)
            ToolResult.ok("حُفظت في الذاكرة [memory_id=${m.id}]: $text")
        },

        Tool(
            "forget_memory", "يحذف من الذاكرة",
            "يحذف معلومة من ذاكرة رفيق عندما يطلب المستخدم نسيانها.",
            schema("memory_id" to prop("integer", "معرّف المعلومة (من قائمة الذاكرة في تعليماتك)"), required = listOf("memory_id"))
        ) { input ->
            val m = LocalStore.memories.value.firstOrNull { it.id == input.optLong("memory_id") }
                ?: return@Tool ToolResult.error("لم أجد هذه المعلومة في الذاكرة.")
            LocalStore.deleteMemory(m.id)
            ToolResult.ok("نُسيت: ${m.text}")
        },

        // ——————————————— البحث الشامل

        Tool(
            "search_everything", "يبحث في كل شيء",
            "يبحث عن كلمة (اسم شخص، شركة، موضوع) في المهام والملاحظات والذاكرة وجهات الاتصال والتقويم (الشهرين القادمين والماضي القريب) والتذكيرات دفعة واحدة.",
            schema("query" to prop("string", "كلمة البحث"), required = listOf("query"))
        ) { input ->
            val q = input.str("query")
            if (q.isBlank()) return@Tool ToolResult.error("كلمة البحث فارغة.")
            val sb = StringBuilder()
            val tasks = LocalStore.tasks.value.filter { Arabic.matches(it.title + " " + it.description + " " + it.notes + " " + it.person + " " + it.project, q) }
            if (tasks.isNotEmpty()) sb.append("المهام:\n").append(tasks.take(10).joinToString("\n") { describe(it) }).append("\n\n")
            val notes = LocalStore.notes.value.filter { Arabic.matches(it.title + " " + it.body + " " + it.tags, q) }
            if (notes.isNotEmpty()) sb.append("الملاحظات:\n").append(notes.take(8).joinToString("\n") { "[note_id=${it.id}] ${it.title}: ${it.body.take(200)}" }).append("\n\n")
            val mem = LocalStore.activeMemories().filter { Arabic.matches(it.text, q) }
            if (mem.isNotEmpty()) sb.append("الذاكرة:\n").append(mem.joinToString("\n") { "- ${it.text}" }).append("\n\n")
            val rem = LocalStore.reminders.value.filter { Arabic.matches(it.text, q) }
            if (rem.isNotEmpty()) sb.append("التذكيرات:\n").append(rem.joinToString("\n") { "[id=${it.id}] ${it.text}" }).append("\n\n")
            if (env.has(Manifest.permission.READ_CONTACTS)) {
                val c = runCatching { Contacts.search(env.context, q) }.getOrDefault(emptyList())
                if (c.isNotEmpty()) sb.append("جهات الاتصال:\n").append(c.take(8).joinToString("\n") { "${it.name} — ${it.number}" }).append("\n\n")
            }
            if (env.has(Manifest.permission.READ_CALENDAR)) {
                val ev = runCatching { CalendarReader.search(env.context, q) }.getOrDefault(emptyList())
                if (ev.isNotEmpty()) sb.append("التقويم:\n").append(ev.joinToString("\n")).append("\n")
            }
            ToolResult.ok(sb.toString().trim().ifBlank { "لم أجد \"$q\" في المهام أو الملاحظات أو الذاكرة أو جهات الاتصال أو التقويم." })
        }
    )
}

/** قراءة مبسطة للتقويم للبحث والشاشة الرئيسية */
object CalendarReader {
    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

    fun range(context: android.content.Context, from: Long, to: Long, limit: Int = 100): List<Event> {
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<Event>()
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Instances.EVENT_ID, android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.EVENT_LOCATION
            ),
            null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += Event(c.getLong(0), c.getString(1).orEmpty().ifBlank { "(بدون عنوان)" }, c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5).orEmpty())
            }
        }
        return out
    }

    fun search(context: android.content.Context, q: String): List<String> {
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("EEEE d MMM، h:mm a", Locale("ar"))
        return range(context, now - 14 * 86_400_000L, now + 60 * 86_400_000L, 400)
            .filter { Arabic.matches(it.title + " " + it.location, q) }
            .take(10)
            .map { "[event_id=${it.id}] ${it.title} — ${fmt.format(java.util.Date(it.begin))}" }
    }

    @Suppress("unused")
    private val zone get() = ZoneId.systemDefault()
}

/** بحث الإنترنت عبر Gemini مع مصادر Google Search (يحتاج مفتاح Gemini). */
object WebTools {
    fun tools(env: ToolEnv): List<Tool> = listOf(
        Tool(
            "search_web", "يبحث في الإنترنت",
            "يبحث في الإنترنت عن معلومات حديثة (أخبار، أسعار، شركات، منتجات، معلومات رسمية، مواعيد صلاة، طقس…) ويعيد الخلاصة مع المصادر. " +
                "استخدمه لأي معلومة قد تكون تغيّرت ولا تعتمد على معلوماتك القديمة. اذكر للمستخدم مصدرًا أو اثنين باسم الموقع.",
            schema("query" to prop("string", "سؤال البحث بصياغة واضحة"), required = listOf("query"))
        ) { input ->
            val key = Prefs.geminiApiKey
            if (key.isBlank()) return@Tool ToolResult.error(
                "البحث في الإنترنت يحتاج مفتاح Gemini (مجاني من aistudio.google.com) في الإعدادات حتى لو كان المزوّد الأساسي غير Gemini."
            )
            val r = com.alharith.ai.brain.GeminiClient().groundedSearch(key, Prefs.geminiModel, input.str("query"))
            ToolResult.ok(r)
        }
    )
}
