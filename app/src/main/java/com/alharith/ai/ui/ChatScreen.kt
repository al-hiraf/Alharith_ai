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
fun ChatScreen(onOpenSettings: () -> Unit, onOpenLog: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val messages by ConversationStore.messages.collectAsState()
    val state by ConversationStore.state.collectAsState()
    val partial by ConversationStore.partial.collectAsState()
    val confirmation by ConversationStore.confirmation.collectAsState()
    val error by ConversationStore.error.collectAsState()
    val shared by SharedInbox.item.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

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

    // المحادثة داكنة دائمًا بهوية رفيق الذهبية
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
        OrnamentBackdrop(shiftFraction = 0.30f, scrim = listOf(0f to 0.97f, 0.55f to 0.92f, 0.85f to 0.70f, 1f to 0.80f))
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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "الرئيسية", tint = HarithColors.Fg)
                }
                MiniOrb(state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GoldText("رفيق", MaterialTheme.typography.headlineSmall.copy(fontFamily = Ruqaa), shimmer = false)
                    Text(statusText(state), style = MaterialTheme.typography.bodySmall, color = statusColor(state))
                }
                if (messages.isNotEmpty()) IconButton(onClick = {
                    AssistantService.send(context, AssistantService.ACTION_RESET)
                }) { Icon(Icons.Default.DeleteSweep, "محادثة جديدة", tint = HarithColors.Muted) }
                IconButton(onClick = onOpenLog) {
                    Icon(Icons.Default.History, "سجل النشاط", tint = HarithColors.Muted)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, "الإعدادات", tint = HarithColors.Muted)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Luxe.Gold.copy(alpha = 0.45f), Color.Transparent))))

            // ——— تنبيه بالأخطاء أو الإعداد الناقص
            val setupHint = when {
                Prefs.aiKeyMissing -> "أضف مفتاح ${Prefs.providerLabel} من الإعدادات ليبدأ رفيق العمل."
                else -> null
            }
            (error ?: setupHint)?.let { msg ->
                Banner(msg, onClick = if (error == null) onOpenSettings else ({ ConversationStore.setError(null) }))
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
                        items(messages, key = { it.id }) { Bubble(it) }
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

            // ——— الكتابة والدائرة
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!listening) LuxeOrb(state, 76.dp, level, onClick = ::talk)
                Spacer(Modifier.width(6.dp))
                GlassCard(Modifier.weight(1f), RoundedCornerShape(28.dp), strong = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 16.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = HarithColors.Fg),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Luxe.Gold),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendText() }),
                            decorationBox = { inner ->
                                if (input.isEmpty()) Text("اكتب أمرًا لرفيق…", color = HarithColors.Muted, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        )
                        IconButton(
                            onClick = ::sendText, enabled = input.isNotBlank(),
                            modifier = Modifier.padding(end = 6.dp).size(44.dp).clip(CircleShape)
                                .background(if (input.isNotBlank()) Luxe.goldBrush else Brush.linearGradient(listOf(HarithColors.SurfaceHigh, HarithColors.SurfaceHigh)))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = if (input.isNotBlank()) Color(0xFF2A1F0C) else HarithColors.Muted)
                        }
                    }
                }
            }
        }
    }}
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
private fun Bubble(m: ChatMessage) {
    if (m.isAction) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Luxe.Gold.copy(alpha = 0.7f)))
            Spacer(Modifier.width(8.dp))
            Text(m.text, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (!m.fromUser) {
        Row(Modifier.fillMaxWidth().reveal(0), horizontalArrangement = Arrangement.End) {
            GlassCard(Modifier.widthIn(max = 330.dp), RoundedCornerShape(topStart = 22.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp)) {
                Text(m.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
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
                .background(Brush.linearGradient(listOf(Luxe.Gold.copy(alpha = 0.30f), Luxe.GoldDeep.copy(alpha = 0.16f))))
                .border(1.dp, Luxe.Gold.copy(alpha = 0.45f), shape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = HarithColors.Fg,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HarithColors.Red.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.WarningAmber, null, tint = HarithColors.Red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
    }
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
        GoldText("أهلًا ${Prefs.userName}", MaterialTheme.typography.displaySmall)
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
