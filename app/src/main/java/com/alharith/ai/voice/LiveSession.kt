package com.alharith.ai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import com.alharith.ai.data.AssistantState
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * محادثة صوتية مباشرة مع Gemini Live: يسمعك ويرد فورًا بصوت طبيعي، يمكنك مقاطعته،
 * ويستطيع تنفيذ أدوات رفيق (اتصال، رسائل، مهام، تذكيرات…) أثناء الكلام.
 */
class LiveSession(
    private val context: Context,
    private val registry: ToolRegistry,
    private val systemPrompt: String,
    private val scope: CoroutineScope,
    private val onEnded: (error: String?) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micJob: Job? = null
    private var playJob: Job? = null
    private val playback = Channel<ByteArray>(Channel.UNLIMITED)
    private val ended = AtomicBoolean(false)
    @Volatile private var speaking = false
    @Volatile private var lastAudioAt = 0L
    private var setupDone: CompletableDeferred<Boolean>? = null
    private val userBuf = StringBuilder()
    private val botBuf = StringBuilder()
    private var savedMode = AudioManager.MODE_NORMAL

    /** موافقة صوتية معلّقة (تُحسم بكلمة "نعم/لا" من المستخدم أو بزر البطاقة) */
    @Volatile var pendingConfirm: CompletableDeferred<Boolean>? = null

    val isActive get() = ws != null && !ended.get()

    /** يجرب النماذج بالترتيب حتى يقبل أحدها الاتصال */
    suspend fun start(): String? {
        val key = Prefs.keyFor("gemini")
        if (key.isBlank()) return "المحادثة المباشرة تحتاج مفتاح Google Gemini"
        val models = (listOf(Prefs.liveModel) + LIVE_MODELS).filter { it.isNotBlank() }.distinct()
        var lastErr = "تعذّر الاتصال"
        for (m in models) {
            val err = connect(key, m)
            if (err == null) {
                Prefs.liveModel = m
                startAudio()
                ConversationStore.setState(AssistantState.LISTENING)
                return null
            }
            lastErr = err
        }
        return "تعذّر بدء المحادثة المباشرة: $lastErr"
    }

    private suspend fun connect(key: String, model: String): String? {
        val done = CompletableDeferred<Boolean>()
        setupDone = done
        var failure: String? = null
        val req = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key")
            .build()
        val socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                failure = reason.ifBlank { "أُغلق الاتصال ($code)" }
                if (!done.isCompleted) done.complete(false) else end(if (code == 1000) null else failure)
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t.message ?: "خطأ في الاتصال"
                if (!done.isCompleted) done.complete(false) else end("انقطع الاتصال: $failure")
            }
        })
        val ok = withTimeoutOrNull(10_000) { done.await() } ?: false
        return if (ok) { ws = socket; null } else { socket.cancel(); failure ?: "انتهت المهلة" }
    }

    private fun setupMessage(model: String): JSONObject {
        val tools = com.alharith.ai.brain.GeminiClient().convertTools(registry.definitions())
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.liveVoice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup)
    }

    // ——— الرسائل الواردة
    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            msg.has("setupComplete") -> setupDone?.complete(true)
            msg.has("serverContent") -> onContent(msg.getJSONObject("serverContent"))
            msg.has("toolCall") -> onToolCall(msg.getJSONObject("toolCall"))
            msg.has("goAway") -> scope.launch {
                ConversationStore.action("انتهت مدة المحادثة المباشرة، اضغط الدائرة للبدء من جديد")
                end(null)
            }
        }
    }

    private fun onContent(c: JSONObject) {
        c.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            synchronized(userBuf) { userBuf.append(t) }
            ConversationStore.setPartial(synchronized(userBuf) { userBuf.toString() })
            pendingConfirm?.let { d -> parseYesNo(synchronized(userBuf) { userBuf.toString() })?.let { d.complete(it) } }
        }
        c.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            flushUser()
            synchronized(botBuf) { botBuf.append(t) }
        }
        c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                if (data.isNotEmpty()) {
                    flushUser()
                    playback.trySend(Base64.decode(data, Base64.DEFAULT))
                }
            }
        }
        if (c.optBoolean("interrupted")) {
            // المستخدم قاطع رفيق: نوقف الصوت فورًا
            while (playback.tryReceive().isSuccess) { /* تفريغ */ }
            runCatching { track?.pause(); track?.flush(); track?.play() }
            speaking = false
            flushBot(interrupted = true)
            ConversationStore.setState(AssistantState.LISTENING)
        }
        if (c.optBoolean("turnComplete")) {
            flushUser()
            flushBot(interrupted = false)
        }
    }

    private fun flushUser() {
        val u = synchronized(userBuf) { userBuf.toString().trim().also { userBuf.clear() } }
        if (u.isNotEmpty()) ConversationStore.user(u)
        ConversationStore.setPartial("")
    }

    private fun flushBot(interrupted: Boolean) {
        val b = synchronized(botBuf) { botBuf.toString().trim().also { botBuf.clear() } }
        if (b.isNotEmpty()) ConversationStore.assistant(if (interrupted) "$b…" else b)
    }

    private fun onToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        scope.launch {
            ConversationStore.setState(AssistantState.THINKING)
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val fc = calls.getJSONObject(i)
                val name = fc.optString("name")
                ConversationStore.action(registry.label(name) + "…")
                val r = registry.run(name, fc.optJSONObject("args") ?: JSONObject())
                responses.put(JSONObject().put("id", fc.optString("id")).put("name", name)
                    .put("response", JSONObject().put("result", (if (r.isError) "خطأ: " else "") + r.text.take(6000))))
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            ConversationStore.setState(AssistantState.LISTENING)
        }
    }

    // ——— الصوت
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val am = context.getSystemService(AudioManager::class.java)
        savedMode = am.mode
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        }
        val inRate = 16000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400))
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        recorder = rec

        val outRate = 24000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minOut * 4, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        tr.play()
        track = tr

        rec.startRecording()
        micJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(640) // 40ms
            val bytes = ByteArray(buf.size * 2)
            while (isActive && !ended.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val lvl = rms(buf, n)
                // أثناء كلام رفيق نرسل الصوت فقط إن كان المستخدم يتكلم بوضوح (للمقاطعة)، لتفادي سماع صدى رفيق
                val talkingBack = speaking && System.currentTimeMillis() - lastAudioAt < 600
                if (!talkingBack) ConversationStore.setLevel(lvl)
                if (talkingBack && lvl < 0.55f) continue
                for (i in 0 until n) { bytes[2 * i] = (buf[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                val b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP)
                ws?.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$b64\"}}}")
            }
        }
        playJob = scope.launch(Dispatchers.IO) {
            for (chunk in playback) {
                if (ended.get()) break
                speaking = true
                lastAudioAt = System.currentTimeMillis()
                ConversationStore.setState(AssistantState.SPEAKING)
                ConversationStore.setLevel(rmsBytes(chunk))
                track?.write(chunk, 0, chunk.size)
                if (playback.isEmpty) {
                    kotlinx.coroutines.delay(250)
                    if (playback.isEmpty) {
                        speaking = false
                        if (ConversationStore.state.value == AssistantState.SPEAKING) ConversationStore.setState(AssistantState.LISTENING)
                    }
                }
            }
        }
    }

    /** نص مكتوب أثناء المحادثة المباشرة */
    fun sendText(text: String) {
        ConversationStore.user(text)
        ws?.send(JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true)).toString())
    }

    fun end(error: String?) {
        if (!ended.compareAndSet(false, true)) return
        runCatching { ws?.close(1000, "bye") }
        ws = null
        micJob?.cancel(); playJob?.cancel()
        playback.close()
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }
        runCatching { track?.stop() }; runCatching { track?.release() }
        recorder = null; track = null
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            am.mode = savedMode
        }
        pendingConfirm?.complete(false)
        flushUser(); flushBot(false)
        ConversationStore.setLevel(0f)
        onEnded(error)
    }

    private fun rms(b: ShortArray, n: Int): Float {
        var s = 0.0
        for (i in 0 until n) s += b[i] * b[i].toDouble()
        val r = sqrt(s / n) / 32768.0
        return (r * 6).toFloat().coerceIn(0f, 1f)
    }

    private fun rmsBytes(b: ByteArray): Float {
        var s = 0.0; var n = 0
        var i = 0
        while (i + 1 < b.size) { val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); s += v.toShort() * v.toShort().toDouble(); n++; i += 2 }
        return if (n == 0) 0f else (sqrt(s / n) / 32768.0 * 5).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** نماذج Gemini Live بالترتيب (يُجرَّب التالي إن رفض الخادم السابق) */
        val LIVE_MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-flash-native-audio-preview-09-2025"
        )

        /** أصوات رجالية من Gemini */
        val MALE_VOICES = listOf("Charon" to "شارون — عميق وهادئ", "Orus" to "أوروس — حازم", "Fenrir" to "فنرير — حيوي", "Puck" to "بك — مرح")

        fun parseYesNo(s: String): Boolean? {
            val words = com.alharith.ai.tools.Arabic.norm(s).split(' ', '،', ',', '.').toSet()
            val no = setOf("لا", "لاء", "لأ", "الغ", "الغي", "الغاء", "وقف", "كنسل", "no", "cancel", "بلاش")
            val yes = setOf("نعم", "ايوه", "ايوا", "اي", "ايه", "اكيد", "تمام", "اوكي", "ok", "yes", "اكد", "ارسل", "ارسلها",
                "اتصل", "كمل", "موافق", "طيب", "يلا", "زين", "ابشر")
            return when {
                words.any { it in no } -> false
                words.any { it in yes } -> true
                else -> null
            }
        }
    }
}
