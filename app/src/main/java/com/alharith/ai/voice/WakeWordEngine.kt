package com.alharith.ai.voice

import android.content.Context
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import com.alharith.ai.data.Prefs
import java.io.File

/**
 * كلمة التنبيه "يا رفيق" عبر Picovoice Porcupine (تعمل على الجهاز بدون إنترنت).
 * ملف الكلمة (.ppn) يُدرَّب مجانًا في Picovoice Console ثم يُستورد من الإعدادات.
 */
class WakeWordEngine(private val context: Context, private val onWake: () -> Unit) {

    private var manager: PorcupineManager? = null
    var lastError: String? = null
        private set

    val isRunning get() = manager != null

    fun isConfigured(): Boolean =
        Prefs.picovoiceKey.isNotBlank() && keywordFile(context).exists()

    fun start(): Boolean {
        if (manager != null) return true
        if (!Prefs.wakeWordEnabled) return false
        if (!isConfigured()) {
            lastError = "كلمة التنبيه غير مُعدّة: أضف مفتاح Picovoice واستورد ملف الكلمة من الإعدادات."
            return false
        }
        return try {
            val m = PorcupineManager.Builder()
                .setAccessKey(Prefs.picovoiceKey)
                .setKeywordPath(keywordFile(context).absolutePath)
                .apply { modelFile(context).takeIf { it.exists() }?.let { setModelPath(it.absolutePath) } }
                .setSensitivity(Prefs.wakeSensitivity)
                .setErrorCallback { e -> lastError = e.message }
                .build(context, PorcupineManagerCallback { onWake() })
            m.start()
            manager = m
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "تعذّر تشغيل كلمة التنبيه: ${e.message}"
            false
        }
    }

    fun stop() {
        val m = manager ?: return
        manager = null
        runCatching { m.stop() }
        runCatching { m.delete() }
    }

    companion object {
        fun keywordFile(context: Context) = File(context.filesDir, "wake_word.ppn")
        /** نموذج اللغة (porcupine_params_xx.pv) — مطلوب فقط إن كانت لغة الكلمة غير الإنجليزية */
        fun modelFile(context: Context) = File(context.filesDir, "wake_model.pv")
    }
}
