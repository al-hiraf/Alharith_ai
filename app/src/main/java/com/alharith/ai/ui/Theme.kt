package com.alharith.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.alharith.ai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * نظام تصميم رفيق 3.0
 * الفحمي #252525 أساسًا، والذهبي #C6A15B للمهم فقط (الإجراء الرئيسي، المؤشرات، التميّز).
 * الفاتح: خلفية #F7F7F5 وبطاقات بيضاء ونص #242424/#777777. الداكن: فحمي وذهبي.
 * (أسماء الحقول gold* بقيت كما هي داخليًا وتعني اللون المميز)
 */
@Immutable
data class HarithPalette(
    val bg: Color,
    val surface: Color,       // البطاقات
    val surfaceHigh: Color,   // حقول الإدخال والعناصر المرفوعة
    val line: Color,
    val fg: Color,
    val muted: Color,
    val gold: Color,      // الذهبي للأزرار والعناصر البارزة
    val goldText: Color,  // ذهبي مقروء كنص فوق الخلفية
    val goldSoft: Color,  // خلفية ذهبية خفيفة (فقاعة المستخدم، التحديد)
    val onGold: Color,
    val red: Color,
    val green: Color,
    val silver: Color,
    val isDark: Boolean = false
)

internal val Dark = HarithPalette(
    bg = Color(0xFF1C1C1C),
    surface = Color(0xFF252525),          // الفحمي
    surfaceHigh = Color(0xFF2F2F2F),
    line = Color(0xFF383838),
    fg = Color(0xFFF2F0EC),
    muted = Color(0xFFA19E98),
    gold = Color(0xFFC6A15B),
    goldText = Color(0xFFD9BB7E),
    goldSoft = Color(0xFF3A3326),
    onGold = Color(0xFF1C1C1C),
    red = Color(0xFFFF8A7A),
    green = Color(0xFF8FC49A),
    silver = Color(0xFFC9C6C0),
    isDark = true
)

internal val Light = HarithPalette(
    bg = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF0EFEB),
    line = Color(0xFFE7E5E0),
    fg = Color(0xFF242424),
    muted = Color(0xFF777777),
    gold = Color(0xFFC6A15B),
    goldText = Color(0xFF8A6A2C),        // ذهبي داكن بتباين كافٍ على الخلفية الفاتحة
    goldSoft = Color(0xFFF5EDDD),
    onGold = Color(0xFF252525),
    red = Color(0xFFC0392B),
    green = Color(0xFF2E7D4F),
    silver = Color(0xFF5A5A5A)
)

val LocalHarithPalette = staticCompositionLocalOf { Light }

/** ألوان رفيق الحالية (تتغير مع وضع العرض) */
object HarithColors {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.surfaceHigh
    val Line: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.line
    val Fg: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.fg
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.muted
    val Gold: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.gold
    val GoldText: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.goldText
    val GoldSoft: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.goldSoft
    val OnGold: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.onGold
    /** للتوافق مع الشاشات السابقة: درجة ذهبية هادئة للحدود والنصوص الثانوية */
    val GoldDim: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.goldText
    val Red: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.red
    val Green: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.green
    /** كان أزرق؛ صار فضيًا ليبقى التصميم فحميًا وذهبيًا */
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.silver
    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.isDark
}

/** IBM Plex Sans Arabic: خط عربي حديث واضح لكل النصوص */
val Plex = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_medium, FontWeight.Medium),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
    Font(R.font.plex_bold, FontWeight.Bold)
)

/** خط العناوين الكبيرة: المرعي (Almarai) الثقيل — عريض ودائري، قريب من أسلوب العناوين الحديثة */
val Display = FontFamily(
    Font(R.font.almarai_bold, FontWeight.Bold),
    Font(R.font.almarai_extrabold, FontWeight.ExtraBold)
)

/** للتوافق مع الشاشات السابقة */
val Tajawal = Plex
val Messiri = Plex

/** خط الكلمة الشعارية «رفيق» — مطابق للشعار (Tajawal Bold) */
val Ruqaa = FontFamily(Font(R.font.tajawal_bold, FontWeight.Bold))

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.5f) =
    TextStyle(fontFamily = Plex, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

/** خطوط العناوين المتاحة للاختيار من «حسابي» — كلها مجانية (رخصة OFL) ومضمّنة في التطبيق */
data class HeadFont(val id: String, val name: String, val family: FontFamily)

private fun one(res: Int) = FontFamily(Font(res, FontWeight.Bold), Font(res, FontWeight.ExtraBold))

object HeadFonts {
    val all = listOf(
        HeadFont("almarai", "المرعي", Display),
        HeadFont("plex", "بلكس", Plex),
        HeadFont("tajawal", "تجوال", one(R.font.head_tajawal)),
        HeadFont("cairo", "القاهرة", one(R.font.head_cairo)),
        HeadFont("alexandria", "الإسكندرية", one(R.font.head_alexandria)),
        HeadFont("lalezar", "لاله‌زار", one(R.font.head_lalezar)),
        HeadFont("reemkufi", "ريم الكوفي", one(R.font.head_reemkufi)),
        HeadFont("messiri", "المسيري", one(R.font.head_messiri)),
        HeadFont("amiri", "أميري", one(R.font.head_amiri))
    )
    /** خط يختاره المستخدم من ملفات جواله (مثل خط «ثمانية» بعد تنزيله من موقعهم الرسمي) — يُحفظ داخل التطبيق فقط ولا يُرفع لأي مكان */
    var custom by androidx.compose.runtime.mutableStateOf<HeadFont?>(null)
        private set
    val list: List<HeadFont> get() = all + listOfNotNull(custom)
    fun byId(id: String) = list.firstOrNull { it.id == id } ?: all.first()

    private fun dir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "fonts").apply { mkdirs() }

    fun init(ctx: android.content.Context) {
        val f = dir(ctx).listFiles { x -> x.name.startsWith("head_custom_") }?.maxByOrNull { it.lastModified() } ?: return
        custom = runCatching { load(f, com.alharith.ai.data.Prefs.customFontName) }.getOrNull()
    }

    private fun load(f: java.io.File, name: String) = HeadFont("custom", name,
        FontFamily(androidx.compose.ui.text.font.Font(f, FontWeight.Bold), androidx.compose.ui.text.font.Font(f, FontWeight.ExtraBold)))

    /**
     * يستورد خطًا من ملف على الجوال: ttf أو otf أو woff، أو ملف مضغوط (zip) فيه خطوط ولو داخل مجلدات أو zip داخلي.
     * من الحزمة يختار العائلة الأساسية (Sans) بالوزن العريض. يرجع (نجح؟، رسالة للمستخدم).
     */
    fun importFont(ctx: android.content.Context, uri: android.net.Uri): Pair<Boolean, String> { return try {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false to "تعذّر فتح الملف"
        if (raw.size > 60 * 1024 * 1024) return false to "الملف كبير جدًا"
        val display = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "font"
        val found = mutableListOf<Pair<String, ByteArray>>()
        collect(display, raw, found, 0)
        val woff2 = found.any { it.first.lowercase().endsWith(".woff2") }
        val usable = found.mapNotNull { (n, b) -> toSfnt(b)?.let { n to it } }
        if (usable.isEmpty()) {
            false to when {
                woff2 -> "الملف فيه خطوط ويب (woff2) فقط، وهذه لا تعمل على الجوال. اختر ملفات otf أو ttf من الحزمة"
                found.isEmpty() && isZip(raw) -> "الملف المضغوط لا يحتوي خطوطًا (otf أو ttf)"
                else -> "هذا الملف ليس خطًا. اختر ملف الخط (otf أو ttf) أو الملف المضغوط كما نزل"
            }
        } else {
            val (pickName, bytes) = usable.minByOrNull { (n, _) -> rank(n) }!!
            val base = pickName.substringAfterLast('/').substringBeforeLast('.')
            val name = base.replace(Regex("[-_ ]?(Bold|Regular|Medium|Black|SemiBold|ExtraBold|Light|Heavy|Book|\\d{3})$", RegexOption.IGNORE_CASE), "")
                .replace('-', ' ').replace('_', ' ').trim().take(24).ifBlank { "خطي" }
            dir(ctx).listFiles { x -> x.name.startsWith("head_custom_") }?.forEach { it.delete() }
            val ext = if (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OTTO") "otf" else "ttf"
            val dest = java.io.File(dir(ctx), "head_custom_${System.currentTimeMillis()}.$ext")
            dest.writeBytes(bytes)
            // تحقق فعلي أن النظام يقرأ الخط قبل اعتباره محفوظًا
            val tf = runCatching { android.graphics.Typeface.createFromFile(dest) }.getOrNull()
            if (tf == null || tf == android.graphics.Typeface.DEFAULT) { dest.delete(); false to "النظام لم يستطع قراءة هذا الخط" }
            else {
                com.alharith.ai.data.Prefs.customFontName = name
                custom = load(dest, name)
                ThemeState.setFont("custom")
                true to "✓ طُبّق خط «$name» على العناوين (من الملف: ${pickName.substringAfterLast('/')})"
            }
        }
    } catch (e: Exception) {
        false to "تعذّر استيراد الخط: ${e.message ?: "خطأ غير معروف"}"
    } }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun collect(name: String, b: ByteArray, out: MutableList<Pair<String, ByteArray>>, depth: Int) {
        if (!isZip(b)) { out += name to b; return }
        if (depth > 2) return
        for (cs in listOf(Charsets.UTF_8, charset("CP437"))) {
            val before = out.size
            val ok = runCatching {
                java.util.zip.ZipInputStream(b.inputStream(), cs).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name; val low = n.lowercase()
                        if (e.isDirectory || "__macosx" in low || low.substringAfterLast('/').startsWith("._")) continue
                        if (low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".woff") || low.endsWith(".woff2") || low.endsWith(".zip"))
                            collect(n, z.readBytes(), out, depth + 1)
                    }
                }
            }.isSuccess
            if (ok) return
            while (out.size > before) out.removeAt(out.size - 1)
        }
    }

    /** يقبل sfnt (ttf/otf) كما هو، ويحوّل woff الإصدار الأول إلى sfnt؛ غير ذلك null */
    private fun toSfnt(b: ByteArray): ByteArray? {
        if (b.size < 12) return null
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        if (tag == "OTTO" || tag == "true" || (b[0].toInt() == 0 && b[1].toInt() == 1 && b[2].toInt() == 0 && b[3].toInt() == 0)) return b
        if (tag != "wOFF") return null
        return runCatching {
            val bb = java.nio.ByteBuffer.wrap(b)
            val flavor = bb.getInt(4); val num = bb.getShort(12).toInt() and 0xFFFF
            data class T(val tag: Int, val data: ByteArray, val checksum: Int)
            val tables = (0 until num).map { i ->
                val o = 44 + i * 20
                val t = bb.getInt(o); val off = bb.getInt(o + 4); val comp = bb.getInt(o + 8); val orig = bb.getInt(o + 12); val ck = bb.getInt(o + 16)
                val d = if (comp == orig) b.copyOfRange(off, off + comp) else {
                    val inf = java.util.zip.Inflater(); inf.setInput(b, off, comp)
                    val r = ByteArray(orig); var n = 0; while (n < orig && !inf.finished()) n += inf.inflate(r, n, orig - n); inf.end(); r
                }
                T(t, d, ck)
            }.sortedBy { Integer.toUnsignedLong(it.tag) }
            var es = 0; while ((1 shl (es + 1)) <= num) es++
            val sr = (1 shl es) * 16
            val head = 12 + 16 * num
            val total = head + tables.sumOf { (it.data.size + 3) and 3.inv() }
            val out = java.nio.ByteBuffer.allocate(total)
            out.putInt(flavor).putShort(num.toShort()).putShort(sr.toShort()).putShort(es.toShort()).putShort((num * 16 - sr).toShort())
            var off = head
            tables.forEach { t -> out.putInt(t.tag).putInt(t.checksum).putInt(off).putInt(t.data.size); off += (t.data.size + 3) and 3.inv() }
            tables.forEach { t -> out.put(t.data); repeat(((t.data.size + 3) and 3.inv()) - t.data.size) { out.put(0) } }
            out.array()
        }.getOrNull()
    }

    /** ترتيب الاختيار من الحزمة: Sans أولًا، ثم Display، ثم غيرها؛ والوزن العريض أولًا؛ ونتجنب المائل */
    private fun rank(path: String): Int {
        val p = path.lowercase(); val f = p.substringAfterLast('/')
        val family = when { "serif" !in p && "sans" in p -> 0; "serif" !in p -> 1; "display" in p -> 2; else -> 3 }
        val w = when {
            "extrabold" in f || "extra-bold" in f || "extra bold" in f || "800" in f -> 2
            "semibold" in f || "semi-bold" in f || "600" in f -> 3
            "bold" in f || "700" in f -> 0
            "black" in f || "heavy" in f || "900" in f -> 1
            "medium" in f || "500" in f -> 4
            else -> 5
        }
        val ital = if ("italic" in f || "oblique" in f) 50 else 0
        val fmt = if (f.endsWith(".woff")) 5 else 0
        return family * 100 + ital + w * 6 + fmt
    }

    fun removeCustom(ctx: android.content.Context) {
        dir(ctx).listFiles { x -> x.name.startsWith("head_custom_") }?.forEach { it.delete() }
        custom = null
        if (ThemeState.font.value == "custom") ThemeState.setFont("almarai")
    }
}

private fun harithTypography(head: FontFamily) = Typography(
    displayLarge = style(40, FontWeight.ExtraBold, 1.3f).copy(fontFamily = head),
    displayMedium = style(34, FontWeight.ExtraBold, 1.3f).copy(fontFamily = head),
    displaySmall = style(28, FontWeight.ExtraBold, 1.35f).copy(fontFamily = head),
    headlineMedium = style(26, FontWeight.ExtraBold, 1.35f).copy(fontFamily = head),
    headlineSmall = style(22, FontWeight.Bold, 1.4f).copy(fontFamily = head),
    titleLarge = style(19, FontWeight.SemiBold, 1.4f),
    titleMedium = style(17, FontWeight.SemiBold, 1.45f),
    titleSmall = style(15, FontWeight.SemiBold, 1.45f),
    bodyLarge = style(16, line = 1.65f),
    bodyMedium = style(15, line = 1.6f),
    bodySmall = style(13, line = 1.55f),
    labelLarge = style(15, FontWeight.Medium, 1.4f),
    labelMedium = style(13, FontWeight.Medium, 1.4f),
    labelSmall = style(12, FontWeight.Medium, 1.4f)
)

/** وضع العرض المختار (يُحفظ في الإعدادات ويُطبق فورًا) */
object ThemeState {
    private val _mode = MutableStateFlow("system")
    val mode: StateFlow<String> = _mode.asStateFlow()
    private val _accent = MutableStateFlow("gold")
    val accent: StateFlow<String> = _accent.asStateFlow()
    private val _font = MutableStateFlow("almarai")
    val font: StateFlow<String> = _font.asStateFlow()
    fun setFont(f: String) { com.alharith.ai.data.Prefs.headFont = f; _font.value = f }
    fun init() {
        _mode.value = runCatching { com.alharith.ai.data.Prefs.themeMode }.getOrDefault("system")
        _accent.value = runCatching { com.alharith.ai.data.Prefs.accent }.getOrDefault("gold").let { if (Accent.byId(it) != null) it else "gold" }
        applyLuxe(_accent.value)
        _font.value = runCatching { com.alharith.ai.data.Prefs.headFont }.getOrDefault("almarai")
    }
    private fun applyLuxe(id: String) { Accent.byId(id)?.let { Luxe.Gold = it.base; Luxe.GoldLight = it.light; Luxe.GoldDeep = it.deep } }
    fun set(m: String) { com.alharith.ai.data.Prefs.themeMode = m; _mode.value = m }
    fun setAccent(a: String) { com.alharith.ai.data.Prefs.accent = a; _accent.value = a; applyLuxe(a) }
}

/**
 * الألوان المميزة المتاحة. الفحمي يبقى أساس الهوية، والمميز يتغير حسب ذوقك.
 * لكل لون: درجة للأزرار، ونص مقروء على الفاتح وعلى الداكن (تباين 4.5:1 على الأقل)، وخلفية خفيفة، ولون النص فوقه.
 */
data class Accent(
    val id: String, val name: String, val base: Color,
    val textLight: Color, val textDark: Color, val softLight: Color, val softDark: Color,
    val onBase: Color, val light: Color, val deep: Color
) {
    companion object {
        val all = listOf(
            Accent("gold", "ذهبي", Color(0xFFC6A15B), Color(0xFF8A6A2C), Color(0xFFD9BB7E), Color(0xFFF5EDDD), Color(0xFF3A3326),
                Color(0xFF252525), Color(0xFFE6D2A6), Color(0xFF9E7C3A)),
            Accent("indigo", "نيلي", Color(0xFF4F46E5), Color(0xFF3B30C4), Color(0xFFA9A3FF), Color(0xFFECEBFD), Color(0xFF25234A),
                Color(0xFFFFFFFF), Color(0xFFB9B4FF), Color(0xFF2A1B8F)),
            Accent("emerald", "زمردي", Color(0xFF2E8B6E), Color(0xFF1D6650), Color(0xFF74C9AA), Color(0xFFE2F2EC), Color(0xFF1E3530),
                Color(0xFFFFFFFF), Color(0xFFA6DCC8), Color(0xFF1F6650)),
            Accent("navy", "أزرق", Color(0xFF3E6FB0), Color(0xFF2C568F), Color(0xFF93B6E8), Color(0xFFE4ECF7), Color(0xFF1F2A3A),
                Color(0xFFFFFFFF), Color(0xFFB7CDEE), Color(0xFF2C568F)),
            Accent("burgundy", "عنابي", Color(0xFF9E3B4A), Color(0xFF85303D), Color(0xFFE09AA6), Color(0xFFF6E4E7), Color(0xFF3A2226),
                Color(0xFFFFFFFF), Color(0xFFE7B3BB), Color(0xFF7A2834)),
            Accent("sand", "رملي", Color(0xFFB08968), Color(0xFF7D5A3C), Color(0xFFD7B898), Color(0xFFF3EAE1), Color(0xFF362C24),
                Color(0xFF252525), Color(0xFFE2CDB8), Color(0xFF8A6A4E)),
            Accent("graphite", "رمادي", Color(0xFF6E6E6E), Color(0xFF555555), Color(0xFFC2C2C2), Color(0xFFEDEDED), Color(0xFF2E2E2E),
                Color(0xFFFFFFFF), Color(0xFFCFCFCF), Color(0xFF4A4A4A))
        )
        fun byId(id: String) = all.firstOrNull { it.id == id }
    }
}

@Composable
fun HarithTheme(content: @Composable () -> Unit) {
    val mode by ThemeState.mode.collectAsState()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accentId by ThemeState.accent.collectAsState()
    val a = Accent.byId(accentId) ?: Accent.all.first()
    // الرسوم والأيقونات التي تستخدم Luxe.Gold تتبع اللون المختار
    androidx.compose.runtime.SideEffect { Luxe.Gold = a.base; Luxe.GoldLight = a.light; Luxe.GoldDeep = a.deep }
    val p = (if (dark) Dark else Light).copy(
        gold = a.base, goldText = if (dark) a.textDark else a.textLight,
        goldSoft = if (dark) a.softDark else a.softLight, onGold = if (dark && a.onBase == Color(0xFF252525)) Color(0xFF1C1C1C) else a.onBase
    )
    val scheme = if (dark) darkColorScheme(
        primary = p.gold, onPrimary = p.onGold, secondary = p.gold, background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        surfaceContainerLow = p.surface, outline = p.line, outlineVariant = p.line, error = p.red,
        secondaryContainer = p.goldSoft, onSecondaryContainer = p.fg
    ) else lightColorScheme(
        primary = p.gold, onPrimary = p.onGold, secondary = p.gold, background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surfaceHigh,
        surfaceContainerLow = p.surface, outline = p.line, outlineVariant = p.line, error = p.red,
        secondaryContainer = p.goldSoft, onSecondaryContainer = p.fg
    )
    // أيقونات شريط الحالة والتنقل تتبع وضع العرض المختار (لا وضع الجوال فقط)
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalHarithPalette provides p) {
        val fontId by ThemeState.font.collectAsState()
        val typography = androidx.compose.runtime.remember(fontId) { harithTypography(HeadFonts.byId(fontId).family) }
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
        }
    }
}
