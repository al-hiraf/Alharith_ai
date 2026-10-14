package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.NoteItem
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.ReminderItem
import com.alharith.ai.data.TaskItem
import com.alharith.ai.service.AssistantService
import com.alharith.ai.tools.CalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * الرئيسية: لوحة اليوم الذكية — التحية والتاريخ، بطاقة رفيق (أمر مكتوب أو صوتي)، مؤشرات اليوم
 * المحسوبة من مهامك الفعلية، مهام اليوم بإجراءات سريعة، الموعد التالي، والإضافة السريعة.
 */
@Composable
fun HomeScreen(
    onVoice: () -> Unit,
    onAsk: (text: String, label: String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBusiness: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenAccount: () -> Unit = onOpenSettings,
    onOpenCalendar: () -> Unit = {}
) {
    val context = LocalContext.current
    val tasks by LocalStore.tasks.collectAsState()
    val reminders by LocalStore.reminders.collectAsState()
    val messages by ConversationStore.messages.collectAsState()
    var events by remember { mutableStateOf<List<CalendarReader.Event>>(emptyList()) }
    var calendarAllowed by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var confirmDelete by remember { mutableStateOf<TaskItem?>(null) }
    var sort by rememberSaveable { mutableStateOf(Prefs.homeSort) }

    // يُعاد التحميل عند كل عودة للشاشة (بعد منح صلاحية أو إضافة موعد)
    var resumes by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
    LaunchedEffect(resumes) {
        calendarAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (calendarAllowed) {
            events = withContext(Dispatchers.IO) {
                val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
                runCatching { CalendarReader.range(context, start, start + 86_400_000L, 30) }.getOrDefault(emptyList())
            }
        }
    }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 4..11 -> "صباح الخير"; in 12..16 -> "مساء النور"; else -> "مساء الخير" }
    val now = Date()
    val gregorian = SimpleDateFormat("EEEE d MMMM", Locale("ar")).format(now)
    val hijri = runCatching {
        android.icu.text.SimpleDateFormat("d MMMM y", android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura")).format(now) + " هـ"
    }.getOrDefault("")

    // ——— المؤشرات: محسوبة من المهام الفعلية وتتحدث تلقائيًا مع أي تغيير
    val today = LocalDate.now()
    val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val remaining = tasks.filter { it.isOpen && (it.dueDate?.let { d -> !d.isAfter(today) } ?: false) }
    val overdue = tasks.filter { it.isOverdue }
    val doneToday = tasks.filter { it.status == "done" && it.completedAt >= startOfDay }
    val dayTotal = remaining.size + doneToday.size
    val progress = if (dayTotal > 0) doneToday.size.toFloat() / dayTotal else null
    val nowMs = System.currentTimeMillis()
    val upcomingEvents = events.filter { it.end > nowMs }
    val todayReminders = reminders.filter { it.at > nowMs && it.at < startOfDay + 86_400_000L }
    val nextEvent = events.firstOrNull { it.end > nowMs && !it.allDay } ?: events.firstOrNull { it.allDay }
    val nextReminders = reminders.filter { it.at > nowMs }.sortedBy { it.at }.take(3)
    val timeFmt = SimpleDateFormat("h:mm a", Locale("ar"))

    val todayList = (if (remaining.isNotEmpty()) remaining else tasks.filter { it.isOpen && it.status == "in_progress" })
        .sortedWith(
            if (sort == "due") compareBy<TaskItem>({ it.dueMillis ?: Long.MAX_VALUE }, { it.priorityRank })
            else compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE })
        )
    val upcomingOpen = tasks.filter { it.isOpen && (it.dueDate?.isAfter(today) ?: true) }
        .sortedWith(compareBy<TaskItem>({ it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE })).take(3)

    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ——— الترويسة: الشعار، البحث، الحساب
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.alharith.ai.R.drawable.rafiq_mark), "شعار رفيق", Modifier.size(38.dp))
                Spacer(Modifier.width(8.dp))
                Text("رفيق", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Ruqaa, fontSize = 26.sp), color = HarithColors.Fg)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, "بحث شامل", tint = HarithColors.Fg) }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(HarithColors.GoldSoft).clickable(onClick = onOpenAccount),
                    contentAlignment = Alignment.Center
                ) {
                    Text(Prefs.userName.take(1).ifBlank { "ر" }, color = HarithColors.GoldText, style = MaterialTheme.typography.titleMedium)
                }
            }

            // ——— التحية والتاريخ
            Column {
                Text("$greeting، ${Prefs.userName}", style = MaterialTheme.typography.headlineMedium, color = HarithColors.Fg)
                Text(listOf(gregorian, hijri).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
            }

            // ——— تنبيه مهم
            if (overdue.isNotEmpty()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Red.copy(alpha = 0.08f))
                    .clickable(onClick = onOpenTasks).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.WarningAmber, null, tint = HarithColors.Red, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("لديك ${arNum(overdue.size)} ${if (overdue.size == 1) "مهمة متأخرة" else "مهام متأخرة"} — اضغط للمراجعة",
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
            }

            // ——— بطاقة رفيق AI
            AssistantCard(
                hasConversation = messages.any { !it.isAction },
                onAsk = onAsk, onVoice = onVoice, onOpenChat = onOpenChat,
                onNewChat = { AssistantService.send(context, AssistantService.ACTION_RESET); onOpenChat() },
                overdue = overdue.size
            )

            // ——— مؤشرات اليوم
            GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpenTasks), RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("إنجاز اليوم", style = MaterialTheme.typography.titleSmall, color = HarithColors.Muted)
                            Text(
                                if (dayTotal == 0) "لا مهام مستحقة اليوم" else "أنجزت ${arNum(doneToday.size)} من ${arNum(dayTotal)}",
                                style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg
                            )
                        }
                        Text(progress?.let { "${arNum((it * 100).toInt())}٪" } ?: "—", style = MaterialTheme.typography.headlineMedium,
                            color = HarithColors.GoldText)
                    }
                    LinearProgressIndicator(
                        progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = HarithColors.Gold, trackColor = HarithColors.SurfaceHigh
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(Modifier.weight(1f), arNum(remaining.size), "متبقية اليوم", onClick = onOpenTasks)
                MetricCard(Modifier.weight(1f), arNum(overdue.size), "متأخرة", accent = if (overdue.isNotEmpty()) HarithColors.Red else null, onClick = onOpenTasks)
                MetricCard(Modifier.weight(1f), arNum(if (calendarAllowed) upcomingEvents.size + todayReminders.size else todayReminders.size),
                    "مواعيد قادمة", onClick = onOpenCalendar)
            }

            // ——— الموعد التالي
            val nextTxt = when {
                !calendarAllowed && nextReminders.isEmpty() -> "امنح صلاحية التقويم لترى مواعيدك هنا"
                nextEvent != null && nextEvent.allDay -> "طوال اليوم: ${nextEvent.title}"
                nextEvent != null -> "التالي: ${nextEvent.title} — ${timeFmt.format(Date(nextEvent.begin))}"
                nextReminders.isNotEmpty() -> "تذكير: ${nextReminders.first().text} — ${timeFmt.format(Date(nextReminders.first().at))}"
                else -> "لا مواعيد متبقية اليوم"
            }
            GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenCalendar), RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = HarithColors.Gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(nextTxt, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ——— مهام اليوم
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (remaining.isNotEmpty()) "مهام اليوم" else "قيد التنفيذ", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sortMenu = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, tint = HarithColors.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sort == "due") "حسب الموعد" else "حسب الأولوية", style = MaterialTheme.typography.labelMedium, color = HarithColors.Muted)
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DropdownMenuItem({ Text("حسب الأولوية") }, { sort = "priority"; Prefs.homeSort = "priority"; sortMenu = false })
                        DropdownMenuItem({ Text("حسب الموعد") }, { sort = "due"; Prefs.homeSort = "due"; sortMenu = false })
                    }
                }
                Text("الكل", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenTasks).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
            }
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (todayList.isEmpty() && doneToday.isEmpty()) {
                        EmptyView("لا مهام مستحقة اليوم", "أضف مهمة من زر + أو اكتب لرفيق: «أضف مهمة…»", Icons.Default.TaskAlt)
                        if (upcomingOpen.isNotEmpty()) {
                            Text("القادمة", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge, color = HarithColors.Muted)
                            upcomingOpen.forEach { t -> TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t }) }
                        }
                    }
                    todayList.take(8).forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HarithColors.Line)
                        TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t })
                    }
                    if (todayList.size > 8) Text("و${arNum(todayList.size - 8)} أخرى…", Modifier.clickable(onClick = onOpenTasks).padding(16.dp, 8.dp),
                        color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
                    doneToday.sortedByDescending { it.completedAt }.take(3).forEach { t ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HarithColors.Line)
                        TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t })
                    }
                }
            }

            // ——— التذكيرات القادمة
            if (nextReminders.isNotEmpty()) {
                SectionHeader("التذكيرات", action = "التقويم", onAction = onOpenCalendar)
                GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        val fmt = SimpleDateFormat("EEEE، h:mm a", Locale("ar"))
                        nextReminders.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, tint = HarithColors.Gold, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(r.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                                Text(fmt.format(Date(r.at)), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ——— اختصارات للأقسام
            SectionHeader("أقسام")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutCard(Modifier.weight(1f), Icons.Default.AccountTree, "المشاريع", onOpenProjects)
                ShortcutCard(Modifier.weight(1f), Icons.Default.Folder, "الأعمال والمالية", onOpenBusiness)
            }
        }

        // ——— زر الإضافة السريعة
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp).size(58.dp).clip(RoundedCornerShape(18.dp))
                .background(if (HarithColors.IsDark) HarithColors.Gold else Color(0xFF252525)).clickable { showAdd = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "إضافة سريعة", tint = if (HarithColors.IsDark) HarithColors.OnGold else Color.White, modifier = Modifier.size(28.dp)) }
    }

    if (showAdd) QuickAddSheet(onDismiss = { showAdd = false }, onOpenProjects = { showAdd = false; onOpenProjects() })
    editTask?.let { t -> TaskEditDialog(t) { editTask = null } }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = HarithColors.Surface,
            title = { Text("حذف المهمة؟") }, text = { Text(t.title) },
            confirmButton = { TextButton(onClick = { LocalStore.deleteTask(t.id); confirmDelete = null }) { Text("حذف", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء", color = HarithColors.Fg) } }
        )
    }
}

/** بطاقة المساعد: أمر مكتوب أو صوتي، محادثة جديدة، ومتابعة السابقة، واقتراحات مرتبطة بأدوات فعلية */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantCard(
    hasConversation: Boolean, overdue: Int,
    onAsk: (String, String) -> Unit, onVoice: () -> Unit, onOpenChat: () -> Unit, onNewChat: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val state by ConversationStore.state.collectAsState()
    fun send() { val t = input.trim(); if (t.isNotEmpty()) { input = ""; onAsk(t, t) } }
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniOrb(state, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("رفيق AI", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                    Text("اطلب بالكتابة أو بصوتك، وينفّذ بأدوات فعلية بعد تأكيدك للحساس", style = MaterialTheme.typography.bodySmall,
                        color = HarithColors.Muted, maxLines = 2)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarithColors.SurfaceHigh).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    input, { input = it }, Modifier.weight(1f).padding(vertical = 14.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = HarithColors.Fg), cursorBrush = SolidColor(HarithColors.Gold),
                    maxLines = 3, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("مثل: أضف مهمة للتواصل مع العميل غدًا الساعة العاشرة", color = HarithColors.Muted,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                )
                if (input.isBlank()) IconButton(onClick = onVoice) { Icon(Icons.Default.Mic, "تحدّث مع رفيق", tint = HarithColors.GoldText) }
                else IconButton(onClick = ::send, modifier = Modifier.size(42.dp).clip(CircleShape).background(HarithColors.Gold)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = HarithColors.OnGold, modifier = Modifier.size(20.dp))
                }
            }
            val suggestions = buildList {
                add("رتب لي مهام اليوم حسب الأولوية")
                if (overdue > 0) add("ما المهام المتأخرة التي يجب أن أنجزها؟")
                add("لخص لي أهم أعمال هذا الأسبوع")
                add("أعطني موجز اليوم")
                add("ساعدني أخطط لإنجاز مشروع")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s -> Pill(s) { onAsk(s, s) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("+ محادثة جديدة", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onNewChat).padding(horizontal = 6.dp, vertical = 4.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
                if (hasConversation) Text("متابعة المحادثة", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenChat)
                    .padding(horizontal = 6.dp, vertical = 4.dp), color = HarithColors.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** صف مهمة: إنجاز سريع، الأولوية بنص وأيقونة، الموعد، المشروع، وقائمة خيارات (تعديل/تأجيل/بدء/حذف) */
@Composable
fun TodayTaskRow(t: TaskItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    val done = t.status == "done"
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            LocalStore.updateTask(t.id) {
                if (done) it.copy(status = "new", completedAt = 0L) else it.copy(status = "done", completedAt = System.currentTimeMillis())
            }
        }) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (done) "إلغاء الإنجاز" else "إنجاز",
                tint = if (done) HarithColors.Green else if (t.isOverdue || t.priority == "urgent") HarithColors.Red else HarithColors.Muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.title, color = if (done) HarithColors.Muted else HarithColors.Fg, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.description.isNotBlank() && !done) Text(t.description, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!done) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PriorityBadge(t.priority, t.isOverdue)
                if (t.due.isNotBlank()) Tag(dueLabel(t).removePrefix("متأخرة: "), Icons.Default.Schedule)
                if (t.status == "in_progress") Tag("قيد التنفيذ")
                if (t.project.isNotBlank()) Tag(t.project, Icons.Default.Folder)
            } else Text("أُنجزت", color = HarithColors.Green, style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "خيارات", tint = HarithColors.Muted) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("تعديل") }, { menu = false; onEdit() })
                if (!done) DropdownMenuItem({ Text("تأجيل يوم") }, {
                    menu = false
                    LocalStore.updateTask(t.id) {
                        val base = it.dueDate?.takeIf { d -> !d.isBefore(LocalDate.now()) } ?: LocalDate.now()
                        val time = if (it.due.length > 10) it.due.substring(10) else ""
                        it.copy(due = base.plusDays(1).toString() + time, status = "postponed")
                    }
                })
                if (!done && t.status != "in_progress") DropdownMenuItem({ Text("بدء التنفيذ") }, {
                    menu = false; LocalStore.updateTask(t.id) { it.copy(status = "in_progress") }
                })
                DropdownMenuItem({ Text("حذف", color = HarithColors.Red) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ShortcutCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    GlassCard(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick), RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(HarithColors.GoldSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ———————————————————————————— الإضافة السريعة
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onOpenProjects: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HarithColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == null) {
                Text("إضافة سريعة", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                val opts = listOf(
                    Triple("task", "مهمة", Icons.Default.AddTask), Triple("reminder", "تذكير", Icons.Default.AlarmAdd),
                    Triple("note", "ملاحظة", Icons.Default.EditNote), Triple("event", "موعد", Icons.Default.Event),
                    Triple("goal", "هدف", Icons.Default.Flag), Triple("project", "مشروع", Icons.Default.AccountTree)
                )
                opts.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (id, label, icon) ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(HarithColors.SurfaceHigh)
                                    .clickable { if (id == "project") onOpenProjects() else kind = id }.padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(label, style = MaterialTheme.typography.labelLarge, color = HarithColors.Fg)
                            }
                        }
                    }
                }
            } else {
                QuickForm(kind!!, onBack = { kind = null }) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickForm(kind: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(if (kind == "goal") "none" else "today") }
    var time by remember { mutableStateOf(if (kind == "event") "10:00" else "") }
    var priority by remember { mutableStateOf(if (kind == "goal") "high" else "medium") }
    var remindAt by remember { mutableStateOf("1h") }
    var err by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val heading = mapOf("task" to "مهمة جديدة", "reminder" to "تذكير جديد", "note" to "ملاحظة جديدة", "event" to "موعد جديد", "goal" to "هدف جديد")[kind] ?: ""

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        Text("رجوع", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(8.dp), color = HarithColors.Muted,
            style = MaterialTheme.typography.labelLarge)
    }
    OutlinedTextField(title, { title = it; err = "" }, Modifier.fillMaxWidth(), singleLine = kind != "note",
        label = { Text(when (kind) { "reminder" -> "ذكّرني بـ…"; "note" -> "العنوان"; "goal" -> "الهدف"; "event" -> "عنوان الموعد"; else -> "ماذا تريد أن تنجز؟" }) })
    if (kind == "note") OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("النص") })

    if (kind == "task" || kind == "goal" || kind == "event") {
        Text(if (kind == "event") "اليوم" else "الموعد", style = MaterialTheme.typography.labelLarge, color = HarithColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val days = buildList {
                add("today" to "اليوم"); add("tomorrow" to "غدًا"); add("week" to "بعد أسبوع")
                if (kind != "event") add("none" to "بدون موعد")
            }
            days.forEach { (k, l) -> Pill(l, day == k) { day = k } }
        }
        OutlinedTextField(time, { time = it.filter { c -> c.isDigit() || c == ':' }.take(5) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (kind == "event") "الساعة (مثل 14:30)" else "الساعة (اختياري، مثل 10:00)") },
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
    }
    if (kind == "task" || kind == "goal") {
        Text("الأولوية", style = MaterialTheme.typography.labelLarge, color = HarithColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("medium" to "عادية", "high" to "مرتفعة", "urgent" to "عاجلة").forEach { (k, l) -> Pill(l, priority == k) { priority = k } }
        }
    }
    if (kind == "reminder") {
        Text("متى؟", style = MaterialTheme.typography.labelLarge, color = HarithColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("30m" to "بعد ٣٠ دقيقة", "1h" to "بعد ساعة", "21" to "الليلة ٩ م", "t9" to "غدًا ٩ ص").forEach { (k, l) -> Pill(l, remindAt == k) { remindAt = k } }
        }
    }
    if (err.isNotBlank()) Text(err, color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)

    PrimaryButton("حفظ", Modifier.fillMaxWidth(), enabled = title.isNotBlank()) {
        val tm = time.trim()
        if (tm.isNotEmpty() && !Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(tm)) { err = "اكتب الساعة بصيغة 14:30"; return@PrimaryButton }
        val date = when (day) { "today" -> today; "tomorrow" -> today.plusDays(1); "week" -> today.plusDays(7); else -> null }
        val hhmm = tm.split(":").let { if (it.size == 2) String.format(Locale.US, "%02d:%02d", it[0].toInt(), it[1].toInt()) else "" }
        when (kind) {
            "task", "goal" -> {
                val id = LocalStore.newId()
                val due = date?.let { d -> if (hhmm.isNotEmpty()) "${d}T$hhmm" else d.toString() } ?: ""
                LocalStore.addTask(TaskItem(id, title.trim(), priority = priority, due = due, category = if (kind == "goal") "هدف" else ""))
                // لا نعلن النجاح إلا بعد التحقق من الحفظ
                if (LocalStore.task(id) != null) onDone(if (kind == "goal") "حُفظ الهدف" else "أُضيفت المهمة") else err = "تعذّر الحفظ، حاول مرة أخرى"
            }
            "note" -> {
                val id = LocalStore.newId()
                LocalStore.addNote(NoteItem(id, title.trim(), body.trim().ifBlank { title.trim() }))
                if (LocalStore.notes.value.any { it.id == id }) onDone("حُفظت الملاحظة") else err = "تعذّر الحفظ"
            }
            "reminder" -> {
                val nowDt = LocalDateTime.now()
                val at = when (remindAt) {
                    "30m" -> nowDt.plusMinutes(30); "1h" -> nowDt.plusHours(1)
                    "21" -> today.atTime(21, 0).let { if (it.isAfter(nowDt)) it else it.plusDays(1) }
                    else -> today.plusDays(1).atTime(9, 0)
                }.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val r = ReminderItem(LocalStore.newId(), title.trim(), at)
                LocalStore.upsertReminder(r)
                val exact = com.alharith.ai.service.Reminders.schedule(context, r)
                if (LocalStore.reminder(r.id) != null) onDone("ضُبط التذكير ${SimpleDateFormat("EEEE h:mm a", Locale("ar")).format(Date(at))}" +
                    if (!exact) " (قد يتأخر دقائق)" else "")
                else err = "تعذّر ضبط التذكير"
            }
            "event" -> {
                val d = date ?: today
                val (h, m) = (hhmm.ifEmpty { "10:00" }).split(":").map { it.toInt() }
                val begin = d.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                // يفتح تقويم الجوال لتأكيد الموعد وحفظه هناك (لا نحفظه دون تأكيدك)
                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, title.trim())
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60_000L)
                runCatching { context.startActivity(intent) }
                    .onSuccess { onDone("أكمل حفظ الموعد في التقويم") }
                    .onFailure { err = "لا يوجد تطبيق تقويم على الجوال" }
            }
        }
    }
}

/** العاجلة بالأحمر، العالية بالذهبي، والباقي رمادي */
@Composable
fun priorityColor(p: String) = when (p) {
    "urgent" -> HarithColors.Red
    "high" -> HarithColors.GoldText
    "medium" -> HarithColors.Fg
    else -> HarithColors.Muted
}

/** للتوافق: صف مهمة مختصر */
@Composable
fun HomeTaskRow(t: TaskItem) = TodayTaskRow(t, onEdit = {}, onDelete = {})

fun dueLabel(t: TaskItem): String {
    val d = t.dueDate ?: return t.due
    val today = LocalDate.now()
    val day = when (d) {
        today -> "اليوم"
        today.plusDays(1) -> "غدًا"
        today.minusDays(1) -> "أمس"
        else -> arNum("${d.dayOfMonth}/${d.monthValue}")
    }
    val time = if (t.due.length > 10) " " + arNum(t.due.substring(11, 16)) else ""
    return (if (t.isOverdue) "متأخرة: " else "") + day + time
}

/** أرقام عربية مشرقية (٠١٢٣) لتتسق مع التواريخ */
fun arNum(v: Any): String = v.toString().map { c -> if (c in '0'..'9') ('٠' + (c - '0')) else c }.joinToString("")
