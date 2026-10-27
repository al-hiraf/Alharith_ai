package com.alharith.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.alharith.ai.data.Pairing
import com.alharith.ai.data.Prefs
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** رابط دعوة وصل من صفحة التحميل (rafiq://join?u=…): تفتح الواجهة نافذة الانضمام وهو معبّأ */
object PendingInvite {
    val link = MutableStateFlow<String?>(null)
}

/** نتيجة «المتابعة بحساب قوقل» (rafiq://auth?code=…&su=…): رمز استبدال + عنوان الخادم */
object PendingGoogleAuth {
    val pending = MutableStateFlow<Pair<String, String>?>(null)
}

/**
 * الانضمام برابط دعوة (https://…/join/CODE): ينشئ حسابك على خادم رفيق ويربط التطبيق به.
 * إن لم يكن عندك مفتاح ذكاء خاص، يعمل رفيق بذكاء الخادم مباشرة (بلا أي مفتاح).
 */
@Composable
fun InviteJoinDialog(initialLink: String = "", onDismiss: () -> Unit, onJoined: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink) }
    var preview by remember { mutableStateOf<Result<String>?>(null) }
    // معاينة الدعوة أثناء الكتابة: من أرسلها، وهل ما زالت صالحة
    LaunchedEffect(link) {
        preview = null
        if (link.contains("/join/")) { delay(400); preview = Pairing.peek(link) }
    }
    var name by remember { mutableStateOf(Prefs.userName) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    fun canJoin() = !busy && link.isNotBlank() && preview?.isFailure != true && user.length >= 3 && pass.length >= 8
    fun submit() {
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
    }
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = HarithColors.Surface,
        title = { Text("الانضمام برابط دعوة", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(link, { link = it.trim() }, label = { Text("الصق رابط الدعوة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                preview?.let { p ->
                    p.onSuccess { Text("✓ $it", color = HarithColors.GoldText, style = MaterialTheme.typography.bodySmall) }
                    p.onFailure { Text("✗ ${it.message}", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("اسمك") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("اسم مستخدم بالإنجليزية") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة مرور (٨ أحرف على الأقل)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canJoin()) submit() }), modifier = Modifier.fillMaxWidth())
                Text("احفظ اسم المستخدم وكلمة المرور: تدخل بهما من أي جوال أو من المتصفح.", color = HarithColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                if (busy) Text("جارٍ إنشاء حسابك…", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (err.isNotBlank()) Text("✗ $err", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
                Text("— أو —", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !busy, onClick = {
                    val bm = Regex("""(https?://[^\s/]+)/join/([A-Za-z0-9-]+)""").find(link)
                    val base = bm?.groupValues?.get(1) ?: Prefs.serverUrl
                    val inv = bm?.groupValues?.get(2) ?: ""
                    if (!base.startsWith("http")) { err = "الصق رابط الدعوة أولًا (فيه عنوان الخادم)"; return@TextButton }
                    busy = true
                    scope.launch {
                        val (url, e) = Pairing.googleStartUrl(base, inv)
                        busy = false
                        if (url != null) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        else err = e ?: "تعذّر بدء دخول قوقل"
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("المتابعة بحساب قوقل — دخول وربط بريد معًا", color = HarithColors.GoldText)
                }
                Text("إن كان بريدك مسجلًا عند صاحب الخادم يدخلك مباشرة، وإلا يُنشئ حسابك برمز الدعوة — بلا كلمة مرور.",
                    color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = canJoin(), onClick = { submit() }) { Text("انضم", color = HarithColors.GoldText) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}


/** يكمل «المتابعة بحساب قوقل»: يستبدل رمز النتيجة برمز وصول ويربط التطبيق، ثم onJoined عند النجاح. */
@Composable
fun GoogleAuthRedeemDialog(code: String, serverUrl: String, onDismiss: () -> Unit, onJoined: () -> Unit) {
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(code) {
        val r = Pairing.redeemGoogle(code, serverUrl)
        busy = false
        if (r == null) onJoined() else err = r
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = HarithColors.Surface,
        title = { Text("دخول بحساب قوقل", color = HarithColors.Fg) },
        text = { Text(if (busy) "جارٍ إكمال دخولك…" else "✗ $err", color = HarithColors.Fg) },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إغلاق", color = HarithColors.Muted) } })
}


/** شاشة الدخول بعد تسجيل خروج آمن: دخول بقوقل أو برابط دعوة/كلمة مرور — بيانات الجهاز باقية وتُستأنف مزامنتها. */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(HarithColors.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("رفيق", style = MaterialTheme.typography.displayMedium, color = HarithColors.GoldText)
            Spacer(Modifier.height(8.dp))
            Text("سجّل دخولك للمتابعة — بياناتك المحفوظة ترجع مع دخولك",
                style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            TextButton(enabled = !busy, onClick = {
                val base = Prefs.serverUrl
                if (!base.startsWith("http")) { err = "عنوان الخادم غير معروف — استخدم رابط الدعوة"; return@TextButton }
                busy = true
                scope.launch {
                    val (url, e) = Pairing.googleStartUrl(base, "")
                    busy = false
                    if (url != null) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    else err = e ?: "تعذّر بدء دخول قوقل"
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("المتابعة بحساب قوقل", color = HarithColors.GoldText, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(enabled = !busy, onClick = { showInvite = true }, modifier = Modifier.fillMaxWidth()) {
                Text("دخول برابط دعوة أو اسم مستخدم وكلمة مرور", color = HarithColors.Muted)
            }
            if (err.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("✗ $err", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
    if (showInvite) InviteJoinDialog(onDismiss = { showInvite = false }) { showInvite = false; onLoggedIn() }
}
