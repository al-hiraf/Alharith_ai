package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.Prefs

/** حسابي: الملف الشخصي، وضع العرض، وكل الأقسام والإعدادات في مكان واحد (لا شيء حُذف — كله هنا). */
@Composable
fun AccountScreen(
    onOpenSettings: () -> Unit, onOpenServer: () -> Unit, onOpenMemory: () -> Unit, onOpenLog: () -> Unit,
    onOpenDiagnostics: () -> Unit, onOpenProjects: () -> Unit, onOpenBusiness: () -> Unit, onOpenSearch: () -> Unit,
    onOpenNotes: () -> Unit
) {
    val mode by ThemeState.mode.collectAsState()
    val tasks by LocalStore.tasks.collectAsState()
    var name by remember { mutableStateOf(Prefs.userName) }
    var editName by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding()) {
        RafiqTopBar("حسابي")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ——— الملف الشخصي
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(HarithColors.GoldSoft), contentAlignment = Alignment.Center) {
                        Text(name.take(1).ifBlank { "ر" }, style = MaterialTheme.typography.headlineSmall, color = HarithColors.GoldText)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
                        val done = tasks.count { it.status == "done" }; val open = tasks.count { it.isOpen }
                        Text("${arNum(open)} مهمة مفتوحة · ${arNum(done)} مكتملة", style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                        Text(if (Prefs.sharedBrainReady) "مربوط بخادم رفيق" else "يعمل على هذا الجوال", style = MaterialTheme.typography.labelSmall,
                            color = if (Prefs.sharedBrainReady) HarithColors.Green else HarithColors.Muted)
                    }
                    Box(Modifier.clip(CircleShape).clickable { editName = true }.padding(8.dp)) {
                        Icon(Icons.Default.Edit, "تعديل الاسم", tint = HarithColors.Muted)
                    }
                }
            }

            // ——— وضع العرض
            SectionHeader("وضع العرض")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "حسب الجوال", "light" to "فاتح", "dark" to "داكن").forEach { (k, l) ->
                    Box(Modifier.weight(1f)) { Pill(l, mode == k) { ThemeState.set(k) } }
                }
            }

            // ——— الأقسام
            SectionHeader("الأقسام")
            Group {
                Item(Icons.Default.AccountTree, "المشاريع والفريق", "اللوحة، المراحل، القوالب، دعوة الأعضاء", onOpenProjects)
                Item(Icons.Default.BusinessCenter, "الأعمال والمالية", "الشركات، الفواتير، الدخل والمصروف", onOpenBusiness)
                Item(Icons.Default.EditNote, "الملاحظات", "كل ملاحظاتك", onOpenNotes)
                Item(Icons.Default.Search, "البحث الشامل", "ابحث في كل بياناتك على الخادم", onOpenSearch)
            }

            // ——— رفيق
            SectionHeader("رفيق")
            Group {
                Item(Icons.Default.Settings, "الإعدادات", "الذكاء، الصوت، الصلاحيات، الموجز، الأمان", onOpenSettings)
                Item(Icons.Default.Dns, "خادمي", "الحالة، رابط الإنترنت، المفاتيح، الربط", onOpenServer)
                Item(Icons.Default.Memory, "الذاكرة", "ما يتذكره رفيق عنك", onOpenMemory)
                Item(Icons.Default.History, "سجل النشاط", "كل ما نفّذه رفيق", onOpenLog)
                Item(Icons.Default.MonitorHeart, "فحص رفيق", "تأكد أن كل شيء يعمل", onOpenDiagnostics)
            }
            Text("رفيق — الإصدار ${com.alharith.ai.BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = HarithColors.Muted)
        }
    }
    if (editName) {
        var v by remember { mutableStateOf(name) }
        AlertDialog(onDismissRequest = { editName = false }, containerColor = HarithColors.Surface,
            title = { Text("اسمك") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("كيف يناديك رفيق؟") }) },
            confirmButton = { TextButton(onClick = { if (v.isNotBlank()) { Prefs.userName = v.trim(); name = v.trim() }; editName = false }) {
                Text("حفظ", color = HarithColors.GoldText) } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("إلغاء", color = HarithColors.Fg) } })
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

@Composable
private fun Item(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(HarithColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = HarithColors.Fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted, maxLines = 1)
        }
        Icon(Icons.Default.ChevronLeft, null, tint = HarithColors.Muted)
    }
    HorizontalDivider(Modifier.padding(start = 64.dp), color = HarithColors.Line.copy(alpha = 0.6f))
}
