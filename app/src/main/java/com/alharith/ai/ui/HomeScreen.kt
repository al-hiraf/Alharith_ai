package com.alharith.ai.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.alharith.ai.data.SharedBrain
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
    onOpenCalendar: () -> Unit = {},
    onOpenApprovals: () -> Unit = {}
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
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
    val weekday = SimpleDateFormat("EEEE", Locale("ar")).format(now)
    val gregorian = arNum(SimpleDateFormat("d MMMM y", Locale("ar")).format(now))
    val hijri = runCatching {
        android.icu.text.SimpleDateFormat("d MMMM", android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura")).format(now)
    }.getOrDefault("")

    // ——— المؤشرات: محسوبة من المهام الفعلية وتتحدث تلقائيًا مع أي تغيير
    val today = LocalDate.now()
    val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endOfDay = startOfDay + 86_400_000L
    val remaining = tasks.filter { it.isOpen && (it.dueDate?.let { d -> !d.isAfter(today) } ?: false) }
    val overdue = tasks.filter { it.isOverdue }
    val doneToday = tasks.filter { it.status == "done" && it.completedAt >= startOfDay }
    val dayTotal = remaining.size + doneToday.size
    val progress = if (dayTotal > 0) doneToday.size.toFloat() / dayTotal else 0f
    val nowMs = System.currentTimeMillis()
    val timeFmt = SimpleDateFormat("h:mm a", Locale("ar"))
    val nextUp = TaskActions.nextUp(tasks)

    // ——— جدول اليوم: المواعيد والتذكيرات والمهام ذات الساعة، مرتبة بالوقت
    val agenda = buildList {
        events.filter { it.end > nowMs }.forEach { add(Agenda(if (it.allDay) startOfDay else it.begin, it.title, "event", it.allDay)) }
        reminders.filter { it.at in nowMs until endOfDay }.forEach { add(Agenda(it.at, it.text, "reminder")) }
        tasks.filter { it.isOpen && it.due.length > 10 && it.dueDate == today && it.id != nextUp?.id }
            .forEach { t -> t.dueMillis?.let { add(Agenda(it, t.title, "task")) } }
    }.sortedWith(compareBy({ !it.allDay }, { it.at }))
    val laterReminders = reminders.filter { it.at >= endOfDay }.sortedBy { it.at }.take(3)

    val todayList = (if (remaining.isNotEmpty()) remaining else tasks.filter { it.isOpen && it.status == "in_progress" })
        .filter { it.id != nextUp?.id }
        .sortedWith(
            if (sort == "due") compareBy<TaskItem>({ it.dueMillis ?: Long.MAX_VALUE }, { it.priorityRank })
            else compareBy<TaskItem>({ !it.isOverdue }, { it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE })
        )
    val upcomingOpen = tasks.filter { it.isOpen && (it.dueDate?.isAfter(today) ?: true) }
        .sortedWith(compareBy<TaskItem>({ it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE })).take(3)

    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 90.dp).statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // ——— الترويسة: الشعار، البحث، الحساب
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(34.dp)
                Spacer(Modifier.width(8.dp))
                Text("رفيق", style = MaterialTheme.typography.titleLarge.copy(fontFamily = Ruqaa, fontSize = 22.sp), color = HarithColors.Fg)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, "بحث شامل", tint = HarithColors.Fg) }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).border(1.dp, HarithColors.Line, CircleShape).clickable(onClick = onOpenAccount),
                    contentAlignment = Alignment.Center
                ) { Text(Prefs.userName.take(1).ifBlank { "ر" }, color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall) }
            }

            // ——— اليوم: التحية عنوانًا، والتاريخان تحتها
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HeadlineText(if (Prefs.userName.isBlank()) greeting else "$greeting يا ${Prefs.userName}", MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 42.sp), maxLines = 2)
                Text(if (hijri.isNotBlank()) "$weekday ${arNum(hijri)}، الموافق $gregorian" else "$weekday، $gregorian",
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
            }

            // ——— الإشعارات متوقفة: بدونها لا تظهر التذكيرات ولا تنبيهات الخادم
            NotificationsOffBanner(resumes)
            // ——— إنجاز اليوم: حلقة صغيرة وجملة واحدة مفهومة
            RafiqHero(Modifier.fillMaxWidth().clickable(onClick = onOpenTasks)) {
            Row(Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    ProgressRing(progress, 76.dp, 6.dp)
                    Text(when { dayTotal == 0 -> "—"; else -> arNum("${doneToday.size}/${dayTotal}") }, style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF2F0EC))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("إنجاز اليوم", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2F0EC))
                    val left = remaining.size
                    Text(when {
                        dayTotal == 0 -> "لا مهام مستحقة اليوم، يومك مرن"
                        left == 0 -> "أنجزت كل مهام اليوم، عمل رائع"
                        doneToday.isEmpty() -> "أمامك ${arNum(left)} ${if (left == 1) "مهمة" else "مهام"} اليوم"
                        else -> "أنجزت ${arNum(doneToday.size)} من ${arNum(dayTotal)}، وبقي ${arNum(left)}"
                    }, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB7CCD3))
                    if (overdue.isNotEmpty()) Text("${arNum(overdue.size)} ${if (overdue.size == 1) "مهمة متأخرة" else "مهام متأخرة"}",
                        style = MaterialTheme.typography.labelLarge, color = HarithColors.Red)
                }
            }

            }

            // ——— رفيق: سطر أوامر واحد واقتراحات صغيرة
            AskRafiq(
                hasConversation = messages.any { !it.isAction }, overdue = overdue.size,
                onAsk = onAsk, onVoice = onVoice, onOpenChat = onOpenChat,
                onNewChat = { AssistantService.send(context, AssistantService.ACTION_RESET); onOpenChat() }
            )

            if (overdue.isNotEmpty()) GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpenTasks), strong = true) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, tint = HarithColors.Red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${arNum(overdue.size)} مهام متأخرة تحتاج انتباهك", Modifier.weight(1f), color = HarithColors.Red, style = MaterialTheme.typography.bodyMedium)
                    Text("راجعها", color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
                }
            }

            // ——— ابدأ بهذه: قرار واحد واضح بدل قائمة طويلة
            androidx.compose.animation.AnimatedContent(nextUp, contentKey = { it?.id }, label = "nextUp",
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(tween(260, delayMillis = 90)) +
                        androidx.compose.animation.slideInVertically(tween(320)) { it / 5 } +
                        androidx.compose.animation.scaleIn(tween(320), initialScale = 0.96f)) togetherWith
                        (androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.scaleOut(tween(180), targetScale = 0.94f))
                }) { up ->
                up?.let { t -> NextUpCard(t, onOpen = { editTask = t },
                    onDone = { undoable(scope, snack, "أحسنت، أُنجزت «${t.title.take(30)}»", TaskActions.complete(t.id)) },
                    onLater = { undoable(scope, snack, "أُجّلت إلى الغد", TaskActions.postpone(t.id)) }) }
            }

            // ——— بطاقات النصيحة تأتي بعد «رفيق» و«ابدأ بهذه»: الفعل الأساسي أولًا، والتنبيهات الثانوية بعده
            // (الإشعارات المتوقفة وحدها تبقى في الأعلى لأنها تعطل التذكير)
            UpdateBanner(resumes)
            BackgroundTip(resumes)

            // ——— رسالة اليوم: بطاقة تحفيزية هادئة من الخادم (تظهر فقط مع ربط نشط)
            MotivationCard(resumes)

            // ——— جدول اليوم
            GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                SectionHeader("جدول اليوم", action = "التقويم", onAction = onOpenCalendar)
                if (!calendarAllowed) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenCalendar)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, null, tint = HarithColors.GoldText, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("امنح صلاحية التقويم لترى مواعيدك هنا", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
                }
                if (agenda.isEmpty()) Text(if (calendarAllowed) "لا مواعيد متبقية اليوم" else "لا تذكيرات متبقية اليوم",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                agenda.take(5).forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 76.dp), color = HarithColors.Line)
                    Row(Modifier.fillMaxWidth().clickable(onClick = if (a.kind == "task") onOpenTasks else onOpenCalendar).padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (a.allDay) "طوال اليوم" else timeFmt.format(Date(a.at)), Modifier.width(64.dp),
                            style = MaterialTheme.typography.labelMedium, color = HarithColors.Muted, maxLines = 1)
                        Box(Modifier.padding(horizontal = 6.dp).size(8.dp).clip(CircleShape).background(
                            when (a.kind) { "event" -> HarithColors.Gold; "reminder" -> HarithColors.Fg; else -> HarithColors.Muted }))
                        Spacer(Modifier.width(6.dp))
                        Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (a.kind) { "event" -> "موعد"; "reminder" -> "تذكير"; else -> "مهمة" },
                            style = MaterialTheme.typography.labelSmall, color = HarithColors.Muted)
                    }
                }
                if (agenda.size > 5) Text("و${arNum(agenda.size - 5)} أخرى في التقويم", Modifier.clickable(onClick = onOpenCalendar).padding(vertical = 6.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
            }

            }

            // ——— مهام اليوم: قائمة على الصفحة مباشرة، تُسحب للإنجاز أو التأجيل
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (remaining.isNotEmpty()) "مهام اليوم" else "قيد التنفيذ", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sortMenu = true }.padding(horizontal = 8.dp, vertical = 6.dp),
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
                    Text("كل المهام", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenTasks).padding(horizontal = 8.dp, vertical = 6.dp),
                        color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
                }
                if (todayList.isNotEmpty()) Text("اسحب المهمة لليسار لإنجازها، ولليمين لتأجيلها يومًا",
                    Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                if (todayList.isEmpty() && doneToday.isEmpty() && nextUp == null) {
                    EmptyView("لا مهام مستحقة اليوم", "اضغط + لإضافة مهمة، أو اكتب لرفيق: أضف مهمة…", Icons.Default.TaskAlt)
                    if (upcomingOpen.isNotEmpty()) {
                        Text("القادمة", style = MaterialTheme.typography.labelLarge, color = HarithColors.Muted)
                        upcomingOpen.forEach { t ->
                            SwipeTask(t, snack) { TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t }, snack = snack) }
                        }
                    }
                } else if (todayList.isEmpty() && nextUp != null && doneToday.isEmpty())
                    Text("لا شيء غير المهمة أعلاه، ركّز عليها", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                todayList.take(8).forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
                    SwipeTask(t, snack) { TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t }, snack = snack) }
                }
                if (todayList.size > 8) Text("و${arNum(todayList.size - 8)} مهام أخرى", Modifier.clickable(onClick = onOpenTasks).padding(vertical = 8.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
                doneToday.sortedByDescending { it.completedAt }.take(3).forEach { t ->
                    HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
                    TodayTaskRow(t, onEdit = { editTask = t }, onDelete = { confirmDelete = t }, snack = snack)
                }
            }

            // ——— التذكيرات القادمة (بعد اليوم)
            if (laterReminders.isNotEmpty()) Column {
                SectionHeader("تذكيرات قادمة", action = "التقويم", onAction = onOpenCalendar)
                val fmt = SimpleDateFormat("EEEE، h:mm a", Locale("ar"))
                laterReminders.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = HarithColors.Line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = HarithColors.Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(r.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                        Text(fmt.format(Date(r.at)), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ——— الأقسام: روابط بسيطة
            Column {
                SectionHeader("أقسام")
                SectionLink(Icons.Default.AccountTree, "المشاريع", "اللوحة والمراحل والفريق", onOpenProjects)
                HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
                SectionLink(Icons.Default.Folder, "الأعمال والمالية", "الفواتير والدخل والمصروف", onOpenBusiness)
                HorizontalDivider(Modifier.padding(start = 48.dp), color = HarithColors.Line)
                SectionLink(Icons.Default.VerifiedUser, "طلبات الموافقة", "الردود المقترحة والإجراءات بانتظار قرارك", onOpenApprovals)
            }
        }

        // ——— رسائل التراجع + زر الإضافة السريعة
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            SnackbarHost(snack, Modifier.padding(horizontal = 12.dp).padding(bottom = 84.dp)) { d ->
                Snackbar(d, containerColor = if (HarithColors.IsDark) HarithColors.SurfaceHigh else Color(0xFF252525),
                    contentColor = Color(0xFFF7F5F0), actionColor = HarithColors.Gold)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp).size(58.dp).clip(RoundedCornerShape(18.dp))
                .background(Luxe.goldBrush).pressable { showAdd = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "إضافة سريعة", tint = HarithColors.OnGold, modifier = Modifier.size(28.dp)) }
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

private data class Agenda(val at: Long, val title: String, val kind: String, val allDay: Boolean = false)

/** سطر رفيق: كتابة أو صوت، واقتراحات صغيرة مرتبطة بأدوات فعلية */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AskRafiq(
    hasConversation: Boolean, overdue: Int,
    onAsk: (String, String) -> Unit, onVoice: () -> Unit, onOpenChat: () -> Unit, onNewChat: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    fun send() { val t = input.trim(); if (t.isNotEmpty()) { input = ""; onAsk(t, t) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CommandBar(input, { input = it }, "اطلب من رفيق… ذكّرني أتصل بالمقاول بكرة", onSend = { send() }, onMic = onVoice,
            sendLabel = "أرسل الأمر")
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasConversation) Chip("متابعة المحادثة", strong = true, onClick = onOpenChat)
            else Chip("محادثة جديدة", strong = true, onClick = onNewChat)
            buildList {
                add("موجز اليوم")
                if (overdue > 0) add("وش المتأخر اللي لازم أنجزه؟")
                add("رتب مهام اليوم حسب الأولوية")
                add("لخص أعمال هذا الأسبوع")
            }.forEach { s -> Chip(s) { onAsk(s, s) } }
            if (hasConversation) Chip("محادثة جديدة", onClick = onNewChat)
        }
    }
}

@Composable
private fun Chip(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(50))
        .border(1.dp, if (strong) HarithColors.Gold else HarithColors.Line, RoundedCornerShape(50))
        .pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (strong) HarithColors.GoldText else HarithColors.Fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

/** بطاقة «ابدأ بهذه»: أهم مهمة الآن مع زرين واضحين */
@Composable
private fun MotivationCard(resumes: Int) {
    if (!Prefs.sharedBrainReady) return
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumes) {
        text = withContext(Dispatchers.IO) {
            runCatching { SharedBrain.get("/api/motivation").optString("text").ifBlank { null } }.getOrNull()
        }
    }
    val msg = text ?: return
    GlassCard(Modifier.fillMaxWidth().semantics { contentDescription = "رسالة اليوم" }) {
        Column(Modifier.padding(14.dp)) {
            Text("✨ رسالة اليوم", style = MaterialTheme.typography.labelLarge, color = HarithColors.Gold)
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        }
    }
}

@Composable
private fun NextUpCard(t: TaskItem, onOpen: () -> Unit, onDone: () -> Unit, onLater: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (HarithColors.IsDark) androidx.compose.ui.graphics.Brush.linearGradient(listOf(HarithColors.SurfaceHigh, HarithColors.Surface)) else androidx.compose.ui.graphics.SolidColor(HarithColors.Surface)).border(1.dp, HarithColors.Line, shape)
            .pressable(onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (t.isOverdue || t.priority == "urgent") HarithColors.Red else HarithColors.Gold))
            Spacer(Modifier.width(8.dp))
            Text(if (t.isOverdue) "ابدأ بهذه، تأخرت" else "ابدأ بهذه", style = MaterialTheme.typography.labelLarge,
                color = if (t.isOverdue) HarithColors.Red else HarithColors.GoldText)
            Spacer(Modifier.weight(1f))
            PriorityBadge(t.priority)
        }
        Text(t.title, style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        TaskMetaLine(t)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("أنجزتها", Modifier.weight(1f), icon = Icons.Default.CheckCircle, onClick = onDone)
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, HarithColors.Line, RoundedCornerShape(14.dp))
                .pressable(onClick = onLater), contentAlignment = Alignment.Center) {
                Text("أجّلها لبكرة", style = MaterialTheme.typography.labelLarge, color = HarithColors.Fg)
            }
        }
    }
}

/** صف مهمة: إنجاز سريع (مع تراجع)، الأولوية بنص وأيقونة، الموعد، المشروع، وقائمة خيارات (تعديل/تأجيل/بدء/حذف) */
@Composable
fun TodayTaskRow(t: TaskItem, onEdit: () -> Unit, onDelete: () -> Unit, snack: SnackbarHostState? = null) {
    val done = t.status == "done"
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (done) LocalStore.updateTask(t.id) { it.copy(status = "new", completedAt = 0L) }
            else {
                val before = TaskActions.complete(t.id)
                if (snack != null) undoable(scope, snack, "أُنجزت «${t.title.take(30)}»", before)
            }
        }) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (done) "إلغاء الإنجاز" else "إنجاز",
                tint = if (done) HarithColors.Green else if (t.isOverdue || t.priority == "urgent") HarithColors.Red else HarithColors.Muted,
                modifier = Modifier.popWhen(done))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.title, color = if (done) HarithColors.Muted else HarithColors.Fg, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.description.isNotBlank() && !done) Text(t.description, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!done) TaskMetaLine(t) else Text("أُنجزت", color = HarithColors.Green, style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "خيارات", tint = HarithColors.Muted) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("تعديل") }, { menu = false; onEdit() })
                if (!done) DropdownMenuItem({ Text("تأجيل يوم") }, {
                    menu = false
                    val before = TaskActions.postpone(t.id)
                    if (snack != null) undoable(scope, snack, "أُجّلت إلى الغد", before)
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
private fun SectionLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        }
        Icon(Icons.Default.ChevronLeft, null, tint = HarithColors.Muted)
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
                if (LocalStore.notes.value.any { it.id == id }) onDone("حُفظت الملاحظة") else err = "تعذّر الحفظ، حاول مرة أخرى"
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
                else err = "تعذّر ضبط التذكير: امنح رفيق صلاحية المنبهات من الإعدادات ثم حاول مرة أخرى"
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


/** تنبيه واضح إذا كانت إشعارات رفيق متوقفة من الجوال، مع زر يفعّلها مباشرة */
@Composable
private fun NotificationsOffBanner(resumes: Int) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LaunchedEffect(resumes) { enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val openSettings = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok ->
        enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!ok && !enabled) openSettings()
    }
    if (enabled) return
    GlassCard(Modifier.fillMaxWidth().semantics { contentDescription = "الإشعارات متوقفة" }, RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الإشعارات متوقفة لرفيق", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            Text("بدونها ما تظهر التذكيرات ولا تنبيهات المهام والفريق.", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
            androidx.compose.material3.TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else openSettings()
            }) { Text("فعّل الإشعارات", color = Luxe.Gold) }
        }
    }
}


/** بطاقة «تحديث جديد متاح»: تقارن نسخة التطبيق بآخر نسخة على الخادم وتفتح رابط التحميل المناسب (خفيفة/كاملة) */
@Composable
private fun UpdateBanner(resumes: Int) {
    val context = LocalContext.current
    var latest by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    LaunchedEffect(resumes) {
        latest = withContext(Dispatchers.IO) {
            runCatching {
                val base = Prefs.serverUrl.ifBlank { com.alharith.ai.BuildConfig.PROVISION_URL }.trimEnd('/')
                if (!base.startsWith("http")) return@runCatching null
                val c = java.net.URL("$base/api/public/app-version").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.inputStream.bufferedReader().use { r ->
                    val o = org.json.JSONObject(r.readText())
                    val link = if (com.alharith.ai.BuildConfig.FLAVOR == "lite") o.optString("lite") else o.optString("full")
                    Triple(o.optInt("version_code"), o.optString("version_name"), link.ifBlank { o.optString("page") })
                }
            }.getOrNull()
        }
    }
    val l = latest ?: return
    if (l.first <= com.alharith.ai.BuildConfig.VERSION_CODE || l.third.isBlank()) return
    GlassCard(Modifier.fillMaxWidth().semantics { contentDescription = "تحديث جديد متاح" }, RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("تحديث جديد متاح" + if (l.second.isNotBlank()) " (${arNum(l.second)})" else "", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            Text("نزّله وثبّته فوق التطبيق الحالي — بياناتك تبقى كما هي.", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
            androidx.compose.material3.TextButton(onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l.third))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("حمّل التحديث", color = Luxe.Gold) }
        }
    }
}


/** جوالات تقتل التطبيقات في الخلفية (شاومي، هواوي، أوبو، فيفو، ريلمي، ون بلس، سامسونج): بطاقة تشرح وتفتح الإعداد */
@Composable
private fun BackgroundTip(resumes: Int) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        show = !Prefs.batteryTipDismissed && pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName) &&
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    if (!show) return
    val brand = android.os.Build.MANUFACTURER.lowercase()
    val extra = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "وفي شاومي: الإعدادات ← التطبيقات ← رفيق ← «التشغيل التلقائي» شغّله، و«توفير البطارية» اختر «بلا قيود»."
        brand.contains("huawei") || brand.contains("honor") ->
            "وفي هواوي: الإعدادات ← البطارية ← تشغيل التطبيقات ← رفيق ← «إدارة يدوية» وفعّل الثلاث خيارات."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") || brand.contains("vivo") ->
            "وفي جوالك: الإعدادات ← البطارية ← رفيق ← «السماح بالنشاط في الخلفية»."
        brand.contains("samsung") ->
            "وفي سامسونج: الإعدادات ← البطارية ← حدود استخدام الخلفية ← تأكد إن رفيق مو في «التطبيقات النائمة»."
        else -> ""
    }
    // بطاقة مختصرة: أيقونة + سطر واحد + زر واضح. خطوات الشركة المصنّعة مطوية حتى لا تزحم الرئيسية
    var steps by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().semantics { contentDescription = "تشغيل رفيق في الخلفية" }, RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(HarithColors.GoldText.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Alarm, null, tint = HarithColors.GoldText, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("خلّ المنبّه يرن في وقته", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                    Text("اسمح لرفيق يشتغل في الخلفية عشان ما يتأخر التذكير ولا الأذان.",
                        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                }
            }
            if (steps && extra.isNotBlank()) Text(extra.removePrefix("و"), style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg.copy(alpha = 0.8f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(Luxe.goldBrush).clickable {
                    runCatching {
                        @Suppress("BatteryLife")
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(horizontal = 18.dp, vertical = 9.dp)) { Text("اسمح الآن", color = Luxe.OnGold, style = MaterialTheme.typography.labelLarge) }
                if (extra.isNotBlank()) androidx.compose.material3.TextButton(onClick = { steps = !steps }) {
                    Text(if (steps) "إخفاء الخطوات" else "خطوات جوالك", color = HarithColors.GoldText)
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { Prefs.batteryTipDismissed = true; show = false }) {
                    Text("لاحقًا", color = HarithColors.Muted)
                }
            }
        }
    }
}
