package com.alharith.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.alharith.ai.data.ConversationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * تحويل الكلام إلى نص عبر SpeechRecognizer الرسمي في Android.
 * يُفضَّل العربية السعودية مع قبول بقية اللهجات العربية.
 */
class Listener(private val context: Context) {

    sealed class Result {
        data class Text(val text: String) : Result()
        object Silence : Result()
        data class Error(val message: String) : Result()
    }

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listen(): Result = withContext<Result>(Dispatchers.Main) {
        if (!isAvailable()) {
            return@withContext Result.Error("خدمة التعرف على الكلام غير متوفرة. ثبّت تطبيق Google أو فعّل الكتابة الصوتية.")
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine<Result> { cont ->
                cont.invokeOnCancellation {
                    // يُستدعى على أي خيط؛ نعيد الإلغاء إلى الخيط الرئيسي
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { recognizer.cancel() }
                    }
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { ConversationStore.setPartial("") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle) {
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let { ConversationStore.setPartial(it) }
                    }

                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        ConversationStore.setPartial("")
                        if (cont.isActive) cont.resume(if (text.isBlank()) Result.Silence else Result.Text(text))
                    }

                    override fun onError(error: Int) {
                        ConversationStore.setPartial("")
                        val r = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.Silence
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Result.Error("مشكلة في الاتصال بالإنترنت")
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Result.Error("صلاحية الميكروفون غير ممنوحة")
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Result.Error("خدمة التعرف مشغولة، حاول مرة أخرى")
                            else -> Result.Error("تعذّر التعرف على الكلام (رمز $error)")
                        }
                        if (cont.isActive) cont.resume(r)
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
                recognizer.startListening(intent)
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }
}
