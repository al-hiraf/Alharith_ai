package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.TaskItem
import com.alharith.ai.tools.CalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** الشاشة الرئيسية "يومي": التحية، التاريخ، ملخص اليوم، المهام المهمة، والزر الصوتي الكبير. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onVoice: () -> Unit,
    onAsk: (text: String, label: String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val tasks by LocalStore.tasks.collectAsState()
    val reminders by LocalStore.reminders.collectAsState()
    var events by remember { mutableStateOf<List<CalendarReader.Event>>(emptyList()) }
    var calendarAllowed by remember { mutableStateOf(true) }

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
    val greeting = if (hour in 4..11) "صباح الخير" else "مساء الخير"
    val now = Date()
    val gregorian = SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar")).format(now)
    val hijri = runCatching {
        android.icu.text.SimpleDateFormat("d MMMM y", android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura")).format(now) + " هـ"
    }.getOrDefault("")

    val today = LocalDate.now()
    val open = tasks.filter { it.isOpen }
    val overdue = open.filter { it.isOverdue }
    val dueToday = open.filter { t -> t.dueDate?.let { !it.isAfter(today) } ?: false }
    val important = open.sortedWith(compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE })).take(5)
    val nowMs = System.currentTimeMillis()
    val nextEvent = events.firstOrNull { it.end > nowMs && !it.allDay } ?: events.firstOrNull { it.allDay }
    val nextReminders = reminders.filter { it.at > nowMs }.sortedBy { it.at }.take(3)
    val timeFmt = SimpleDateFormat("h:mm a", Locale("ar"))

    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 170.dp)
        ) {
            // ——— سطر علوي هادئ: التاريخ والتنقل
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(gregorian, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                    if (hijri.isNotBlank()) Text(hijri, style = MaterialTheme.typography.bodySmall, color = HarithColors.GoldText)
                }
                IconButton(onClick = onOpenTasks) { Icon(Icons.Default.Checklist, "المهام", tint = HarithColors.Fg) }
                IconButton(onClick = onOpenChat) { Icon(Icons.AutoMirrored.Filled.Chat, "المحادثة", tint = HarithColors.Fg) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "الإعدادات", tint = HarithColors.Fg) }
            }

            // ——— التحية: العنصر الطباعي الأبرز
            Spacer(Modifier.height(36.dp))
            Text("$greeting،", style = MaterialTheme.typography.displayMedium, color = HarithColors.Muted)
            Text("${Prefs.userName}.", style = MaterialTheme.typography.displayMedium, color = HarithColors.Fg)

            // ——— ملخص اليوم في جملة واحدة، الأرقام بالذهبي
            Spacer(Modifier.height(20.dp))
            val num = SpanStyle(color = HarithColors.GoldText, fontWeight = FontWeight.Bold)
            val summary = buildAnnotatedString {
                append("اليوم ")
                withStyle(num) { append(dueToday.size.toString()) }
                append(if (dueToday.size == 1) " مهمة" else " مهام")
                if (overdue.isNotEmpty()) {
                    append("، منها ")
                    withStyle(SpanStyle(color = HarithColors.Red, fontWeight = FontWeight.Bold)) { append(overdue.size.toString()) }
                    append(" متأخرة")
                }
                if (calendarAllowed) {
                    append("، و")
                    withStyle(num) { append(events.size.toString()) }
                    append(if (events.size == 1) " موعد." else " مواعيد.")
                } else append(".")
            }
            Text(summary, style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            val nextTxt = when {
                !calendarAllowed -> "امنح صلاحية التقويم لترى مواعيدك هنا."
                nextEvent == null -> "لا مواعيد متبقية اليوم."
                nextEvent.allDay -> "طوال اليوم: ${nextEvent.title}"
                else -> "التالي: ${nextEvent.title}، الساعة ${timeFmt.format(Date(nextEvent.begin))}"
            }
            Text(nextTxt, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)

            // ——— اقتراحات سريعة
            Spacer(Modifier.height(28.dp))
            val suggestions = buildList {
                add("رتب لي يومي")
                if (overdue.isNotEmpty()) add("رتب المتأخرة حسب الأولوية")
                add("ما الذي يحتاج متابعة؟")
                if (hour >= 17) add("ماذا أنجزت اليوم؟") else add("جهز لي يومي بكرة")
                add("موجز اليوم")
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { s ->
                    Text(
                        s,
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, HarithColors.Line, RoundedCornerShape(50))
                            .clickable { onAsk(if (s == "موجز اليوم") "أعطني موجز اليوم" else s, s) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg
                    )
                }
            }

            // ——— المهام: قائمة نظيفة بفواصل رفيعة
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("المهام", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                Text(
                    "عرض الكل", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenTasks).padding(6.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(4.dp))
            if (important.isEmpty()) {
                Text(
                    "لا مهام مفتوحة. قل: \"أضف مهمة أرسل العرض بكرة الساعة 10\".",
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted
                )
            }
            important.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = HarithColors.Line)
                HomeTaskRow(t)
            }

            // ——— التذكيرات القادمة
            if (nextReminders.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("التذكيرات", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                Spacer(Modifier.height(4.dp))
                val fmt = SimpleDateFormat("EEEE، h:mm a", Locale("ar"))
                nextReminders.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = HarithColors.Line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, null, tint = HarithColors.GoldText, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(r.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                        Text(fmt.format(Date(r.at)), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ——— زر الصوت: الدائرة الذهبية هي العنصر الوحيد البارز في الشاشة
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, HarithColors.Bg, HarithColors.Bg)))
                .navigationBarsPadding()
                .padding(top = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(HarithColors.Gold)
                    .clickable(onClick = onVoice),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Mic, "تحدّث مع الحارث", tint = HarithColors.OnGold, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("ماذا تريد أن أفعل؟", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        }
    }
}

@Composable
fun HomeTaskRow(t: TaskItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            LocalStore.updateTask(t.id) { it.copy(status = "done", completedAt = System.currentTimeMillis()) }
        }) {
            Icon(
                if (t.status == "done") Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, "إنجاز",
                tint = priorityColor(t.priority)
            )
        }
        Text(
            t.title, Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (t.due.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(dueLabel(t), color = if (t.isOverdue) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** العاجلة بالأحمر، العالية بالذهبي، والباقي رمادي */
@Composable
fun priorityColor(p: String) = when (p) {
    "urgent" -> HarithColors.Red
    "high" -> HarithColors.Gold
    "medium" -> HarithColors.Fg
    else -> HarithColors.Muted
}

fun dueLabel(t: TaskItem): String {
    val d = t.dueDate ?: return t.due
    val today = LocalDate.now()
    val day = when (d) {
        today -> "اليوم"
        today.plusDays(1) -> "غدًا"
        today.minusDays(1) -> "أمس"
        else -> "${d.dayOfMonth}/${d.monthValue}"
    }
    val time = if (t.due.length > 10) " " + t.due.substring(11, 16) else ""
    return (if (t.isOverdue) "متأخرة: " else "") + day + time
}
