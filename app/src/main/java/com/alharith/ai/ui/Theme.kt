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

/** للتوافق مع الشاشات السابقة */
val Tajawal = Plex
val Messiri = Plex

/** خط الكلمة الشعارية «رفيق» — مطابق للشعار (Tajawal Bold) */
val Ruqaa = FontFamily(Font(R.font.tajawal_bold, FontWeight.Bold))

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.5f) =
    TextStyle(fontFamily = Plex, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

private val HarithTypography = Typography(
    displayLarge = style(40, FontWeight.Bold, 1.2f),
    displayMedium = style(34, FontWeight.Bold, 1.25f),
    displaySmall = style(28, FontWeight.Bold, 1.3f),
    headlineMedium = style(26, FontWeight.Bold, 1.3f),
    headlineSmall = style(22, FontWeight.SemiBold, 1.35f),
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
    fun init() { _mode.value = runCatching { com.alharith.ai.data.Prefs.themeMode }.getOrDefault("system") }
    fun set(m: String) { com.alharith.ai.data.Prefs.themeMode = m; _mode.value = m }
}

@Composable
fun HarithTheme(content: @Composable () -> Unit) {
    val mode by ThemeState.mode.collectAsState()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) Dark else Light
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
        MaterialTheme(colorScheme = scheme, typography = HarithTypography) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
        }
    }
}
