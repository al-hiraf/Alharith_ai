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
import com.alharith.ai.service.AssistantService
import com.alharith.ai.service.HarithNotificationListener
import com.alharith.ai.voice.WakeWordEngine

private val RUNTIME_PERMISSIONS: List<Pair<String, List<String>>> = buildList {
    add("الميكروفون (للاستماع لأوامرك)" to listOf(Manifest.permission.RECORD_AUDIO))
    add("جهات الاتصال والمكالمات" to listOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG
    ))
    add("الرسائل القصيرة SMS" to listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
    add("التقويم" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    if (Build.VERSION.SDK_INT >= 33) add("الإشعارات (للتذكيرات)" to listOf(Manifest.permission.POST_NOTIFICATIONS))
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
            androidx.compose.runtime.key(tick) { PermissionsSection(context) }
            BrainSection()
            WakeSection(context)
            SafetySection()
            VoiceSection()
            EmailSection()
            FilesSection(context)
            Text(
                "الحارث AI — الإصدار 1.0.0\nالمفاتيح وكلمات المرور محفوظة مشفّرة على هاتفك فقط، وتُرسل الطلبات مباشرة إلى Claude.",
                style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

// ———————————————————————————— الأقسام

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
            StatusRow(
                "قراءة إشعارات واتساب وتيليجرام وغيرها",
                HarithNotificationListener.isEnabled(context),
                hint = "لقراءة الرسائل الواردة والرد عليها من الإشعار"
            ) { open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            StatusRow(
                "فتح التطبيقات بعد \"يا الحارث\"",
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
    var key by remember { mutableStateOf(Prefs.claudeApiKey) }
    var model by remember { mutableStateOf(Prefs.claudeModel) }
    var name by remember { mutableStateOf(Prefs.userName) }
    Section("الذكاء الاصطناعي", "الحارث يفكّر عبر Claude من Anthropic. أنشئ مفتاحًا من console.anthropic.com") {
        Field("اسمك (يناديك به الحارث)", name) { name = it; Prefs.userName = it }
        SecretField("مفتاح Claude API", key) { key = it; Prefs.claudeApiKey = it }
        Text("النموذج", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
        Prefs.MODELS.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { model = id; Prefs.claudeModel = id },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = model == id, onClick = { model = id; Prefs.claudeModel = id })
                Text(label, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
            }
        }
    }
}

@Composable
private fun WakeSection(context: Context) {
    var enabled by remember { mutableStateOf(Prefs.wakeWordEnabled) }
    var pv by remember { mutableStateOf(Prefs.picovoiceKey) }
    var sens by remember { mutableFloatStateOf(Prefs.wakeSensitivity) }
    var hasFile by remember { mutableStateOf(WakeWordEngine.keywordFile(context).exists()) }
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

    Section(
        "كلمة التنبيه \"يا الحارث\"",
        "تعمل على الهاتف نفسه بدون إنترنت عبر Picovoice. أنشئ حسابًا مجانيًا في console.picovoice.ai، " +
            "درّب الكلمة \"يا الحارث\" (اللغة: Arabic، المنصة: Android)، ثم استورد ملف ‎.ppn‎ هنا."
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
            "بدائل سريعة: زر الميكروفون في التطبيق، زر \"تحدّث\" في الإشعار، مربع \"يا الحارث\" في الإعدادات السريعة، " +
                "أو اجعل الحارث المساعد الافتراضي ليعمل بالضغط المطوّل على زر الرئيسية.",
            style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted
        )
        OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) {
            Text("اختيار المساعد الافتراضي", color = HarithColors.Fg)
        }
    }
}

@Composable
private fun SafetySection() {
    var calls by remember { mutableStateOf(Prefs.confirmCalls) }
    var msgs by remember { mutableStateOf(Prefs.confirmMessages) }
    var mails by remember { mutableStateOf(Prefs.confirmEmails) }
    Section("الأمان والتأكيد", "يسألك الحارث قبل تنفيذ الإجراءات الحساسة. يمكنك الرد صوتًا بـ\"نعم\" أو \"لا\".") {
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
        ToggleRow("متابعة الاستماع عندما يسألك الحارث", follow) { follow = it; Prefs.followUpListening = it }
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
    Section("الملفات", "اختر المجلد الذي يبحث فيه الحارث عن ملفاتك (مثل Download أو Documents). تستطيع أيضًا مشاركة أي ملف مع الحارث من تطبيق آخر.") {
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HarithColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HarithColors.Gold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        Spacer(Modifier.height(2.dp))
        content()
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
