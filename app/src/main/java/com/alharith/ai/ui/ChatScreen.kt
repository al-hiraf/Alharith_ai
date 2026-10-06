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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
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
fun ChatScreen(onOpenSettings: () -> Unit) {
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
            busy -> AssistantService.send(context, AssistantService.ACTION_STOP)
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

    Scaffold(containerColor = HarithColors.Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ——— الشريط العلوي
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("الحارث", style = MaterialTheme.typography.headlineSmall, color = HarithColors.Fg)
                    Text(statusText(state), style = MaterialTheme.typography.bodySmall, color = statusColor(state))
                }
                if (messages.isNotEmpty()) IconButton(onClick = {
                    AssistantService.send(context, AssistantService.ACTION_RESET)
                }) { Icon(Icons.Default.DeleteSweep, "محادثة جديدة", tint = HarithColors.Muted) }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, "الإعدادات", tint = HarithColors.Muted)
                }
            }

            // ——— تنبيه بالأخطاء أو الإعداد الناقص
            val setupHint = when {
                Prefs.claudeApiKey.isBlank() -> "أضف مفتاح Claude من الإعدادات ليبدأ الحارث العمل."
                else -> null
            }
            (error ?: setupHint)?.let { msg ->
                Banner(msg, onClick = if (error == null) onOpenSettings else ({ ConversationStore.setError(null) }))
            }

            // ——— المحادثة
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(Modifier.align(Alignment.Center)) { input = it; sendText() }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(messages, key = { it.id }) { Bubble(it) }
                    }
                }
            }

            // ——— التأكيد قبل الإجراءات الحساسة
            confirmation?.let { c ->
                ConfirmCard(c.question, c.detail,
                    onYes = { ConversationStore.answerConfirmation(true) },
                    onNo = { ConversationStore.answerConfirmation(false) })
            }

            // ——— النص الجزئي أثناء الاستماع
            AnimatedVisibility(partial.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    partial, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    color = HarithColors.Gold, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
                )
            }

            // ——— ملف مشارك
            shared?.let { item ->
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HarithColors.SurfaceHigh)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, null, tint = HarithColors.Gold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(item.label, Modifier.weight(1f, false), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { SharedInbox.set(null) }) {
                        Icon(Icons.Default.Close, "إزالة", tint = HarithColors.Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ——— زر التحدث
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Orb(state, onClick = ::talk)
            }

            // ——— الكتابة
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب أمرًا للحارث…", color = HarithColors.Muted) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HarithColors.GoldDim,
                        unfocusedBorderColor = HarithColors.Line,
                        focusedContainerColor = HarithColors.Surface,
                        unfocusedContainerColor = HarithColors.Surface
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = ::sendText,
                    enabled = input.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (input.isNotBlank()) HarithColors.Gold else HarithColors.SurfaceHigh)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "إرسال",
                        tint = if (input.isNotBlank()) Color(0xFF1A1405) else HarithColors.Muted
                    )
                }
            }
        }
    }
}

private fun statusText(s: AssistantState) = when (s) {
    AssistantState.IDLE -> "جاهز"
    AssistantState.WAITING_WAKE -> "قل \"يا الحارث\" في أي وقت"
    AssistantState.LISTENING -> "أستمع إليك…"
    AssistantState.THINKING -> "أفكّر وأنفّذ…"
    AssistantState.SPEAKING -> "أتحدث…"
    AssistantState.CONFIRMING -> "بانتظار تأكيدك"
}

private fun statusColor(s: AssistantState) = when (s) {
    AssistantState.WAITING_WAKE -> HarithColors.Green
    AssistantState.LISTENING, AssistantState.CONFIRMING -> HarithColors.Gold
    AssistantState.THINKING, AssistantState.SPEAKING -> HarithColors.Blue
    else -> HarithColors.Muted
}

@Composable
private fun Orb(state: AssistantState, onClick: () -> Unit) {
    val active = state == AssistantState.LISTENING || state == AssistantState.THINKING ||
        state == AssistantState.SPEAKING || state == AssistantState.CONFIRMING
    val color = when (state) {
        AssistantState.THINKING, AssistantState.SPEAKING -> HarithColors.Blue
        else -> HarithColors.Gold
    }
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (state == AssistantState.LISTENING) 900 else 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    Box(
        Modifier
            .size(112.dp)
            .drawBehind {
                if (active) {
                    val r = size.minDimension / 2
                    drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = r * (0.62f + 0.38f * pulse))
                    val p2 = (pulse + 0.5f) % 1f
                    drawCircle(color.copy(alpha = 0.25f * (1f - p2)), radius = r * (0.62f + 0.38f * p2))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (active) Brush.radialGradient(listOf(color, color.copy(alpha = 0.75f)))
                    else Brush.radialGradient(listOf(HarithColors.SurfaceHigh, HarithColors.Surface))
                )
                .border(1.dp, if (active) Color.Transparent else HarithColors.GoldDim, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (active) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (active) "إيقاف" else "تحدّث",
                tint = if (active) Color(0xFF111111) else HarithColors.Gold,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    if (m.isAction) {
        Text(
            m.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.Start else Arrangement.End) {
        Text(
            m.text,
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (m.fromUser) 4.dp else 18.dp,
                        bottomEnd = if (m.fromUser) 18.dp else 4.dp
                    )
                )
                .background(if (m.fromUser) HarithColors.GoldDim.copy(alpha = 0.45f) else HarithColors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
    val samples = remember {
        listOf(
            "ما عندي اليوم؟",
            "لخص لي الرسائل الجديدة",
            "هل عندي إيميل مهم؟",
            "ذكرني الساعة 8 أتصل بأحمد",
            "اقرأ آخر رسالة واتساب",
            "ابحث عن ملف العقد"
        )
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("أهلًا ${Prefs.userName}", style = MaterialTheme.typography.displaySmall, color = HarithColors.Fg)
        Spacer(Modifier.height(6.dp))
        Text(
            "اضغط الميكروفون أو قل \"يا الحارث\"، أو جرّب:",
            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        samples.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                row.forEach { s ->
                    Text(
                        s,
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, HarithColors.Line, RoundedCornerShape(20.dp))
                            .clickable { onPick(s) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg
                    )
                }
            }
        }
    }
}
