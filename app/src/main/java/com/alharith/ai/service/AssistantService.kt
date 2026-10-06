package com.alharith.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.brain.Brain
import com.alharith.ai.data.AssistantState
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.PendingConfirmation
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedInbox
import com.alharith.ai.tools.Arabic
import com.alharith.ai.tools.Confirmer
import com.alharith.ai.tools.FileReader
import com.alharith.ai.tools.ToolEnv
import com.alharith.ai.tools.ToolRegistry
import com.alharith.ai.ui.MainActivity
import com.alharith.ai.voice.Listener
import com.alharith.ai.voice.Speaker
import com.alharith.ai.voice.WakeWordEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * الخدمة الأمامية التي تشغّل الحارث: تستمع لكلمة التنبيه، تحوّل الكلام لنص،
 * تمرره للعقل (Claude)، وتنطق الرد. كل الطلبات (صوت أو كتابة) تمر من هنا.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var voiceMode = false

    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var wake: WakeWordEngine
    private lateinit var brain: Brain
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        listener = Listener(this)
        wake = WakeWordEngine(this) { onWakeWord() }
        brain = Brain(ToolRegistry(ToolEnv(this, confirmer)))
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LISTEN -> startVoiceTurn()
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { startTextTurn(it) }
            ACTION_STOP -> { job?.cancel(); speaker.stop(); resumeWake() }
            ACTION_RELOAD -> { wake.stop(); resumeWake() }
            ACTION_RESET -> { job?.cancel(); speaker.stop(); brain.reset(); ConversationStore.clear(); resumeWake() }
            ACTION_SHUTDOWN -> { stopEverything(); return START_NOT_STICKY }
            else -> if (job?.isActive != true) resumeWake()
        }
        return START_STICKY
    }

    // ——— الخدمة الأمامية

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // غالبًا: صلاحية الميكروفون غير ممنوحة، أو بدء الخدمة من الخلفية
        ConversationStore.setError("تعذّر تشغيل الحارث في الخلفية: امنح صلاحية الميكروفون وافتح التطبيق.")
        false
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun svc(action: String, code: Int) = PendingIntent.getForegroundService(
            this, code, Intent(this, AssistantService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            wake.isRunning -> "قل \"يا الحارث\" في أي وقت"
            else -> "جاهز — اضغط \"تحدّث\""
        }
        return NotificationCompat.Builder(this, AlHarithApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("الحارث")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "تحدّث", svc(ACTION_LISTEN, 1))
            .addAction(0, "إيقاف الحارث", svc(ACTION_SHUTDOWN, 2))
            .build()
    }

    private fun refreshNotification() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    // ——— كلمة التنبيه

    private fun onWakeWord() {
        if (job?.isActive == true) return
        startVoiceTurn()
    }

    private fun resumeWake() {
        scope.launch {
            delay(350) // ننتظر تحرير الميكروفون من محرك التعرف
            if (job?.isActive == true) return@launch
            val ok = wake.start()
            if (!ok && Prefs.wakeWordEnabled && wake.lastError != null && Prefs.picovoiceKey.isNotBlank()) {
                ConversationStore.setError(wake.lastError)
            }
            ConversationStore.setState(if (ok) AssistantState.WAITING_WAKE else AssistantState.IDLE)
            refreshNotification()
        }
    }

    // ——— دورة صوتية

    private fun startVoiceTurn() {
        job?.cancel()
        speaker.stop()
        job = scope.launch {
            wake.stop()
            voiceMode = true
            ConversationStore.setError(null)
            try {
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                delay(180)
                var turns = 0
                while (true) {
                    ConversationStore.setState(AssistantState.LISTENING)
                    val text = when (val r = listener.listen()) {
                        is Listener.Result.Text -> stripWakeWord(r.text)
                        is Listener.Result.Silence -> ""
                        is Listener.Result.Error -> {
                            ConversationStore.setError(r.message)
                            ""
                        }
                    }
                    if (text.isBlank()) break
                    val reply = think(text)
                    ConversationStore.setState(AssistantState.SPEAKING)
                    speaker.speak(reply)
                    turns++
                    val asksBack = reply.trimEnd().let { it.endsWith("؟") || it.endsWith("?") }
                    if (!Prefs.followUpListening || !asksBack || turns >= 5) break
                }
            } finally {
                voiceMode = false
                ConversationStore.setPartial("")
                ConversationStore.setState(AssistantState.IDLE)
                resumeWake()
            }
        }
    }

    // ——— دورة كتابية

    private fun startTextTurn(text: String) {
        job?.cancel()
        speaker.stop()
        job = scope.launch {
            wake.stop()
            voiceMode = false
            ConversationStore.setError(null)
            try {
                val reply = think(text)
                if (Prefs.speakTypedReplies) {
                    ConversationStore.setState(AssistantState.SPEAKING)
                    speaker.speak(reply)
                }
            } finally {
                ConversationStore.setState(AssistantState.IDLE)
                resumeWake()
            }
        }
    }

    private suspend fun think(text: String): String {
        ConversationStore.user(text)
        ConversationStore.setState(AssistantState.THINKING)

        var attachments = emptyList<JSONObject>()
        var note: String? = null
        SharedInbox.take()?.let { item ->
            if (item.uri != null) {
                val r = FileReader.read(this, item.uri)
                attachments = r.attachments
                note = "المستخدم أرفق ملفًا: ${item.label}\n" + if (r.attachments.isEmpty()) r.text else ""
            }
            if (item.text != null) {
                note = (note.orEmpty() + "\nنص شاركه المستخدم:\n" + item.text.take(20_000)).trim()
            }
        }

        val reply = brain.handle(text, attachments, note)
        ConversationStore.assistant(reply)
        return reply
    }

    private fun stripWakeWord(s: String): String =
        s.replace(Regex("^\\s*(يا\\s*)?(ال)?حارث[،,.\\s]*"), "").trim()

    // ——— التأكيد قبل الإجراءات الحساسة (صوتًا أو بالأزرار)

    private val confirmer = object : Confirmer {
        override suspend fun confirm(question: String, detail: String): Boolean = askConfirmation(question, detail)
    }

    private suspend fun askConfirmation(question: String, detail: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        ConversationStore.showConfirmation(PendingConfirmation(question, detail, answer))
        val prev = ConversationStore.state.value
        ConversationStore.setState(AssistantState.CONFIRMING)
        val voiceJob = if (voiceMode) scope.launch {
            speaker.speak("$question، نعم أو لا؟")
            repeat(2) {
                if (answer.isCompleted) return@launch
                val r = listener.listen()
                val yes = (r as? Listener.Result.Text)?.let { parseYesNo(it.text) }
                if (yes != null) { answer.complete(yes); return@launch }
                if (it == 0) speaker.speak("قل نعم أو لا")
            }
        } else null
        return try {
            withTimeoutOrNull(45_000) { answer.await() } ?: false
        } finally {
            voiceJob?.cancel()
            ConversationStore.showConfirmation(null)
            ConversationStore.setState(prev)
        }
    }

    private fun parseYesNo(s: String): Boolean? {
        val words = Arabic.norm(s).split(' ').toSet()
        val no = setOf("لا", "لاء", "لأ", "الغ", "الغي", "الغاء", "وقف", "توقف", "كنسل", "no", "cancel", "stop", "بلاش")
        val yes = setOf(
            "نعم", "ايوه", "ايوا", "اي", "ايه", "اكيد", "تمام", "اوكي", "ok", "okay", "yes", "يس", "اكد",
            "ارسل", "ارسلها", "ارسله", "اتصل", "كمل", "موافق", "صح", "طيب", "يلا", "زين", "ابشر"
        )
        return when {
            words.any { it in no } -> false
            words.any { it in yes } -> true
            else -> null
        }
    }

    private fun stopEverything() {
        job?.cancel()
        wake.stop()
        speaker.stop()
        ConversationStore.setState(AssistantState.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        job?.cancel()
        wake.stop()
        speaker.shutdown()
        tone?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_LISTEN = "listen"
        const val ACTION_TEXT = "text"
        const val ACTION_STOP = "stop"
        const val ACTION_RELOAD = "reload"
        const val ACTION_RESET = "reset"
        const val ACTION_SHUTDOWN = "shutdown"
        const val EXTRA_TEXT = "text"
        private const val NOTIF_ID = 7

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, AssistantService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                ConversationStore.setError("تعذّر تشغيل خدمة الحارث: ${e.message}")
            }
        }
    }
}
