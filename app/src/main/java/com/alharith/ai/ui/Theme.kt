package com.alharith.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.alharith.ai.R

object HarithColors {
    val Bg = Color(0xFF0E0E0F)
    val Surface = Color(0xFF17171A)
    val SurfaceHigh = Color(0xFF212126)
    val Line = Color(0xFF2C2C32)
    val Fg = Color(0xFFF2F2F2)
    val Muted = Color(0xFF9A9AA3)
    val Gold = Color(0xFFD4A84B)
    val GoldDim = Color(0xFF6B5527)
    val Green = Color(0xFF4FBF8A)
    val Red = Color(0xFFE5675C)
    val Blue = Color(0xFF6EA8FE)
}

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold)
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.5).toInt()) =
    TextStyle(fontFamily = Tajawal, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

private val HarithTypography = Typography(
    displaySmall = style(30, FontWeight.Bold),
    headlineSmall = style(22, FontWeight.Bold),
    titleLarge = style(20, FontWeight.Bold),
    titleMedium = style(16, FontWeight.Medium),
    titleSmall = style(14, FontWeight.Medium),
    bodyLarge = style(16),
    bodyMedium = style(14),
    bodySmall = style(12),
    labelLarge = style(14, FontWeight.Medium),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(11, FontWeight.Medium)
)

private val HarithScheme = darkColorScheme(
    primary = HarithColors.Gold,
    onPrimary = Color(0xFF1A1405),
    secondary = HarithColors.Gold,
    background = HarithColors.Bg,
    onBackground = HarithColors.Fg,
    surface = HarithColors.Surface,
    onSurface = HarithColors.Fg,
    surfaceVariant = HarithColors.SurfaceHigh,
    onSurfaceVariant = HarithColors.Muted,
    surfaceContainer = HarithColors.Surface,
    surfaceContainerHigh = HarithColors.SurfaceHigh,
    surfaceContainerHighest = HarithColors.SurfaceHigh,
    outline = HarithColors.Line,
    outlineVariant = HarithColors.Line,
    error = HarithColors.Red
)

@Composable
fun HarithTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HarithScheme, typography = HarithTypography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
