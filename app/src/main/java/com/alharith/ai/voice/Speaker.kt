package com.alharith.ai.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.alharith.ai.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** نطق عربي عبر محرك Android الرسمي (Google TTS أو غيره). */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onError(utteranceId: String, errorCode: Int) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
        })
    }

    private suspend fun ensureReady(): Boolean {
        val ok = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (ok) {
            val sa = Locale("ar", "SA")
            val r = tts.setLanguage(sa)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale("ar"))
            }
            tts.setSpeechRate(Prefs.speechRate)
        }
        return ok
    }

    /** ينطق النص وينتظر حتى ينتهي. الإلغاء يوقف النطق فورًا. */
    suspend fun speak(raw: String) {
        val text = clean(raw)
        if (text.isBlank() || !ensureReady()) return
        val chunks = chunk(text, (TextToSpeech.getMaxSpeechInputLength() - 50).coerceAtLeast(500))
        var last: CompletableDeferred<Unit>? = null
        chunks.forEachIndexed { i, c ->
            val id = "u${counter++}"
            val d = CompletableDeferred<Unit>()
            pending[id] = d
            last = d
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
        val waitFor = last ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { tts.stop() }
            waitFor.invokeOnCompletion { if (cont.isActive) cont.resume(Unit) }
        }
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.stop(); tts.shutdown() }

    private fun clean(s: String) = s
        .replace(Regex("[*_#`>|]+"), " ")
        .replace(Regex("https?://\\S+"), "رابط")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun chunk(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (part in s.split(Regex("(?<=[.!؟?،\\n])"))) {
            if (sb.length + part.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(part)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
