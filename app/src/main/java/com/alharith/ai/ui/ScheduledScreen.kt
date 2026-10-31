package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Read-only view of existing recurring tasks and server jobs; never alters scheduling. */
@Composable
fun ScheduledScreen(onBack: () -> Unit, onOpenTasks: () -> Unit) {
    val tasks by LocalStore.tasks.collectAsState()
    val reminders by LocalStore.reminders.collectAsState()
    var jobs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch {
        error = ""; loading = true
        if (Prefs.sharedBrainReady) runCatching {
            val rows = SharedBrain.get("/api/jobs").getJSONArray("items")
            jobs = (0 until rows.length()).map { rows.getJSONObject(it) }
        }.onFailure { error = "تعذر جلب المجدولة: ${it.message ?: "حاول مجددًا"}" }
        loading = false
    } }
    LaunchedEffect(Unit) { reload() }
    val kinds = mapOf("reminder" to "تذكير", "briefing_morning" to "ملخص صباحي", "briefing_evening" to "خلاصة مسائية", "agent_prompt" to "أمر مجدول", "notify" to "تنبيه", "mailwatch" to "مراقبة البريد", "backup" to "نسخ احتياطي", "cleanup" to "تنظيف")
    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text("المهام الدورية والمجدولة", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("المهام المتكررة", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            val recurring = tasks.filter { it.repeat != "none" && it.isOpen }
            if (recurring.isEmpty()) Text("لا توجد مهام متكررة مفتوحة على هذا الجوال", color = HarithColors.Muted)
            recurring.forEach { t -> GlassCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(t.title, color = HarithColors.Fg)
                Text("${t.due} · ${mapOf("daily" to "يوميًا", "weekly" to "أسبوعيًا", "monthly" to "شهريًا", "yearly" to "سنويًا")[t.repeat] ?: t.repeat}", color = HarithColors.Muted)
            } } }
            TextButton(onClick = onOpenTasks) { Text("فتح مهامي وتعديل التكرار") }
            val recurringReminders = reminders.filter { it.repeat != "none" }
            if (recurringReminders.isNotEmpty()) {
                Text("التذكيرات المتكررة", color = HarithColors.Fg)
                recurringReminders.forEach { Text(it.text, color = HarithColors.Fg) }
            }
            HorizontalDivider(color = HarithColors.Line)
            Row { Text("مجدولة الحساب على الخادم", Modifier.weight(1f), color = HarithColors.Fg); TextButton(onClick = { reload() }, enabled = !loading) { Text("تحديث") } }
            when {
                loading -> CircularProgressIndicator(color = HarithColors.Gold)
                !Prefs.sharedBrainReady -> Text("اربط حسابك بالخادم لعرض المجدولة", color = HarithColors.Muted)
                error.isNotBlank() -> Text(error, color = HarithColors.Red)
                jobs.isEmpty() -> Text("لا توجد مهام مجدولة على الخادم", color = HarithColors.Muted)
                else -> jobs.forEach { j -> GlassCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(j.optString("label").ifBlank { kinds[j.optString("kind")] ?: j.optString("kind") }, color = HarithColors.Fg)
                    Text(j.optString("when"), color = HarithColors.Muted)
                    Text(mapOf("pending" to "بانتظار التنفيذ", "done" to "مكتملة", "failed" to "فشلت", "running" to "قيد التنفيذ", "cancelled" to "ملغاة")[j.optString("status")] ?: j.optString("status"), color = HarithColors.GoldText)
                } } }
            }
        }
    }
}
