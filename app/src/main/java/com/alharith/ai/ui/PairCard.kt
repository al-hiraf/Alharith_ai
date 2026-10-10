package com.alharith.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alharith.ai.data.Pairing
import kotlinx.coroutines.launch

/** «اربط برمز»: 6 أرقام من Termux (rafiq-pair) أو من لوحة التحكم، ويتم الباقي تلقائيًا. */
@Composable
fun PairCard(modifier: Modifier = Modifier, onPaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    GlassCard(modifier.fillMaxWidth(), RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("اربط برمز", style = MaterialTheme.typography.titleMedium, color = HarithColors.Fg)
            Text("١) في تطبيق Termux اكتب:  rafiq-pair\n٢) سيظهر رمز من 6 أرقام — اكتبه هنا.",
                style = MaterialTheme.typography.bodyMedium, color = HarithColors.Fg.copy(alpha = 0.8f))
            OutlinedTextField(
                code, { v -> code = v.filter { it.isDigit() }.take(6); msg = "" },
                Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("● ● ● ● ● ●") },
                textStyle = TextStyle(fontSize = 26.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            Button(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true; msg = "أبحث عن خادمك وأربطه…"
                    scope.launch {
                        val err = Pairing.pair(code)
                        busy = false
                        if (err == null) { msg = "✓ تم الربط"; onPaired() } else msg = "✗ $err"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Luxe.Gold, contentColor = HarithColors.OnGold)
            ) { Text(if (busy) "جارٍ الربط…" else "اربط") }
            if (msg.isNotBlank()) Text(msg, color = when {
                msg.startsWith("✓") -> HarithColors.Green; msg.startsWith("✗") -> HarithColors.Red; else -> HarithColors.Muted
            }, style = MaterialTheme.typography.bodyMedium)
            Text("أو من لوحة التحكم: الإعدادات ← «رمز ربط سريع».", style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
        }
    }
}
