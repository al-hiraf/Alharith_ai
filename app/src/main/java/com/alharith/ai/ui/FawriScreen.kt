package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.Fawri
import com.alharith.ai.data.Prefs
import com.alharith.ai.voice.Listener
import com.alharith.ai.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «فوري»: نافذة أوامر سريعة لسلمى — نفس نافذة الويب داخل التطبيق.
 * المايك إملاء (الكلام ينكتب في الخانة ويراجعه المستخدم قبل الإرسال)،
 * وزر السماعة في الأعلى يقرأ الردود الجديدة بصوت. إضافة فقط.
 */
@Composable
fun FawriScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<Fawri.Load?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var speakOn by remember { mutableStateOf(Prefs.fawriSpeak) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val speaker = remember { Speaker(context) }
    val listener = remember { Listener(context) }

    DisposableEffect(Unit) { onDispose { listenJob?.cancel(); speaker.shutdown() } }

    suspend fun refresh() {
        val r = Fawri.list()
        load = r
        if (r is Fawri.Load.Ok) error = null
    }

    // جلب دوري كل ١٥ ثانية (مثل نسخة الويب) + قراءة الردود الجديدة بصوت عند تفعيل السماعة
    LaunchedEffect(Unit) {
        var first = true
        while (isActive) {
            refresh()
            val msgs = (load as? Fawri.Load.Ok)?.messages
            if (msgs != null) {
                val maxId = msgs.maxOfOrNull { it.id } ?: 0L
                if (first) {
                    // أول فتح: لا نقرأ ردودًا قديمة — نبدأ مما يصل من الآن
                    if (Prefs.fawriSpokenUpTo <= 0L || Prefs.fawriSpokenUpTo < msgs.minOfOrNull { it.id } ?: 0L)
                        Prefs.fawriSpokenUpTo = maxId
                    first = false
                } else if (speakOn) {
                    val fresh = msgs.filter { it.id > Prefs.fawriSpokenUpTo && it.state == "done" && it.reply.isNotBlank() }
                    if (fresh.isNotEmpty()) {
                        Prefs.fawriSpokenUpTo = fresh.maxOf { it.id }
                        for (m in fresh) speaker.speak(m.reply)
                    }
                }
                if (msgs.isNotEmpty()) runCatching { listState.animateScrollToItem(msgs.size - 1) }
            }
            delay(15_000)
        }
    }

    fun stopListening() { listenJob?.cancel(); listenJob = null; listening = false }

    fun startListening() {
        if (listening || sending) return
        listening = true
        val base = input
        listenJob = scope.launch {
            val collect = launch {
                ConversationStore.partial.collect { p ->
                    if (p.isNotBlank()) input = listOf(base, p.trim()).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            when (val r = listener.listen()) {
                is Listener.Result.Text -> input = listOf(base, r.text).filter { it.isNotBlank() }.joinToString(" ")
                Listener.Result.Silence -> input = base
                is Listener.Result.Error -> { input = base; error = r.message }
            }
            collect.cancel()
            listening = false
            listenJob = null
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else error = "بدون صلاحية الميكروفون لا يعمل الإملاء. يمكنك الكتابة."
    }

    fun mic() {
        if (listening) { stopListening(); return }
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || sending) return
        stopListening()
        sending = true
        scope.launch {
            val err = Fawri.send(t)
            sending = false
            if (err == null) { input = ""; refresh() } else error = err
        }
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).navigationBarsPadding().imePadding()) {
        RafiqTopBar(
            title = "فوري",
            subtitle = when (val l = load) {
                null -> "جارٍ الاتصال…"
                is Fawri.Load.Ok -> "متصلة"
                else -> null
            },
            onBack = onBack
        ) {
            // زر السماعة: قراءة الردود الجديدة بصوت
            IconButton(onClick = {
                speakOn = !speakOn
                Prefs.fawriSpeak = speakOn
                if (speakOn) {
                    // عند التفعيل لا نقرأ الموجود حاليًا — فقط ما يصل بعده
                    Prefs.fawriSpokenUpTo = (load as? Fawri.Load.Ok)?.messages?.maxOfOrNull { it.id } ?: Prefs.fawriSpokenUpTo
                } else speaker.stop()
            }) {
                Icon(
                    if (speakOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    if (speakOn) "إطفاء صوت الردود" else "تشغيل صوت الردود",
                    tint = if (speakOn) HarithColors.GoldText else HarithColors.Muted
                )
            }
        }

        when (val l = load) {
            null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HarithColors.Gold)
            }
            is Fawri.Load.NoServer -> FawriNotice("«فوري» يحتاج ارتباطًا بخادم رفيق", "افتح «حسابي» وأكمل الربط أولًا، ثم عد هنا.")
            is Fawri.Load.Unavailable -> FawriNotice("«فوري» غير مفعّل لحسابك بعد", "هذه النافذة لأعضاء مشروع «فوري» فقط.")
            is Fawri.Load.Error -> FawriNotice("تعذّر الاتصال", l.message)
            is Fawri.Load.Ok -> {
                if (l.messages.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("اكتب أول أمر لفوري", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                        Spacer(Modifier.height(6.dp))
                        Text("أمرك يصل لسلمى، والرد يظهر هنا خلال دقائق.", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(l.messages, key = { it.id }) { m ->
                            FawriUserBubble(m)
                            FawriReplyBubble(m)
                        }
                    }
                }
            }
        }

        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
        }

        // ——— صندوق الكتابة: مايك إملاء + نص + إرسال
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp)).background(HarithColors.Surface)
                .border(1.dp, HarithColors.Line, RoundedCornerShape(26.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::mic) {
                Icon(Icons.Default.Mic, if (listening) "إيقاف الاستماع" else "إملاء صوتي",
                    tint = if (listening) HarithColors.Red else HarithColors.GoldText)
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (listening) "أسمعك… تكلم" else "اكتب أمرك…", color = HarithColors.Muted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = HarithColors.Fg, unfocusedTextColor = HarithColors.Fg
                ),
                maxLines = 4
            )
            Box(Modifier.size(40.dp).clip(CircleShape).background(Luxe.goldBrush), contentAlignment = Alignment.Center) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = HarithColors.OnGold, strokeWidth = 2.dp)
                else IconButton(onClick = ::send) { Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = HarithColors.OnGold, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun FawriNotice(title: String, sub: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center)
    }
}

/** فقاعة أمر المستخدم (يسار الشاشة كالمحادثات العربية) */
@Composable
private fun FawriUserBubble(m: Fawri.Msg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(Luxe.goldBrush).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(m.text, color = HarithColors.OnGold, style = MaterialTheme.typography.bodyMedium)
            Text(m.created, color = HarithColors.OnGold.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** فقاعة فوري: حالة الأمر + الرد عند اكتماله */
@Composable
private fun FawriReplyBubble(m: Fawri.Msg) {
    val (label, color) = when (m.state) {
        "done" -> "تم" to HarithColors.Green
        "working" -> "أشتغل عليها" to HarithColors.GoldText
        else -> "استلمت" to HarithColors.Muted
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(HarithColors.Surface).border(1.dp, HarithColors.Line, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(label, color = color, style = MaterialTheme.typography.labelSmall)
            }
            if (m.reply.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(m.reply, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
            Text(m.updated, color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
