package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.NoteItem
import com.alharith.ai.data.TaskItem
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** المهام والملاحظات: عرض، إضافة سريعة، إنجاز، تأجيل، أولوية، مهام فرعية، حذف. */
@Composable
fun TasksScreen(onBack: () -> Unit) {
    val tasks by LocalStore.tasks.collectAsState()
    val notes by LocalStore.notes.collectAsState()
    var tab by rememberSaveable { mutableStateOf("open") }
    var input by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableLongStateOf(0L) }
    var confirmDelete by remember { mutableStateOf<TaskItem?>(null) }
    var confirmNote by remember { mutableStateOf<NoteItem?>(null) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var editNote by remember { mutableStateOf<NoteItem?>(null) }
    val today = LocalDate.now()

    val tabs = listOf("today" to "اليوم", "week" to "الأسبوع", "open" to "المفتوحة", "overdue" to "المتأخرة", "done" to "المكتملة", "notes" to "الملاحظات")
    val shown = tasks.filter { t ->
        when (tab) {
            "today" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today) } ?: false)
            "week" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today.plusDays(6)) } ?: false)
            "overdue" -> t.isOverdue
            "done" -> t.status == "done" || t.status == "cancelled"
            else -> t.isOpen
        }
    }.let { l ->
        if (tab == "done") l.sortedByDescending { it.completedAt }
        else l.sortedWith(compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE }))
    }

    fun add() {
        val t = input.trim()
        if (t.isEmpty()) return
        if (tab == "notes") LocalStore.addNote(NoteItem(LocalStore.newId(), t.take(40), t))
        else LocalStore.addTask(TaskItem(LocalStore.newId(), t, due = if (tab == "today") today.toString() else ""))
        input = ""
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("المهام والملاحظات", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEach { (id, label) ->
                val count = when (id) {
                    "today" -> tasks.count { t -> t.isOpen && (t.dueDate?.let { !it.isAfter(today) } ?: false) }
                    "open" -> tasks.count { it.isOpen }
                    "overdue" -> tasks.count { it.isOverdue }
                    "notes" -> notes.size
                    else -> null
                }
                val sel = tab == id
                Text(
                    label + (count?.takeIf { it > 0 }?.let { " ${arNum(it)}" } ?: ""),
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (sel) HarithColors.Gold else Color.Transparent)
                        .border(1.dp, if (sel) HarithColors.Gold else HarithColors.Line, RoundedCornerShape(18.dp))
                        .clickable { tab = id }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (sel) HarithColors.OnGold else HarithColors.Fg,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (tab == "notes") {
                if (notes.isEmpty()) item { Empty("لا ملاحظات. اكتب ملاحظة بالأسفل أو قل لرفيق: \"احفظ ملاحظة…\"") }
                items(notes.sortedByDescending { it.createdAt }, key = { it.id }) { n ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Surface)
                            .clickable { editNote = n }.padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
                            IconButton(onClick = { confirmNote = n }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.DeleteOutline, "حذف", tint = HarithColors.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (n.body != n.title) Text(n.body, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                        Text(
                            SimpleDateFormat("d MMM yyyy", Locale("ar")).format(Date(n.createdAt)),
                            style = MaterialTheme.typography.labelSmall, color = HarithColors.GoldDim
                        )
                    }
                }
            } else {
                if (shown.isEmpty()) item {
                    Empty(
                        when (tab) {
                            "overdue" -> "لا مهام متأخرة. أحسنت!"
                            "done" -> "لا مهام مكتملة بعد."
                            else -> "لا مهام هنا. أضف مهمة بالأسفل، أو قل لرفيق: \"أضف مهمة…\""
                        }
                    )
                }
                if (tab == "week") {
                    // عرض أسبوعي: مجمّع حسب اليوم
                    shown.groupBy { it.dueDate?.let { d -> if (d.isBefore(today)) today else d } ?: today }.toSortedMap().forEach { (day, list) ->
                        item(key = "h$day") {
                            Text(
                                if (day == today) "اليوم" else day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("ar"))),
                                color = HarithColors.Gold, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(list, key = { it.id }) { t ->
                            TaskCard(t, expanded == t.id, onToggle = { expanded = if (expanded == t.id) 0L else t.id },
                                onDelete = { confirmDelete = t }, onEdit = { editTask = t })
                        }
                    }
                } else items(shown, key = { it.id }) { t ->
                    TaskCard(
                        t, expanded == t.id,
                        onToggle = { expanded = if (expanded == t.id) 0L else t.id },
                        onDelete = { confirmDelete = t }, onEdit = { editTask = t }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (tab == "notes") "ملاحظة جديدة…" else "مهمة جديدة…", color = HarithColors.Muted) },
                shape = RoundedCornerShape(24.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HarithColors.GoldDim, unfocusedBorderColor = HarithColors.Line,
                    focusedContainerColor = HarithColors.Surface, unfocusedContainerColor = HarithColors.Surface
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { add() },
                modifier = Modifier.size(48.dp).clip(CircleShape).background(HarithColors.Gold)
            ) { Icon(Icons.Default.Add, "إضافة", tint = HarithColors.OnGold) }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف المهمة؟") },
            text = { Text(t.title) },
            confirmButton = { TextButton(onClick = { LocalStore.deleteTask(t.id); confirmDelete = null }) { Text("حذف", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } }
        )
    }
    editTask?.let { t0 -> TaskEditDialog(t0) { editTask = null } }
    editNote?.let { n0 -> NoteEditDialog(n0) { editNote = null } }
    confirmNote?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmNote = null },
            title = { Text("حذف الملاحظة؟") },
            text = { Text(n.title) },
            confirmButton = { TextButton(onClick = { LocalStore.deleteNote(n.id); confirmNote = null }) { Text("حذف", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmNote = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(32.dp),
        color = HarithColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun TaskCard(t: TaskItem, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit = {}) {
    val done = t.status == "done"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HarithColors.Surface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                LocalStore.updateTask(t.id) {
                    if (done) it.copy(status = "new", completedAt = 0L)
                    else it.copy(status = "done", completedAt = System.currentTimeMillis())
                }
            }) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, "إنجاز",
                    tint = if (done) HarithColors.Green else priorityColor(t.priority)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, color = if (done) HarithColors.Muted else HarithColors.Fg,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else null
                )
                val sub = listOfNotNull(
                    t.due.takeIf { it.isNotBlank() }?.let { dueLabel(t) },
                    TaskItem.PRIORITY_AR[t.priority]?.takeIf { t.priority != "medium" },
                    t.project.takeIf { it.isNotBlank() },
                    t.person.takeIf { it.isNotBlank() },
                    t.subtasks.takeIf { it.isNotEmpty() }?.let { s -> arNum("${s.count { it.done }}/${s.size}") }
                ).joinToString("، ")
                if (sub.isNotBlank()) Text(sub, color = if (t.isOverdue) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.description.isNotBlank()) Text(t.description, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                if (t.notes.isNotBlank()) Text("ملاحظات: ${t.notes}", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                t.subtasks.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            LocalStore.updateTask(t.id) { it.copy(subtasks = it.subtasks.mapIndexed { j, x -> if (j == i) x.copy(done = !x.done) else x }) }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (s.done) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                            tint = if (s.done) HarithColors.Green else HarithColors.Muted, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s.title, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "الحالة: ${TaskItem.STATUS_AR[t.status]} · الأولوية: ${TaskItem.PRIORITY_AR[t.priority]}",
                    color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("أولوية") {
                        val order = listOf("low", "medium", "high", "urgent")
                        LocalStore.updateTask(t.id) { it.copy(priority = order[(order.indexOf(it.priority) + 1) % order.size]) }
                    }
                    Chip("تأجيل يوم") {
                        LocalStore.updateTask(t.id) {
                            val base = it.dueDate?.takeIf { d -> !d.isBefore(LocalDate.now()) } ?: LocalDate.now()
                            val time = if (it.due.length > 10) it.due.substring(10) else ""
                            it.copy(due = base.plusDays(1).toString() + time, status = "postponed")
                        }
                    }
                    Chip("تعديل") { onEdit() }
                    if (t.status != "in_progress" && t.isOpen) Chip("بدء التنفيذ") {
                        LocalStore.updateTask(t.id) { it.copy(status = "in_progress") }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, "حذف", tint = HarithColors.Red, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, HarithColors.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = HarithColors.Fg, style = MaterialTheme.typography.labelMedium
    )
}


/** تعديل كامل للمهمة: العنوان، الموعد، المشروع، الشخص، الوصف، المهام الفرعية */
@Composable
private fun TaskEditDialog(t: TaskItem, onClose: () -> Unit) {
    var title by remember { mutableStateOf(t.title) }
    var due by remember { mutableStateOf(t.due.replace("T", " ")) }
    var project by remember { mutableStateOf(t.project) }
    var person by remember { mutableStateOf(t.person) }
    var desc by remember { mutableStateOf(t.description) }
    var subs by remember { mutableStateOf(t.subtasks.joinToString("\n") { it.title }) }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("تعديل المهمة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("العنوان") }, singleLine = true)
                OutlinedTextField(due, { due = it }, label = { Text("الموعد: 2026-10-20 أو 2026-10-20 14:30") }, singleLine = true)
                OutlinedTextField(project, { project = it }, label = { Text("المشروع") }, singleLine = true)
                OutlinedTextField(person, { person = it }, label = { Text("الشخص المرتبط") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("الوصف") }, minLines = 2)
                OutlinedTextField(subs, { subs = it }, label = { Text("مهام فرعية (سطر لكل واحدة)") }, minLines = 2)
                if (err.isNotBlank()) Text(err, color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = due.trim().replace(" ", "T")
                val ok = d.isEmpty() || runCatching {
                    if (d.length <= 10) LocalDate.parse(d) else java.time.LocalDateTime.parse(d.take(16)); true
                }.getOrDefault(false)
                if (title.isBlank()) { err = "العنوان مطلوب"; return@TextButton }
                if (!ok) { err = "صيغة الموعد غير صحيحة"; return@TextButton }
                val old = t.subtasks.associateBy { it.title }
                LocalStore.updateTask(t.id) {
                    it.copy(title = title.trim(), due = d, project = project.trim(), person = person.trim(), description = desc.trim(),
                        subtasks = subs.lines().map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                            .map { s -> old[s] ?: com.alharith.ai.data.SubTask(s) })
                }
                onClose()
            }) { Text("حفظ", color = HarithColors.Gold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun NoteEditDialog(n: NoteItem, onClose: () -> Unit) {
    var title by remember { mutableStateOf(n.title) }
    var body by remember { mutableStateOf(n.body) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("تعديل الملاحظة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("العنوان") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("النص") }, minLines = 4)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (body.isNotBlank()) LocalStore.updateNote(n.id, title.ifBlank { body.take(40) }.trim(), body.trim())
                onClose()
            }) { Text("حفظ", color = HarithColors.Gold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}
