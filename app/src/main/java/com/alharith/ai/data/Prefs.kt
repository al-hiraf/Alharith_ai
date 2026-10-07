package com.alharith.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * إعدادات التطبيق. المفاتيح وكلمات المرور تُحفظ مشفّرة على الجهاز فقط.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "harith_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // في حال فشل مخزن المفاتيح على بعض الأجهزة
            context.getSharedPreferences("harith_plain", Context.MODE_PRIVATE)
        }
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // ——— الهوية
    var userName: String get() = str("user_name", "مهند"); set(v) = put("user_name", v)

    // ——— مزوّد الذكاء الاصطناعي: gemini | openai | claude
    var provider: String get() = str("provider", "gemini"); set(v) = put("provider", v)

    // ——— Google Gemini
    var geminiApiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v.trim())
    var geminiModel: String get() = str("gemini_model", GEMINI_MODELS.first().first); set(v) = put("gemini_model", v.trim())
    var geminiFastModel: String get() = str("gemini_fast", "gemini-flash-lite-latest"); set(v) = put("gemini_fast", v.trim())

    // ——— OpenAI
    var openaiApiKey: String get() = str("openai_key"); set(v) = put("openai_key", v.trim())
    var openaiModel: String get() = str("openai_model", OPENAI_MODELS.first().first); set(v) = put("openai_model", v.trim())
    var openaiFastModel: String get() = str("openai_fast", "gpt-4.1-mini"); set(v) = put("openai_fast", v.trim())

    val aiKeyMissing get() = when (provider) {
        "openai" -> openaiApiKey.isBlank()
        "claude" -> claudeApiKey.isBlank()
        else -> geminiApiKey.isBlank()
    }

    val providerLabel get() = when (provider) { "openai" -> "OpenAI"; "claude" -> "Claude"; else -> "Gemini" }

    // ——— Claude
    var claudeApiKey: String get() = str("claude_key"); set(v) = put("claude_key", v.trim())
    var claudeModel: String get() = str("claude_model", MODELS.first().first); set(v) = put("claude_model", v)

    // ——— كلمة التنبيه
    var picovoiceKey: String get() = str("pv_key"); set(v) = put("pv_key", v.trim())
    var wakeWordEnabled: Boolean get() = bool("wake_on", true); set(v) = putB("wake_on", v)
    var wakeSensitivity: Float
        get() = sp.getFloat("wake_sens", 0.6f)
        set(v) = sp.edit().putFloat("wake_sens", v).apply()

    // ——— الأمان والتأكيد
    var confirmCalls: Boolean get() = bool("confirm_calls", true); set(v) = putB("confirm_calls", v)
    var confirmMessages: Boolean get() = bool("confirm_msgs", true); set(v) = putB("confirm_msgs", v)
    var confirmEmails: Boolean get() = bool("confirm_emails", true); set(v) = putB("confirm_emails", v)

    // ——— الصوت
    var speakTypedReplies: Boolean get() = bool("speak_typed", false); set(v) = putB("speak_typed", v)
    var followUpListening: Boolean get() = bool("follow_up", true); set(v) = putB("follow_up", v)
    var speechRate: Float
        get() = sp.getFloat("tts_rate", 1.0f)
        set(v) = sp.edit().putFloat("tts_rate", v).apply()

    // ——— الموجز الصباحي
    var briefingEnabled: Boolean get() = bool("brief_on", false); set(v) = putB("brief_on", v)
    var briefingTime: String get() = str("brief_time", "07:00"); set(v) = put("brief_time", v)

    // ——— تنبيه الرسائل المهمة (مع رد مقترح يُرسل فقط بضغطة منك)
    var importantAlerts: Boolean get() = bool("imp_alerts", false); set(v) = putB("imp_alerts", v)

    // ——— البريد
    var emailAddress: String get() = str("mail_addr"); set(v) = put("mail_addr", v.trim())
    var emailPassword: String get() = str("mail_pass"); set(v) = put("mail_pass", v.replace(" ", ""))
    var imapHost: String get() = str("imap_host", "imap.gmail.com"); set(v) = put("imap_host", v.trim())
    var imapPort: String get() = str("imap_port", "993"); set(v) = put("imap_port", v.trim())
    var smtpHost: String get() = str("smtp_host", "smtp.gmail.com"); set(v) = put("smtp_host", v.trim())
    var smtpPort: String get() = str("smtp_port", "465"); set(v) = put("smtp_port", v.trim())

    // ——— الملفات (مجلد يختاره المستخدم عبر Storage Access Framework)
    var filesTreeUri: String get() = str("files_tree"); set(v) = put("files_tree", v)

    val emailConfigured get() = emailAddress.isNotBlank() && emailPassword.isNotBlank()

    val GEMINI_MODELS = listOf(
        "gemini-flash-latest" to "Gemini Flash (أحدث إصدار) — سريع وذكي (مُوصى به)",
        "gemini-flash-lite-latest" to "Gemini Flash-Lite — الأسرع والأرخص",
        "gemini-pro-latest" to "Gemini Pro — الأقوى (أبطأ)",
        "gemini-2.5-flash" to "Gemini 2.5 Flash"
    )

    val OPENAI_MODELS = listOf(
        "gpt-4.1" to "GPT-4.1 — سريع وذكي (مُوصى به للصوت)",
        "gpt-4.1-mini" to "GPT-4.1 mini — الأسرع والأرخص",
        "gpt-5-mini" to "GPT-5 mini — تفكير أعمق بتكلفة منخفضة",
        "gpt-5" to "GPT-5 — الأقوى (أبطأ)",
        "gpt-4o" to "GPT-4o"
    )

    /** (المعرّف، الاسم المعروض) */
    val MODELS = listOf(
        "claude-sonnet-5-5" to "Sonnet 5.5 — سريع وذكي (مُوصى به)",
        "claude-haiku-4-5-20251001" to "Haiku 4.5 — الأسرع والأرخص",
        "claude-opus-5-5" to "Opus 5.5 — الأقوى"
    )
}
