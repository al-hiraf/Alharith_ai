package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.ActivityLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل النشاط: كل ما نفّذه الحارث، الأحدث أولًا. */
@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val entries by ActivityLog.entries.collectAsState()
    val time = SimpleDateFormat("h:mm a", Locale("ar"))
    val day = SimpleDateFormat("EEEE d MMMM", Locale("ar"))

    Column(
        Modifier
            .fillMaxSize()
            .background(HarithColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg)
            }
            Text("سجل النشاط", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            if (entries.isNotEmpty()) IconButton(onClick = { ActivityLog.clear() }) {
                Icon(Icons.Default.DeleteSweep, "مسح السجل", tint = HarithColors.Muted)
            }
        }

        if (entries.isEmpty()) {
            Text(
                "لا يوجد نشاط بعد. كل ما ينفّذه الحارث يُسجَّل هنا بالوقت والإجراء والنتيجة.",
                Modifier.fillMaxWidth().padding(32.dp),
                color = HarithColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val rows = entries.mapIndexed { i, e ->
                val d = day.format(Date(e.time))
                Triple(i, e, if (i == 0 || day.format(Date(entries[i - 1].time)) != d) d else null)
            }
            items(rows, key = { it.first }) { (_, e, header) ->
                Column {
                    if (header != null) {
                        Text(
                            header, Modifier.padding(top = 8.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = HarithColors.Gold
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HarithColors.Surface)
                            .padding(12.dp)
                    ) {
                        Icon(
                            if (e.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
                            tint = if (e.ok) HarithColors.Green else HarithColors.Red,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.action, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = HarithColors.Fg)
                                Text(time.format(Date(e.time)), style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                            }
                            Text(e.source, style = MaterialTheme.typography.labelSmall, color = HarithColors.Gold)
                            if (e.command.isNotBlank()) {
                                Text(e.command, style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted, maxLines = 2)
                            }
                            Text(e.result, style = MaterialTheme.typography.bodySmall, color = HarithColors.Fg)
                        }
                    }
                }
            }
        }
    }
}
