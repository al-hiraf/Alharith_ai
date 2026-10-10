package com.alharith.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alharith.ai.R
import com.alharith.ai.data.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** عناصر الهوية الفاخرة لرفيق: الذهب المتدرج، الزجاج، الدائرة الحية، والظهور المتتابع. */
object Luxe {
    val GoldLight = Color(0xFFF7E7B0)
    val Gold = Color(0xFFE0BC62)
    val GoldDeep = Color(0xFFB8862B)
    val Bronze = Color(0xFF6B4A1E)

    /** تدرج ذهبي معدني للنصوص والحواف */
    val goldBrush = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep, Gold, GoldLight))

    @Composable
    fun shimmerBrush(): Brush {
        val t = rememberInfiniteTransition(label = "shimmer")
        val x by t.animateFloat(-600f, 1400f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "x")
        return Brush.linearGradient(
            listOf(GoldDeep, Gold, GoldLight, Gold, GoldDeep),
            start = Offset(x, 0f), end = Offset(x + 600f, 120f)
        )
    }
}

/** بطاقة زجاجية بحافة ذهبية رفيعة */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = LocalHarithPalette.current.bg.luminanceLow()
    val fill = if (dark) Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (strong) 0.10f else 0.065f), Color.White.copy(alpha = 0.025f))
    ) else Brush.verticalGradient(listOf(Color.White, Color(0xFFF8F4EA)))
    val edge = Brush.linearGradient(
        listOf(Luxe.Gold.copy(alpha = if (dark) 0.55f else 0.65f), Luxe.Gold.copy(alpha = 0.06f), Luxe.GoldDeep.copy(alpha = 0.35f))
    )
    Box(modifier.clip(shape).background(fill).border(1.dp, edge, shape), content = content)
}

private fun Color.luminanceLow() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/** ظهور متتابع: كل عنصر ينزلق ويتلاشى للداخل بعد سابقه */
@Composable
fun Modifier.reveal(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L * index)
        a.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 36f
    }
}

/** خلفية زخرفة الكسوة تتحرك ببطء شديد (حركة كن بيرنز) مع تظليل للقراءة */
@Composable
fun OrnamentBackdrop(shiftFraction: Float = 0.2f, scrim: List<Pair<Float, Float>>) {
    val t = rememberInfiniteTransition(label = "ken")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val shiftPx = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight * shiftFraction).toPx() }
        Image(
            painter = painterResource(R.drawable.home_ornament), contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val s = 1.04f + 0.08f * p
                scaleX = s; scaleY = s
                translationY = shiftPx - 30f * p
                translationX = 18f * (p - 0.5f)
            }
        )
        val bg = LocalHarithPalette.current.bg
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*scrim.map { (f, a) -> f to bg.copy(alpha = a) }.toTypedArray())))
    }
}

/** نص بتدرج ذهبي (لامع ومتحرك اختياريًا) */
@Composable
fun GoldText(text: String, style: TextStyle, modifier: Modifier = Modifier, shimmer: Boolean = true) {
    val brush = if (shimmer) Luxe.shimmerBrush() else Luxe.goldBrush
    Text(text, modifier, style = style.copy(brush = brush))
}

/**
 * الدائرة الحية: كرة ذهبية بحلقة متلألئة تدور، تتنفس وهي جاهزة،
 * تموج مع صوتك وهي تستمع، تدور أسرع وهي تفكر، وتنبض بأشرطة صوتية وهي تتكلم.
 */
@Composable
fun LuxeOrb(state: AssistantState, size: Dp, level: Float = 0f, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = state == AssistantState.LISTENING || state == AssistantState.THINKING ||
        state == AssistantState.SPEAKING || state == AssistantState.CONFIRMING
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == AssistantState.THINKING) 1600 else 9000, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "ripple")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wave")
    val lv by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "lv")

    Box(
        modifier.size(size).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            val core = r * 0.52f * (1f + 0.03f * breath + 0.10f * lv)

            // هالة خارجية
            drawCircle(
                Brush.radialGradient(
                    listOf(Luxe.Gold.copy(alpha = 0.42f + 0.25f * lv), Luxe.Gold.copy(alpha = 0.10f), Color.Transparent),
                    center = c, radius = r * (0.92f + 0.08f * breath)
                ), radius = r
            )

            // تموجات الاستماع
            if (state == AssistantState.LISTENING) {
                for (k in 0 until 3) {
                    val p = (ripple + k / 3f) % 1f
                    drawCircle(
                        Luxe.GoldLight.copy(alpha = (1f - p) * (0.45f + 0.4f * lv)),
                        radius = core * (1.05f + p * (0.75f + 0.4f * lv)),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // أشرطة الكلام حول الكرة
            if (state == AssistantState.SPEAKING) {
                val bars = 40
                for (i in 0 until bars) {
                    val a = (i.toFloat() / bars) * 2f * PI.toFloat()
                    val amp = 0.5f + 0.5f * sin(wave * 2 + i * 0.7f) * cos(wave + i * 0.31f)
                    val len = r * (0.06f + 0.16f * amp)
                    val start = core * 1.12f
                    drawLine(
                        Luxe.GoldLight.copy(alpha = 0.85f),
                        Offset(c.x + cos(a) * start, c.y + sin(a) * start),
                        Offset(c.x + cos(a) * (start + len), c.y + sin(a) * (start + len)),
                        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round
                    )
                }
            }

            // حلقة متلألئة تدور
            rotate(spin, c) {
                drawCircle(
                    Brush.sweepGradient(
                        listOf(Color.Transparent, Luxe.GoldLight, Luxe.Gold, Color.Transparent, Luxe.GoldDeep.copy(alpha = 0.6f), Color.Transparent),
                        center = c
                    ),
                    radius = core * 1.22f, style = Stroke(width = 1.6.dp.toPx())
                )
            }
            rotate(-spin * 0.6f, c) {
                drawArc(
                    Luxe.Gold.copy(alpha = 0.55f), startAngle = 0f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(c.x - core * 1.36f, c.y - core * 1.36f), size = Size(core * 2.72f, core * 2.72f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    Luxe.GoldLight.copy(alpha = 0.35f), startAngle = 180f, sweepAngle = 40f, useCenter = false,
                    topLeft = Offset(c.x - core * 1.36f, c.y - core * 1.36f), size = Size(core * 2.72f, core * 2.72f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // نقطة تدور حول الكرة أثناء التفكير
            if (state == AssistantState.THINKING) {
                val a = Math.toRadians(spin.toDouble() * 2).toFloat()
                val rr = core * 1.22f
                drawCircle(Luxe.GoldLight, radius = 4.dp.toPx(), center = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr))
            }

            // الكرة: ذهب معدني بتدرج شعاعي
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFFF4CF), Luxe.Gold, Luxe.GoldDeep, Luxe.Bronze),
                    center = Offset(c.x - core * 0.35f, c.y - core * 0.40f), radius = core * 1.6f
                ), radius = core, center = c
            )
            // لمعة
            drawOval(
                Color.White.copy(alpha = 0.28f),
                topLeft = Offset(c.x - core * 0.62f, c.y - core * 0.80f), size = Size(core * 0.9f, core * 0.5f)
            )
            // حافة داخلية
            drawCircle(Luxe.GoldLight.copy(alpha = 0.6f), radius = core, center = c, style = Stroke(width = 1.dp.toPx()))
        }
        Icon(
            if (active) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (active) "إيقاف" else "تحدّث مع رفيق",
            tint = Color(0xFF2A1F0C),
            modifier = Modifier.size(size * 0.22f)
        )
    }
}

/** حلقة ذهبية صغيرة متحركة تدل على العمل (للعناوين) */
@Composable
fun MiniOrb(state: AssistantState, size: Dp = 34.dp) {
    val t = rememberInfiniteTransition(label = "mini")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == AssistantState.IDLE || state == AssistantState.WAITING_WAKE) 8000 else 1400, easing = LinearEasing)), label = "s")
    Canvas(Modifier.size(size).rotate(spin)) {
        val c = center; val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4CF), Luxe.Gold, Luxe.GoldDeep), center = Offset(c.x - r * 0.2f, c.y - r * 0.25f), radius = r), radius = r * 0.62f)
        drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Luxe.GoldLight, Color.Transparent), center = c), radius = r * 0.9f, style = Stroke(1.5.dp.toPx()))
    }
}
