package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.LocalStore

/** الشريط السفلي: الرئيسية، مهامي، رفيق AI، التقويم، حسابي — القسم الحالي واضح بلون وخط تحته. */
@Composable
fun RafiqBottomBar(current: String, onSelect: (String) -> Unit) {
    val tasks by LocalStore.tasks.collectAsState()
    val overdue = tasks.count { it.isOverdue }
    Column(Modifier.fillMaxWidth().background(HarithColors.Surface)) {
        HorizontalDivider(color = HarithColors.Line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Item("home", "الرئيسية", Icons.Default.Home, Icons.Outlined.Home, current, onSelect)
            Item("tasks", "مهامي", Icons.Default.Checklist, Icons.Default.Checklist, current, onSelect, badge = overdue)
            AiItem(current == "chat") { onSelect("chat") }
            Item("calendar", "التقويم", Icons.Default.CalendarMonth, Icons.Outlined.CalendarMonth, current, onSelect)
            Item("account", "حسابي", Icons.Default.Person, Icons.Outlined.Person, current, onSelect)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    id: String, label: String, on: ImageVector, off: ImageVector, current: String, onSelect: (String) -> Unit, badge: Int = 0
) {
    val sel = current == id
    Column(
        Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(id) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(if (sel) on else off, label, tint = if (sel) HarithColors.Fg else HarithColors.Muted, modifier = Modifier.size(24.dp))
            if (badge > 0) Box(
                Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(RoundedCornerShape(50)).background(HarithColors.Red),
                contentAlignment = Alignment.Center
            ) { Text(arNum(badge.coerceAtMost(9)), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (sel) HarithColors.Fg else HarithColors.Muted)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (sel) HarithColors.Gold else androidx.compose.ui.graphics.Color.Transparent))
    }
}

/** زر رفيق AI في المنتصف: ذهبي مميز */
@Composable
private fun androidx.compose.foundation.layout.RowScope.AiItem(selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 34.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) HarithColors.Gold else HarithColors.GoldSoft),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.AutoMirrored.Outlined.Chat, "رفيق AI", tint = if (selected) HarithColors.OnGold else HarithColors.GoldText, modifier = Modifier.size(22.dp)) }
        Text("رفيق AI", style = MaterialTheme.typography.labelSmall, color = if (selected) HarithColors.Fg else HarithColors.Muted)
    }
}
