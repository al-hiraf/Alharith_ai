package com.alharith.ai.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alharith.ai.data.AssistantState
import com.alharith.ai.data.Prefs
import kotlinx.coroutines.launch

private val IS_FULL = com.alharith.ai.BuildConfig.FLAVOR != "lite"
private val KEY_RX = Regex("AIza[0-9A-Za-z_\\-]{30,}")

/** معالج أول تشغيل: الاسم، مفتاح Gemini المجاني (يُلتقط تلقائيًا)، الصلاحيات بزر واحد، ثم التجربة. */
@Composable
fun OnboardingScreen(onDone: (tryVoice: Boolean) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalHarithPalette provides Dark) {
        Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
            OrnamentBackdrop(shiftFraction = 0.28f, scrim = listOf(0f to 0.97f, 0.5f to 0.88f, 0.8f to 0.45f, 1f to 0.7f))
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)
            ) {
                // مؤشر الخطوات
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i <= step) Luxe.goldBrush else SolidColor(Color.White.copy(alpha = 0.12f)))
                        )
                    }
                }
                AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step",
                    modifier = Modifier.weight(1f)) { s ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp)) {
                        when (s) {
                            0 -> WelcomeStep { step = 1 }
                            1 -> KeyStep(onNext = { step = 2 })
                            2 -> PermissionsStep { step = 3 }
                            else -> DoneStep(onDone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp))
            .background(if (enabled) Luxe.goldBrush else SolidColor(Color.White.copy(alpha = 0.10f)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color(0xFF2A1F0C) else HarithColors.Muted)
    }
}

@Composable
private fun SkipLink(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp),
        style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center
    )
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    var name by remember { mutableStateOf(Prefs.userName) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LuxeOrb(AssistantState.IDLE, 190.dp, onClick = {})
        Spacer(Modifier.height(14.dp))
        GoldText("رفيق", MaterialTheme.typography.displayLarge.copy(fontFamily = Ruqaa, fontSize = 64.sp))
        Text("مساعدك الشخصي. تكلّمه كأنه إنسان، وينفّذ عنك.", style = MaterialTheme.typography.titleMedium,
            color = HarithColors.Fg.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Text("بماذا أناديك؟", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), strong = true) {
            BasicTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = HarithColors.Fg, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Luxe.Gold),
                modifier = Modifier.fillMaxWidth().padding(18.dp)
            )
        }
        Spacer(Modifier.height(28.dp))
        StepButton("ابدأ", enabled = name.isNotBlank()) { Prefs.userName = name.trim(); onNext() }
    }
}

@Composable
private fun KeyStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Prefs.keyFor("gemini")) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun test(k: String) {
        testing = true; status = "أتحقق من المفتاح…"
        Prefs.setKeyFor("gemini", k); Prefs.provider = "gemini"
        scope.launch {
            status = try {
                com.alharith.ai.brain.AI.send(
                    "أجب بكلمة واحدة فقط.", org.json.JSONArray(),
                    org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "قل: تم")), maxTokens = 20
                )
                ok = true; "✓ ممتاز، رفيق متصل الآن"
            } catch (e: Exception) {
                ok = false; "✗ المفتاح لم يعمل: ${e.message?.take(120) ?: ""}"
            }
            testing = false
        }
    }

    // عند العودة من صفحة Google: نلتقط المفتاح من الحافظة تلقائيًا
    LifecycleResumeEffect(Unit) {
        if (!ok && !testing) {
            val clip = runCatching {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull().orEmpty()
            KEY_RX.find(clip)?.value?.let { found -> if (found != key || status == null) { key = found; test(found) } }
        }
        onPauseOrDispose { }
    }

    Text("عقل رفيق", style = MaterialTheme.typography.displaySmall, color = HarithColors.Fg)
    Spacer(Modifier.height(8.dp))
    Text(
        "رفيق يفكر ويتكلم بذكاء Google المجاني. تحتاج مرة واحدة فقط لمفتاح مجاني من حسابك في Google:",
        style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg.copy(alpha = 0.8f)
    )
    Spacer(Modifier.height(18.dp))
    listOf(
        "اضغط الزر بالأسفل فتفتح صفحة Google",
        "اضغط «Create API key» ثم انسخ المفتاح",
        "ارجع لرفيق — سيلتقط المفتاح ويختبره وحده"
    ).forEachIndexed { i, t ->
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Luxe.goldBrush), contentAlignment = Alignment.Center) {
                Text(arNum(i + 1), color = Color(0xFF2A1F0C), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Text(t, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg)
        }
    }
    Spacer(Modifier.height(20.dp))
    StepButton("افتح صفحة المفتاح المجاني") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
    }
    Spacer(Modifier.height(14.dp))
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        BasicTextField(
            value = key, onValueChange = { key = it.trim(); ok = false; status = null }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = HarithColors.Fg),
            cursorBrush = SolidColor(Luxe.Gold),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            decorationBox = { inner -> if (key.isEmpty()) Text("أو الصق المفتاح هنا", color = HarithColors.Muted); inner() }
        )
    }
    status?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✓")) HarithColors.Green else if (it.startsWith("✗")) HarithColors.Red else Luxe.GoldLight)
    }
    Spacer(Modifier.height(18.dp))
    if (ok) StepButton("التالي", onClick = onNext)
    else {
        StepButton(if (testing) "أتحقق…" else "تحقق من المفتاح", enabled = !testing && KEY_RX.containsMatchIn(key)) { test(KEY_RX.find(key)!!.value) }
        SkipLink("لاحقًا", onNext)
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.SEND_SMS)
        if (IS_FULL) add(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = true }
    var notifAccess by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notifAccess = (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
            .contains(context.packageName)
        onPauseOrDispose { }
    }

    Text("ليقدر يساعدك", style = MaterialTheme.typography.displaySmall, color = HarithColors.Fg)
    Spacer(Modifier.height(8.dp))
    Text("رفيق يحتاج إذنك ليسمعك ويتصل ويرسل ويقرأ مواعيدك. لا يُنفّذ أي إرسال أو اتصال إلا بموافقتك.",
        style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg.copy(alpha = 0.8f))
    Spacer(Modifier.height(18.dp))
    listOf("الميكروفون — ليسمعك", "جهات الاتصال والمكالمات", "الرسائل القصيرة", "التقويم — مواعيدك", "الإشعارات — التذكيرات")
        .forEach { t ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = if (granted) HarithColors.Green else Luxe.Gold.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(t, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg)
            }
        }
    Spacer(Modifier.height(16.dp))
    StepButton(if (granted) "✓ تم" else "امنح الكل بضغطة") { ask.launch(perms) }
    if (IS_FULL) {
        Spacer(Modifier.height(18.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("واتساب وتيليجرام", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                Text("ليقرأ لك الرسائل ويرد عنها بكلمة منك. فعّل «رفيق» في الشاشة التي ستفتح.",
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg.copy(alpha = 0.75f))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (notifAccess) "✓ مفعّل" else "تفعيل قراءة الرسائل",
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !notifAccess) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    }.padding(vertical = 8.dp),
                    color = if (notifAccess) HarithColors.Green else Luxe.GoldLight, style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    StepButton("التالي", onClick = onNext)
}

@Composable
private fun DoneStep(onDone: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LuxeOrb(AssistantState.SPEAKING, 200.dp, onClick = { Prefs.onboarded = true; onDone(true) })
        Spacer(Modifier.height(16.dp))
        GoldText("جاهز يا ${Prefs.userName}", MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text("اضغط الدائرة وتكلّم معي كأنك تكلم إنسان. جرّب:", style = MaterialTheme.typography.bodyLarge,
            color = HarithColors.Fg.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        listOf("«ذكرني بعد ساعة أتصل بأحمد»", "«وش آخر رسائل الواتساب؟»", "«رتب لي يومي»").forEach {
            GlassCard(Modifier.padding(vertical = 4.dp), RoundedCornerShape(18.dp)) {
                Text(it, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(26.dp))
        StepButton("تكلّم مع رفيق الآن") { Prefs.onboarded = true; onDone(true) }
        SkipLink("الذهاب للرئيسية") { Prefs.onboarded = true; onDone(false) }
    }
}
