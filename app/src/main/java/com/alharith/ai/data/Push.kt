package com.alharith.ai.data

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * إشعارات فورية عبر FCM بلا ملف google-services في الكود:
 * إعداد Firebase (غير سري) يطلبه التطبيق من خادم رفيق بعد الدخول ويحفظه، ثم يُسجَّل رمز الجهاز على الخادم.
 * الخادم يرسل رسالة «إيقاظ» بلا نص، فيجلب التطبيق الإشعارات بمفتاح المستخدم (SharedBrain).
 * إن لم يفعّل الخادم الميزة أو تعذّر Firebase يبقى الجلب الدوري (WorkManager) كما كان.
 */
object Push {
    /** عند بدء العملية: أعد تهيئة Firebase من الإعداد المحفوظ (تحتاجه خدمة الاستقبال بعد قتل التطبيق) */
    @Volatile private var appCtx: Context? = null

    fun initFromCache(ctx: Context) {
        appCtx = ctx.applicationContext
        runCatching {
            val cfg = Prefs.pushConfig
            if (cfg.isNotBlank()) init(ctx.applicationContext, JSONObject(cfg))
        }
    }

    private fun init(ctx: Context, c: JSONObject): Boolean {
        if (!c.optBoolean("enabled")) return false
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return true
        val o = FirebaseOptions.Builder()
            .setApplicationId(c.getString("app_id"))
            .setApiKey(c.getString("api_key"))
            .setProjectId(c.getString("project_id"))
            .setGcmSenderId(c.getString("sender_id"))
            .build()
        FirebaseApp.initializeApp(ctx, o)
        return true
    }

    private suspend fun fetchToken(): String? = suspendCoroutine { k ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
            k.resume(if (t.isSuccessful) t.result else null)
        }
    }

    private fun key(token: String) = PushPolicy.regKey(Prefs.serverUrl, Prefs.serverToken, token)

    /** يضمن تسجيل رمز هذا الجهاز على الخادم (مرة لكل رمز/حساب، ويُجدَّد أسبوعيًا). آمن للاستدعاء المتكرر. */
    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        if (!Prefs.sharedBrainReady) return@withContext
        val app = ctx.applicationContext
        // الإعداد المحفوظ يكفي بعد أول مرة: لا طلب شبكة إلا لتسجيل رمز جديد أو التجديد الأسبوعي
        val cached = runCatching { JSONObject(Prefs.pushConfig) }.getOrNull()
        val cfg = if (cached?.optBoolean("enabled") == true) cached
            else SharedBrain.request("GET", "/api/push/config").also { if (it.optBoolean("enabled")) Prefs.pushConfig = it.toString() }
        if (!cfg.optBoolean("enabled")) return@withContext
        if (!init(app, cfg)) return@withContext
        val token = fetchToken() ?: return@withContext
        val k = key(token)
        if (!PushPolicy.needsRegister(Prefs.pushRegKey, Prefs.pushRegAt, System.currentTimeMillis(), k)) return@withContext
        SharedBrain.request("POST", "/api/push/register",
            JSONObject().put("token", token).put("label", Build.MODEL ?: "Android"))
        Prefs.pushRegKey = k
        Prefs.pushRegAt = System.currentTimeMillis()
    }

    /** رمز جديد من Firebase: سجّله إن كان الحساب مربوطًا */
    suspend fun onNewToken(token: String) {
        if (!Prefs.sharedBrainReady) return
        SharedBrain.request("POST", "/api/push/register",
            JSONObject().put("token", token).put("label", Build.MODEL ?: "Android"))
        Prefs.pushRegKey = key(token)
        Prefs.pushRegAt = System.currentTimeMillis()
    }

    /**
     * فك ارتباط هذا الجهاز بالحساب الحالي: (1) إلغاء تسجيل الرمز على الخادم (بأفضل جهد ومهلة قصيرة)،
     * (2) حذف رمز FCM محليًا فيُصدر Firebase رمزًا جديدًا ولا يصل الرمز القديم لأي حساب،
     * (3) مسح حالة التسجيل. يُستدعى قبل تغيير/مسح مفتاح الخادم (خروج، فك ربط، تبديل حساب، حذف حساب).
     * ملاحظة: الخادم يربط الرمز بآخر حساب سجّله، فحتى لو فشل الإلغاء لا يُسرَّب شيء، والرسالة بلا نص.
     */
    suspend fun release(serverSide: Boolean = true) = withContext(Dispatchers.IO) {
        val app = appCtx
        val hadReg = Prefs.pushRegKey.isNotBlank()
        if (hadReg && app != null && FirebaseApp.getApps(app).isNotEmpty()) {
            val token = kotlinx.coroutines.withTimeoutOrNull(5_000) { fetchToken() }
            if (serverSide && token != null && Prefs.sharedBrainReady) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    runCatching { SharedBrain.request("DELETE", "/api/push/register", JSONObject().put("token", token)) }
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                suspendCoroutine<Unit> { k ->
                    FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { k.resume(Unit) }
                }
            }
        }
        Prefs.pushRegKey = ""
        Prefs.pushRegAt = 0L
    }
}
