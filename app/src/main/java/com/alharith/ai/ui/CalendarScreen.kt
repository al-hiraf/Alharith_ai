package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.ReminderItem
import com.alharith.ai.data.TaskItem
import com.alharith.ai.tools.CalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * التقويم: شريط الأسبوع، وجدول اليوم المختار يجمع مواعيد تقويم الجوال والتذكيرات والمهام المستحقة.
 * الإضافة: موعد (يُفتح تقويم الجوال لتأكيده) أو تذكير (يُجدول فعليًا بمنبهات النظام).
 */
@Composable
fun CalendarScreen(onBack: (() -> Unit)? = null, onOpenTasks: () -> Unit = {}) {
    val context = LocalContext.current
    val tasks by LocalStore.tasks.collectAsState()
    val reminders by LocalStore.reminders.collectAsState()
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day = LocalDate.parse(selected)
    var weekStart by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(((LocalDate.now().dayOfWeek.value + 1) % 7).toLong()).toString()) }
    var events by remember { mutableStateOf<List<CalendarReader.Event>>(emptyList()) }
    var granted by remember { mutableStateOf(has(context)) }
    var tick by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var delReminder by remember { mutableStateOf<ReminderItem?>(null) }
    var delTask by remember { mutableStateOf<TaskItem?>(null) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = has(context); tick++ }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { granted = has(context); tick++; onPauseOrDispose { } }

    LaunchedEffect(selected, granted, tick) {
        events = if (!granted) emptyList() else withContext(Dispatchers.IO) {
            val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching { CalendarReader.range(context, start, start + 86_400_000L, 60) }.getOrDefault(emptyList())
        }
    }
    val zone = ZoneId.systemDefault()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayReminders = reminders.filter { it.at in dayStart until dayStart + 86_400_000L }.sortedBy { it.at }
    val dayTasks = tasks.filter { it.status != "cancelled" && it.dueDate == day }.sortedBy { it.dueMillis ?: Long.MAX_VALUE }
    val overdueBefore = if (day == LocalDate.now()) tasks.filter { it.isOverdue && it.dueDate?.isBefore(day) == true } else emptyList()
    val fmtTime = SimpleDateFormat("h:mm a", Locale("ar"))

    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            RafiqTopBar("التقويم", onBack, subtitle = arNum(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale("ar"))))) {
                if (day != LocalDate.now()) Text("اليوم", Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    selected = LocalDate.now().toString()
                    weekStart = LocalDate.now().minusDays(((LocalDate.now().dayOfWeek.value + 1) % 7).toLong()).toString()
                }.padding(8.dp), color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
            }
            // ——— شريط الأسبوع (يبدأ السبت)
            val ws = LocalDate.parse(weekStart)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekStart = ws.minusDays(7).toString(); selected = ws.minusDays(7).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "الأسبوع السابق", tint = HarithColors.Muted)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until 7) {
                        val d = ws.plusDays(i.toLong())
                        val sel = d == day
                        val isToday = d == LocalDate.now()
                        val busy = tasks.any { it.isOpen && it.dueDate == d } ||
                            reminders.any { r -> r.at >= d.atStartOfDay(zone).toInstant().toEpochMilli() && r.at < d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) HarithColors.Fg else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (isToday && !sel) Modifier.border(1.5.dp, HarithColors.Gold, RoundedCornerShape(14.dp)) else Modifier)
                                .clickable { selected = d.toString() }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(d.format(DateTimeFormatter.ofPattern("EEE", Locale("ar"))).take(3), style = MaterialTheme.typography.labelSmall,
                                color = if (sel) HarithColors.Bg else HarithColors.Muted, maxLines = 1)
                            Text(arNum(d.dayOfMonth), style = MaterialTheme.typography.titleSmall, color = if (sel) HarithColors.Bg else HarithColors.Fg)
                            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(if (busy) HarithColors.Gold else androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
                IconButton(onClick = { weekStart = ws.plusDays(7).toString(); selected = ws.plusDays(7).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "الأسبوع التالي", tint = HarithColors.Muted)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!granted) GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("اعرض مواعيد تقويم جوالك هنا", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
                        Text("رفيق يقرأ التقويم على جوالك فقط لعرض مواعيدك وتنبيهك بها.", style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                        PrimaryButton("السماح بقراءة التقويم") {
                            ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }
                    }
                }

                // ——— جدول زمني واحد: المواعيد والتذكيرات والمهام مرتبة بالوقت
                val nowMs = System.currentTimeMillis()
                val timed = (events.map { Entry(if (it.allDay) dayStart - 1 else it.begin, e = it) } +
                    dayReminders.map { Entry(it.at, r = it) } +
                    dayTasks.filter { it.due.length > 10 }.map { Entry(it.dueMillis ?: dayStart, t = it) }).sortedBy { it.at }
                val untimed = overdueBefore + dayTasks.filter { it.due.length <= 10 }

                if (timed.isEmpty() && untimed.isEmpty()) {
                    EmptyView(if (day == LocalDate.now()) "يومك فاضي" else "لا شيء في هذا اليوم",
                        if (granted) "لا مواعيد ولا تذكيرات ولا مهام مستحقة." else "لا تذكيرات ولا مهام مستحقة.", Icons.Default.TaskAlt)
                    PrimaryButton("أضف موعدًا أو تذكيرًا", Modifier.fillMaxWidth(), icon = Icons.Default.Add) { showAdd = true }
                }
                if (timed.isNotEmpty()) {
                    SectionHeader("الجدول")
                    var nowShown = day != LocalDate.now()
                    Column { timed.forEach { en ->
                        if (!nowShown && en.at > nowMs) { NowLine(fmtTime.format(Date(nowMs))); nowShown = true }
                        en.e?.let { e ->
                            TimelineRow(if (e.allDay) "طوال اليوم" else fmtTime.format(Date(e.begin)), HarithColors.Gold) {
                                EntryText(e.title, listOfNotNull("موعد", if (!e.allDay) "حتى ${fmtTime.format(Date(e.end))}" else null,
                                    e.location.takeIf { it.isNotBlank() }).joinToString("، ")) {
                                    runCatching {
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)))
                                    }
                                }
                            }
                        }
                        en.r?.let { r ->
                            TimelineRow(fmtTime.format(Date(r.at)), HarithColors.Fg) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        EntryText(r.text, "تذكير" + (if (r.repeat != "none") "، يتكرر ${com.alharith.ai.service.Reminders.REPEAT_AR[r.repeat] ?: r.repeat}" else "")) {}
                                    }
                                    IconButton(onClick = { delReminder = r }) { Icon(Icons.Default.DeleteOutline, "إلغاء التذكير", tint = HarithColors.Muted) }
                                }
                            }
                        }
                        en.t?.let { t ->
                            TimelineRow(fmtTime.format(Date(en.at)), if (t.isOverdue) HarithColors.Red else HarithColors.Muted) {
                                SwipeTask(t, snack) { TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { delTask = t }, snack = snack) }
                            }
                        }
                    } }
                }
                if (untimed.isNotEmpty()) {
                    SectionHeader(if (timed.isEmpty()) "المهام المستحقة" else "مهام بلا وقت محدد", action = "كل المهام", onAction = onOpenTasks)
                    untimed.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
                        SwipeTask(t, snack) { TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { delTask = t }, snack = snack) }
                    }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 88.dp)) { d ->
            androidx.compose.material3.Snackbar(d, containerColor = if (HarithColors.IsDark) HarithColors.SurfaceHigh else androidx.compose.ui.graphics.Color(0xFF252525),
                contentColor = androidx.compose.ui.graphics.Color(0xFFF7F5F0), actionColor = HarithColors.Gold)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(58.dp).clip(RoundedCornerShape(18.dp))
                .background(if (HarithColors.IsDark) HarithColors.Gold else androidx.compose.ui.graphics.Color(0xFF252525)).clickable { showAdd = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "إضافة موعد أو تذكير", tint = if (HarithColors.IsDark) HarithColors.OnGold else androidx.compose.ui.graphics.Color.White) }
    }
    if (showAdd) QuickAddSheet(onDismiss = { showAdd = false; tick++ }, onOpenProjects = { showAdd = false })
    editTask?.let { t -> TaskEditDialog(t) { editTask = null } }
    delTask?.let { t ->
        AlertDialog(onDismissRequest = { delTask = null }, containerColor = HarithColors.Surface,
            title = { Text("حذف المهمة؟") }, text = { Text(t.title) },
            confirmButton = { TextButton(onClick = { LocalStore.deleteTask(t.id); delTask = null }) { Text("حذف", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { delTask = null }) { Text("رجوع", color = HarithColors.Fg) } })
    }
    delReminder?.let { r ->
        AlertDialog(onDismissRequest = { delReminder = null }, containerColor = HarithColors.Surface,
            title = { Text("إلغاء التذكير؟") }, text = { Text(r.text) },
            confirmButton = { TextButton(onClick = {
                com.alharith.ai.service.Reminders.cancel(context, r); LocalStore.deleteReminder(r.id); delReminder = null
            }) { Text("إلغاء التذكير", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { delReminder = null }) { Text("رجوع", color = HarithColors.Fg) } })
    }
}

private fun has(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

@Composable
private fun AgendaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, extra: String?,
                      trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(sub, extra).joinToString("، "), style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
            }
            trailing()
        }
        HorizontalDivider(Modifier.padding(start = 38.dp), color = HarithColors.Line)
    }
}

private data class Entry(val at: Long, val e: CalendarReader.Event? = null, val r: ReminderItem? = null, val t: TaskItem? = null)

/** صف في الجدول الزمني: عمود الوقت، ثم خط رأسي ونقطة ملوّنة، ثم المحتوى */
@Composable
private fun TimelineRow(time: String, dot: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(time, Modifier.width(62.dp).padding(top = 14.dp), style = MaterialTheme.typography.labelMedium, color = HarithColors.Muted, maxLines = 2)
        Box(Modifier.width(18.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(HarithColors.Line))
            Box(Modifier.padding(top = 18.dp).size(9.dp).clip(RoundedCornerShape(50)).background(dot))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun EntryText(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
    }
}

/** خط «الآن» في جدول اليوم */
@Composable
private fun NowLine(time: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("الآن", Modifier.width(62.dp), style = MaterialTheme.typography.labelMedium, color = HarithColors.Red)
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(HarithColors.Red))
        Box(Modifier.weight(1f).height(1.5.dp).background(HarithColors.Red.copy(alpha = 0.6f)))
        Spacer(Modifier.width(6.dp))
        Text(time, style = MaterialTheme.typography.labelSmall, color = HarithColors.Red)
    }
}
