package com.alharith.ai.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alharith.ai.brain.AI
import com.alharith.ai.data.ActivityLog
import com.alharith.ai.data.Health
import com.alharith.ai.data.Prefs
import com.alharith.ai.service.HarithNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private enum class Level { OK, WARN, FAIL }

private data class Check(val title: String, val level: Level, val detail: String, val fix: (() -> Unit)? = null, val fixLabel: String = "إصلاح")

/** فحص الحارث: يتحقق من كل حلقة في السلسلة (المفتاح، الشبكة، الصوت، الصلاحيات، الخلفية) ويقترح الإصلاح. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    var aiResult by remember { mutableStateOf<Pair<Level, String>?>(null) }
    var ttsResult by remember { mutableStateOf<Pair<Level, String>?>(null) }
    var crash by remember { mutableStateOf(Health.lastCrash()) }

    fun runAiTest() {
        aiResult = Level.WARN to "جارٍ الاختبار…"
        scope.launch {
            aiResult = try {
                AI.send(
                    "أجب بكلمة واحدة.", JSONArray(),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "قل: تم")), maxTokens = 50
                )
                Level.OK to "يعمل — ${Prefs.providerLabel} / ${Prefs.modelFor(Prefs.provider)}"
            } catch (e: Exception) {
                Level.FAIL to (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // اختبار النطق العربي على الجهاز
    LaunchedEffect(Unit) {
        val ready = CompletableDeferred<Int>()
        val tts = TextToSpeech(context.applicationContext) { ready.complete(it) }
        val status = withTimeoutOrNull(6000) { ready.await() }
        ttsResult = when {
            status != TextToSpeech.SUCCESS -> Level.FAIL to "لا يوجد محرك نطق على الهاتف. ثبّت \"خدمات الكلام من Google\"."
            else -> when (tts.isLanguageAvailable(Locale("ar"))) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                    Level.FAIL to "الصوت العربي غير مثبت: الإعدادات ← تحويل النص إلى كلام ← محرك Google ← تثبيت بيانات الصوت ← العربية."
                else -> Level.OK to "النطق العربي متاح."
            }
        }
        runCatching { tts.shutdown() }
        if (!Prefs.aiKeyMissing) runAiTest()
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun open(i: Intent) { runCatching { context.startActivity(i) } }

    val checks = remember(tick, aiResult, ttsResult, crash) {
        buildList {
            // ——— العقل
            add(
                when {
                    Prefs.aiKeyMissing -> Check("مفتاح الذكاء الاصطناعي", Level.FAIL, "لم يُضَف مفتاح ${Prefs.providerLabel}.", onOpenSettings, "الإعدادات")
                    aiResult == null -> Check("الاتصال بالذكاء الاصطناعي", Level.WARN, "لم يُختبر بعد.", { runAiTest() }, "اختبار")
                    else -> Check("الاتصال بالذكاء الاصطناعي", aiResult!!.first, aiResult!!.second, { runAiTest() }, "إعادة الاختبار")
                }
            )
            add(
                Prefs.fallbackProvider?.let { Check("مزوّد احتياطي", Level.OK, "${it.name} — يُستخدم تلقائيًا إذا تعطل الأساسي.") }
                    ?: Check("مزوّد احتياطي", Level.WARN, "غير محدد. يُنصح بإضافة مزوّد ثانٍ بمفتاحه ليستمر الحارث إذا تعطل الأساسي.", onOpenSettings, "الإعدادات")
            )
            add(
                if (Health.isOnline(context)) Check("الإنترنت", Level.OK, "متصل.")
                else Check("الإنترنت", Level.FAIL, "غير متصل. الأوامر تُحفظ وتُنفَّذ تلقائيًا عند عودة الاتصال.")
            )
            val pending = Health.pending().size
            if (pending > 0) add(Check("أوامر مؤجلة", Level.WARN, "$pending بانتظار عودة الإنترنت."))

            // ——— الصوت
            val dialog = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
            val engine = SpeechRecognizer.isRecognitionAvailable(context)
            add(
                when {
                    dialog || engine -> Check("التعرف على الكلام", Level.OK, if (dialog) "نافذة الإدخال الصوتي متاحة." else "محرك التعرف متاح.")
                    else -> Check("التعرف على الكلام", Level.FAIL, "لا توجد خدمة إدخال صوتي. ثبّت تطبيق Google من المتجر.",
                        { open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.googlequicksearchbox"))) }, "تثبيت")
                }
            )
            add(ttsResult?.let { Check("النطق بالعربية", it.first, it.second,
                if (it.first == Level.FAIL) ({ open(Intent("com.android.settings.TTS_SETTINGS")) }) else null, "الإعدادات") }
                ?: Check("النطق بالعربية", Level.WARN, "جارٍ الفحص…"))
            add(
                if (granted(Manifest.permission.RECORD_AUDIO)) Check("الميكروفون", Level.OK, "ممنوح.")
                else Check("الميكروفون", Level.WARN, "غير ممنوح: زر الميكروفون يعمل عبر نافذة Google، لكن كلمة التنبيه تحتاجه.", onOpenSettings, "منح")
            )

            // ——— العمل في الخلفية والتذكيرات
            if (Build.VERSION.SDK_INT >= 33) add(
                if (granted(Manifest.permission.POST_NOTIFICATIONS)) Check("الإشعارات", Level.OK, "ممنوحة — التذكيرات ستظهر.")
                else Check("الإشعارات", Level.FAIL, "غير ممنوحة — التذكيرات لن تظهر!", onOpenSettings, "منح")
            )
            if (Build.VERSION.SDK_INT >= 31) {
                val am = context.getSystemService(AlarmManager::class.java)
                add(
                    if (am.canScheduleExactAlarms()) Check("التذكيرات في وقتها بالضبط", Level.OK, "مفعّلة.")
                    else Check("التذكيرات في وقتها بالضبط", Level.WARN, "غير مفعّلة — قد تتأخر التذكيرات دقائق.",
                        { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) })
                )
            }
            val pm = context.getSystemService(PowerManager::class.java)
            add(
                if (pm.isIgnoringBatteryOptimizations(context.packageName)) Check("العمل في الخلفية", Level.OK, "لن يوقف النظام الحارث.")
                else Check("العمل في الخلفية", Level.WARN, "قد يوقف النظام الحارث لتوفير البطارية.",
                    { @Suppress("BatteryLife") open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) })
            )
            add(
                if (Settings.canDrawOverlays(context)) Check("فتح التطبيقات من الخلفية", Level.OK, "مسموح.")
                else Check("فتح التطبيقات من الخلفية", Level.WARN, "غير مسموح — فتح التطبيقات يعمل فقط والحارث ظاهر.",
                    { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) })
            )
            if (com.alharith.ai.BuildConfig.FLAVOR != "lite") add(
                if (HarithNotificationListener.isEnabled(context)) Check("قراءة رسائل واتساب", Level.OK, "مفعّلة.")
                else Check("قراءة رسائل واتساب", Level.WARN, "غير مفعّلة.", { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            )

            // ——— الأعطال
            val failures = ActivityLog.entries.value.take(50).count { !it.ok }
            add(
                if (crash == null) Check("الأعطال المفاجئة", Level.OK, "لم يُسجَّل أي عطل.")
                else Check("الأعطال المفاجئة", Level.FAIL, "سُجّل عطل: ${crash!!.lineSequence().take(2).joinToString(" ")}", {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, crash)
                    open(Intent.createChooser(send, "مشاركة تقرير العطل"))
                }, "مشاركة التقرير")
            )
            add(Check("آخر 50 عملية", if (failures == 0) Level.OK else Level.WARN,
                if (failures == 0) "كلها نجحت." else "$failures منها لم تنجح — راجع سجل النشاط."))
        }
    }

    val fails = checks.count { it.level == Level.FAIL }
    val warns = checks.count { it.level == Level.WARN }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("فحص الحارث", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            IconButton(onClick = { tick++; crash = Health.lastCrash(); if (!Prefs.aiKeyMissing) runAiTest() }) {
                Icon(Icons.Default.Refresh, "إعادة الفحص", tint = HarithColors.Muted)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when {
                    fails > 0 -> "يوجد $fails ${if (fails == 1) "مشكلة تمنع" else "مشكلات تمنع"} الحارث من العمل الكامل."
                    warns > 0 -> "الحارث يعمل، مع $warns ${if (warns == 1) "ملاحظة" else "ملاحظات"} لرفع الاعتمادية."
                    else -> "كل شيء يعمل بشكل ممتاز."
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (fails > 0) HarithColors.Red else if (warns > 0) HarithColors.Gold else HarithColors.Green
            )
            checks.forEach { c -> CheckRow(c) }
            if (crash != null) {
                OutlinedButton(onClick = { Health.clearCrash(); crash = null }) { Text("مسح سجل العطل", color = HarithColors.Fg) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    val (icon, color) = when (c.level) {
        Level.OK -> Icons.Default.CheckCircle to HarithColors.Green
        Level.WARN -> Icons.Default.WarningAmber to HarithColors.Gold
        Level.FAIL -> Icons.Default.ErrorOutline to HarithColors.Red
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        }
        if (c.fix != null) {
            Text(
                c.fixLabel,
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = c.fix).background(Color(0x14FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = HarithColors.Gold, style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
