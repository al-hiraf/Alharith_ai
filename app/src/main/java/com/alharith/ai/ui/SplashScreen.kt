package com.alharith.ai.ui

// شاشة البداية: الشعار الجديد (حلقة + صح ذهبي + اسم رفيق) برسم متحرك خفيف ~1.5 ثانية
// الحلقة تُرسم، الصح يظهر بنبضة، والاسم يصعد بتلاشي. لا تُغيّر أي مسار قائم: مجرد طبقة فوق المحتوى عند الإقلاع البارد.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

private const val SPLASH_MS = 1500
private const val FADE_MS = 250

// من ملف الشعار الرسمي (viewBox 1024): الحلقة (قوس مفتوح)، الصح، واسم رفيق بخط الثمانية المُحوَّل لمسارات
private const val RING_PATH = "M722.3,285.3 A215,215 0 1 1 556.7,119.7"
private const val CHECK_PATH = "M410.0,337.3 L490.1,421.1 L668.7,217.0"
private const val NAME_PATH = "M725.9 798.4H720.8L703.0999999999999 840.4C718.4 857.5 737.3 877.9 746.5999999999999 896.2C734.9 931.3 694.9999999999999 953.5 641.9 961.0V966.1L703.0999999999999 994.0C731.3 961.9 752.5999999999999 916.6 752.5999999999999 875.5C752.5999999999999 871.3 752.3 866.8 751.6999999999999 862.6L760.0999999999999 843.4ZM564.8 875.8C555.1999999999999 875.8 549.8 880.9 549.8 890.5V925.0H558.8C595.9999999999999 925.0 633.1999999999999 918.4 660.1999999999999 905.5L667.0999999999999 879.7C691.3999999999999 788.2 652.6999999999999 745.0 618.4999999999999 745.0C592.0999999999999 745.0 566.8999999999999 770.8 566.8999999999999 816.4C566.8999999999999 838.9 580.6999999999999 859.6 607.9999999999999 859.6C630.4999999999999 859.6 651.1999999999999 845.2 662.5999999999999 833.8C665.8999999999999 844.6 666.8 856.3 665.5999999999999 868.9C648.1999999999999 872.8 612.8 875.8 578.8999999999999 875.8ZM642.1999999999999 692.8C642.4999999999999 692.5 642.4999999999999 688.6 642.1999999999999 688.3L616.0999999999999 662.2L589.9999999999999 688.6V692.5L616.0999999999999 718.9ZM610.6999999999999 821.5C588.8 821.5 571.6999999999999 816.1 571.6999999999999 806.5C571.6999999999999 796.6 589.3999999999999 790.0 606.8 790.0C628.0999999999999 790.0 644.5999999999999 800.2 654.4999999999999 815.8C643.0999999999999 818.8 626.5999999999999 821.5 610.6999999999999 821.5ZM466.39999999999986 875.8C456.7999999999999 875.8 451.9999999999999 880.6 451.9999999999999 890.2V925.0C466.9999999999999 925.0 485.2999999999999 921.1 496.9999999999999 913.3L505.9999999999999 879.4C505.39999999999986 914.5 525.7999999999998 925.0 549.7999999999998 925.0C559.3999999999999 925.0 564.4999999999999 919.9 564.4999999999999 910.3V875.8C540.7999999999998 875.8 522.7999999999998 868.6 521.5999999999999 844.9L520.6999999999999 825.4H515.2999999999998L509.5999999999999 852.1C507.7999999999999 859.6 506.89999999999986 866.2 506.2999999999999 872.2C497.5999999999999 873.7 480.1999999999999 875.8 466.39999999999986 875.8ZM541.0999999999999 976.6C541.3999999999999 976.3 541.3999999999999 972.7 541.0999999999999 972.4L517.6999999999999 949.0L496.6999999999999 970.0L475.6999999999999 949.0L451.9999999999999 972.7V976.3L475.6999999999999 1000.0L496.6999999999999 979.0L517.6999999999999 1000.0ZM453.79999999999984 925.0C463.39999999999986 925.0 468.4999999999999 919.9 468.4999999999999 910.3V875.8H445.6999999999999C439.0999999999999 819.4 412.6999999999999 800.8 389.29999999999984 800.8C355.39999999999986 800.8 336.79999999999984 838.9 336.79999999999984 871.9C336.79999999999984 900.7 347.29999999999984 925.0 377.29999999999984 925.0H440.5999999999999C440.5999999999999 929.2 440.29999999999984 933.4 439.9999999999999 937.9C420.1999999999999 949.6 381.4999999999999 960.7 346.9999999999999 960.7C303.4999999999999 960.7 273.7999999999999 944.5 273.7999999999999 907.6C273.7999999999999 896.8 276.4999999999999 882.7 280.9999999999999 864.7L275.89999999999986 864.4C269.59999999999985 883.0 262.39999999999986 910.3 262.39999999999986 935.5C262.39999999999986 990.4 296.2999999999999 1009.0 338.59999999999985 1009.0C372.1999999999999 1009.0 409.0999999999999 996.7 431.89999999999986 981.1C439.6999999999999 962.8 444.4999999999999 946.3 446.29999999999984 925.0ZM433.39999999999986 754.6C433.6999999999999 754.3 433.6999999999999 750.7 433.39999999999986 750.4L409.9999999999999 727.0L388.9999999999999 748.0L367.9999999999999 727.0L344.29999999999984 750.7V754.3L367.9999999999999 778.0L388.9999999999999 757.0L409.9999999999999 778.0ZM342.1999999999999 861.4C342.1999999999999 853.3 356.29999999999984 846.1 374.59999999999985 846.1C394.39999999999986 846.1 415.6999999999999 854.5 428.5999999999999 875.5H376.9999999999999C357.79999999999984 875.5 342.1999999999999 871.6 342.1999999999999 861.4Z"

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(SPLASH_MS, easing = LinearEasing))
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        onDone()
    }
    val ringPath = remember { PathParser().parsePathString(RING_PATH).toPath() }
    val checkPath = remember { PathParser().parsePathString(CHECK_PATH).toPath() }
    val namePath = remember { PathParser().parsePathString(NAME_PATH).toPath() }
    val ringLen = remember { PathMeasure().also { it.setPath(ringPath, false) }.length }
    val checkLen = remember { PathMeasure().also { it.setPath(checkPath, false) }.length }
    val t = progress.value * SPLASH_MS
    // المراحل: حلقة 0-600ms، صح 400-900ms مع نبضة عند الاكتمال، الاسم 800-1400ms
    val ringP = (t / 600f).coerceIn(0f, 1f)
    val checkP = ((t - 400f) / 500f).coerceIn(0f, 1f)
    val pop = when {
        t < 850f -> 1f
        t < 1000f -> 1f + 0.08f * (t - 850f) / 150f
        t < 1150f -> 1.08f - 0.08f * (t - 1000f) / 150f
        else -> 1f
    }
    val nameP = ((t - 800f) / 600f).coerceIn(0f, 1f)
    val ringSeg = remember(ringP) {
        Path().also { if (ringP > 0f) PathMeasure().apply { setPath(ringPath, false) }.getSegment(0f, ringLen * ringP, it, true) }
    }
    val checkSeg = remember(checkP) {
        Path().also { if (checkP > 0f) PathMeasure().apply { setPath(checkPath, false) }.getSegment(0f, checkLen * checkP, it, true) }
    }
    val gold = remember { Brush.linearGradient(listOf(Color(0xFFE8A93A), Color(0xFFFFD87A)), start = Offset(380f, 451f), end = Offset(698f, 187f)) }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF14566A), Color(0xFF0A2530))))) {
        Canvas(Modifier.fillMaxSize()) {
            val L = size.minDimension * 0.55f
            val s = L / 1024f
            val tx = size.width / 2f - 511f * s
            val ty = size.height * 0.44f - 547f * s
            val alpha = fade.value
            withTransform({
                translate(tx, ty)
                scale(s, s, Offset.Zero)
            }) {
                if (ringP > 0f) drawPath(ringSeg, Color.White, alpha = alpha,
                    style = Stroke(width = 60f, cap = StrokeCap.Round))
                if (checkP > 0f) withTransform({ scale(pop, pop, Offset(539f, 319f)) }) {
                    drawPath(checkSeg, brush = gold, alpha = alpha,
                        style = Stroke(width = 60f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (nameP > 0f) withTransform({
                translate(tx, ty + (1f - nameP) * 28f)
                scale(s, s, Offset.Zero)
            }) {
                drawPath(namePath, Color.White, alpha = nameP * alpha)
            }
        }
    }
}
