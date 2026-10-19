package com.alharith.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.Pairing
import com.alharith.ai.data.Prefs
import kotlinx.coroutines.launch

/**
 * الانضمام برابط دعوة (https://…/join/CODE): ينشئ حسابك على خادم رفيق ويربط التطبيق به.
 * إن لم يكن عندك مفتاح ذكاء خاص، يعمل رفيق بذكاء الخادم مباشرة (بلا أي مفتاح).
 */
@Composable
fun InviteJoinDialog(onDismiss: () -> Unit, onJoined: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Prefs.userName) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = HarithColors.Surface,
        title = { Text("الانضمام برابط دعوة", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(link, { link = it.trim() }, label = { Text("الصق رابط الدعوة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("اسمك") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("اسم مستخدم بالإنجليزية") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة مرور (٨ أحرف على الأقل)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("احفظ اسم المستخدم وكلمة المرور: تدخل بهما من أي جوال أو من المتصفح.", color = HarithColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                if (busy) Text("جارٍ إنشاء حسابك…", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (err.isNotBlank()) Text("✗ $err", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && link.isNotBlank() && user.length >= 3 && pass.length >= 8, onClick = {
                busy = true; err = ""
                scope.launch {
                    val r = Pairing.join(link, name.trim(), user, pass)
                    busy = false
                    if (r.first == null) {
                        if (name.isNotBlank()) Prefs.userName = name.trim()
                        // بلا مفتاح خاص: نستخدم ذكاء الخادم
                        if (Prefs.aiKeyMissing) Prefs.provider = "rafiq"
                        onJoined()
                    } else err = r.first!!
                }
            }) { Text("انضم", color = HarithColors.GoldText) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}
