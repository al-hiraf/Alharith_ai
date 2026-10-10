package com.alharith.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.SharedInbox
import com.alharith.ai.service.AssistantService
import java.io.File
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // أيقونات شريط الحالة تتبع الوضع الفاتح/الداكن تلقائيًا
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIntent(intent)
        // نحسبها الآن: التركيب يحدث بعد onResume الذي يستهلك pendingListen
        val startScreen = when {
            !com.alharith.ai.data.Prefs.onboarded && com.alharith.ai.data.Prefs.aiKeyMissing -> "onboarding"
            pendingListen == true || pendingBriefing -> "chat"
            else -> "home"
        }
        setContent {
            HarithTheme {
                var screen by rememberSaveable {
                    // في نسخة الاختبار فقط: فتح شاشة محددة لالتقاط لقطات الشاشة آليًا
                    val start = startScreen
                    mutableStateOf(if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("open_screen") ?: start else start)
                }
                openChat = { screen = "chat" }
                BackHandler(enabled = screen != "home") { screen = if (screen in setOf("log", "memory", "diagnostics")) "settings" else "home" }
                val home = { screen = "home" }
                when (screen) {
                    "onboarding" -> OnboardingScreen { tryVoice ->
                        if (tryVoice) { screen = "chat"; startListening() } else screen = "home"
                    }
                    "settings" -> SettingsScreen(
                        onBack = home, onOpenLog = { screen = "log" }, onOpenMemory = { screen = "memory" },
                        onOpenDiagnostics = { screen = "diagnostics" }
                    )
                    "diagnostics" -> DiagnosticsScreen(onBack = { screen = "settings" }, onOpenSettings = { screen = "settings" })
                    "log" -> ActivityScreen(onBack = { screen = "settings" })
                    "memory" -> MemoryScreen(onBack = { screen = "settings" })
                    "tasks" -> TasksScreen(onBack = home)
                    "chat" -> ChatScreen(
                        onOpenSettings = { screen = "settings" }, onOpenLog = { screen = "log" }, onBack = home
                    )
                    else -> HomeScreen(
                        onVoice = { screen = "chat"; startListening() },
                        onAsk = { text, label ->
                            screen = "chat"
                            AssistantService.send(this, AssistantService.ACTION_TEXT, text, speak = false, display = label)
                        },
                        onOpenChat = { screen = "chat" },
                        onOpenTasks = { screen = "tasks" },
                        onOpenSettings = { screen = "settings" }
                    )
                }
            }
        }
        // الخدمة تطلب نافذة Google إن تعذّر الاستماع داخل التطبيق
        handledDialogRequest = ConversationStore.dialogRequest.value
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                ConversationStore.dialogRequest.collect { req ->
                    if (req > handledDialogRequest) { handledDialogRequest = req; launchVoiceDialog() }
                }
            }
        }
        // في نسخة الاختبار فقط: بيانات تجريبية لالتقاط لقطات الشاشة (لا تُضاف أبدًا في النسخة الفعلية)
        if (com.alharith.ai.BuildConfig.DEBUG && intent?.getBooleanExtra("seed_demo", false) == true &&
            com.alharith.ai.data.LocalStore.tasks.value.isEmpty()
        ) {
            val st = com.alharith.ai.data.LocalStore
            val today = java.time.LocalDate.now()
            st.addTask(com.alharith.ai.data.TaskItem(st.newId(), "إرسال عرض السعر للعميل", priority = "urgent", due = "${today}T10:00", project = "المبيعات"))
            st.addTask(com.alharith.ai.data.TaskItem(st.newId(), "مراجعة العقد الجديد", priority = "high", due = today.minusDays(1).toString(),
                subtasks = listOf(com.alharith.ai.data.SubTask("قراءة البنود المالية", true), com.alharith.ai.data.SubTask("إرسال الملاحظات"))))
            st.addTask(com.alharith.ai.data.TaskItem(st.newId(), "متابعة أحمد بخصوص الدفعة", due = today.plusDays(1).toString(), person = "أحمد"))
            st.addMemory(com.alharith.ai.data.MemoryItem(st.newId(), "يفضّل الردود المختصرة"))
        }
        // في نسخة الاختبار فقط: ربط العقل المشترك بخادم اختبار (لا يوجد في النسخة الفعلية)
        if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("server_token")?.let { tok ->
            com.alharith.ai.data.Prefs.serverUrl = intent.getStringExtra("server_url") ?: "http://127.0.0.1:8787"
            com.alharith.ai.data.Prefs.serverToken = tok
            com.alharith.ai.data.Prefs.sharedBrain = true
            com.alharith.ai.data.Prefs.syncCursor = ""
            com.alharith.ai.data.Prefs.lastPushMs = 0L
            com.alharith.ai.data.SharedBrain.requestSync()
        }
        if (com.alharith.ai.BuildConfig.DEBUG && intent?.getBooleanExtra("sync_now", false) == true) {
            com.alharith.ai.data.SharedBrain.requestSync()
        }
        // في نسخة الاختبار فقط: إرسال أمر مكتوب للتحقق من مسار المعالجة كاملًا
        if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("ask")?.let {
            AssistantService.send(this, AssistantService.ACTION_TEXT, it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        // تشغيل الخدمة (وكلمة التنبيه) ما دامت صلاحية الميكروفون ممنوحة
        if (hasMic()) AssistantService.send(this, AssistantService.ACTION_START)
        pendingListen?.let { pendingListen = null; if (it) { openChat?.invoke(); startListening() } }
        if (pendingBriefing) {
            pendingBriefing = false
            val evening = pendingBriefingKind == com.alharith.ai.service.BriefingReceiver.KIND_EVENING
            AssistantService.send(
                this, AssistantService.ACTION_TEXT,
                if (evening) com.alharith.ai.service.BriefingReceiver.EVENING_PROMPT else com.alharith.ai.service.BriefingReceiver.BRIEFING_PROMPT,
                speak = true, display = if (evening) "ماذا أنجزت اليوم؟" else "موجز اليوم"
            )
            openChat?.invoke()
        }
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * الاستماع عبر نافذة الإدخال الصوتي الرسمية في Android (Google).
     * تعمل على كل الهواتف تقريبًا وتعرض حالة الاستماع بوضوح، ثم يُرسل النص لرفيق ويُنطق الرد.
     */
    private val speech = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
            .replace(Regex("^\\s*(يا\\s*)?((ال)?حارث|(ال)?رفيق)[،,.\\s]*"), "").trim()
        if (text.isNotBlank()) {
            AssistantService.send(this, AssistantService.ACTION_TEXT, text, speak = true)
        } else if (res.resultCode != RESULT_OK && res.resultCode != RESULT_CANCELED) {
            ConversationStore.setError("لم أسمع شيئًا واضحًا، اضغط الميكروفون وحاول مرة أخرى.")
        }
    }

    /** طلب صلاحية الميكروفون: عند الرفض نستخدم نافذة Google (لا تحتاج صلاحية من التطبيق) */
    private val micAsk = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) AssistantService.send(this, if (com.alharith.ai.data.Prefs.liveAvailable) AssistantService.ACTION_LIVE else AssistantService.ACTION_LISTEN)
        else launchVoiceDialog()
    }

    /**
     * الاستماع داخل التطبيق: الدائرة الذهبية تتفاعل مع صوتك وكلامك يُكتب أمامك.
     * إن لم يدعم الجهاز ذلك ننتقل تلقائيًا لنافذة الإدخال الصوتي من Google ونتذكر الاختيار.
     */
    fun startListening() {
        ConversationStore.setError(null)
        // المحادثة المباشرة (صوت طبيعي ومقاطعة وتنفيذ أثناء الكلام)
        if (com.alharith.ai.data.Prefs.liveAvailable) {
            if (hasMic()) AssistantService.send(this, AssistantService.ACTION_LIVE)
            else micAsk.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!com.alharith.ai.data.Prefs.voiceDialogFallback && android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            if (hasMic()) AssistantService.send(this, AssistantService.ACTION_LISTEN)
            else micAsk.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchVoiceDialog()
    }

    private fun launchVoiceDialog() {
        AssistantService.send(this, AssistantService.ACTION_STOP)
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "تكلّم… رفيق يسمعك")
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speech.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            ConversationStore.setError(
                "لا توجد خدمة إدخال صوتي على الهاتف. ثبّت تطبيق Google أو Google Voice Typing من المتجر، أو اكتب أمرك."
            )
        }
    }

    private var handledDialogRequest = 0L

    private var pendingListen: Boolean? = null
    private var pendingBriefing = false
    private var pendingBriefingKind: String? = null

    /** تنقل للمحادثة (تضبطه الواجهة) */
    var openChat: (() -> Unit)? = null

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> receiveShare(intent)
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN_NOW -> pendingListen = true
            ACTION_BRIEFING -> {
                pendingBriefing = true
                pendingBriefingKind = intent.getStringExtra(com.alharith.ai.service.BriefingReceiver.EXTRA_KIND)
            }
        }
    }

    /** ملف أو نص شاركه المستخدم من تطبيق آخر: يُنسخ محليًا ويُرفق بالطلب التالي. */
    private fun receiveShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri != null) {
            val name = displayName(uri)
            val local = runCatching { copyToCache(uri, name) }.getOrNull()
            SharedInbox.set(SharedInbox.Item(local ?: uri, text, name))
        } else if (!text.isNullOrBlank()) {
            SharedInbox.set(SharedInbox.Item(null, text, text.take(40)))
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "ملف"

    private fun copyToCache(uri: Uri, name: String): Uri {
        val dir = File(cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val out = File(dir, safe)
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    companion object {
        const val ACTION_LISTEN_NOW = "com.alharith.ai.LISTEN"
        const val ACTION_BRIEFING = "com.alharith.ai.OPEN_BRIEFING"

        /** هل الواجهة ظاهرة الآن؟ (فتح التطبيقات من الخلفية يحتاج صلاحية إضافية) */
        @Volatile
        var isVisible = false
            private set
    }
}
