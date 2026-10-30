package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.AssistantState
import com.alharith.ai.data.ChatMessage
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedInbox
import com.alharith.ai.service.AssistantService

@Composable
fun ChatScreen(onOpenSettings: () -> Unit, onOpenLog: () -> Unit = {}, onBack: (() -> Unit)? = {}) {
    val context = LocalContext.current
    val messages by ConversationStore.messages.collectAsState()
    val state by ConversationStore.state.collectAsState()
    val partial by ConversationStore.partial.collectAsState()
    val confirmation by ConversationStore.confirmation.collectAsState()
    val error by ConversationStore.error.collectAsState()
    val shared by SharedInbox.item.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }
    var toTask by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val lastUser = messages.lastOrNull { it.fromUser }?.text

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) AssistantService.send(context, AssistantService.ACTION_LISTEN)
        else ConversationStore.setError("بدون صلاحية الميكروفون لا أستطيع سماعك. يمكنك الكتابة بدلًا من ذلك.")
    }

    fun talk() {
        val busy = state == AssistantState.LISTENING || state == AssistantState.THINKING || state == AssistantState.SPEAKING
        when {
            ConversationStore.live.value -> AssistantService.send(context, AssistantService.ACTION_LIVE_STOP)
            busy -> AssistantService.send(context, AssistantService.ACTION_STOP)
            context is MainActivity -> context.startListening()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                AssistantService.send(context, AssistantService.ACTION_LISTEN)
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun sendText() {
        val t = input.trim()
        if (t.isEmpty()) return
        input = ""
        AssistantService.send(context, AssistantService.ACTION_TEXT, t)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val level by ConversationStore.level.collectAsState()
    val live by ConversationStore.live.collectAsState()
    val listening = state == AssistantState.LISTENING || live

    run {
    Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ——— الشريط العلوي
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "الرئيسية", tint = HarithColors.Fg)
                } else Spacer(Modifier.width(12.dp))
                MiniOrb(state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("رفيق AI", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                    Text(statusText(state), style = MaterialTheme.typography.bodySmall, color = statusColor(state))
                }
                if (messages.isNotEmpty()) IconButton(onClick = {
                    AssistantService.send(context, AssistantService.ACTION_RESET)
                }) { Icon(Icons.Default.AddComment, "محادثة جديدة", tint = HarithColors.Fg) }
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) {
                        Icon(Icons.Default.MoreVert, "المزيد", tint = HarithColors.Fg)
                    }
                    androidx.compose.material3.DropdownMenu(more, { more = false }, containerColor = HarithColors.Surface) {
                        androidx.compose.material3.DropdownMenuItem({ Text("محادثة جديدة") },
                            { more = false; AssistantService.send(context, AssistantService.ACTION_RESET) },
                            leadingIcon = { Icon(Icons.Default.AddComment, null, tint = HarithColors.GoldText) })
                        androidx.compose.material3.DropdownMenuItem({ Text("المحادثات السابقة") }, { more = false; showHistory = true },
                            leadingIcon = { Icon(Icons.Default.Forum, null, tint = HarithColors.GoldText) })
                        androidx.compose.material3.DropdownMenuItem({ Text("سجل النشاط") }, { more = false; onOpenLog() },
                            leadingIcon = { Icon(Icons.Default.History, null, tint = HarithColors.Muted) })
                        androidx.compose.material3.DropdownMenuItem({ Text("الإعدادات") }, { more = false; onOpenSettings() },
                            leadingIcon = { Icon(Icons.Default.Settings, null, tint = HarithColors.Muted) })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HarithColors.Line))

            // ——— تنبيه بالأخطاء أو الإعداد الناقص
            val setupHint = when {
                // أغلب المستخدمين بلا مفتاح خاص: رفيق يربطهم بخادمه تلقائيًا، فلا نطلب منهم «مفتاحًا» تقنيًا
                Prefs.aiKeyMissing -> "حسابك لسه ما ارتبط بخادم رفيق. اضغط «اربط الآن»، وتأكد إن الإنترنت شغال."
                else -> null
            }
            val linkScope = rememberCoroutineScope()
            var linking by remember { mutableStateOf(false) }
            if (error != null) {
                Banner(error!!, onClick = { ConversationStore.setError(null) },
                    onRetry = if (lastUser != null && state == AssistantState.IDLE) ({
                        ConversationStore.setError(null); AssistantService.send(context, AssistantService.ACTION_TEXT, lastUser)
                    }) else null)
            } else if (setupHint != null) {
                // ليس خطأً: بطاقة هادئة بزر «اربط الآن» يحاول الربط بخادم رفيق فورًا
                Banner(if (linking) "أربط حسابك بخادم رفيق…" else setupHint, onClick = onOpenSettings, calm = true,
                    retryLabel = "اربط الآن",
                    onRetry = if (linking) null else ({
                        linking = true
                        linkScope.launch {
                            val info = com.alharith.ai.data.Provisioning.info()
                            val err = if (info.available && !info.inviteRequired) com.alharith.ai.data.Provisioning.register("")
                                else if (info.inviteRequired) "الخادم يحتاج رابط دعوة" else "خادم رفيق غير متاح الآن، حاول بعد قليل"
                            linking = false
                            android.widget.Toast.makeText(context, err ?: "تم ربط حسابك ✓", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }))
            }

            // ——— المحادثة، أو شاشة الاستماع الحية
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !listening) {
                    EmptyState(Modifier.align(Alignment.Center)) { input = it; sendText() }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(messages, key = { it.id }) { m ->
                            Bubble(m, onCopy = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                                android.widget.Toast.makeText(context, "نُسخ الرد", android.widget.Toast.LENGTH_SHORT).show()
                            }, onToTask = { toTask = m.text })
                        }
                        if (state == AssistantState.THINKING) item { ThinkingDots() }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    listening, enter = fadeIn(tween(250)), exit = fadeOut(tween(250)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize().background(HarithColors.Bg.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            LuxeOrb(state, 220.dp, level, onClick = ::talk)
                            Spacer(Modifier.height(18.dp))
                            val lastBot = messages.lastOrNull { !it.fromUser && !it.isAction }?.text
                            when {
                                partial.isNotBlank() -> Text(partial, style = MaterialTheme.typography.headlineSmall, color = HarithColors.Fg, textAlign = TextAlign.Center)
                                state == AssistantState.SPEAKING && live && lastBot != null ->
                                    Text(lastBot, style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg.copy(alpha = 0.85f), textAlign = TextAlign.Center, maxLines = 4)
                                state == AssistantState.THINKING -> GoldText(if (live && messages.isEmpty()) "أتصل…" else "لحظة…", MaterialTheme.typography.headlineSmall)
                                else -> GoldText(if (live) "تكلّم، أنا معك" else "أسمعك…", MaterialTheme.typography.headlineSmall)
                            }
                            if (live) {
                                Spacer(Modifier.height(10.dp))
                                Text("محادثة مباشرة — قاطعني متى شئت", style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                                Spacer(Modifier.height(22.dp))
                                OutlinedButton(onClick = { AssistantService.send(context, AssistantService.ACTION_LIVE_STOP) },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Luxe.Gold.copy(alpha = 0.6f))) {
                                    Text("إنهاء المحادثة", color = Luxe.GoldLight)
                                }
                            }
                        }
                    }
                }
            }

            // ——— التأكيد قبل الإجراءات الحساسة
            confirmation?.let { c ->
                ConfirmCard(c.question, c.detail,
                    onYes = { ConversationStore.answerConfirmation(true) },
                    onNo = { ConversationStore.answerConfirmation(false) })
            }

            // ——— ملف مشارك
            shared?.let { item ->
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, tint = Luxe.Gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, Modifier.weight(1f, false), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg)
                        IconButton(onClick = { SharedInbox.set(null) }) {
                            Icon(Icons.Default.Close, "إزالة", tint = HarithColors.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ——— لوح الأوامر الفحمي: كتابة، أو ميكروفون حين يكون الحقل فارغًا
            CommandBar(
                input, { input = it }, "اكتب أمرًا لرفيق…", onSend = ::sendText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                onMic = if (listening) null else ({ talk() }), sendLabel = "أرسل الأمر"
            )
        }
    }}
    ChatDialogs(showHistory, { showHistory = false }, toTask) { msg ->
        toTask = null
        if (msg != null) android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ChatDialogs(showHistory: Boolean, onHistoryClose: () -> Unit, toTask: String?, onToTaskDone: (String?) -> Unit) {
    if (showHistory) HistorySheet(onHistoryClose)
    toTask?.let { ToTaskDialog(it, onToTaskDone) }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "p")
    Row(Modifier.padding(start = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a = (1f - kotlin.math.abs(p - i - 0.5f).coerceAtMost(1f)).coerceIn(0.25f, 1f)
            Box(Modifier.size(9.dp).clip(CircleShape).background(Luxe.Gold.copy(alpha = a)))
        }
    }
}

private fun statusText(s: AssistantState) = when (s) {
    AssistantState.IDLE -> "جاهز"
    AssistantState.WAITING_WAKE -> "قل \"يا رفيق\" في أي وقت"
    AssistantState.LISTENING -> "أستمع إليك…"
    AssistantState.THINKING -> "أفكّر وأنفّذ…"
    AssistantState.SPEAKING -> "أتحدث…"
    AssistantState.CONFIRMING -> "بانتظار تأكيدك"
}

@Composable
private fun statusColor(s: AssistantState) = when (s) {
    AssistantState.LISTENING, AssistantState.CONFIRMING, AssistantState.THINKING, AssistantState.SPEAKING -> HarithColors.GoldText
    else -> HarithColors.Muted
}

@Composable
private fun Bubble(m: ChatMessage, onCopy: () -> Unit = {}, onToTask: () -> Unit = {}) {
    if (m.isAction) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Luxe.Gold.copy(alpha = 0.7f)))
            Spacer(Modifier.width(8.dp))
            Text(m.text, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (!m.fromUser) {
        Column(Modifier.fillMaxWidth().reveal(0), horizontalAlignment = Alignment.End) {
            GlassCard(Modifier.fillMaxWidth(), strong = true) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(HarithColors.GoldSoft).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("رفيق", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = HarithColors.GoldText)
                    }
                    // Preserve the actual response verbatim. No guessed success label or fabricated fields.
                    androidx.compose.foundation.text.selection.SelectionContainer(Modifier.padding(16.dp)) {
                        Text(m.text, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BubbleAction(Icons.Default.ContentCopy, "نسخ", onCopy)
                BubbleAction(Icons.Default.AddTask, "إلى مهمة", onToTask)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().reveal(0), horizontalArrangement = Arrangement.Start) {
        val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
        Text(
            m.text,
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(HarithColors.GoldSoft)
                .border(1.dp, HarithColors.Gold.copy(alpha = 0.35f), shape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = HarithColors.Fg,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit, onRetry: (() -> Unit)? = null,
                   calm: Boolean = false, retryLabel: String = "إعادة المحاولة") {
    val tone = if (calm) HarithColors.GoldText else HarithColors.Red
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tone.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (calm) Icons.Default.Info else Icons.Default.WarningAmber, null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        if (onRetry != null) Text(retryLabel, Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRetry).padding(8.dp),
            color = tone, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BubbleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HarithColors.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

/** تحويل رد رفيق إلى مهمة فعلية: تراجع العنوان والموعد قبل الحفظ، ولا نعلن النجاح إلا بعد التحقق. */
@Composable
private fun ToTaskDialog(text: String, onDone: (String?) -> Unit) {
    val firstLine = text.lines().map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.firstOrNull { it.isNotBlank() }.orEmpty()
    var title by remember { mutableStateOf(firstLine.take(80)) }
    var day by remember { mutableStateOf("none") }
    var err by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(onDismissRequest = { onDone(null) }, containerColor = HarithColors.Surface,
        title = { Text("إنشاء مهمة من الرد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(title, { title = it; err = "" }, label = { Text("عنوان المهمة") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "بدون موعد", "today" to "اليوم", "tomorrow" to "غدًا").forEach { (k, l) -> Pill(l, day == k) { day = k } }
                }
                Text("يُحفظ نص الرد كاملًا في وصف المهمة.", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (err.isNotBlank()) Text(err, color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (title.isBlank()) { err = "اكتب عنوانًا"; return@TextButton }
                val id = com.alharith.ai.data.LocalStore.newId()
                val due = when (day) { "today" -> java.time.LocalDate.now().toString(); "tomorrow" -> java.time.LocalDate.now().plusDays(1).toString(); else -> "" }
                com.alharith.ai.data.LocalStore.addTask(com.alharith.ai.data.TaskItem(id, title.trim(), description = text.take(2000), due = due))
                if (com.alharith.ai.data.LocalStore.task(id) != null) onDone("أُضيفت المهمة «${title.trim()}»") else err = "تعذّر الحفظ، حاول مرة أخرى"
            }) { Text("إنشاء المهمة", color = HarithColors.GoldText) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { onDone(null) }) { Text("إلغاء", color = HarithColors.Fg) } })
}

/** المحادثات السابقة (محفوظة على جوالك فقط) */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(onDismiss: () -> Unit) {
    val convs by com.alharith.ai.data.ChatArchive.items.collectAsState()
    var open by remember { mutableStateOf<com.alharith.ai.data.ChatArchive.Conversation?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = java.text.SimpleDateFormat("EEEE d MMMM، h:mm a", java.util.Locale("ar"))
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = HarithColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val c = open
            if (c == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("المحادثات السابقة", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                    if (convs.isNotEmpty()) Text("حذف الكل", Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmClear = true }.padding(8.dp),
                        color = HarithColors.Red, style = MaterialTheme.typography.labelLarge)
                }
                Text("تُحفظ على جوالك فقط عند بدء محادثة جديدة (آخر ٣٠ محادثة).", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (convs.isEmpty()) EmptyView("لا محادثات سابقة بعد", "عند الضغط على «محادثة جديدة» تُحفظ الحالية هنا.")
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(convs, key = { it.id }) { x ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = x }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.title, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${fmt.format(java.util.Date(x.at))}، ${arNum(x.messages.size)} رسالة", color = HarithColors.Muted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { com.alharith.ai.data.ChatArchive.delete(x.id) }) {
                                Icon(Icons.Default.DeleteOutline, "حذف", tint = HarithColors.Muted)
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg, maxLines = 1)
                    Text("رجوع", Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = null }.padding(8.dp), color = HarithColors.Muted,
                        style = MaterialTheme.typography.labelLarge)
                }
                Text("للاطلاع فقط — ابدأ محادثة جديدة لمتابعة الموضوع.", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(c.messages) { m -> Bubble(m) }
                }
            }
        }
    }
    if (confirmClear) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = HarithColors.Surface,
        title = { Text("حذف كل المحادثات السابقة؟") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { com.alharith.ai.data.ChatArchive.clear(); confirmClear = false }) {
            Text("حذف", color = HarithColors.Red) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("إلغاء", color = HarithColors.Fg) } })
}

@Composable
private fun ConfirmCard(question: String, detail: String, onYes: () -> Unit, onNo: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(HarithColors.SurfaceHigh)
            .border(1.dp, HarithColors.GoldDim, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onYes,
                colors = ButtonDefaults.buttonColors(containerColor = HarithColors.Gold),
                modifier = Modifier.weight(1f)
            ) { Text("نعم، نفّذ", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onNo, modifier = Modifier.weight(1f)) { Text("إلغاء", color = HarithColors.Fg) }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val samples = remember {
        listOf(
            "أعطني موجز اليوم",
            "لخص لي الرسائل الجديدة",
            "هل عندي إيميل مهم؟",
            "ذكرني الساعة 8 أتصل بأحمد",
            "اقرأ آخر رسالة واتساب",
            "ابحث عن ملف العقد"
        )
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LuxeOrb(AssistantState.IDLE, 120.dp, onClick = { (context as? MainActivity)?.startListening() })
        Spacer(Modifier.height(10.dp))
        GoldText(if (Prefs.userName.isBlank()) "أهلًا" else "أهلًا ${Prefs.userName}", MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "اضغط الميكروفون أو قل \"يا رفيق\"، أو جرّب:",
            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        samples.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                row.forEach { s ->
                    GlassCard(shape = RoundedCornerShape(20.dp)) {
                        Text(
                            s,
                            Modifier.clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg
                        )
                    }
                }
            }
        }
    }
}
