package com.alharith.ai.ui

import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.collectAsState
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // أيقونات شريط الحالة تتبع الوضع الفاتح/الداكن تلقائيًا
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIntent(intent)
        // من ثبّت رفيق سابقًا ولم يضبط ذكاءً: نربطه بخادم رفيق تلقائيًا في الخلفية (بلا شاشة ولا إدخال)
        if (com.alharith.ai.data.Prefs.onboarded && com.alharith.ai.data.Prefs.aiKeyMissing) {
            lifecycleScope.launch {
                val info = com.alharith.ai.data.Provisioning.info()
                if (info.available && !info.inviteRequired) com.alharith.ai.data.Provisioning.register("")
            }
        }
        // نحسبها الآن: التركيب يحدث بعد onResume الذي يستهلك pendingListen
        val startScreen = when {
            !com.alharith.ai.data.Prefs.onboarded && com.alharith.ai.data.Prefs.aiKeyMissing -> "onboarding"
            pendingListen == true || pendingBriefing -> "chat"
            intent?.getStringExtra(EXTRA_NAV) in NAV_TARGETS -> intent!!.getStringExtra(EXTRA_NAV)!!
            else -> "home"
        }
        setContent {
            HarithTheme {
                // التنقل: خمس وجهات رئيسية بشريط سفلي، وبقية الشاشات تُفتح فوقها ويعود زر الرجوع لما قبلها
                var stack by rememberSaveable {
                    // في نسخة الاختبار فقط: فتح شاشة محددة لالتقاط لقطات الشاشة آليًا
                    val first = if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("open_screen") ?: startScreen else startScreen
                    mutableStateOf(if (first == "home" || first == "onboarding") arrayListOf(first) else arrayListOf("home", first))
                }
                val screen = stack.last()
                fun go(to: String) {
                    stack = when {
                        to == "home" -> arrayListOf("home")
                        to in TABS -> arrayListOf("home", to)
                        else -> ArrayList(stack.filter { it != to } + to)
                    }
                }
                fun back() { stack = if (stack.size > 1) ArrayList(stack.dropLast(1)) else arrayListOf("home") }
                openChat = { go("chat") }
                navTo = { to -> if (to in NAV_TARGETS) go(to) }
                BackHandler(enabled = screen != "home") { back() }
                val home: () -> Unit = { go("home") }
                val showBar = screen in TABS
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(HarithColors.Bg)) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.weight(1f).then(
                            if (showBar) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                            else androidx.compose.ui.Modifier
                        )
                    ) {
                        androidx.compose.animation.AnimatedContent(screen, label = "screen", transitionSpec = {
                            val sub = targetState !in TABS && initialState in TABS || (targetState !in TABS && initialState !in TABS)
                            val backToTab = targetState in TABS && initialState !in TABS
                            when {
                                backToTab -> (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 6 }) togetherWith
                                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240)) { -it / 4 })
                                sub -> (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { -it / 4 }) togetherWith
                                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260)) { it / 6 })
                                else -> (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
                                    androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(240), initialScale = 0.985f)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                            }
                        }) { screen ->
                        when (screen) {
                            "onboarding" -> OnboardingScreen { tryVoice ->
                                if (tryVoice) { go("chat"); startListening() } else go("home")
                            }
                            "settings" -> SettingsScreen(
                                onBack = ::back, onOpenLog = { go("log") }, onOpenMemory = { go("memory") },
                                onOpenDiagnostics = { go("diagnostics") }, onOpenServer = { go("server") }
                            )
                            "diagnostics" -> DiagnosticsScreen(onBack = ::back, onOpenSettings = { go("settings") })
                            "log" -> ActivityScreen(onBack = ::back)
                            "memory" -> MemoryScreen(onBack = ::back)
                            "tasks" -> TasksScreen(onBack = null)
                            "notes" -> TasksScreen(onBack = ::back, initialTab = "notes")
                            "calendar" -> CalendarScreen(onBack = null, onOpenTasks = { go("tasks") })
                            "account" -> AccountScreen(
                                onOpenSettings = { go("settings") }, onOpenServer = { go("server") }, onOpenMemory = { go("memory") },
                                onOpenLog = { go("log") }, onOpenDiagnostics = { go("diagnostics") }, onOpenProjects = { go("projects") },
                                onOpenBusiness = { go("business") }, onOpenSearch = { go("search") }, onOpenNotes = { go("notes") }
                            )
                            "business" -> BusinessScreen(onBack = ::back, onOpenSettings = { go("server") })
                            "server" -> ServerScreen(onBack = ::back)
                            "projects" -> ProjectsScreen(onBack = ::back)
                            "search" -> SearchScreen(onBack = ::back, onOpenTasks = { go("tasks") }, onOpenBusiness = { go("business") })
                            "chat" -> ChatScreen(onOpenSettings = { go("settings") }, onOpenLog = { go("log") }, onBack = null)
                            else -> HomeScreen(
                                onVoice = { go("chat"); startListening() },
                                onAsk = { text, label ->
                                    go("chat")
                                    AssistantService.send(this@MainActivity, AssistantService.ACTION_TEXT, text, speak = false, display = label)
                                },
                                onOpenChat = { go("chat") },
                                onOpenTasks = { go("tasks") },
                                onOpenBusiness = { go("business") },
                                onOpenProjects = { go("projects") },
                                onOpenSearch = { go("search") },
                                onOpenSettings = { go("settings") },
                                onOpenAccount = { go("account") },
                                onOpenCalendar = { go("calendar") }
                            )
                        }
                        }
                        CelebrationOverlay()
                        // رابط دعوة فُتح من صفحة التحميل: نافذة الانضمام معبّأة
                        val invite by PendingInvite.link.collectAsState()
                        invite?.let { l ->
                            InviteJoinDialog(initialLink = l, onDismiss = { PendingInvite.link.value = null }) {
                                PendingInvite.link.value = null
                                com.alharith.ai.data.Prefs.onboarded = true
                                go("home")
                            }
                        }
                    }
                    if (showBar && !androidx.compose.foundation.layout.WindowInsets.isImeVisible) RafiqBottomBar(screen) { go(it) }
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
        // في نسخة الاختبار فقط: اختبار استيراد خط من ملف (نفس مسار «خط من جوالي»)
        if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("import_font")?.let { n ->
            val (ok, msg) = HeadFonts.importFont(this, android.net.Uri.fromFile(java.io.File(filesDir, n)))
            android.util.Log.i("RafiqFont", "import_font result=" + (if (ok) "OK " + HeadFonts.custom?.name else "ERR") + " " + msg)
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
        if (com.alharith.ai.BuildConfig.DEBUG && intent?.getBooleanExtra("unlink_server", false) == true) {
            com.alharith.ai.data.Prefs.sharedBrain = false
            com.alharith.ai.data.Prefs.serverToken = ""
        }
        if (com.alharith.ai.BuildConfig.DEBUG) intent?.getStringExtra("provision_url")?.let { com.alharith.ai.data.Prefs.provisionUrl = it }
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
    /** فتح شاشة محددة عند لمس إشعار والتطبيق مفتوح أصلًا */
    var navTo: ((String) -> Unit)? = null

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // من الإشعار: نفتح الشاشة المرتبطة، إلا أثناء الإعداد الأول (لا نقطعه)
        val inSetup = !com.alharith.ai.data.Prefs.onboarded && com.alharith.ai.data.Prefs.aiKeyMissing
        intent.getStringExtra(EXTRA_NAV)?.let { to -> if (!inSetup && to in NAV_TARGETS) navTo?.invoke(to) }
        // نسخة الاختبار فقط: إنشاء تذكير حقيقي عبر AlarmManager لفحص الإشعارات آليًا على المحاكي
        if (com.alharith.ai.BuildConfig.DEBUG) {
            val inS = intent.getIntExtra("debug_reminder_in_s", -1)
            if (inS >= 0) {
                val r = com.alharith.ai.data.ReminderItem(com.alharith.ai.data.LocalStore.newId(),
                    intent.getStringExtra("debug_text") ?: "CI reminder", System.currentTimeMillis() + inS * 1000L)
                com.alharith.ai.data.LocalStore.upsertReminder(r)
                com.alharith.ai.service.Reminders.schedule(this, r)
            }
            if (intent.getBooleanExtra("debug_missed_reminder", false)) {
                val r = com.alharith.ai.data.ReminderItem(com.alharith.ai.data.LocalStore.newId(),
                    intent.getStringExtra("debug_text") ?: "CI missed", System.currentTimeMillis() - 5 * 60_000L)
                com.alharith.ai.data.LocalStore.upsertReminder(r)
                com.alharith.ai.service.Reminders.rescheduleAll(this)
            }
        }
        when (intent.action) {
            Intent.ACTION_SEND -> receiveShare(intent)
            Intent.ACTION_VIEW -> intent.data?.let { u ->
                if (u.scheme == "rafiq" && u.host == "join") PendingInvite.link.value = u.getQueryParameter("u").orEmpty()
            }
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
        /** الوجهات الرئيسية في الشريط السفلي */
        val TABS = setOf("home", "tasks", "chat", "calendar", "account")
        /** الشاشات التي يجوز أن يفتحها لمس الإشعار (قائمة محددة، لا شيء غيرها) */
        const val EXTRA_NAV = "nav"
        val NAV_TARGETS = setOf("home", "tasks", "chat", "calendar", "projects", "business")
        const val ACTION_LISTEN_NOW = "com.alharith.ai.LISTEN"
        const val ACTION_BRIEFING = "com.alharith.ai.OPEN_BRIEFING"

        /** هل الواجهة ظاهرة الآن؟ (فتح التطبيقات من الخلفية يحتاج صلاحية إضافية) */
        @Volatile
        var isVisible = false
            private set
    }
}
