package com.alharith.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import com.alharith.ai.data.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** احتفال صغير عند إنجاز مهمة، وأكبر عند إنهاء كل مهام اليوم (على طريقة تطبيقات الإنتاجية) */
object Celebrate {
    data class Burst(val id: Long, val big: Boolean)
    val burst = MutableStateFlow(Burst(0, false))
    fun fire(big: Boolean = false) { burst.value = Burst(System.nanoTime(), big) }

    /** يُستدعى بعد الإنجاز: إن لم يبقَ شيء مستحق اليوم فالاحتفال كبير */
    fun afterComplete() {
        val today = LocalDate.now()
        val left = LocalStore.tasks.value.count { it.isOpen && (it.dueDate?.let { d -> !d.isAfter(today) } ?: false) }
        fire(big = left == 0)
    }
}

private class Piece(val angle: Float, val speed: Float, val spin: Float, val size: Float, val color: Color, val round: Boolean)

/** طبقة الاحتفال: قصاصات ملوّنة تنطلق وتسقط بالجاذبية خلال ثانية تقريبًا، ولا تعترض اللمس */
@Composable
fun CelebrationOverlay() {
    val b by Celebrate.burst.collectAsState()
    if (b.id == 0L) return
    val t = remember(b.id) { Animatable(0f) }
    var alive by remember(b.id) { mutableStateOf(true) }
    val gold = HarithColors.Gold
    val palette = listOf(gold, HarithColors.GoldText, HarithColors.Green, Color(0xFFE9D3A2), HarithColors.Fg.copy(alpha = 0.75f))
    val pieces = remember(b.id) {
        val n = if (b.big) 70 else 26
        List(n) {
            // تنطلق للأعلى بزاوية مفتوحة
            val a = (-90f + Random.nextFloat() * (if (b.big) 150f else 110f) - (if (b.big) 75f else 55f)) * (Math.PI / 180).toFloat()
            Piece(a, 0.55f + Random.nextFloat() * (if (b.big) 0.9f else 0.55f), Random.nextFloat() * 720f - 360f,
                6f + Random.nextFloat() * 8f, palette[it % palette.size], Random.nextBoolean())
        }
    }
    LaunchedEffect(b.id) {
        t.animateTo(1f, tween(if (b.big) 1700 else 1100, easing = LinearEasing)); alive = false
    }
    if (!alive) return
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        val origin = if (b.big) Offset(size.width / 2, size.height * 0.62f) else Offset(size.width / 2, size.height * 0.72f)
        val reach = size.height * (if (b.big) 0.75f else 0.45f)
        val fade = if (p > 0.7f) 1f - (p - 0.7f) / 0.3f else 1f
        pieces.forEach { pc ->
            val d = reach * pc.speed * p
            val x = origin.x + cos(pc.angle) * d
            val y = origin.y + sin(pc.angle) * d + size.height * 0.55f * p * p   // جاذبية
            val c = pc.color.copy(alpha = pc.color.alpha * fade)
            val s = pc.size * density / 2.5f
            if (pc.round) drawCircle(c, s / 2, Offset(x, y))
            else rotate(pc.spin * p, Offset(x, y)) { drawRect(c, Offset(x - s / 2, y - s / 4), Size(s, s / 2)) }
        }
    }
}

/** ضغطة حيّة: العنصر ينكمش قليلًا ثم يرتد بنعومة */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

/** ارتداد عند تحوّل القيمة إلى true (علامة الإنجاز مثلًا) — لا يتحرك عند أول ظهور */
@Composable
fun Modifier.popWhen(on: Boolean): Modifier {
    val s = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(on) {
        if (first) { first = false; return@LaunchedEffect }
        if (on) { s.snapTo(0.5f); s.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
        else { s.snapTo(0.85f); s.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
    }
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }
}
