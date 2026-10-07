package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.MemoryItem
import com.alharith.ai.data.Prefs

/** الذاكرة الشخصية: عرض، تعديل، حذف، إيقاف، ومسح الكل. */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val all by LocalStore.memories.collectAsState()
    val memories = all.filter { it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis() }.sortedByDescending { it.createdAt }
    var enabled by remember { mutableStateOf(Prefs.memoryEnabled) }
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("ذاكرة الحارث", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            if (memories.isNotEmpty()) IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Default.DeleteSweep, "مسح الكل", tint = HarithColors.Muted)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarithColors.Surface)
                    .clickable { enabled = !enabled; Prefs.memoryEnabled = enabled }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("تفعيل الذاكرة", color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "يتذكر الحارث ما تطلب منه حفظه (تفضيلاتك، مشاريعك، أسماء عملائك) ويستخدمه في ردوده. " +
                            "لا يحفظ المعلومات الحساسة إلا بطلبك الصريح. كل شيء محفوظ على هاتفك فقط.",
                        color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled, onCheckedChange = { enabled = it; Prefs.memoryEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = HarithColors.Gold)
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (memories.isEmpty()) item {
                Text(
                    "الذاكرة فارغة. قل للحارث مثلًا: \"تذكر إن عميلنا الأهم شركة كذا\"، أو أضف معلومة بالأسفل.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    color = HarithColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium
                )
            }
            items(memories, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarithColors.Surface)
                        .clickable { editing = m; editText = m.text }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                        if (m.kind == "temp") Text("مؤقتة — تُحذف بعد أسبوع", color = HarithColors.GoldDim, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { LocalStore.deleteMemory(m.id) }) {
                        Icon(Icons.Default.DeleteOutline, "حذف", tint = HarithColors.Muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("أضف معلومة يتذكرها الحارث…", color = HarithColors.Muted) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HarithColors.GoldDim, unfocusedBorderColor = HarithColors.Line,
                    focusedContainerColor = HarithColors.Surface, unfocusedContainerColor = HarithColors.Surface
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (input.isNotBlank()) { LocalStore.addMemory(MemoryItem(LocalStore.newId(), input.trim())); input = "" }
                },
                modifier = Modifier.size(48.dp).clip(CircleShape).background(HarithColors.Gold)
            ) { Icon(Icons.Default.Add, "إضافة", tint = Color(0xFF1A1405)) }
        }
    }

    editing?.let { m ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("تعديل المعلومة") },
            text = {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editText.isNotBlank()) LocalStore.updateMemory(m.id, editText.trim())
                    editing = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل الذاكرة؟") },
            text = { Text("سيُحذف كل ما يتذكره الحارث عنك نهائيًا.") },
            confirmButton = { TextButton(onClick = { LocalStore.clearMemories(); confirmClear = false }) { Text("مسح الكل", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
        )
    }
}
