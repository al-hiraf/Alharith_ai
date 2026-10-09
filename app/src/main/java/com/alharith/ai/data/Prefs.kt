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
    var geminiApiKey: String get() = cleanKey(str("gemini_key"), GEMINI_KEY); set(v) = put("gemini_key", v)
    var geminiModel: String get() = str("gemini_model", GEMINI_MODELS.first().first); set(v) = put("gemini_model", v.trim())
    var geminiFastModel: String get() = str("gemini_fast", "gemini-flash-lite-latest"); set(v) = put("gemini_fast", v.trim())

    // ——— OpenAI
    var openaiApiKey: String get() = cleanKey(str("openai_key"), OPENAI_KEY); set(v) = put("openai_key", v)
    var openaiModel: String get() = str("openai_model", OPENAI_MODELS.first().first); set(v) = put("openai_model", v.trim())
    var openaiFastModel: String get() = str("openai_fast", "gpt-4.1-mini"); set(v) = put("openai_fast", v.trim())

    // ——— إعدادات عامة لكل مزوّد (المفتاح والنموذج محفوظان لكل مزوّد على حدة)
    private fun keyPref(id: String) = when (id) { "gemini" -> "gemini_key"; "openai" -> "openai_key"; "claude" -> "claude_key"; else -> "key_$id" }
    private fun modelPref(id: String) = when (id) { "gemini" -> "gemini_model"; "openai" -> "openai_model"; "claude" -> "claude_model"; else -> "model_$id" }

    fun keyFor(id: String): String = when (id) {
        "gemini" -> geminiApiKey
        "openai" -> openaiApiKey
        "claude" -> claudeApiKey
        else -> str(keyPref(id)).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    }
    fun setKeyFor(id: String, v: String) = put(keyPref(id), v)
    fun rawKeyFor(id: String): String = str(keyPref(id))

    fun modelFor(id: String): String =
        str(modelPref(id)).trim().ifBlank { Providers.byId(id).models.firstOrNull()?.first.orEmpty() }
    fun setModelFor(id: String, v: String) = put(modelPref(id), v.trim())

    /** الرابط الأساسي للمزوّد المخصص، مثل http://192.168.1.10:11434/v1 */
    var customBaseUrl: String get() = str("custom_base"); set(v) = put("custom_base", v.trim().trimEnd('/'))

    val currentProvider get() = Providers.byId(provider)

    /** مزوّد احتياطي يُستخدم تلقائيًا إذا تعطل الأساسي (فارغ = بدون) */
    var fallbackProviderId: String get() = str("fallback_provider"); set(v) = put("fallback_provider", v)
    val fallbackProvider: Provider?
        get() = fallbackProviderId.takeIf { it.isNotBlank() && it != provider }
            ?.let { Providers.byId(it) }
            ?.takeIf { keyFor(it.id).isNotBlank() || it.keyOptional }
    val aiKeyMissing get() = keyFor(provider).isBlank() && !currentProvider.keyOptional
    val providerLabel get() = currentProvider.name.substringBefore(" (")

    // ——— Claude
    var claudeApiKey: String get() = cleanKey(str("claude_key"), CLAUDE_KEY); set(v) = put("claude_key", v)
    var claudeModel: String get() = str("claude_model", MODELS.first().first); set(v) = put("claude_model", v)

    // ——— كلمة التنبيه
    var picovoiceKey: String get() = str("pv_key"); set(v) = put("pv_key", v.trim())
    var wakeWordEnabled: Boolean get() = bool("wake_on", false); set(v) = putB("wake_on", v)
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

    // ——— المراجعة المسائية "ماذا أنجزت اليوم؟"
    var eveningEnabled: Boolean get() = bool("eve_on", false); set(v) = putB("eve_on", v)
    var eveningTime: String get() = str("eve_time", "21:00"); set(v) = put("eve_time", v)

    // ——— زر الصوت الخارجي لاستدعاء الحارث
    var volumeTrigger: Boolean get() = bool("vol_trigger", true); set(v) = putB("vol_trigger", v)
    var volumeMode: String get() = str("vol_mode", "long_up"); set(v) = put("vol_mode", v)

    // ——— الذاكرة الشخصية
    var memoryEnabled: Boolean get() = bool("memory_on", true); set(v) = putB("memory_on", v)

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

    // ——— العقل المشترك (خادم الحارث على Termux أو VPS)
    var sharedBrain: Boolean get() = bool("shared_on", false); set(v) = putB("shared_on", v)
    var serverUrl: String get() = str("server_url", "http://127.0.0.1:8787"); set(v) = put("server_url", v.trim().trimEnd('/'))
    var serverToken: String get() = str("server_token"); set(v) = put("server_token", v.trim())
    var syncCursor: String get() = str("sync_cursor"); set(v) = put("sync_cursor", v)
    var lastPushMs: Long get() = sp.getLong("sync_push_ms", 0L); set(v) = sp.edit().putLong("sync_push_ms", v).apply()
    var lastNoticeId: Long get() = sp.getLong("notice_id", -1L); set(v) = sp.edit().putLong("notice_id", v).apply()
    var lastSyncStatus: String get() = str("sync_status"); set(v) = put("sync_status", v)
    val sharedBrainReady get() = sharedBrain && serverUrl.isNotBlank() && serverToken.isNotBlank()

    private val GEMINI_KEY = Regex("AIza[0-9A-Za-z_\\-]{30,}")
    private val OPENAI_KEY = Regex("sk-[0-9A-Za-z_\\-]{20,}")
    private val CLAUDE_KEY = Regex("sk-ant-[0-9A-Za-z_\\-]{20,}")

    /** يستخرج المفتاح حتى لو لُصق معه نص أو كود أو أسطر زائدة */
    private fun cleanKey(raw: String, pattern: Regex): String =
        pattern.find(raw)?.value ?: raw.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

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
