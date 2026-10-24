package com.alharith.ai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.TaskItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/** إجراءات المهمة السريعة مع إمكانية التراجع: كل إجراء يعيد نسخة المهمة قبل التغيير. */
object TaskActions {
    fun complete(id: Long): TaskItem? {
        val before = LocalStore.task(id) ?: return null
        LocalStore.updateTask(id) { it.copy(status = "done", completedAt = System.currentTimeMillis()) }
        Celebrate.afterComplete()
        return before
    }

    /** تأجيل يوم: من موعدها إن كان قادمًا، وإلا من اليوم، مع الإبقاء على الساعة */
    fun postpone(id: Long): TaskItem? {
        val before = LocalStore.task(id) ?: return null
        LocalStore.updateTask(id) {
            val base = it.dueDate?.takeIf { d -> !d.isBefore(LocalDate.now()) } ?: LocalDate.now()
            val time = if (it.due.length > 10) it.due.substring(10) else ""
            it.copy(due = base.plusDays(1).toString() + time, status = "postponed")
        }
        return before
    }

    fun restore(before: TaskItem) {
        LocalStore.updateTask(before.id) { before }
    }

    /** المهمة التي يبدأ بها اليوم: المتأخرة أولًا، ثم المستحقة اليوم الأعلى أولوية، ثم الجارية */
    fun nextUp(tasks: List<TaskItem>): TaskItem? {
        val today = LocalDate.now()
        val open = tasks.filter { it.isOpen }
        return open.filter { it.isOverdue }.minWithOrNull(compareBy({ it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE }))
            ?: open.filter { it.dueDate == today }.minWithOrNull(compareBy({ it.priorityRank }, { it.dueMillis ?: Long.MAX_VALUE }))
            ?: open.filter { it.status == "in_progress" }.minByOrNull { it.priorityRank }
    }
}

/** رسالة قصيرة مع زر «تراجع» بعد الإنجاز أو التأجيل */
fun undoable(scope: CoroutineScope, host: SnackbarHostState, message: String, before: TaskItem?) {
    before ?: return
    scope.launch {
        host.currentSnackbarData?.dismiss()
        val r = host.showSnackbar(message, actionLabel = "تراجع", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) TaskActions.restore(before)
    }
}

/**
 * سحب المهمة: من جهة البداية (اليمين) لليسار = إنجاز، ومن جهة النهاية لليمين = تأجيل يوم.
 * المهمة لا تختفي فجأة: تعود لمكانها، والقائمة تتحدث، وتظهر رسالة «تراجع».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeTask(
    t: TaskItem, host: SnackbarHostState, modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!t.isOpen) { Box(modifier) { content() }; return }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                undoable(scope, host, "أُنجزت «${t.title.take(30)}»", TaskActions.complete(t.id))
            }
            SwipeToDismissBoxValue.EndToStart -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                undoable(scope, host, "أُجّلت «${t.title.take(30)}» إلى الغد", TaskActions.postpone(t.id))
            }
            else -> {}
        }
        false   // تعود لمكانها؛ التغيير يظهر في القائمة نفسها
    }, positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(
        state = state, modifier = modifier,
        backgroundContent = {
            val dir = state.dismissDirection
            val done = dir == SwipeToDismissBoxValue.StartToEnd
            val color = if (done) HarithColors.Green else HarithColors.GoldText
            Row(
                Modifier.fillMaxSize().background(color.copy(alpha = 0.12f)).padding(horizontal = 20.dp),
                horizontalArrangement = if (done) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Icon(if (done) Icons.Default.Check else Icons.Default.Snooze, null, tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(if (done) "إنجاز" else "تأجيل للغد", color = color, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { Box(Modifier.background(HarithColors.Bg)) { content() } }
}

/** سطر وصف المهمة بلا تكرار: شارة الحالة المهمة (متأخرة/عاجلة/مرتفعة) ثم الموعد ثم المشروع */
@Composable
fun TaskMetaLine(t: TaskItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PriorityBadge(t.priority, t.isOverdue)
        if (t.due.isNotBlank()) Tag(dueLabel(t).removePrefix("متأخرة: "), Icons.Default.Schedule)
        if (t.status == "in_progress") Tag("قيد التنفيذ")
        if (t.project.isNotBlank()) Tag(t.project, Icons.Default.Folder)
        if (t.subtasks.isNotEmpty()) Tag(arNum("${t.subtasks.count { it.done }}/${t.subtasks.size}"))
    }
}

/** حلقة تقدّم صغيرة (تتحرك بهدوء عند تغير النسبة) */
@Composable
fun ProgressRing(progress: Float, size: Dp = 52.dp, stroke: Dp = 5.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    val track = HarithColors.Line; val fill = HarithColors.Gold
    Canvas(Modifier.size(size)) {
        val s = stroke.toPx(); val d = this.size.minDimension - s
        val tl = Offset(s / 2, s / 2); val sz = Size(d, d)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(s))
        // في الواجهة العربية تمتلئ الحلقة عكس عقارب الساعة من الأعلى
        drawArc(fill, -90f, -360f * p, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** رقم كبير أو كلمة عند الصفر (الصفر العربي «٠» بخط كبير يُقرأ نقطة) */
fun countWord(n: Int, zero: String = "لا شيء"): String = if (n == 0) zero else arNum(n)
