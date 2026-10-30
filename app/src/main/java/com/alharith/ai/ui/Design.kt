package com.alharith.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Shared visual tokens only. No data, navigation or business state lives here. */
object RafiqDesign {
    val teal = Color(0xFF0E4256)
    val deepTeal = Color(0xFF082734)
    val heroBrush get() = Brush.linearGradient(listOf(Color(0xFF286172), teal, deepTeal))
}

@Composable
fun RafiqIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(HarithColors.GoldSoft)
        .border(1.dp, HarithColors.Gold.copy(alpha = 0.12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(21.dp))
    }
}

@Composable
fun RafiqHero(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(RafiqDesign.heroBrush)
        .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(24.dp))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color(0xFFE8C983).copy(alpha = 0.12f), radius = size.height * 0.9f,
                center = Offset(size.width * 0.95f, 0f), style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.height * 1.2f,
                center = Offset(size.width, 0f), style = Stroke(1.dp.toPx()))
        }
        content()
    }
}

/** Decorative first-run illustration. All apparent controls are painted, not dead buttons. */
@Composable
fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val dark = HarithColors.IsDark
    val gold = Luxe.Gold
    val pale = Luxe.GoldLight
    Canvas(modifier.fillMaxWidth().height(222.dp)) {
        val unit = size.minDimension / 230f
        val w = 102f * unit; val h = 187f * unit
        val cx = size.width / 2; val cy = size.height / 2
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .20f), Color.Transparent), center = Offset(cx,cy), radius = 125f*unit), 125f*unit, Offset(cx,cy))
        fun phone(x: Float, angle: Float, brush: Brush, front: Boolean) {
            rotate(angle, Offset(x,cy)) {
                drawRoundRect(Color.Black.copy(alpha = if (dark) .25f else .10f), Offset(x-w/2+5*unit,cy-h/2+9*unit),Size(w,h),CornerRadius(18*unit))
                drawRoundRect(brush, Offset(x-w/2,cy-h/2),Size(w,h),CornerRadius(18*unit))
                drawRoundRect(pale.copy(alpha=.65f),Offset(x-w/2,cy-h/2),Size(w,h),CornerRadius(18*unit),style=Stroke(1*unit))
                if (front) {
                    drawRoundRect(Color(0xFF061B25),Offset(x-22*unit,cy-h/2+10*unit),Size(44*unit,6*unit),CornerRadius(3*unit))
                    drawArc(Color(0xFFEFF5F4), startAngle=0f,sweepAngle=290f,useCenter=false,topLeft=Offset(x-16*unit,cy-53*unit),size=Size(32*unit,32*unit),style=Stroke(4*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                    drawLine(gold,Offset(x-8*unit,cy-37*unit),Offset(x-1*unit,cy-30*unit),4*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(gold,Offset(x-1*unit,cy-30*unit),Offset(x+13*unit,cy-47*unit),4*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                    repeat(3) { i ->
                        val y=cy+(i*27f)*unit
                        drawRoundRect(Color.White.copy(alpha=.07f),Offset(x-39*unit,y),Size(78*unit,21*unit),CornerRadius(6*unit))
                        drawRoundRect(Brush.linearGradient(listOf(pale,gold)),Offset(x+19*unit,y+4*unit),Size(13*unit,13*unit),CornerRadius(4*unit))
                        drawLine(Color.White.copy(alpha=.27f),Offset(x-29*unit,y+10*unit),Offset(x+6*unit,y+10*unit),2.5f*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
        phone(cx-45*unit,-19f,Brush.linearGradient(listOf(Color(0xFF286879),Color(0xFF12394B))),false)
        phone(cx+42*unit,17f,Brush.linearGradient(listOf(pale,gold,Color(0xFF967337))),false)
        phone(cx,-6f,RafiqDesign.heroBrush,true)
        listOf(Offset(cx-88*unit,cy+14*unit),Offset(cx+87*unit,cy-49*unit),Offset(cx+70*unit,cy+87*unit)).forEachIndexed { i,o ->
            rotate(if(i==1) 12f else -10f,o) {
                drawRoundRect(Color.Black.copy(alpha=.08f),Offset(o.x-19*unit,o.y-18*unit),Size(40*unit,40*unit),CornerRadius(11*unit))
                drawRoundRect(if(dark) Color(0xFF255569) else Color(0xFFFFF7E4),Offset(o.x-20*unit,o.y-20*unit),Size(40*unit,40*unit),CornerRadius(11*unit))
                drawRoundRect(pale.copy(alpha=.7f),Offset(o.x-20*unit,o.y-20*unit),Size(40*unit,40*unit),CornerRadius(11*unit),style=Stroke(.6f*unit))
                val ink=if(dark) pale else Color(0xFF947025)
                if(i==0) repeat(3) { k -> drawLine(ink,Offset(o.x-8*unit,o.y+(k-1)*6*unit),Offset(o.x+8*unit,o.y+(k-1)*6*unit),1.6f*unit) }
                else if(i==1) {
                    drawRoundRect(ink,Offset(o.x-8*unit,o.y-8*unit),Size(16*unit,16*unit),CornerRadius(2*unit),style=Stroke(1.6f*unit))
                    drawLine(ink,Offset(o.x-8*unit,o.y-3*unit),Offset(o.x+8*unit,o.y-3*unit),1.6f*unit)
                } else {
                    drawRoundRect(ink,Offset(o.x-3*unit,o.y-8*unit),Size(6*unit,12*unit),CornerRadius(3*unit),style=Stroke(1.6f*unit))
                    drawArc(ink,0f,180f,false,Offset(o.x-7*unit,o.y-3*unit),Size(14*unit,14*unit),style=Stroke(1.6f*unit))
                    drawLine(ink,Offset(o.x,o.y+11*unit),Offset(o.x,o.y+7*unit),1.6f*unit)
                }
            }
        }
    }
}
