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
import androidx.compose.ui.text.font.FontWeight
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

    LaunchedEffect(Unit) {
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
                .padding(horizontal = 18.dp)
                .padding(bottom = 140.dp)
        ) {
            // ——— الترويسة
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$greeting، ${Prefs.userName}", style = MaterialTheme.typography.headlineSmall, color = HarithColors.Fg)
                    Text(gregorian, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                    if (hijri.isNotBlank()) Text(hijri, style = MaterialTheme.typography.bodySmall, color = HarithColors.GoldDim)
                }
                IconButton(onClick = onOpenTasks) { Icon(Icons.Default.Checklist, "المهام", tint = HarithColors.Muted) }
                IconButton(onClick = onOpenChat) { Icon(Icons.AutoMirrored.Filled.Chat, "المحادثة", tint = HarithColors.Muted) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "الإعدادات", tint = HarithColors.Muted) }
            }

            Spacer(Modifier.height(18.dp))

            // ——— بطاقة "يومك"
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A2214), HarithColors.Surface)))
                    .border(1.dp, HarithColors.GoldDim.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Text("يومك", style = MaterialTheme.typography.titleMedium, color = HarithColors.Gold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("مهام اليوم", dueToday.size.toString(), Modifier.weight(1f))
                    Stat("متأخرة", overdue.size.toString(), Modifier.weight(1f), if (overdue.isNotEmpty()) HarithColors.Red else HarithColors.Fg)
                    Stat("مواعيد", if (calendarAllowed) events.size.toString() else "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                val nextTxt = when {
                    !calendarAllowed -> "امنح صلاحية التقويم من الإعدادات لعرض مواعيدك."
                    nextEvent == null -> "لا مواعيد متبقية اليوم."
                    nextEvent.allDay -> "اليوم: ${nextEvent.title}"
                    else -> "أقرب موعد: ${nextEvent.title} — ${timeFmt.format(Date(nextEvent.begin))}"
                }
                Text(nextTxt, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
                if (overdue.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "لديك ${overdue.size} ${if (overdue.size == 1) "مهمة متأخرة" else "مهام متأخرة"} — اطلب \"رتبها حسب الأولوية\".",
                        style = MaterialTheme.typography.bodySmall, color = HarithColors.Red
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ——— اقتراحات الحارث
            Text("اقتراحات", style = MaterialTheme.typography.titleSmall, color = HarithColors.Muted)
            Spacer(Modifier.height(8.dp))
            val suggestions = buildList {
                add("رتب لي يومي")
                if (overdue.isNotEmpty()) add("رتب المهام المتأخرة حسب الأولوية")
                add("ما الأشياء المهمة التي تحتاج متابعة؟")
                if (hour >= 17) add("ماذا أنجزت اليوم؟") else add("جهز لي يومي بكرة")
                add("أعطني موجز اليوم")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Text(
                        s,
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, HarithColors.Line, RoundedCornerShape(20.dp))
                            .clickable { onAsk(s, s) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ——— المهام المهمة
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("المهام المهمة", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = HarithColors.Muted)
                Text("الكل", Modifier.clickable(onClick = onOpenTasks).padding(6.dp), color = HarithColors.Gold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(6.dp))
            if (important.isEmpty()) {
                Text(
                    "لا مهام مفتوحة. قل مثلًا: \"أضف مهمة أرسل العرض بكرة الساعة 10\".",
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted
                )
            }
            important.forEach { t -> HomeTaskRow(t) }

            // ——— التذكيرات القادمة
            if (nextReminders.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("التذكيرات القادمة", style = MaterialTheme.typography.titleSmall, color = HarithColors.Muted)
                Spacer(Modifier.height(6.dp))
                val fmt = SimpleDateFormat("EEEE h:mm a", Locale("ar"))
                nextReminders.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, null, tint = HarithColors.GoldDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(r.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                        Text(fmt.format(Date(r.at)), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ——— الزر الصوتي الكبير
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, HarithColors.Bg, HarithColors.Bg)))
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(HarithColors.Gold)
                    .clickable(onClick = onVoice)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x22000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, null, tint = Color(0xFF1A1405), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("ماذا تريد أن أفعل؟", color = Color(0xFF1A1405), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = HarithColors.Fg) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x14FFFFFF))
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HomeTaskRow(t: TaskItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HarithColors.Surface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
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
        Column(Modifier.weight(1f)) {
            Text(t.title, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                t.due.takeIf { it.isNotBlank() }?.let { dueLabel(t) },
                t.project.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = if (t.isOverdue) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun priorityColor(p: String) = when (p) {
    "urgent" -> HarithColors.Red
    "high" -> HarithColors.Gold
    "medium" -> HarithColors.Blue
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
    return (if (t.isOverdue) "متأخرة · " else "") + day + time
}
