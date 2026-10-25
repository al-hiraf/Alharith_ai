package com.alharith.ai.ui

// شاشة البداية: الشعار الجديد (حلقة + صح ذهبي + اسم رفيق بخط الثمانية) — مطابقة تمامًا للنسخة المعتمدة
// (rafiq-splash-animated-serif.svg): نفس المسارات، التوقيت، والتكوين. طبقة إضافية عند الإقلاع البارد فقط.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

private const val FADE_MS = 250

// من النسخة المعتمدة (viewBox 1024): الحلقة مركزها (512,350)، الصح، والاسم — لا تُعدَّل القيم
private const val RING_PATH = "M722.3,305.3 A215,215 0 1 1 556.7,139.7"
private const val CHECK_PATH = "M410.0,357.3 L490.1,441.1 L668.7,237.0"
private const val NAME_PATH = "M718.77 757.62H713.8399999999999L696.73 798.22C711.52 814.75 729.79 834.47 738.78 852.16C727.4699999999999 886.09 688.9 907.55 637.5699999999999 914.8V919.73L696.73 946.7C723.99 915.67 744.5799999999999 871.88 744.5799999999999 832.15C744.5799999999999 828.09 744.29 823.74 743.7099999999999 819.6800000000001L751.8299999999999 801.12ZM563.04 832.44C553.7599999999999 832.44 548.54 837.37 548.54 846.65V880.0H557.2399999999999C593.1999999999999 880.0 629.16 873.62 655.2599999999999 861.15L661.93 836.21C685.42 747.76 648.0099999999999 706.0 614.9499999999999 706.0C589.43 706.0 565.0699999999999 730.94 565.0699999999999 775.02C565.0699999999999 796.77 578.41 816.78 604.8 816.78C626.55 816.78 646.56 802.86 657.5799999999999 791.84C660.77 802.28 661.6399999999999 813.59 660.4799999999999 825.77C643.66 829.54 609.4399999999999 832.44 576.67 832.44ZM637.8599999999999 655.54C638.1499999999999 655.25 638.1499999999999 651.48 637.8599999999999 651.19L612.6299999999999 625.96L587.4 651.48V655.25L612.6299999999999 680.77ZM607.41 779.95C586.2399999999999 779.95 569.7099999999999 774.73 569.7099999999999 765.45C569.7099999999999 755.88 586.8199999999999 749.5 603.6399999999999 749.5C624.2299999999999 749.5 640.18 759.36 649.7499999999999 774.44C638.7299999999999 777.34 622.78 779.95 607.41 779.95ZM467.91999999999996 832.44C458.63999999999993 832.44 453.99999999999994 837.08 453.99999999999994 846.36V880.0C468.49999999999994 880.0 486.18999999999994 876.23 497.49999999999994 868.69L506.19999999999993 835.92C505.61999999999995 869.85 525.3399999999999 880.0 548.54 880.0C557.8199999999999 880.0 562.7499999999999 875.07 562.7499999999999 865.79V832.44C539.8399999999999 832.44 522.4399999999999 825.48 521.28 802.57L520.41 783.72H515.1899999999999L509.67999999999995 809.53C507.93999999999994 816.78 507.06999999999994 823.16 506.48999999999995 828.96C498.0799999999999 830.41 481.25999999999993 832.44 467.91999999999996 832.44ZM540.1299999999999 929.88C540.42 929.59 540.42 926.11 540.1299999999999 925.82L517.51 903.2L497.2099999999999 923.5L476.90999999999997 903.2L453.99999999999994 926.11V929.59L476.90999999999997 952.5L497.2099999999999 932.2L517.51 952.5ZM455.73999999999995 880.0C465.02 880.0 469.94999999999993 875.07 469.94999999999993 865.79V832.44H447.90999999999997C441.53 777.92 416.00999999999993 759.94 393.38999999999993 759.94C360.61999999999995 759.94 342.64 796.77 342.64 828.67C342.64 856.51 352.78999999999996 880.0 381.78999999999996 880.0H442.9799999999999C442.9799999999999 884.06 442.68999999999994 888.12 442.4 892.47C423.25999999999993 903.78 385.84999999999997 914.51 352.49999999999994 914.51C310.44999999999993 914.51 281.73999999999995 898.85 281.73999999999995 863.18C281.73999999999995 852.74 284.34999999999997 839.11 288.69999999999993 821.71L283.77 821.42C277.67999999999995 839.4 270.71999999999997 865.79 270.71999999999997 890.15C270.71999999999997 943.22 303.48999999999995 961.2 344.37999999999994 961.2C376.85999999999996 961.2 412.53 949.31 434.56999999999994 934.23C442.10999999999996 916.54 446.74999999999994 900.59 448.48999999999995 880.0ZM436.02 715.28C436.30999999999995 714.99 436.30999999999995 711.51 436.02 711.22L413.4 688.6L393.09999999999997 708.9L372.79999999999995 688.6L349.89 711.51V714.99L372.79999999999995 737.9L393.09999999999997 717.6L413.4 737.9ZM347.85999999999996 818.52C347.85999999999996 810.69 361.48999999999995 803.73 379.17999999999995 803.73C398.31999999999994 803.73 418.90999999999997 811.85 431.37999999999994 832.15H381.49999999999994C362.93999999999994 832.15 347.85999999999996 828.38 347.85999999999996 818.52Z"

@Composable
fun SplashScreen(onDone: () -> Unit) {
    // التوقيت المعتمد: حلقة 0-900ms (cubic .2,.7,.2,1) — صح 550-950ms — نبضة 900-1250ms (ذروة 1.12) — اسم 1000-1500ms
    val ringP = remember { Animatable(0f) }
    val checkP = remember { Animatable(0f) }
    val popP = remember { Animatable(0f) }
    val nameP = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { ringP.animateTo(1f, tween(900, easing = CubicBezierEasing(.2f, .7f, .2f, 1f))) }
        launch { delay(550); checkP.animateTo(1f, tween(400, easing = CubicBezierEasing(.33f, 1f, .68f, 1f))) }
        launch { delay(900); popP.animateTo(1f, tween(350, easing = LinearEasing)) }
        launch { delay(1000); nameP.animateTo(1f, tween(500, easing = CubicBezierEasing(.33f, 1f, .68f, 1f))) }
        delay(1500)
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        onDone()
    }
    val ringPath = remember { PathParser().parsePathString(RING_PATH).toPath() }
    val checkPath = remember { PathParser().parsePathString(CHECK_PATH).toPath() }
    val namePath = remember { PathParser().parsePathString(NAME_PATH).toPath() }
    val ringLen = remember { PathMeasure().also { it.setPath(ringPath, false) }.length }
    val checkLen = remember { PathMeasure().also { it.setPath(checkPath, false) }.length }
    val pop = 1f + 0.12f * sin(popP.value * Math.PI).toFloat()
    val ringSeg = remember(ringP.value) {
        Path().also { if (ringP.value > 0f) PathMeasure().apply { setPath(ringPath, false) }.getSegment(0f, ringLen * ringP.value, it, true) }
    }
    val checkSeg = remember(checkP.value) {
        Path().also { if (checkP.value > 0f) PathMeasure().apply { setPath(checkPath, false) }.getSegment(0f, checkLen * checkP.value, it, true) }
    }
    val gold = remember { Brush.linearGradient(listOf(Color(0xFFE8A93A), Color(0xFFFFD87A)), start = Offset(380f, 471f), end = Offset(700f, 207f)) }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF14566A), Color(0xFF0A2530))))) {
        Canvas(Modifier.fillMaxSize()) {
            // التكوين المعتمد: مربع 1024 بعرض 92% من الشاشة، مركزه عند 45% من الارتفاع
            val s = size.width * 0.92f / 1024f
            val tx = size.width * 0.04f
            val ty = size.height * 0.45f - 512f * s
            withTransform({
                translate(tx, ty)
                scale(s, s, Offset.Zero)
            }) {
                if (ringP.value > 0f) drawPath(ringSeg, Color.White, alpha = fade.value,
                    style = Stroke(width = 60f, cap = StrokeCap.Round))
                if (checkP.value > 0f) withTransform({ scale(pop, pop, Offset(512f, 350f)) }) {
                    drawPath(checkSeg, brush = gold, alpha = fade.value,
                        style = Stroke(width = 60f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (nameP.value > 0f) withTransform({
                translate(tx, ty + (1f - nameP.value) * 24f * s)
                scale(s, s, Offset.Zero)
            }) {
                drawPath(namePath, Color.White, alpha = nameP.value * fade.value)
            }
        }
    }
}
