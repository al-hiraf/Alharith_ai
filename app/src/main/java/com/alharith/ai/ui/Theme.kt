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
 * هوية الحارث: أسود وذهبي فقط، تتبع وضع الجوال (فاتح نهارًا / داكن ليلًا).
 * الذهبي للعنصر الأهم فقط (زر الصوت والإجراء الرئيسي)، والباقي حبر ورمادي.
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

private val Dark = HarithPalette(
    bg = Color(0xFF000000),
    surface = Color(0xFF121212),
    surfaceHigh = Color(0xFF1C1C1C),
    line = Color(0xFF262626),
    fg = Color(0xFFFFFFFF),
    muted = Color(0xFF8E8E8E),
    gold = Color(0xFFD4AF37),
    goldText = Color(0xFFE2C25F),
    goldSoft = Color(0xFF2A2310),
    onGold = Color(0xFF000000),
    red = Color(0xFFFF6B5E),
    green = Color(0xFF4CC38A),
    silver = Color(0xFFBDBDBD)
)

private val Light = HarithPalette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F6F6),
    surfaceHigh = Color(0xFFEDEDED),
    line = Color(0xFFE4E4E4),
    fg = Color(0xFF000000),
    muted = Color(0xFF6B6B6B),
    gold = Color(0xFFC9A227),
    goldText = Color(0xFF8C6D12),
    goldSoft = Color(0xFFF7EFD6),
    onGold = Color(0xFF000000),
    red = Color(0xFFD13A2E),
    green = Color(0xFF1F8A55),
    silver = Color(0xFF5E5E5E)
)

val LocalHarithPalette = staticCompositionLocalOf { Dark }

/** ألوان الحارث الحالية (تتغير تلقائيًا مع وضع الجوال) */
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
