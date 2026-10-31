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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.launch

/** المهام والملاحظات: عرض، إضافة سريعة، إنجاز، تأجيل، أولوية، مهام فرعية، حذف. */
@Composable
fun TasksScreen(onBack: (() -> Unit)? = null, initialTab: String = "open") {
    val personal by com.alharith.ai.data.PersonalFeatures.enabled.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.alharith.ai.data.PersonalFeatures.check() }
    val tasks by LocalStore.tasks.collectAsState()
    val notes by LocalStore.notes.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf("priority") }   // priority | due | recent
    var sortMenu by remember { mutableStateOf(false) }
    var newRepeat by rememberSaveable { mutableStateOf("none") }
    var input by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableLongStateOf(0L) }
    var confirmDelete by remember { mutableStateOf<TaskItem?>(null) }
    var confirmNote by remember { mutableStateOf<NoteItem?>(null) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var editNote by remember { mutableStateOf<NoteItem?>(null) }
    var syncMessage by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    val syncScope = androidx.compose.runtime.rememberCoroutineScope()
    fun refreshTasks() { if (com.alharith.ai.data.Prefs.sharedBrainReady && !syncing) syncScope.launch {
        syncing = true
        runCatching { com.alharith.ai.data.SharedBrain.syncNow() }
            .onSuccess { syncMessage = it }.onFailure { syncMessage = "تعذر جلب مهام الحساب: ${it.message}" }
        syncing = false
    } }
    androidx.compose.runtime.LaunchedEffect(Unit) { refreshTasks() }
    val today = LocalDate.now()
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val tabs = listOf("today" to "اليوم", "week" to "الأسبوع", "open" to "المفتوحة", "overdue" to "المتأخرة", "done" to "المكتملة", "notes" to "الملاحظات")
    val shown = tasks.filter { t ->
        when (tab) {
            "today" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today) } ?: false)
            "week" -> t.isOpen && (t.dueDate?.let { !it.isAfter(today.plusDays(6)) } ?: false)
            "overdue" -> t.isOverdue
            "done" -> t.status == "done" || t.status == "cancelled"
            else -> t.isOpen
        }
    }.filter { t ->
        val q = query.trim()
        q.isEmpty() || listOf(t.title, t.description, t.project, t.notes, t.person, t.category).any { it.contains(q, ignoreCase = true) }
    }.let { l ->
        when {
            tab == "done" -> l.sortedByDescending { it.completedAt }
            sortBy == "due" -> l.sortedWith(compareBy<TaskItem>({ it.dueMillis ?: Long.MAX_VALUE }, { it.priorityRank }))
            sortBy == "recent" -> l.sortedByDescending { it.createdAt }
            else -> l.sortedWith(compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE }))
        }
    }
    val shownNotes = notes.filter { n -> query.isBlank() || n.title.contains(query.trim(), true) || n.body.contains(query.trim(), true) }

    fun add() {
        val t = input.trim()
        if (t.isEmpty()) return
        if (tab == "notes") LocalStore.addNote(NoteItem(LocalStore.newId(), t.take(40), t))
        else LocalStore.addTask(TaskItem(LocalStore.newId(), t, due = if (tab == "today" || newRepeat != "none") today.toString() else "", repeat = newRepeat))
        input = ""
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        RafiqTopBar("مهامي", onBack, subtitle = "${arNum(tasks.count { it.isOpen })} مفتوحة، و${arNum(tasks.count { it.isOverdue })} متأخرة") {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "إغلاق البحث" else "بحث", tint = HarithColors.Fg)
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "ترتيب", tint = HarithColors.Fg) }
                androidx.compose.material3.DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("priority" to "حسب الأولوية", "due" to "حسب الموعد", "recent" to "الأحدث إضافة").forEach { (k, l) ->
                        androidx.compose.material3.DropdownMenuItem({ Text(if (sortBy == k) "✓ $l" else l) }, { sortBy = k; sortMenu = false })
                    }
                }
            }
        }
        if (searching) OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text("ابحث في المهام والملاحظات…", color = HarithColors.Muted) }, shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = HarithColors.Muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HarithColors.Gold, unfocusedBorderColor = HarithColors.Line,
                focusedContainerColor = HarithColors.Surface, unfocusedContainerColor = HarithColors.Surface)
        )
        TextTabs(
            tabs.map { (id, label) ->
                val count = when (id) {
                    "today" -> tasks.count { t -> t.isOpen && (t.dueDate?.let { !it.isAfter(today) } ?: false) }
                    "open" -> tasks.count { it.isOpen }
                    "overdue" -> tasks.count { it.isOverdue }
                    "notes" -> notes.size
                    else -> null
                }
                id to (label + (count?.takeIf { it > 0 }?.let { " ${arNum(it)}" } ?: ""))
            },
            tab, Modifier.fillMaxWidth().padding(horizontal = 10.dp)
        ) { tab = it }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (tab == "notes") {
                if (shownNotes.isEmpty()) item { Empty(if (query.isNotBlank()) "لا نتائج لـ «$query»" else "لا ملاحظات. اكتب ملاحظة بالأسفل أو قل لرفيق: \"احفظ ملاحظة…\"") }
                items(shownNotes.sortedByDescending { it.createdAt }, key = { it.id }) { n ->
                    Column(
                        Modifier.fillMaxWidth().clickable { editNote = n }.padding(vertical = 12.dp)
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
                        when {
                            query.isNotBlank() -> "لا نتائج لـ «$query»"
                            tab == "overdue" -> "لا مهام متأخرة. أحسنت!"
                            tab == "done" -> "لا مهام مكتملة بعد."
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
                            SwipeTask(t, snack, Modifier.animateItem()) {
                                TaskCard(t, expanded == t.id, onToggle = { expanded = if (expanded == t.id) 0L else t.id },
                                    onDelete = { confirmDelete = t }, onEdit = { editTask = t }, snack = snack)
                            }
                        }
                    }
                } else if (tab == "open" && sortBy != "recent" && query.isBlank()) {
                    // المفتوحة مجمّعة حسب القرب: ما يحتاج انتباهك أولًا
                    val weekEnd = today.plusDays(6)
                    val groups = listOf(
                        "متأخرة" to shown.filter { it.isOverdue },
                        "اليوم" to shown.filter { !it.isOverdue && it.dueDate == today },
                        "هذا الأسبوع" to shown.filter { !it.isOverdue && it.dueDate?.let { d -> d.isAfter(today) && !d.isAfter(weekEnd) } == true },
                        "لاحقًا" to shown.filter { !it.isOverdue && it.dueDate?.isAfter(weekEnd) == true },
                        "بدون موعد" to shown.filter { !it.isOverdue && it.dueDate == null && it.due.isBlank() },
                        "أخرى" to shown.filter { !it.isOverdue && it.dueDate == null && it.due.isNotBlank() }
                    ).filter { it.second.isNotEmpty() }
                    groups.forEach { (label, list) ->
                        item(key = "g$label") {
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                    color = if (label == "متأخرة") HarithColors.Red else HarithColors.Fg)
                                Text(arNum(list.size), style = MaterialTheme.typography.labelMedium, color = HarithColors.Muted)
                            }
                        }
                        items(list, key = { it.id }) { t ->
                            SwipeTask(t, snack, Modifier.animateItem()) {
                                TaskCard(t, expanded == t.id, onToggle = { expanded = if (expanded == t.id) 0L else t.id },
                                    onDelete = { confirmDelete = t }, onEdit = { editTask = t }, snack = snack)
                            }
                        }
                    }
                } else items(shown, key = { it.id }) { t ->
                    SwipeTask(t, snack, Modifier.animateItem()) {
                        TaskCard(
                            t, expanded == t.id,
                            onToggle = { expanded = if (expanded == t.id) 0L else t.id },
                            onDelete = { confirmDelete = t }, onEdit = { editTask = t }, snack = snack
                        )
                    }
                }
            }
        }

        androidx.compose.material3.SnackbarHost(snack, Modifier.padding(horizontal = 12.dp)) { d ->
            androidx.compose.material3.Snackbar(d, containerColor = if (HarithColors.IsDark) HarithColors.SurfaceHigh else Color(0xFF252525),
                contentColor = Color(0xFFF7F5F0), actionColor = HarithColors.Gold)
        }
        if (com.alharith.ai.data.Prefs.sharedBrainReady) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Text(syncMessage.ifBlank { if (syncing) "جارٍ جلب مهام حسابك…" else "مهام حسابك المرتبط" }, Modifier.weight(1f), color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { refreshTasks() }, enabled = !syncing) { Text("مزامنة الآن") }
            }
        }
        if (personal && tab != "notes") {
            Text("مهمة متكررة", Modifier.padding(horizontal = 16.dp), color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("none" to "بدون", "daily" to "يوميًا", "weekly" to "أسبوعيًا", "monthly" to "شهريًا", "yearly" to "سنويًا").forEach { (k,l) -> Pill(l,newRepeat == k) { newRepeat = k } }
            }
        }
        CommandBar(
            input, { input = it }, if (tab == "notes") "ملاحظة جديدة…" else "مهمة جديدة… (مثل: مراجعة المخططات)",
            onSend = { add() }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            sendLabel = if (tab == "notes") "أضف الملاحظة" else "أضف المهمة", sendIcon = Icons.Default.Add
        )
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
private fun TaskCard(t: TaskItem, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit = {},
                     snack: androidx.compose.material3.SnackbarHostState? = null) {
    val done = t.status == "done"
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp)).background(HarithColors.Surface)
            .border(1.dp, HarithColors.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (done) LocalStore.updateTask(t.id) { it.copy(status = "new", completedAt = 0L) }
                else {
                    val before = TaskActions.complete(t.id)
                    if (snack != null) undoable(scope, snack, "أُنجزت «${t.title.take(30)}»", before)
                }
            }) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (done) "إلغاء الإنجاز" else "إنجاز",
                    tint = if (done) HarithColors.Green else priorityColor(t.priority), modifier = Modifier.popWhen(done)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, color = if (done) HarithColors.Muted else HarithColors.Fg,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else null
                )
                if (done) Text(
                    if (t.completedAt > 0) "أُنجزت " + arNum(SimpleDateFormat("d MMM", Locale("ar")).format(Date(t.completedAt))) else "مكتملة",
                    color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                else Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) { TaskMetaLine(t) }
                    val extra = listOfNotNull(t.person.takeIf { it.isNotBlank() }, "يتكرر".takeIf { t.repeat != "none" }).joinToString("، ")
                    if (extra.isNotBlank()) Text(extra, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
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
                        val before = TaskActions.postpone(t.id)
                        if (snack != null) undoable(scope, snack, "أُجّلت إلى الغد", before)
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
    HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
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
internal fun TaskEditDialog(t: TaskItem, onClose: () -> Unit) {
    var title by remember { mutableStateOf(t.title) }
    var due by remember { mutableStateOf(t.due.replace("T", " ")) }
    var project by remember { mutableStateOf(t.project) }
    var person by remember { mutableStateOf(t.person) }
    var desc by remember { mutableStateOf(t.description) }
    var subs by remember { mutableStateOf(t.subtasks.joinToString("\n") { it.title }) }
    var priority by remember { mutableStateOf(t.priority) }
    var repeat by remember { mutableStateOf(t.repeat) }
    var category by remember { mutableStateOf(t.category) }
    var notes by remember { mutableStateOf(t.notes) }
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
                Text("الأولوية", color = HarithColors.Muted, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("low" to "منخفضة", "medium" to "متوسطة", "high" to "عالية", "urgent" to "عاجلة").forEach { (k, l) -> Pill(l, priority == k) { priority = k } }
                }
                Text("التكرار", color = HarithColors.Muted, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "بدون", "daily" to "يوميًا", "weekly" to "أسبوعيًا", "monthly" to "شهريًا", "yearly" to "سنويًا").forEach { (k, l) -> Pill(l, repeat == k) { repeat = k } }
                }
                OutlinedTextField(category, { category = it }, label = { Text("التصنيف") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات") }, minLines = 2)
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
                val saved = LocalStore.updateTask(t.id) {
                    it.copy(title = title.trim(), due = d, project = project.trim(), person = person.trim(), description = desc.trim(),
                        priority = priority, repeat = repeat, category = category.trim(), notes = notes.trim(),
                        subtasks = subs.lines().map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                            .map { s -> old[s] ?: com.alharith.ai.data.SubTask(s) })
                }
                if (saved == null) { err = "تعذّر الحفظ — ربما حُذفت المهمة"; return@TextButton }
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
