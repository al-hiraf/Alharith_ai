package com.alharith.ai.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.alharith.ai.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/** نتيجة أداة: نص يعود لـ Claude + مرفقات اختيارية (PDF أو صورة) تُرسل معه. */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val attachments: List<JSONObject> = emptyList()
) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** يطلب موافقة المستخدم (صوتًا أو بالأزرار). */
interface Confirmer {
    suspend fun confirm(question: String, detail: String): Boolean
}

class Tool(
    val name: String,
    /** نص الحالة الذي يظهر في المحادثة أثناء التنفيذ */
    val label: String,
    val description: String,
    val schema: JSONObject,
    val run: suspend (JSONObject) -> ToolResult
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("input_schema", schema)
    }
}

class ToolEnv(val context: Context, val confirmer: Confirmer) {

    fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(what: String) = ToolResult.error(
        "صلاحية $what غير ممنوحة. اطلب من المستخدم فتح تطبيق رفيق ثم الإعدادات ← الصلاحيات ومنحها."
    )

    /**
     * يفتح شاشة/تطبيق. Android يمنع فتح الشاشات من الخلفية إلا إذا مُنحت صلاحية
     * "الظهور فوق التطبيقات"، لذلك نتحقق ونعيد رسالة واضحة.
     */
    fun launch(intent: Intent): ToolResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canFromBackground = Settings.canDrawOverlays(context)
        if (!MainActivity.isVisible && !canFromBackground) {
            return ToolResult.error(
                "لا أستطيع فتح الشاشات من الخلفية قبل منح صلاحية \"الظهور فوق التطبيقات\" لرفيق من الإعدادات ← الصلاحيات."
            )
        }
        return try {
            context.startActivity(intent)
            ToolResult.ok("تم الفتح.")
        } catch (e: ActivityNotFoundException) {
            ToolResult.error("لا يوجد تطبيق على الجهاز يستطيع تنفيذ هذا.")
        } catch (e: SecurityException) {
            ToolResult.error("رفض النظام هذا الإجراء: ${e.message}")
        }
    }
}

// ——— بناء مخطط JSON بسهولة
fun prop(type: String, description: String, enum: List<String>? = null): JSONObject =
    JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enum != null) put("enum", JSONArray(enum))
    }

fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

fun JSONObject.str(key: String): String = optString(key, "").trim()
fun JSONObject.intOr(key: String, d: Int): Int = if (has(key)) optInt(key, d) else d

/** توحيد الكتابة العربية للمطابقة: إزالة التشكيل وتوحيد الهمزات والتاء المربوطة. */
object Arabic {
    private val diacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    fun norm(s: String): String = s.lowercase()
        .replace(diacritics, "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace('ة', 'ه')
        .replace('ى', 'ي')
        .replace('ؤ', 'و')
        .replace('ئ', 'ي')
        .replace(Regex("[^\\p{L}\\p{N}+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** هل يطابق الاسمُ الاستعلامَ؟ كل كلمة في الاستعلام يجب أن تظهر في الاسم (مع تجاهل "ال"). */
    fun matches(name: String, query: String): Boolean {
        val n = norm(name)
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        return words.all { w -> n.contains(w) || n.contains(w.removePrefix("ال")) }
    }
}

fun digitsOnly(s: String) = s.filter { it.isDigit() }

/** آخر 9 أرقام تكفي لمطابقة الرقم بصيغه المختلفة (05… / +9665… / 009665…). */
fun phoneKey(s: String) = digitsOnly(s).takeLast(9)

/** هل النص رقم هاتف؟ */
fun looksLikeNumber(s: String) = digitsOnly(s).length >= 6 && s.count { it.isLetter() } == 0
