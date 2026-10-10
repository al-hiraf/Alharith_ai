package com.alharith.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import com.alharith.ai.service.HarithNotificationListener
import com.alharith.ai.tools.Arabic
import com.alharith.ai.tools.CalendarReader
import com.alharith.ai.tools.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** بحث شامل: المهام والملاحظات والذاكرة والتذكيرات والرسائل الواردة وجهات الاتصال والتقويم،
 *  وبيانات الخادم (الشركات، الفواتير، العملاء، القيود، الاجتماعات، الملفات) إن كان مربوطًا. */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenTasks: () -> Unit, onOpenBusiness: () -> Unit) {
    val context = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var serverNote by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(q) {
        val query = q.trim()
        if (query.length < 2) { results = emptyList(); serverNote = ""; return@LaunchedEffect }
        delay(250)
        val local = withContext(Dispatchers.Default) {
            val m = { s: String -> Arabic.matches(s, query) || Arabic.norm(s).contains(Arabic.norm(query)) }
            buildList {
                LocalStore.tasks.value.filter { m(it.title + " " + it.description + " " + it.project + " " + it.person + " " + it.notes) }
                    .take(12).forEach { add(Hit("مهمة", it.title, listOf(it.due.replace("T", " "), it.project).filter { s -> s.isNotBlank() }.joinToString(" · "), "tasks")) }
                LocalStore.notes.value.filter { m(it.title + " " + it.body) }
                    .take(8).forEach { add(Hit("ملاحظة", it.title, it.body.take(90), "tasks")) }
                LocalStore.activeMemories().filter { m(it.text) }.take(6).forEach { add(Hit("ذاكرة", it.text.take(90), "", "")) }
                LocalStore.reminders.value.filter { m(it.text) }.take(6).forEach { add(Hit("تذكير", it.text, "", "")) }
                HarithNotificationListener.messages().filter { m(it.sender + " " + it.text) }.takeLast(8).reversed()
                    .forEach { add(Hit(it.app, it.sender, it.text.take(100), "")) }
            }
        }
        val device = withContext(Dispatchers.IO) {
            buildList {
                if (granted(context, Manifest.permission.READ_CONTACTS)) runCatching {
                    Contacts.search(context, query).take(6).forEach { add(Hit("جهة اتصال", it.name, it.number, "")) }
                }
                if (granted(context, Manifest.permission.READ_CALENDAR)) runCatching {
                    CalendarReader.search(context, query).forEach { add(Hit("موعد", it.substringAfter("] "), "", "")) }
                }
            }
        }
        results = local + device
        serverNote = ""
        if (Prefs.sharedBrainReady) {
            try {
                val arr = SharedBrain.get("/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")).optJSONArray("items")
                val server = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                    .filter { it.optString("type") !in setOf("task", "memory") } // موجودة محليًا بالمزامنة
                    .map { Hit(it.optString("type_ar"), it.optString("title"), it.optString("sub"),
                        if (it.optString("type") in setOf("workspace", "contact", "invoice", "ledger", "meeting")) "business" else "") }
                if (q.trim() == query) results = local + device + server
            } catch (e: Exception) { serverNote = "تعذّر البحث في الخادم: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("ابحث في كل شيء…", color = HarithColors.Muted) }, singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Luxe.Gold, unfocusedBorderColor = HarithColors.Line,
                    focusedContainerColor = HarithColors.Surface, unfocusedContainerColor = HarithColors.Surface)
            )
            Spacer(Modifier.width(8.dp))
        }
        if (serverNote.isNotBlank()) Text(serverNote, Modifier.padding(horizontal = 16.dp), color = HarithColors.Muted,
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (q.trim().length >= 2 && results.isEmpty()) item {
                Text("لا نتائج.", Modifier.padding(24.dp), color = HarithColors.Muted)
            }
            if (q.isBlank()) item {
                Text("المهام، الملاحظات، الذاكرة، التذكيرات، الرسائل الواردة، جهات الاتصال، التقويم" +
                    (if (Prefs.sharedBrainReady) "، والشركات والفواتير والعملاء والاجتماعات والملفات على الخادم." else "."),
                    Modifier.padding(16.dp), color = HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            items(results) { h ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Surface)
                        .clickable(enabled = h.page.isNotBlank()) { if (h.page == "tasks") onOpenTasks() else if (h.page == "business") onOpenBusiness() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(h.type, Modifier.clip(RoundedCornerShape(10.dp)).background(HarithColors.GoldSoft).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = HarithColors.GoldText, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                        if (h.sub.isNotBlank()) Text(h.sub, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private data class Hit(val type: String, val title: String, val sub: String, val page: String)

private fun granted(ctx: android.content.Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
