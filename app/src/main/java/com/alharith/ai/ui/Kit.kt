package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** عناصر واجهة موحّدة لكل الشاشات (نظام تصميم رفيق 3.0). */

/** شريط علوي: عنوان + رجوع اختياري + إجراءات */
@Composable
fun RafiqTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
        Column(Modifier.weight(1f)) {
            // عنوان الصفحة هو العنوان الرئيسي (بلا تسميات فوقه)، والسطر الثاني يشرح الحال
            Text(title, style = if (onBack == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                color = HarithColors.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, maxLines = 1)
        }
        actions()
    }
}

/** عنوان قسم مع رابط اختياري */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
        if (action != null && onAction != null) Text(
            action, Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
            color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge
        )
    }
}

/** شريحة قابلة للاختيار (مرشحات، اختيارات سريعة) */
@Composable
fun Pill(label: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (selected) HarithColors.Fg else HarithColors.Surface)
            .border(1.dp, if (selected) HarithColors.Fg else HarithColors.Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = if (selected) HarithColors.Bg else HarithColors.Muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = if (selected) HarithColors.Bg else HarithColors.Fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * شارة الأولوية: نص + أيقونة (لا تعتمد على اللون وحده).
 * العاجلة: أيقونة تعجب وإطار أحمر، العالية: سهمان للأعلى، المتأخرة: ساعة.
 */
@Composable
fun PriorityBadge(priority: String, overdue: Boolean = false) {
    val (label, icon, color) = when {
        overdue -> Triple("متأخرة", Icons.Default.Schedule, HarithColors.Red)
        priority == "urgent" -> Triple("عاجلة", Icons.Default.PriorityHigh, HarithColors.Red)
        priority == "high" -> Triple("مرتفعة", Icons.Default.KeyboardDoubleArrowUp, HarithColors.GoldText)
        else -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** شارة صغيرة محايدة (مشروع، تصنيف، وقت) */
@Composable
fun Tag(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(HarithColors.SurfaceHigh).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = HarithColors.Muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(3.dp)) }
        Text(text, color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** بطاقة رقم (مؤشر) */
@Composable
fun MetricCard(modifier: Modifier, value: String, label: String, accent: Color? = null, onClick: (() -> Unit)? = null) {
    GlassCard(modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick) else Modifier), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent ?: HarithColors.Fg)
            Text(label, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted, maxLines = 1)
        }
    }
}

/** حالة فارغة */
@Composable
fun EmptyView(title: String, hint: String = "", icon: ImageVector? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(HarithColors.GoldSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg, textAlign = TextAlign.Center)
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted, textAlign = TextAlign.Center)
    }
}

/** حالة خطأ مع إعادة المحاولة */
@Composable
fun ErrorView(message: String, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Red.copy(alpha = 0.08f))
            .border(1.dp, HarithColors.Red.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = HarithColors.Red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg)
        if (onRetry != null) Text(
            "إعادة المحاولة", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRetry).padding(horizontal = 8.dp, vertical = 6.dp),
            color = HarithColors.Red, style = MaterialTheme.typography.labelLarge
        )
    }
}

/** حالة تحميل */
@Composable
fun LoadingView(text: String = "") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = HarithColors.Gold, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        if (text.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(text, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted) }
    }
}

/** زر رئيسي (فحمي في الفاتح / ذهبي في الداكن) */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val dark = HarithColors.IsDark
    val bg = if (!enabled) HarithColors.SurfaceHigh else if (dark) HarithColors.Gold else Color(0xFF252525)
    val fg = if (!enabled) HarithColors.Muted else if (dark) HarithColors.OnGold else Color.White
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}


/**
 * لوح الأوامر الفحمي (هوية رفيق): حقل كتابة على خلفية فحمية وزر ذهبي دائري.
 * الزر يرسل إن وُجد نص، أو يشغّل الميكروفون إن كان فارغًا وأُعطي [onMic].
 */
@Composable
fun CommandBar(
    value: String, onValue: (String) -> Unit, placeholder: String, onSend: () -> Unit,
    modifier: Modifier = Modifier, onMic: (() -> Unit)? = null, sendLabel: String = "إرسال",
    sendIcon: ImageVector = Icons.AutoMirrored.Filled.Send
) {
    val dark = HarithColors.IsDark
    val slab = if (dark) Color(0xFF2A2620) else Color(0xFF252525)
    val ink = Color(0xFFF7F5F0)
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(slab)
            .then(if (dark) Modifier.border(1.dp, HarithColors.Gold.copy(alpha = 0.35f), shape) else Modifier)
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value, onValue, Modifier.weight(1f).padding(vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(HarithColors.Gold), maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = ink.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        )
        Spacer(Modifier.width(8.dp))
        val mic = value.isBlank() && onMic != null
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (value.isBlank() && onMic == null) HarithColors.Gold.copy(alpha = 0.45f) else HarithColors.Gold)
                .clickable { if (mic) onMic?.invoke() else onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (mic) Icons.Default.Mic else sendIcon, if (mic) "تحدّث مع رفيق" else sendLabel,
                tint = HarithColors.OnGold, modifier = Modifier.size(22.dp))
        }
    }
}

/** تبويبات نصية هادئة: المختار بخط أثقل وخط ذهبي تحته (بدل الأزرار المعبأة) */
@Composable
fun TextTabs(tabs: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { (id, label) ->
            val sel = id == selected
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = if (sel) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (sel) HarithColors.Fg else HarithColors.Muted, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(22.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (sel) HarithColors.Gold else Color.Transparent))
            }
        }
    }
}
