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
import androidx.compose.foundation.layout.offset
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

    val state by com.alharith.ai.data.ConversationStore.state.collectAsState()

    // الرئيسية داكنة دائمًا لتظهر زخرفة الكسوة الذهبية كما هي، حتى في الوضع الفاتح
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val win = (view.context as? android.app.Activity)?.window
        val ctl = win?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val prevS = ctl?.isAppearanceLightStatusBars
        val prevN = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false
        ctl?.isAppearanceLightNavigationBars = false
        onDispose {
            prevS?.let { ctl.isAppearanceLightStatusBars = it }
            prevN?.let { ctl.isAppearanceLightNavigationBars = it }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalHarithPalette provides Dark) {
    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        OrnamentBackdrop(
            shiftFraction = 0.24f,
            scrim = listOf(0f to 0.96f, 0.40f to 0.86f, 0.70f to 0.35f, 1f to 0.55f)
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 230.dp)
        ) {
            // ——— الشعار والتاريخ
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).reveal(0), verticalAlignment = Alignment.CenterVertically) {
                GoldText("رفيق", MaterialTheme.typography.headlineMedium.copy(fontFamily = Ruqaa, fontSize = 38.sp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(gregorian, style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg.copy(alpha = 0.75f))
                    if (hijri.isNotBlank()) Text(hijri, style = MaterialTheme.typography.bodySmall, color = Luxe.Gold)
                }
            }

            // ——— التحية
            Spacer(Modifier.height(26.dp))
            Text("$greeting،", Modifier.reveal(1), style = MaterialTheme.typography.headlineSmall, color = HarithColors.Fg.copy(alpha = 0.7f))
            GoldText(Prefs.userName, MaterialTheme.typography.displayLarge, Modifier.reveal(2))

            // ——— بطاقات الأرقام
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().reveal(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), dueToday.size, "مهام اليوم", onClick = onOpenTasks)
                StatTile(Modifier.weight(1f), overdue.size, "متأخرة", alert = overdue.isNotEmpty(), onClick = onOpenTasks)
                StatTile(Modifier.weight(1f), if (calendarAllowed) events.size else nextReminders.size,
                    if (calendarAllowed) "مواعيد" else "تذكيرات", onClick = onOpenChat)
            }

            // ——— الموعد التالي
            val nextTxt = when {
                !calendarAllowed -> "امنح صلاحية التقويم لترى مواعيدك هنا"
                nextEvent == null -> "لا مواعيد متبقية اليوم"
                nextEvent.allDay -> "طوال اليوم: ${nextEvent.title}"
                else -> "التالي: ${nextEvent.title} — ${timeFmt.format(Date(nextEvent.begin))}"
            }
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth().reveal(4), RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = Luxe.Gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(nextTxt, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ——— اقتراحات سريعة
            Spacer(Modifier.height(18.dp))
            val suggestions = buildList {
                add("رتب لي يومي")
                if (overdue.isNotEmpty()) add("رتب المتأخرة حسب الأولوية")
                add("ما الذي يحتاج متابعة؟")
                if (hour >= 17) add("ماذا أنجزت اليوم؟") else add("جهز لي يومي بكرة")
                add("موجز اليوم")
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).reveal(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { s ->
                    GlassCard(shape = RoundedCornerShape(50)) {
                        Text(
                            s,
                            Modifier.clickable { onAsk(if (s == "موجز اليوم") "أعطني موجز اليوم" else s, s) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg
                        )
                    }
                }
            }

            // ——— المهام
            Spacer(Modifier.height(22.dp))
            GlassCard(Modifier.fillMaxWidth().reveal(6), strong = true) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("أهم المهام", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                        Text(
                            "عرض الكل", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenTasks).padding(6.dp),
                            color = Luxe.Gold, style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (important.isEmpty()) {
                        Text(
                            "لا مهام مفتوحة. قل: \"أضف مهمة أرسل العرض بكرة الساعة 10\".",
                            Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted
                        )
                    }
                    important.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = Luxe.Gold.copy(alpha = 0.12f))
                        HomeTaskRow(t)
                    }
                }
            }

            // ——— التذكيرات القادمة
            if (nextReminders.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                GlassCard(Modifier.fillMaxWidth().reveal(7)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("التذكيرات", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                        val fmt = SimpleDateFormat("EEEE، h:mm a", Locale("ar"))
                        nextReminders.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Luxe.Gold))
                                Spacer(Modifier.width(12.dp))
                                Text(r.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                                Text(fmt.format(Date(r.at)), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ——— الدائرة الحية فوق شريط تنقل زجاجي
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, HarithColors.Bg.copy(alpha = 0.85f))))
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            GlassCard(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(70.dp),
                RoundedCornerShape(35.dp), strong = true
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DockItem(Icons.Default.Checklist, "المهام", onOpenTasks)
                    DockItem(Icons.AutoMirrored.Filled.Chat, "المحادثة", onOpenChat)
                    Spacer(Modifier.weight(1.4f))
                    DockItem(Icons.Default.NotificationsActive, "موجز", { onAsk("أعطني موجز اليوم", "موجز اليوم") })
                    DockItem(Icons.Default.Settings, "الإعدادات", onOpenSettings)
                }
            }
            Column(Modifier.padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تكلّم مع رفيق", style = MaterialTheme.typography.labelLarge, color = Luxe.GoldLight)
                LuxeOrb(state, 150.dp, onClick = onVoice)
            }
        }
    }}
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DockItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = HarithColors.Fg.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = HarithColors.Fg.copy(alpha = 0.7f))
    }
}

@Composable
private fun StatTile(modifier: Modifier, value: Int, label: String, alert: Boolean = false, onClick: () -> Unit) {
    GlassCard(modifier.clickable(onClick = onClick), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            if (alert) Text(arNum(value), style = MaterialTheme.typography.headlineMedium, color = HarithColors.Red)
            else GoldText(arNum(value), MaterialTheme.typography.headlineMedium, shimmer = false)
            Text(label, style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg.copy(alpha = 0.7f))
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
        else -> arNum("${d.dayOfMonth}/${d.monthValue}")
    }
    val time = if (t.due.length > 10) " " + arNum(t.due.substring(11, 16)) else ""
    return (if (t.isOverdue) "متأخرة: " else "") + day + time
}

/** أرقام عربية مشرقية (٠١٢٣) لتتسق مع التواريخ */
fun arNum(v: Any): String = v.toString().map { c -> if (c in '0'..'9') ('٠' + (c - '0')) else c }.joinToString("")
