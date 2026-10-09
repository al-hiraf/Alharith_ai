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

/**
 * هوية رفيق: فحمي وذهبي مستوحى من زخرفة الكسوة، تتبع وضع الجوال (فاتح نهارًا / داكن ليلًا).
 * الذهبي للعنصر الأهم (زر الصوت والأرقام والروابط)، والزيتي الغامق للخلفية الليلية والحبر النهاري.
 * (أسماء الحقول gold* بقيت كما هي داخليًا وتعني اللون المميز)
 */
@Immutable
data class HarithPalette(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val gold: Color,      // الذهبي للأزرار والعناصر البارزة
    val goldText: Color,  // ذهبي مقروء كنص فوق الخلفية
    val goldSoft: Color,  // خلفية ذهبية خفيفة (فقاعة المستخدم، التحديد)
    val onGold: Color,
    val red: Color,
    val green: Color,
    val silver: Color
)

internal val Dark = HarithPalette(
    bg = Color(0xFF1E1E1E),          // فحمي (من صورة الكسوة)
    surface = Color(0xFF262626),
    surfaceHigh = Color(0xFF303030),
    line = Color(0xFF3A3A3A),
    fg = Color(0xFFF2EDE3),          // عاجي دافئ
    muted = Color(0xFF9A958C),
    gold = Color(0xFFD4A94C),        // ذهب الزخرفة
    goldText = Color(0xFFE0BC62),
    goldSoft = Color(0xFF3A321F),
    onGold = Color(0xFF1E1E1E),
    red = Color(0xFFFF7A6B),
    green = Color(0xFFA9C47A),
    silver = Color(0xFFC9C3B6)
)

internal val Light = HarithPalette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F3EF),
    surfaceHigh = Color(0xFFEAE6DE),
    line = Color(0xFFE6E2DA),
    fg = Color(0xFF1E1E1E),          // حبر فحمي
    muted = Color(0xFF6F6A62),
    gold = Color(0xFFB8892A),
    goldText = Color(0xFF8A6417),
    goldSoft = Color(0xFFF4EAD3),
    onGold = Color(0xFF1E1E1E),
    red = Color(0xFFC63A2D),
    green = Color(0xFF4A6B2A),
    silver = Color(0xFF5A554D)
)

val LocalHarithPalette = staticCompositionLocalOf { Dark }

/** ألوان رفيق الحالية (تتغير تلقائيًا مع وضع الجوال) */
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
    /** كان أزرق؛ صار فضيًا ليبقى التصميم أسود وذهبي */
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalHarithPalette.current.silver
}

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold)
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.5f) =
    TextStyle(fontFamily = Tajawal, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

private val HarithTypography = Typography(
    displayMedium = style(40, FontWeight.Bold, 1.2f),
    displaySmall = style(32, FontWeight.Bold, 1.25f),
    headlineSmall = style(24, FontWeight.Bold, 1.3f),
    titleLarge = style(20, FontWeight.Bold, 1.35f),
    titleMedium = style(17, FontWeight.Medium, 1.4f),
    titleSmall = style(15, FontWeight.Medium, 1.4f),
    bodyLarge = style(16, line = 1.6f),
    bodyMedium = style(14, line = 1.6f),
    bodySmall = style(12, line = 1.55f),
    labelLarge = style(14, FontWeight.Medium),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(11, FontWeight.Medium)
)

@Composable
fun HarithTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = p.gold, onPrimary = p.onGold, secondary = p.gold, background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        outline = p.line, outlineVariant = p.line, error = p.red
    ) else lightColorScheme(
        primary = p.gold, onPrimary = p.onGold, secondary = p.gold, background = p.bg, onBackground = p.fg,
        surface = p.bg, onSurface = p.fg, surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        outline = p.line, outlineVariant = p.line, error = p.red
    )
    CompositionLocalProvider(LocalHarithPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = HarithTypography) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
        }
    }
}
