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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alharith.ai.data.Prefs
import kotlinx.coroutines.launch
import com.alharith.ai.service.AssistantService
import com.alharith.ai.service.HarithNotificationListener
import com.alharith.ai.voice.WakeWordEngine

private val IS_LITE = com.alharith.ai.BuildConfig.FLAVOR == "lite"

private val RUNTIME_PERMISSIONS: List<Pair<String, List<String>>> = buildList {
    add("الميكروفون (للاستماع لأوامرك)" to listOf(Manifest.permission.RECORD_AUDIO))
    add("جهات الاتصال والمكالمات" to listOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG
    ))
    if (IS_LITE) add("إرسال الرسائل القصيرة SMS" to listOf(Manifest.permission.SEND_SMS))
    else add("الرسائل القصيرة SMS" to listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
    add("التقويم" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    if (Build.VERSION.SDK_INT >= 33) add("الإشعارات (للتذكيرات)" to listOf(Manifest.permission.POST_NOTIFICATIONS))
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLog: () -> Unit = {}, onOpenMemory: () -> Unit = {}, onOpenDiagnostics: () -> Unit = {}) {
    val context = LocalContext.current
    // يُعاد الحساب عند العودة من شاشات النظام
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    Column(
        Modifier
            .fillMaxSize()
            .background(HarithColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg)
            }
            Text("الإعدادات", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // فحص رفيق في الأعلى: أسرع طريق لمعرفة أي خلل وإصلاحه
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(HarithColors.GoldDim.copy(alpha = 0.25f))
                    .clickable(onClick = onOpenDiagnostics).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("فحص رفيق", style = MaterialTheme.typography.titleMedium, color = HarithColors.Gold)
                    Text("يتحقق من المفتاح والإنترنت والصوت والصلاحيات والعمل في الخلفية، ويقترح الإصلاح.",
                        style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg)
                }
                Text("فحص", color = HarithColors.Gold, style = MaterialTheme.typography.labelLarge)
            }
            androidx.compose.runtime.key(tick) { PermissionsSection(context) }
            BrainSection()
            SharedBrainSection(context)
            WakeSection(context)
            if (!IS_LITE) ExternalButtonSection(context)
            BriefingSection(context, onOpenLog)
            DataSection(context, onOpenMemory)
            SafetySection()
            VoiceSection()
            EmailSection()
            FilesSection(context)
            Text(
                "رفيق — الإصدار 2.0.0${if (IS_LITE) " (خفيفة)" else ""}\nالمفاتيح وكلمات المرور محفوظة مشفّرة على هاتفك فقط، وتُرسل الطلبات مباشرة إلى مزوّد الذكاء الاصطناعي الذي اخترته.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

// ———————————————————————————— الأقسام

@Composable
private fun SharedBrainSection(context: Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var on by remember { mutableStateOf(Prefs.sharedBrain) }
    var url by remember { mutableStateOf(Prefs.serverUrl) }
    var token by remember { mutableStateOf(Prefs.serverToken) }
    var busy by remember { mutableStateOf(false) }
    val status by com.alharith.ai.data.SharedBrain.status.collectAsState()
    Section(
        "العقل المشترك (خادم رفيق)",
        "اربط التطبيق بخادم رفيق الذي يعمل 24/7 على Termux أو خادم، فتصبح المهام والذاكرة واحدة مع تيليجرام ولوحة التحكم، وتصلك تذكيرات الخادم هنا."
    ) {
        Field("عنوان الخادم", url, KeyboardType.Uri) { url = it; Prefs.serverUrl = it }
        SecretField("مفتاح الوصول (من لوحة التحكم ← الإعدادات)", token) { token = it; Prefs.serverToken = it }
        ToggleRow("تفعيل العقل المشترك", on) { v ->
            on = v; Prefs.sharedBrain = v
            if (v) { Prefs.syncCursor = ""; Prefs.lastPushMs = 0L; com.alharith.ai.data.SharedBrain.requestSync() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && token.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val msg = try { "✅ متصل بخادم رفيق كـ «${com.alharith.ai.data.SharedBrain.test()}»" }
                    catch (e: Exception) { "❌ ${e.message ?: "تعذّر الاتصال"} — تأكد أن الخادم يعمل (sv status rafiq)" }
                    busy = false
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("اختبار الاتصال", color = HarithColors.Fg) }
            OutlinedButton(enabled = !busy && on && token.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { com.alharith.ai.data.SharedBrain.syncNow() }
                    busy = false
                }
            }) { Text("مزامنة الآن", color = HarithColors.Fg) }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (status.startsWith("تعذ")) HarithColors.Red else HarithColors.Muted)
    }
}

@Composable
private fun PermissionsSection(context: Context) {
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        if (it[Manifest.permission.RECORD_AUDIO] == true) AssistantService.send(context, AssistantService.ACTION_START)
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    Section("الصلاحيات", "كل صلاحية تفتح مجموعة من القدرات. يمكنك منح ما تحتاجه فقط.") {
        androidx.compose.runtime.key(refresh) {
            RUNTIME_PERMISSIONS.forEach { (label, perms) ->
                StatusRow(label, perms.all(::granted)) { launcher.launch(perms.toTypedArray()) }
            }
            val all = RUNTIME_PERMISSIONS.flatMap { it.second }
            if (!all.all(::granted)) {
                Button(
                    onClick = { launcher.launch(all.toTypedArray()) },
                    colors = ButtonDefaults.buttonColors(containerColor = HarithColors.Gold),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("منح كل الصلاحيات الأساسية") }
            }
            HorizontalDivider(color = HarithColors.Line)
            if (IS_LITE) Text(
                "النسخة الخفيفة: لا تقرأ رسائل SMS وإشعارات واتساب (حتى لا يحظرها Play Protect). " +
                    "الاتصال، إرسال SMS، فتح محادثات واتساب، البريد، التقويم والملفات تعمل كاملة.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
            ) else StatusRow(
                "قراءة إشعارات واتساب وتيليجرام وغيرها",
                HarithNotificationListener.isEnabled(context),
                hint = "لقراءة الرسائل الواردة والرد عليها من الإشعار"
            ) { open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            StatusRow(
                "فتح التطبيقات بعد \"يا رفيق\"",
                Settings.canDrawOverlays(context),
                hint = "صلاحية الظهور فوق التطبيقات — مطلوبة لفتح التطبيقات والشاشات والتطبيق في الخلفية"
            ) {
                open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val am = context.getSystemService(AlarmManager::class.java)
                StatusRow("تذكيرات في وقتها بالضبط", am.canScheduleExactAlarms()) {
                    open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
            val pm = context.getSystemService(PowerManager::class.java)
            StatusRow(
                "العمل في الخلفية دون انقطاع",
                pm.isIgnoringBatteryOptimizations(context.packageName),
                hint = "يمنع النظام من إيقاف كلمة التنبيه لتوفير البطارية"
            ) {
                @Suppress("BatteryLife")
                open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

@Composable
private fun BrainSection() {
    var providerId by remember { mutableStateOf(Prefs.provider) }
    var name by remember { mutableStateOf(Prefs.userName) }
    var picking by remember { mutableStateOf(false) }
    val prov = com.alharith.ai.data.Providers.byId(providerId)
    // تُعاد قراءة المفتاح والنموذج عند تغيير المزوّد
    var key by remember(providerId) { mutableStateOf(Prefs.rawKeyFor(providerId)) }
    var model by remember(providerId) { mutableStateOf(Prefs.modelFor(providerId)) }
    var baseUrl by remember { mutableStateOf(Prefs.customBaseUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var fallback by remember { mutableStateOf(Prefs.fallbackProviderId) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Section("الذكاء الاصطناعي", "اختر المزوّد الذي يفكّر به رفيق (${com.alharith.ai.data.Providers.ALL.size} خيارًا)، وضع مفتاحه.") {
        Field("اسمك (يناديك به رفيق)", name) { name = it; Prefs.userName = it }

        Text("المزوّد", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarithColors.SurfaceHigh)
                .clickable { picking = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(prov.name, Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
            Text("تغيير", color = HarithColors.Gold, style = MaterialTheme.typography.labelLarge)
        }
        if (prov.note.isNotBlank()) Text(prov.note, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)

        if (prov.id == "custom") {
            Field("الرابط الأساسي (مثل http://192.168.1.10:11434/v1)", baseUrl, KeyboardType.Uri) { baseUrl = it; Prefs.customBaseUrl = it }
        }
        Text("المفتاح من: ${prov.keyUrl}", style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        SecretField(if (prov.keyOptional) "مفتاح API (اختياري)" else "مفتاح ${prov.name.substringBefore(" (")}", key) {
            key = it; Prefs.setKeyFor(prov.id, it); testResult = null
        }

        Text("النموذج", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        prov.models.forEach { (id, label) ->
            ChoiceRow(label, model == id) { model = id; Prefs.setModelFor(prov.id, id); testResult = null }
        }
        Field(if (prov.models.isEmpty()) "اسم النموذج" else "أو اكتب اسم أي نموذج آخر", model) {
            model = it; Prefs.setModelFor(prov.id, it); testResult = null
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !testing, onClick = {
                testing = true; testResult = null
                scope.launch {
                    testResult = try {
                        val r = com.alharith.ai.brain.AI.send(
                            "أجب بكلمة واحدة فقط.", org.json.JSONArray(),
                            org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "قل: تم")),
                            maxTokens = 50
                        )
                        "✓ يعمل — ${prov.name.substringBefore(" (")} / ${Prefs.modelFor(prov.id)}"
                    } catch (e: Exception) {
                        "✗ ${e.message ?: e.javaClass.simpleName}"
                    }
                    testing = false
                }
            }) { Text(if (testing) "جارٍ الاختبار…" else "اختبار الاتصال", color = HarithColors.Fg) }
        }
        testResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) HarithColors.Green else HarithColors.Red)
        }
        HorizontalDivider(color = HarithColors.Line)
        Text("المزوّد الاحتياطي", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        Text(
            "إذا تعطل المزوّد الأساسي (انقطاع، انتهاء رصيد، ضغط) يكمل رفيق تلقائيًا بالاحتياطي. اختر مزوّدًا آخر سبق أن وضعت مفتاحه.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        val candidates = com.alharith.ai.data.Providers.ALL.filter {
            it.id != providerId && (Prefs.keyFor(it.id).isNotBlank() || (it.keyOptional && Prefs.customBaseUrl.isNotBlank()))
        }
        if (candidates.isEmpty()) {
            Text("لا يوجد مزوّد آخر بمفتاح بعد. اختر مزوّدًا آخر أعلاه وضع مفتاحه، ثم ارجع لاختياره هنا.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Gold)
        } else {
            ChoiceRow("بدون", fallback.isBlank() || candidates.none { it.id == fallback }) { fallback = ""; Prefs.fallbackProviderId = "" }
            candidates.forEach { c -> ChoiceRow(c.name, fallback == c.id) { fallback = c.id; Prefs.fallbackProviderId = c.id } }
        }
        if (prov.id != "gemini") {
            Text(
                "للبحث في الإنترنت يستخدم رفيق مفتاح Gemini إن وُجد، أيًا كان المزوّد المختار.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
            )
        }
    }

    if (picking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("اختر المزوّد") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    com.alharith.ai.data.Providers.ALL.forEach { p ->
                        val hasKey = Prefs.keyFor(p.id).isNotBlank()
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                providerId = p.id; Prefs.provider = p.id; picking = false; testResult = null
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = providerId == p.id, onClick = {
                                providerId = p.id; Prefs.provider = p.id; picking = false; testResult = null
                            })
                            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (hasKey) Text("✓", color = HarithColors.Green)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
    }
}

@Composable
private fun WakeSection(context: Context) {
    var enabled by remember { mutableStateOf(Prefs.wakeWordEnabled) }
    var pv by remember { mutableStateOf(Prefs.picovoiceKey) }
    var sens by remember { mutableFloatStateOf(Prefs.wakeSensitivity) }
    var hasFile by remember { mutableStateOf(WakeWordEngine.keywordFile(context).exists()) }
    var hasModel by remember { mutableStateOf(WakeWordEngine.modelFile(context).exists()) }
    val reload = { AssistantService.send(context, AssistantService.ACTION_RELOAD) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                WakeWordEngine.keywordFile(context).outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        hasFile = WakeWordEngine.keywordFile(context).exists()
        Toast.makeText(context, if (ok) "تم استيراد كلمة التنبيه" else "تعذّر استيراد الملف", Toast.LENGTH_SHORT).show()
        if (ok) reload()
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                WakeWordEngine.modelFile(context).outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        hasModel = WakeWordEngine.modelFile(context).exists()
        Toast.makeText(context, if (ok) "تم استيراد نموذج اللغة" else "تعذّر استيراد الملف", Toast.LENGTH_SHORT).show()
        if (ok) reload()
    }

    Section(
        "كلمة التنبيه \"يا رفيق\"",
        "تعمل على الهاتف نفسه بدون إنترنت عبر Picovoice. أنشئ حسابًا مجانيًا في console.picovoice.ai، " +
            "درّب الكلمة \"يا رفيق\" (اللغة: Arabic، المنصة: Android)، ثم استورد ملف ‎.ppn‎ هنا."
    ) {
        ToggleRow("تفعيل كلمة التنبيه", enabled) { enabled = it; Prefs.wakeWordEnabled = it; reload() }
        SecretField("مفتاح Picovoice AccessKey", pv) { pv = it; Prefs.picovoiceKey = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(if (hasFile) "استبدال ملف الكلمة" else "استيراد ملف الكلمة (.ppn)", color = HarithColors.Fg)
            }
            Spacer(Modifier.width(10.dp))
            StatusDot(hasFile)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                Text(if (hasModel) "استبدال نموذج اللغة" else "نموذج اللغة (.pv) — لغير الإنجليزية", color = HarithColors.Fg)
            }
            Spacer(Modifier.width(10.dp))
            StatusDot(hasModel)
        }
        Text(
            "إن درّبت الكلمة بلغة غير الإنجليزية فاستورد ملف porcupine_params الخاص بتلك اللغة من صفحة Picovoice على GitHub (مجلد lib/common).",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        Text("الحساسية: ${(sens * 100).toInt()}٪", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        Slider(
            value = sens, onValueChange = { sens = it }, valueRange = 0.3f..0.9f,
            onValueChangeFinished = { Prefs.wakeSensitivity = sens; reload() }
        )
        Text(
            "أعلى = يلتقط النداء بسهولة أكبر مع احتمال تنبيه خاطئ أكثر.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        OutlinedButton(onClick = reload) { Text("تطبيق وإعادة تشغيل الاستماع", color = HarithColors.Fg) }
        Text(
            "بدائل سريعة: زر الميكروفون في التطبيق، زر \"تحدّث\" في الإشعار، مربع \"يا رفيق\" في الإعدادات السريعة، " +
                "أو اجعل رفيق المساعد الافتراضي ليعمل بالضغط المطوّل على زر الرئيسية.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) {
            Text("اختيار المساعد الافتراضي", color = HarithColors.Fg)
        }
    }
}

@Composable
private fun ExternalButtonSection(context: Context) {
    var on by remember { mutableStateOf(Prefs.volumeTrigger) }
    var mode by remember { mutableStateOf(Prefs.volumeMode) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val enabled = remember(tick) { com.alharith.ai.service.VolumeButtonService.isEnabled(context) }

    Section("زر النداء الخارجي", "استدعِ رفيق بزر الصوت دون فتح التطبيق، والشاشة مفتوحة أو على شاشة القفل بعد فتحها.") {
        StatusRow(
            "تفعيل خدمة زر رفيق", enabled,
            hint = "من إمكانية الوصول ← التطبيقات المثبتة ← زر رفيق. تستقبل أزرار الصوت فقط ولا تقرأ الشاشة."
        ) { open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ToggleRow("استخدام زر الصوت للنداء", on) { on = it; Prefs.volumeTrigger = it }
        ChoiceRow("ضغط مطوّل على رفع الصوت (نصف ثانية)", mode == com.alharith.ai.service.VolumeButtonService.MODE_LONG_UP) {
            mode = com.alharith.ai.service.VolumeButtonService.MODE_LONG_UP; Prefs.volumeMode = mode
        }
        ChoiceRow("ضغطتان سريعتان على خفض الصوت", mode == com.alharith.ai.service.VolumeButtonService.MODE_DOUBLE_DOWN) {
            mode = com.alharith.ai.service.VolumeButtonService.MODE_DOUBLE_DOWN; Prefs.volumeMode = mode
        }
        Text(
            "الضغطة العادية تغيّر الصوت كالمعتاد، وأثناء المكالمات تبقى الأزرار للنظام. " +
                "إن رفض أندرويد التفعيل: الإعدادات ← التطبيقات ← رفيق ← ⋮ ← السماح بالإعدادات المقيّدة.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        HorizontalDivider(color = HarithColors.Line)
        Text("زر التشغيل أو الرئيسية", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        Text(
            "اجعل رفيق المساعد الرقمي الافتراضي، ثم اضغط مطوّلًا على زر التشغيل أو زر الرئيسية (حسب جوالك) ليبدأ الاستماع.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) {
            Text("اختيار رفيق مساعدًا افتراضيًا", color = HarithColors.Fg)
        }
    }
}

@Composable
private fun DataSection(context: Context, onOpenMemory: () -> Unit) {
    var confirmWipe by remember { mutableStateOf(false) }
    Section("الذاكرة والبيانات", "مهامك وملاحظاتك وذاكرتك محفوظة على هاتفك فقط، وتعمل بدون إنترنت.") {
        OutlinedButton(onClick = onOpenMemory, modifier = Modifier.fillMaxWidth()) {
            Text("ذاكرة رفيق — عرض وتعديل وحذف", color = HarithColors.Fg)
        }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "بيانات رفيق")
                putExtra(Intent.EXTRA_TEXT, com.alharith.ai.data.LocalStore.exportJson())
            }
            runCatching { context.startActivity(Intent.createChooser(send, "تصدير البيانات")) }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تصدير بياناتي (مهام، ملاحظات، ذاكرة، تذكيرات)", color = HarithColors.Fg)
        }
        OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
            Text("حذف كل بياناتي", color = HarithColors.Red)
        }
    }
    if (confirmWipe) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("حذف كل البيانات؟") },
            text = { Text("ستُحذف المهام والملاحظات والذاكرة والتذكيرات وسجل النشاط نهائيًا. الإعدادات والمفاتيح تبقى.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.alharith.ai.data.LocalStore.reminders.value.forEach { com.alharith.ai.service.Reminders.cancel(context, it) }
                    com.alharith.ai.data.LocalStore.wipeAll()
                    com.alharith.ai.data.ActivityLog.clear()
                    confirmWipe = false
                    Toast.makeText(context, "حُذفت كل البيانات", Toast.LENGTH_SHORT).show()
                }) { Text("حذف", color = HarithColors.Red) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmWipe = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun BriefingSection(context: Context, onOpenLog: () -> Unit) {
    var brief by remember { mutableStateOf(Prefs.briefingEnabled) }
    var time by remember { mutableStateOf(Prefs.briefingTime) }
    var alerts by remember { mutableStateOf(Prefs.importantAlerts) }
    var eve by remember { mutableStateOf(Prefs.eveningEnabled) }
    var eveTime by remember { mutableStateOf(Prefs.eveningTime) }

    fun pickEveTime() {
        val (h, m) = eveTime.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 21) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        android.app.TimePickerDialog(context, { _, hh, mm ->
            eveTime = "%02d:%02d".format(java.util.Locale.US, hh, mm)
            Prefs.eveningTime = eveTime
            com.alharith.ai.service.BriefingReceiver.schedule(context)
        }, h, m, false).show()
    }

    fun pickTime() {
        val (h, m) = time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        android.app.TimePickerDialog(context, { _, hh, mm ->
            time = "%02d:%02d".format(java.util.Locale.US, hh, mm)
            Prefs.briefingTime = time
            com.alharith.ai.service.BriefingReceiver.schedule(context)
        }, h, m, false).show()
    }

    Section("الموجز والتنبيهات", "رفيق يجهّز لك ما يهمك، والقرار والإرسال يبقى بيدك.") {
        ToggleRow("الموجز الصباحي اليومي", brief) {
            brief = it; Prefs.briefingEnabled = it
            com.alharith.ai.service.BriefingReceiver.schedule(context)
        }
        if (brief) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الوقت: $time", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
                OutlinedButton(onClick = { pickTime() }) { Text("تغيير", color = HarithColors.Fg) }
            }
            Text(
                "في الوقت المحدد يصلك إشعار، اضغطه ليقرأ لك رفيق مواعيدك ورسائلك وإيميلاتك المهمة.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
            )
        }
        HorizontalDivider(color = HarithColors.Line)
        ToggleRow("المراجعة المسائية \"ماذا أنجزت اليوم؟\"", eve) {
            eve = it; Prefs.eveningEnabled = it
            com.alharith.ai.service.BriefingReceiver.schedule(context)
        }
        if (eve) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الوقت: $eveTime", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
                OutlinedButton(onClick = { pickEveTime() }) { Text("تغيير", color = HarithColors.Fg) }
            }
        }
        if (!IS_LITE) {
            HorizontalDivider(color = HarithColors.Line)
            ToggleRow("تنبيهي بالرسائل المهمة مع رد مقترح", alerts) { alerts = it; Prefs.importantAlerts = it }
            Text(
                "يقيّم رفيق رسائل واتساب وغيرها عند وصولها، وإن كانت مهمة يلخّصها في إشعار مع رد مقترح. " +
                    "الرد لا يُرسل إلا إذا ضغطت \"أرسل الرد المقترح\". يحتاج تفعيل قراءة الإشعارات.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
            )
        }
        HorizontalDivider(color = HarithColors.Line)
        OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
            Text("سجل النشاط — كل ما نفّذه رفيق", color = HarithColors.Fg)
        }
    }
}

@Composable
private fun SafetySection() {
    var calls by remember { mutableStateOf(Prefs.confirmCalls) }
    var msgs by remember { mutableStateOf(Prefs.confirmMessages) }
    var mails by remember { mutableStateOf(Prefs.confirmEmails) }
    Section("الأمان والتأكيد", "يسألك رفيق قبل تنفيذ الإجراءات الحساسة. يمكنك الرد صوتًا بـ\"نعم\" أو \"لا\".") {
        ToggleRow("التأكيد قبل الاتصال", calls) { calls = it; Prefs.confirmCalls = it }
        ToggleRow("التأكيد قبل إرسال الرسائل والرد", msgs) { msgs = it; Prefs.confirmMessages = it }
        ToggleRow("التأكيد قبل إرسال البريد", mails) { mails = it; Prefs.confirmEmails = it }
    }
}

@Composable
private fun VoiceSection() {
    var typed by remember { mutableStateOf(Prefs.speakTypedReplies) }
    var follow by remember { mutableStateOf(Prefs.followUpListening) }
    var rate by remember { mutableFloatStateOf(Prefs.speechRate) }
    Section("الصوت") {
        ToggleRow("نطق الردود على الأوامر المكتوبة", typed) { typed = it; Prefs.speakTypedReplies = it }
        ToggleRow("متابعة الاستماع عندما يسألك رفيق", follow) { follow = it; Prefs.followUpListening = it }
        Text("سرعة النطق: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        Slider(
            value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.6f,
            onValueChangeFinished = { Prefs.speechRate = rate }
        )
    }
}

@Composable
private fun EmailSection() {
    var addr by remember { mutableStateOf(Prefs.emailAddress) }
    var pass by remember { mutableStateOf(Prefs.emailPassword) }
    var imap by remember { mutableStateOf(Prefs.imapHost) }
    var imapPort by remember { mutableStateOf(Prefs.imapPort) }
    var smtp by remember { mutableStateOf(Prefs.smtpHost) }
    var smtpPort by remember { mutableStateOf(Prefs.smtpPort) }
    var advanced by remember { mutableStateOf(false) }

    fun autoFill(a: String) {
        val domain = a.substringAfter('@', "").lowercase()
        val preset = when {
            domain == "gmail.com" || domain == "googlemail.com" -> listOf("imap.gmail.com", "993", "smtp.gmail.com", "465")
            domain in setOf("outlook.com", "hotmail.com", "live.com", "msn.com") ->
                listOf("outlook.office365.com", "993", "smtp-mail.outlook.com", "587")
            domain == "yahoo.com" -> listOf("imap.mail.yahoo.com", "993", "smtp.mail.yahoo.com", "465")
            domain == "icloud.com" || domain == "me.com" -> listOf("imap.mail.me.com", "993", "smtp.mail.me.com", "587")
            else -> null
        } ?: return
        imap = preset[0]; imapPort = preset[1]; smtp = preset[2]; smtpPort = preset[3]
        Prefs.imapHost = imap; Prefs.imapPort = imapPort; Prefs.smtpHost = smtp; Prefs.smtpPort = smtpPort
    }

    Section(
        "البريد الإلكتروني",
        "لـ Gmail: فعّل التحقق بخطوتين ثم أنشئ \"كلمة مرور التطبيقات\" من myaccount.google.com/apppasswords واستخدمها هنا (وليس كلمة مرورك العادية)."
    ) {
        Field("البريد الإلكتروني", addr, KeyboardType.Email) { addr = it; Prefs.emailAddress = it; autoFill(it) }
        SecretField("كلمة مرور التطبيقات", pass) { pass = it; Prefs.emailPassword = it }
        Text(
            if (advanced) "إخفاء إعدادات الخادم" else "إعدادات الخادم (لغير Gmail وOutlook وYahoo)",
            color = HarithColors.Gold, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 4.dp)
        )
        if (advanced) {
            Field("خادم IMAP", imap) { imap = it; Prefs.imapHost = it }
            Field("منفذ IMAP", imapPort, KeyboardType.Number) { imapPort = it; Prefs.imapPort = it }
            Field("خادم SMTP", smtp) { smtp = it; Prefs.smtpHost = it }
            Field("منفذ SMTP", smtpPort, KeyboardType.Number) { smtpPort = it; Prefs.smtpPort = it }
        }
    }
}

@Composable
private fun FilesSection(context: Context) {
    var tree by remember { mutableStateOf(Prefs.filesTreeUri) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Prefs.filesTreeUri = uri.toString()
        tree = uri.toString()
    }
    Section("الملفات", "اختر المجلد الذي يبحث فيه رفيق عن ملفاتك (مثل Download أو Documents). تستطيع أيضًا مشاركة أي ملف مع رفيق من تطبيق آخر.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(null) }) {
                Text(if (tree.isBlank()) "اختيار مجلد" else "تغيير المجلد", color = HarithColors.Fg)
            }
            Spacer(Modifier.width(10.dp))
            StatusDot(tree.isNotBlank())
        }
        if (tree.isNotBlank()) {
            Text(
                Uri.decode(tree).substringAfterLast(':').ifBlank { "المجلد الرئيسي" },
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
            )
        }
    }
}

// ———————————————————————————— عناصر مشتركة

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    // أقسام مسطحة بلا صناديق: العنوان والمسافات والفاصل الرفيع تكفي للتنظيم
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
        Spacer(Modifier.height(2.dp))
        content()
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = HarithColors.Line)
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Icon(
        if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
        tint = if (ok) HarithColors.Green else HarithColors.Muted, modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean, hint: String? = null, onGrant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !ok, onClick = onGrant).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        }
        if (!ok) Text("منح", color = HarithColors.Gold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = HarithColors.Gold)
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = HarithColors.Gold,
    unfocusedBorderColor = HarithColors.Line,
    focusedLabelColor = HarithColors.Gold
)

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "إظهار", tint = HarithColors.Muted)
            }
        },
        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
    )
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}
