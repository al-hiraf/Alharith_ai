package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.ActivityLog
import com.alharith.ai.data.SharedBrain
import kotlinx.coroutines.launch
import org.json.JSONObject

/** طلبات الموافقة: كل إجراء حساس (كإرسال رد بريدي مقترح) يُعرض هنا ولا يُنفَّذ إلا بلمسة «موافقة». */
@Composable
fun ApprovalsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                val r = SharedBrain.get("/api/approvals")
                val arr = r.optJSONArray("items")
                val list = mutableListOf<JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                rows = list.filter { it.optString("status") == "pending" }
            } catch (e: Exception) {
                error = e.message ?: "تعذر الجلب"
            } finally {
                loading = false
            }
        }
    }

    fun decide(id: Long, approve: Boolean) {
        scope.launch {
            busy = id; result = null
            try {
                val r = SharedBrain.post("/api/approvals/$id", JSONObject().put("approve", approve))
                val text = r.optString("text").ifBlank { if (approve) "نُفّذ" else "رُفض" }
                result = text
                ActivityLog.record("طلبات الموافقة", "#$id ${if (approve) "موافقة" else "رفض"}", text.take(80), "")
                load()
            } catch (e: Exception) {
                error = e.message ?: "تعذر التنفيذ"
            } finally {
                busy = 0L
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("طلبات الموافقة", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, "تحديث", tint = HarithColors.Muted) }
        }
        result?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = HarithColors.GoldText, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = HarithColors.Red, style = MaterialTheme.typography.bodyMedium)
        }
        if (loading && rows.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = HarithColors.GoldText)
            }
        } else if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("لا توجد طلبات بانتظارك", color = HarithColors.Muted, textAlign = TextAlign.Center)
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rows, key = { it.optLong("id") }) { a ->
                val id = a.optLong("id")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarithColors.Surface).padding(14.dp)) {
                    Text(a.optString("summary"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(a.optString("created_local"), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { decide(id, true) },
                            enabled = busy == 0L,
                            colors = ButtonDefaults.buttonColors(containerColor = HarithColors.GoldText)
                        ) { Text("✅ موافقة") }
                        OutlinedButton(onClick = { decide(id, false) }, enabled = busy == 0L) {
                            Text("✖️ رفض", color = HarithColors.Fg)
                        }
                        if (busy == id) CircularProgressIndicator(Modifier.padding(start = 8.dp), color = HarithColors.GoldText)
                    }
                }
            }
        }
    }
}
