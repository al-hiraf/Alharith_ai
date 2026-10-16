package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
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
    val accent by ThemeState.accent.collectAsState()
    val tasks by LocalStore.tasks.collectAsState()
    var name by remember { mutableStateOf(Prefs.userName) }
    var editName by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            // ——— الاسم عنوانًا للصفحة
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HeadlineText(name, MaterialTheme.typography.headlineMedium)
                    val done = tasks.count { it.status == "done" }; val open = tasks.count { it.isOpen }
                    Text("${arNum(open)} مهمة مفتوحة، و${arNum(done)} مكتملة", style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted)
                    Text(if (Prefs.sharedBrainReady) "مربوط بخادم رفيق" else "يعمل على هذا الجوال فقط", style = MaterialTheme.typography.bodySmall,
                        color = if (Prefs.sharedBrainReady) HarithColors.Green else HarithColors.Muted)
                }
                Text("تعديل الاسم", Modifier.clip(RoundedCornerShape(10.dp)).clickable { editName = true }.padding(8.dp),
                    color = HarithColors.GoldText, style = MaterialTheme.typography.labelLarge)
            }

            // ——— المظهر: الوضع واللون
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("وضع العرض")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "حسب الجوال", "light" to "فاتح", "dark" to "داكن").forEach { (k, l) ->
                        Box(Modifier.weight(1f)) { Pill(l, mode == k) { ThemeState.set(k) } }
                    }
                }
                Text("الألوان", style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Accent.all.forEach { a ->
                        val sel = a.id == accent
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { ThemeState.setAccent(a.id) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(a.base)
                                    .then(if (sel) Modifier.border(3.dp, HarithColors.Bg, CircleShape) else Modifier)
                                    .then(if (sel) Modifier.border(2.dp, HarithColors.Fg, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) { if (sel) Icon(Icons.Default.Check, "مختار", tint = a.onBase, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.height(4.dp))
                            Text(a.name, style = MaterialTheme.typography.labelSmall, color = if (sel) HarithColors.Fg else HarithColors.Muted)
                        }
                    }
                }
                Text("الفحمي يبقى أساس التصميم، واللون المختار يظهر في الأزرار والمؤشرات المهمة.", style = MaterialTheme.typography.bodySmall,
                    color = HarithColors.Muted)
            }

            // ——— الأقسام
            Column {
                SectionHeader("الأقسام")
                Item(Icons.Default.AccountTree, "المشاريع والفريق", "اللوحة، والمراحل، والقوالب، ودعوة الأعضاء", onOpenProjects)
                Item(Icons.Default.BusinessCenter, "الأعمال والمالية", "الشركات، والفواتير، والدخل والمصروف", onOpenBusiness)
                Item(Icons.Default.EditNote, "الملاحظات", "كل ملاحظاتك", onOpenNotes)
                Item(Icons.Default.Search, "البحث الشامل", "ابحث في كل بياناتك على الخادم", onOpenSearch, last = true)
            }

            // ——— رفيق
            Column {
                SectionHeader("رفيق")
                Item(Icons.Default.Settings, "الإعدادات", "الذكاء، والصوت، والصلاحيات، والموجز، والأمان", onOpenSettings)
                Item(Icons.Default.Dns, "خادمي", "الحالة، ورابط الإنترنت، والمفاتيح، والربط", onOpenServer)
                Item(Icons.Default.Memory, "الذاكرة", "ما يتذكره رفيق عنك", onOpenMemory)
                Item(Icons.Default.History, "سجل النشاط", "كل ما نفّذه رفيق", onOpenLog)
                Item(Icons.Default.MonitorHeart, "فحص رفيق", "تأكد أن كل شيء يعمل", onOpenDiagnostics, last = true)
            }
            Text("رفيق، الإصدار ${com.alharith.ai.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = HarithColors.Muted)
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
private fun Item(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HarithColors.GoldText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HarithColors.Fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted, maxLines = 1)
        }
        Icon(Icons.Default.ChevronLeft, null, tint = HarithColors.Muted)
    }
    if (!last) HorizontalDivider(Modifier.padding(start = 42.dp), color = HarithColors.Line)
}
