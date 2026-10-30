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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.imePadding
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
private val KEY_RX = Regex("AIza[0-9A-Za-z_\\-]{30,}|sk-or-v1-[0-9a-fA-F]{40,}")
private fun providerOf(k: String) = if (k.startsWith("sk-or-")) "openrouter" else "gemini"

/** معالج أول تشغيل: الاسم، مفتاح Gemini المجاني (يُلتقط تلقائيًا)، الصلاحيات بزر واحد، ثم التجربة. */
@Composable
fun OnboardingScreen(onDone: (tryVoice: Boolean) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    // شاشة الترحيب داكنة دائمًا (زخرفة الهوية): أيقونات شريط الحالة فاتحة
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
    }
    CompositionLocalProvider(LocalHarithPalette provides Dark) {
        Box(Modifier.fillMaxSize().background(HarithColors.Bg)) {
            // خلفية الهوية الجديدة: تدرّج كحلي هادئ (بدل زخرفة الهوية القديمة)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E4256), Color(0xFF0A2530), Color(0xFF071C25)))))
            Column(
                // imePadding: الكيبورد يرفع المحتوى فيبقى زر «ابدأ» ظاهرًا
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)
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
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Luxe.OnGold else HarithColors.Muted)
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
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.alharith.ai.R.drawable.rafiq_mark), "شعار رفيق",
            Modifier.size(170.dp))
        Spacer(Modifier.height(8.dp))
        GoldText("رفيق", MaterialTheme.typography.displayLarge.copy(fontFamily = Ruqaa, fontSize = 60.sp))
        Text("مساعدك الشخصي. تكلّمه كأنه إنسان، وينفّذ عنك.", style = MaterialTheme.typography.titleMedium,
            color = HarithColors.Fg.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Text("بماذا أناديك؟", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), strong = true) {
            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                if (name.isEmpty()) Text("اكتب اسمك", style = MaterialTheme.typography.headlineSmall,
                    color = HarithColors.Fg.copy(alpha = 0.4f), textAlign = TextAlign.Center)
                BasicTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = HarithColors.Fg, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Luxe.Gold),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "اسمك" }
                )
            }
        }
        if (name.isBlank()) Text("الاسم مطلوب عشان نسجّلك في رفيق", style = MaterialTheme.typography.bodySmall,
            color = HarithColors.Fg.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        StepButton("ابدأ", enabled = name.isNotBlank()) { Prefs.userName = name.trim(); onNext() }
    }
}

@Composable
private fun KeyStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Prefs.keyFor("gemini").ifBlank { Prefs.keyFor("openrouter") }) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun test(k: String) {
        testing = true; status = "أتحقق من المفتاح…"
        val p = providerOf(k)
        Prefs.setKeyFor(p, k); Prefs.provider = p
        scope.launch {
            status = try {
                com.alharith.ai.brain.AI.send(
                    "أجب بكلمة واحدة فقط.", org.json.JSONArray(),
                    org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "قل: تم")), maxTokens = 20
                )
                ok = true
                if (providerOf(k) == "openrouter") "✓ متصل عبر OpenRouter. المحادثة الصوتية المباشرة تحتاج أيضًا مفتاح Gemini المجاني (اختياري)."
                else "✓ ممتاز، رفيق متصل الآن"
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

    // ——— معك رابط دعوة؟ ينشئ حسابك على الخادم ويشتغل رفيق بذكاء الخادم بلا مفتاح
    var showInvite by remember { mutableStateOf(false) }
    // ——— البدء التلقائي: يسجّل في خادم رفيق وحده بمجرد الوصول لهذه الخطوة (بلا أزرار ولا مفاتيح)،
    // ويعيد المحاولة كل بضع ثوانٍ إن كان الخادم مشغولًا. «عندي مفتاحي الخاص» يُظهر الإعداد اليدوي لمن يريده.
    var manual by remember { mutableStateOf(false) }
    var autoMsg by remember { mutableStateOf("أجهّز رفيق لك…") }
    if (!manual && !ok && Prefs.aiKeyMissing) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            var tries = 0
            while (true) {
                if (PendingInvite.link.value != null) { kotlinx.coroutines.delay(2000); continue }  // وصل برابط دعوة: نافذتها تتولى الانضمام
                val info = com.alharith.ai.data.Provisioning.info()
                if (info.available && !info.inviteRequired) {
                    val err = com.alharith.ai.data.Provisioning.register("")
                    if (err == null) { ok = true; onNext(); break }
                    autoMsg = "✗ $err — أحاول مرة ثانية…"
                } else {
                    tries++
                    autoMsg = if (tries < 3) "أجهّز رفيق لك…" else "خادم رفيق مشغول الآن، أحاول مرة ثانية تلقائيًا…"
                }
                kotlinx.coroutines.delay(5000)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(color = Luxe.Gold)
            Spacer(Modifier.height(24.dp))
            Text(autoMsg, style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("لحظات ويكون رفيق جاهزًا، بدون أي إعداد منك.", style = MaterialTheme.typography.bodyMedium,
                color = HarithColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(48.dp))
            androidx.compose.material3.TextButton(onClick = { manual = true }) {
                Text("عندي مفتاحي الخاص", color = HarithColors.Muted)
            }
        }
        return
    }
    if (!ok) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarithColors.Gold.copy(alpha = 0.14f))
            .pressable { showInvite = true }.padding(16.dp)) {
            Column {
                Text("معك رابط دعوة؟", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                Text("اضغط هنا والصقه، ويشتغل رفيق على طول بدون أي مفتاح.", style = MaterialTheme.typography.bodyMedium,
                    color = HarithColors.Fg.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    if (showInvite) InviteJoinDialog(onDismiss = { showInvite = false }) { showInvite = false; ok = true; onNext() }

    // ——— البدء الفوري: مفتاح محدود من خادم رفيق بلا أي خطوات
    var prov by remember { mutableStateOf<com.alharith.ai.data.Provisioning.Info?>(null) }
    var invite by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) { prov = com.alharith.ai.data.Provisioning.info() }
    if (prov?.available == true && !ok) {
        Text("جاهز خلال ثوانٍ", style = MaterialTheme.typography.displaySmall, color = HarithColors.Fg)
        Spacer(Modifier.height(8.dp))
        Text("اضغط الزر ويبدأ رفيق العمل فورًا، بلا مفاتيح ولا إعدادات.", style = MaterialTheme.typography.bodyLarge,
            color = HarithColors.Fg.copy(alpha = 0.8f))
        Spacer(Modifier.height(16.dp))
        if (prov?.inviteRequired == true) {
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
                BasicTextField(
                    value = invite, onValueChange = { invite = it.trim() }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = HarithColors.Fg, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Luxe.Gold), modifier = Modifier.fillMaxWidth().padding(16.dp),
                    decorationBox = { inner -> if (invite.isEmpty()) Text("رمز الدعوة", color = HarithColors.Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center); inner() }
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        StepButton(if (testing) "لحظة…" else "ابدأ فورًا", enabled = !testing && (prov?.inviteRequired != true || invite.isNotBlank())) {
            testing = true; status = "أجهّز رفيق لك…"
            scope.launch {
                val err = com.alharith.ai.data.Provisioning.register(invite)
                testing = false
                if (err == null) { ok = true; status = "✓ رفيق جاهز" } else status = "✗ $err"
            }
        }
        status?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✗")) HarithColors.Red else Luxe.GoldLight) }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Luxe.Gold.copy(alpha = 0.2f)))
        Spacer(Modifier.height(20.dp))
        Text("أو استخدم مفتاحك الخاص", style = MaterialTheme.typography.titleMedium, color = HarithColors.Muted)
        Spacer(Modifier.height(12.dp))
    }
    if (ok && prov?.available == true && (Prefs.provider == "openrouter" || Prefs.provider == "rafiq")) {
        Text("✓ رفيق جاهز", style = MaterialTheme.typography.displaySmall, color = HarithColors.Green)
        Spacer(Modifier.height(18.dp))
        StepButton("التالي", onClick = onNext)
        Spacer(Modifier.height(24.dp))
        Text("للمحادثة الصوتية المباشرة (اختياري) أضف مفتاح Gemini المجاني لاحقًا من الإعدادات.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        return
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
                Text(arNum(i + 1), color = Luxe.OnGold, style = MaterialTheme.typography.titleSmall)
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
            decorationBox = { inner -> if (key.isEmpty()) Text("أو الصق المفتاح هنا (Gemini أو OpenRouter)", color = HarithColors.Muted); inner() }
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
    // الأهم أولًا: الإشعارات وحدها (بدونها لا تذكير ولا أذان). الباقي اختياري، وكل ميزة تطلب إذنها عند أول استخدام
    var notifOn by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var extrasAsked by remember { mutableStateOf(false) }
    val openNotifSettings = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        Unit
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notifOn) openNotifSettings()
    }
    val extras = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        if (IS_FULL) add(Manifest.permission.READ_SMS)
    }.toTypedArray()
    val askExtras = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { extrasAsked = true }
    var notifAccess by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notifOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        notifAccess = (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
            .contains(context.packageName)
        onPauseOrDispose { }
    }

    Text("فعّل التنبيهات", style = MaterialTheme.typography.displaySmall, color = HarithColors.Fg)
    Spacer(Modifier.height(8.dp))
    Text("عشان يوصلك التذكير في وقته، والأذان، والمنبّه حتى والجوال مقفل.",
        style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg.copy(alpha = 0.8f))
    Spacer(Modifier.height(20.dp))
    if (notifOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = HarithColors.Green, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("التنبيهات مفعّلة", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
        }
    } else {
        StepButton("فعّل التنبيهات") {
            if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotifSettings()
        }
    }
    Spacer(Modifier.height(22.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("صلاحيات إضافية (اختياري)", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            Text("الميكروفون، التقويم، جهات الاتصال والمكالمات والرسائل. تقدر تتجاوزها الآن: كل ميزة تطلب إذنها لما تستخدمها أول مرة. لا يُرسل ولا يتصل رفيق إلا بموافقتك.",
                style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg.copy(alpha = 0.75f))
            Text(
                if (extrasAsked) "✓ تم" else "امنحها الآن",
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !extrasAsked) { askExtras.launch(extras) }.padding(vertical = 10.dp),
                color = if (extrasAsked) HarithColors.Green else Luxe.GoldLight, style = MaterialTheme.typography.titleMedium
            )
        }
    }
    if (IS_FULL) {
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("واتساب وتيليجرام (اختياري)", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
                Text("ليقرأ لك الرسائل ويلخّص المهم منها. فعّل «رفيق» في الشاشة التي ستفتح.",
                    style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg.copy(alpha = 0.75f))
                Text(
                    if (notifAccess) "✓ مفعّل" else "تفعيل قراءة الرسائل",
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !notifAccess) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    }.padding(vertical = 8.dp),
                    color = if (notifAccess) HarithColors.Green else Luxe.GoldLight, style = MaterialTheme.typography.titleMedium
                )
                if (!notifAccess && Build.VERSION.SDK_INT >= 33) {
                    Text("إن ظهر «إعداد محظور»: افتح صفحة رفيق ← ⋮ أعلى الشاشة ← «السماح بالإعدادات المحظورة»، ثم ارجع وفعّل.",
                        style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg.copy(alpha = 0.7f))
                    Text(
                        "افتح صفحة رفيق",
                        Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }.padding(vertical = 8.dp),
                        color = Luxe.GoldLight, style = MaterialTheme.typography.titleSmall
                    )
                }
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
        GoldText(if (Prefs.userName.isBlank()) "جاهز" else "جاهز يا ${Prefs.userName}", MaterialTheme.typography.displaySmall)
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
