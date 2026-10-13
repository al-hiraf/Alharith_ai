package com.alharith.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.Pairing
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TERMUX_APK =
    "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_arm64-v8a.apk"
private const val CMD_UPGRADE = "yes | pkg upgrade -y -o Dpkg::Options::=--force-confold"
private const val CMD_INSTALL =
    "curl -fsSL https://raw.githubusercontent.com/al-hiraf/Alharith_ai/main/server/install-termux.sh | bash"

/**
 * «خادمي»: كل ما تعلّمناه في الإعداد صار هنا — حالة الخادم، الربط، رابط الإنترنت وفحصه،
 * مفاتيح الخدمات الخارجية (مثل Instinct) وإلغاؤها، وأوامر Termux جاهزة للنسخ.
 */
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var localUp by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var publicCheck by remember { mutableStateOf("") }
    var newKeyDialog by remember { mutableStateOf(false) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }

    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text)); toast = "✓ نُسخ $label"
    }

    LaunchedEffect(reload) {
        loading = true; error = ""
        localUp = withContext(Dispatchers.IO) { Pairing.healthy(Pairing.LOCAL) }
        info = if (Prefs.sharedBrainReady) try { SharedBrain.get("/api/server-info") } catch (e: Exception) {
            error = e.message ?: "تعذّر الوصول للخادم"; null
        } else null
        loading = false
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("خادمي", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "تحديث", tint = HarithColors.Fg) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (toast.isNotBlank()) Text(toast, color = if (toast.startsWith("✗")) HarithColors.Red else HarithColors.Green,
                style = MaterialTheme.typography.bodyMedium)
            if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Luxe.Gold)
            }

            // ——— الحالة
            val linked = Prefs.sharedBrainReady && info != null
            Card("الحالة") {
                StatusRow("الخادم على هذا الجوال", localUp, if (localUp) "يعمل" else "متوقف أو غير مثبّت")
                StatusRow("التطبيق مربوط بالخادم", linked, if (linked) "مربوط" else if (Prefs.sharedBrainReady) "مربوط لكن لا يرد" else "غير مربوط")
                info?.let { i ->
                    StatusRow("الذكاء الاصطناعي في الخادم", i.optJSONObject("ai")?.optBoolean("configured") == true,
                        i.optJSONObject("ai")?.optString("provider").orEmpty())
                    StatusRow("تيليجرام", i.optBoolean("telegram"), if (i.optBoolean("telegram")) "مفعّل" else "غير مُعد")
                    Text("إصدار الخادم ${i.optString("version")}", color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (error.isNotBlank()) Text("✗ $error", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
            }

            // ——— غير مثبّت: دليل خطوة بخطوة مع أزرار نسخ
            if (!localUp && !linked) Card("ثبّت الخادم (مرة واحدة)") {
                Step("1", "نزّل Termux وثبّته. إن ظهر «تم حظر تطبيق غير آمن»: مزيد من التفاصيل ← التثبيت على أي حال.") {
                    Action("تنزيل Termux") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_APK))) }
                }
                Step("2", "افتح Termux والصق أمر التحديث ثم Enter، وانتظر حتى يظهر $.") {
                    Action("نسخ أمر التحديث") { copy("أمر التحديث", CMD_UPGRADE) }
                    Action("افتح Termux") { openTermux(context) { toast = it } }
                }
                Step("3", "الصق أمر التثبيت ثم Enter. عند سؤال «الوصول من الإنترنت» اكتب y، وأي سؤال آخر Enter.") {
                    Action("نسخ أمر التثبيت") { copy("أمر التثبيت", CMD_INSTALL) }
                }
                Step("4", "في النهاية يظهر رمز ربط من 6 أرقام — اكتبه في الأسفل.") {}
            }

            // ——— يعمل لكن غير مربوط: الربط بالرمز
            if (!linked) PairCard { reload++ }

            if (linked) {
                val i = info!!
                // ——— رابط الإنترنت
                Card("رابط خادمك على الإنترنت") {
                    val url = i.optString("public_url")
                    if (url.isBlank()) {
                        Text("غير مفعّل. لتفعيله: انسخ أمر التثبيت والصقه في Termux، واكتب y عند سؤال «الوصول من الإنترنت».",
                            color = HarithColors.Fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                        Action("نسخ أمر التثبيت") { copy("أمر التثبيت", CMD_INSTALL) }
                    } else {
                        Mono(url)
                        Text("يتغيّر عند إعادة تشغيل الخادم — يصلك تنبيه بالرابط الجديد لتحدّثه في Instinct.",
                            color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("نسخ الرابط") { copy("الرابط", url) }
                            Action("افحص") {
                                publicCheck = "أفحص…"
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { Pairing.healthy(url) }
                                    publicCheck = if (ok) "✓ يفتح من الإنترنت" else "✗ لا يفتح الآن — شغّل في Termux: sv up rafiq-tunnel"
                                }
                            }
                        }
                        if (publicCheck.isNotBlank()) Text(publicCheck, color = if (publicCheck.startsWith("✓")) HarithColors.Green
                            else if (publicCheck.startsWith("✗")) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // ——— مفاتيح الخدمات الخارجية
                Card("مفاتيح الخدمات (Instinct وغيرها)") {
                    Text("كل خدمة تأخذ مفتاحًا خاصًا بها. لا تلصق المفتاح في محادثة؛ ضعه في خانة الخدمة الآمنة فقط.",
                        color = HarithColors.Fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    Action("+ مفتاح جديد لخدمة") { newKeyDialog = true }
                    val tokens = i.optJSONArray("tokens")
                    for (k in 0 until (tokens?.length() ?: 0)) {
                        val t = tokens!!.optJSONObject(k) ?: continue
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.optString("name"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyLarge)
                                Text("آخر استخدام: " + t.optString("last_used").takeIf { it.isNotBlank() && it != "null" }?.take(16)?.replace("T", " ").orEmpty()
                                    .ifBlank { "لم يُستخدم" }, color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { revoke = t }) { Text("إلغاء", color = HarithColors.Red) }
                        }
                    }
                }
            }

            // ——— أوامر Termux الجاهزة
            Card("أوامر Termux الجاهزة") {
                CommandRow("رمز ربط جديد", "rafiq-pair") { copy("الأمر", it) }
                CommandRow("مفتاح + رابط لخدمة خارجية", "rafiq-token") { copy("الأمر", it) }
                CommandRow("عرض رابط الإنترنت", "cat ~/rafiq/server/data/tunnel-url.txt") { copy("الأمر", it) }
                CommandRow("تشغيل الخادم إن توقف", "sv up rafiq; sv up rafiq-tunnel") { copy("الأمر", it) }
                CommandRow("تحديث الخادم", CMD_INSTALL) { copy("الأمر", it) }
                Action("افتح Termux") { openTermux(context) { toast = it } }
            }
            Text("ليعمل الخادم دائمًا: الإعدادات ← التطبيقات ← Termux ← البطارية ← «غير مقيّد».",
                color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (newKeyDialog) {
        var name by remember { mutableStateOf("Instinct") }
        AlertDialog(
            onDismissRequest = { newKeyDialog = false },
            title = { Text("مفتاح جديد") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("اسم الخدمة") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    newKeyDialog = false
                    scope.launch {
                        try {
                            shownKey = SharedBrain.post("/api/tokens", JSONObject().put("name", name.ifBlank { "خدمة خارجية" })).optString("token")
                            reload++
                        } catch (e: Exception) { toast = "✗ ${e.message}" }
                    }
                }) { Text("إنشاء", color = Luxe.Gold) }
            },
            dismissButton = { TextButton(onClick = { newKeyDialog = false }) { Text("إلغاء") } }
        )
    }
    shownKey?.let { key ->
        AlertDialog(
            onDismissRequest = { shownKey = null },
            title = { Text("المفتاح — يظهر مرة واحدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono(key)
                    Text("انسخه والصقه في خانة الخدمة الآمنة (مثل «احفظ في الخزنة» في Instinct). لا ترسله في محادثة.",
                        style = MaterialTheme.typography.bodySmall, color = HarithColors.Muted)
                    info?.optString("public_url")?.takeIf { it.isNotBlank() }?.let { Text("العنوان: $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { copy("المفتاح", key); shownKey = null }) { Text("نسخ وإغلاق", color = Luxe.Gold) } }
        )
    }
    revoke?.let { t ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("إلغاء مفتاح «${t.optString("name")}»؟") },
            text = { Text("أي خدمة تستخدمه ستتوقف عن الوصول لرفيق فورًا.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = t.optLong("id"); revoke = null
                    scope.launch {
                        toast = try { SharedBrain.delete("/api/tokens/$id"); "✓ أُلغي المفتاح" } catch (e: Exception) { "✗ ${e.message}" }
                        reload++
                    }
                }) { Text("إلغاء المفتاح", color = HarithColors.Red) }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("رجوع") } }
        )
    }
}

private fun openTermux(context: android.content.Context, onError: (String) -> Unit) {
    val i = context.packageManager.getLaunchIntentForPackage("com.termux")
    if (i != null) context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    else onError("✗ Termux غير مثبّت — نزّله من الخطوة 1")
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = HarithColors.Fg, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) HarithColors.Green else HarithColors.Red))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
        Text(detail, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Step(n: String, text: String, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Luxe.Gold), contentAlignment = Alignment.Center) {
            Text(n, color = HarithColors.OnGold, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Luxe.Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), color = Luxe.Gold, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Mono(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(10.dp),
        color = HarithColors.Fg, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
}

@Composable
private fun CommandRow(label: String, cmd: String, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onCopy(cmd) }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
            Text("نسخ", color = Luxe.Gold, style = MaterialTheme.typography.labelLarge)
        }
        Mono(cmd)
    }
}
