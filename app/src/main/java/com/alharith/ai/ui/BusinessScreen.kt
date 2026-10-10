package com.alharith.ai.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * الأعمال والمالية داخل التطبيق: الجهات (شخصي وكل شركة منفصلة)، التنبيهات، تسجيل دخل/مصروف،
 * الفواتير والعروض والدفعات، المشاريع ومؤشرات الأداء، والتصدير Excel.
 * البيانات تعيش في خادم رفيق (العقل المشترك)؛ كل تعديل يمر بأدوات الخادم وقيوده
 * (لا دفع ولا إرسال، والحذف المالي بموافقة).
 */
@Composable
fun BusinessScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var home by remember { mutableStateOf<JSONObject?>(null) }
    var wsList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var wsId by remember { mutableStateOf<Long?>(null) }
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var invoices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var toast by remember { mutableStateOf("") }
    var recordDialog by remember { mutableStateOf<String?>(null) }   // income | expense
    var invoiceDialog by remember { mutableStateOf<String?>(null) }  // invoice | quote | bill
    var actionInvoice by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload, wsId) {
        if (!Prefs.sharedBrainReady) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try {
            home = SharedBrain.get("/api/biz/home")
            wsList = SharedBrain.get("/api/biz/workspaces").optJSONArray("items").objects()
            val sel = wsId ?: wsList.firstOrNull { it.optString("kind") != "personal" }?.optLong("id") ?: wsList.firstOrNull()?.optLong("id")
            if (wsId == null) wsId = sel
            if (sel != null) overview = SharedBrain.get("/api/biz/overview?workspace=$sel")
            invoices = SharedBrain.get("/api/biz/invoices?filter=unpaid").optJSONArray("items").objects()
        } catch (e: Exception) {
            error = e.message ?: "تعذّر الوصول للخادم"
        }
        loading = false
    }

    fun run(label: String, block: suspend () -> JSONObject) {
        scope.launch {
            busy = true
            toast = try { block().optString("text").ifBlank { "تم" } } catch (e: Exception) { "✗ ${e.message}" }
            busy = false
            reload++
        }
    }

    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text("الأعمال والمالية", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg)
            if (Prefs.sharedBrainReady) IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "تحديث", tint = HarithColors.Fg) }
        }

        if (!Prefs.sharedBrainReady) {
            NotLinked(onOpenSettings)
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (toast.isNotBlank()) Text(toast, color = if (toast.startsWith("✗")) HarithColors.Red else HarithColors.Green,
                style = MaterialTheme.typography.bodyMedium)
            if (loading && home == null) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Luxe.Gold)
            }
            if (error.isNotBlank()) Text("✗ $error", color = HarithColors.Red, style = MaterialTheme.typography.bodyMedium)

            // ——— إجراءات سريعة
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("+ مصروف") { recordDialog = "expense" }
                ActionChip("+ دخل") { recordDialog = "income" }
                ActionChip("+ فاتورة") { invoiceDialog = "invoice" }
                ActionChip("+ عرض سعر") { invoiceDialog = "quote" }
                ActionChip("+ فاتورة مورد") { invoiceDialog = "bill" }
                ActionChip("⬇ تقرير Excel") {
                    val ws = wsId ?: return@ActionChip
                    scope.launch {
                        busy = true
                        toast = try {
                            val r = SharedBrain.tool("export_report", JSONObject().put("kind", "report").put("workspace", ws).put("format", "xlsx"))
                            val d = r.optJSONObject("data") ?: throw Exception(r.optString("text"))
                            val f = SharedBrain.download(d.optLong("file_id"), d.optString("name", "report.xlsx"))
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "مشاركة التقرير").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            "جُهّز التقرير ${f.name}"
                        } catch (e: Exception) { "✗ ${e.message}" }
                        busy = false
                    }
                }
            }
            if (busy) Text("رفيق يعمل…", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)

            // ——— التنبيهات والالتزامات
            home?.optJSONObject("alerts")?.let { a -> AlertsCard(a) }

            // ——— الجهات (كل جهة منفصلة)
            val wss = home?.optJSONArray("workspaces").objects()
            if (wss.isNotEmpty()) {
                SectionTitle("ملخص هذا الشهر")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (w in wss) {
                        val sel = w.optLong("id") == wsId
                        GlassCard(
                            Modifier.width(230.dp).clip(RoundedCornerShape(20.dp)).clickable { wsId = w.optLong("id") }
                                .then(if (sel) Modifier.border(1.5.dp, Luxe.Gold, RoundedCornerShape(20.dp)) else Modifier),
                            RoundedCornerShape(20.dp), strong = sel
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(w.optString("workspace"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
                                    Text(w.optString("kind"), color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                val totals = w.optJSONArray("totals").objects()
                                if (totals.isEmpty()) Text("لا قيود هذا الشهر", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                                for (x in totals) {
                                    Text("+${x.optString("income_text")}   −${x.optString("expense_text")}", color = HarithColors.Fg.copy(alpha = 0.8f),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("الصافي: ${x.optString("net_text")}", color = if (x.optDouble("net") < 0) HarithColors.Red else Luxe.Gold,
                                        style = MaterialTheme.typography.titleSmall)
                                }
                                val rec = w.optJSONArray("receivables").objects()
                                if (rec.isNotEmpty()) Text("مستحق لك: " + rec.joinToString(" + ") { it.optString("amount_text") },
                                    color = HarithColors.Fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                                if (w.optInt("overdue_count") > 0) Text("${arNum(w.optInt("overdue_count"))} فاتورة متأخرة",
                                    color = HarithColors.Red, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Text("الأرقام من تسجيلك فقط — لا ربط بنكي، ولا تُنفَّذ مدفوعات من هنا.", color = HarithColors.Muted,
                    style = MaterialTheme.typography.labelSmall)
            }

            // ——— الجهة المختارة: المشاريع والمؤشرات والميزانيات
            overview?.let { ov -> WorkspaceDetail(ov) }

            // ——— الفواتير غير المدفوعة
            SectionTitle("فواتير وعروض مفتوحة")
            if (invoices.isEmpty() && !loading) Text("لا فواتير مفتوحة.", color = HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
            for (inv in invoices.take(30)) {
                GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { actionInvoice = inv }, RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${inv.optString("kind_ar")} ${inv.optString("number")}", color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(inv.optString("contact"), inv.optString("workspace"),
                                inv.optString("due_on").takeIf { it.isNotBlank() }?.let { "الاستحقاق $it" }).filterNot { it.isNullOrBlank() }.joinToString(" · "),
                                color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(inv.optString("outstanding_text"), color = Luxe.Gold, style = MaterialTheme.typography.titleSmall)
                            Text(inv.optString("status_ar"), color = if (inv.optString("status") == "overdue") HarithColors.Red else HarithColors.Muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    recordDialog?.let { kind ->
        RecordDialog(kind, wsList, wsId, onDismiss = { recordDialog = null }) { args ->
            recordDialog = null
            run("record") { SharedBrain.tool("finance_record", args) }
        }
    }
    invoiceDialog?.let { kind ->
        InvoiceDialog(kind, wsList, wsId, onDismiss = { invoiceDialog = null }) { args ->
            invoiceDialog = null
            run("invoice") { SharedBrain.tool("invoice_create", args) }
        }
    }
    actionInvoice?.let { inv ->
        InvoiceActionsDialog(inv, onDismiss = { actionInvoice = null }) { tool, args ->
            actionInvoice = null
            run(tool) { SharedBrain.tool(tool, args) }
        }
    }
}

@Composable
private fun NotLinked(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.alharith.ai.R.drawable.rafiq_mark), null, Modifier.size(110.dp))
        Text("الأعمال والمالية تعمل مع خادم رفيق", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg, textAlign = TextAlign.Center)
        Text("الشركات، الدخل والمصروفات، الفواتير وعروض الأسعار، العملاء والموردون، والتقارير — تُحفظ في خادمك الذي يعمل على مدار الساعة. " +
            "اربط الخادم مرة واحدة من الإعدادات ← «العقل المشترك».",
            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center)
        TextButton(onClick = onOpenSettings) { Text("افتح الإعدادات", color = Luxe.Gold) }
    }
}

@Composable
private fun AlertsCard(a: JSONObject) {
    val lines = mutableListOf<Pair<Boolean, String>>()
    a.optJSONArray("overdue_invoices").objects().forEach {
        lines += true to "${it.optString("kind_ar")} ${it.optString("number")} — ${it.optString("contact")}: متأخرة منذ ${it.optString("due_on")}، المتبقي ${it.optString("outstanding_text")}"
    }
    a.optJSONArray("recurring_next_7_days").objects().forEach {
        lines += false to "${it.optString("category")} ${it.optString("amount_text")} — ${it.optString("workspace")} · ${it.optString("date")}"
    }
    a.optJSONArray("due_within_7_days").objects().forEach {
        lines += false to "${it.optString("kind_ar")} ${it.optString("number")} تستحق ${it.optString("due_on")} · ${it.optString("outstanding_text")}"
    }
    a.optJSONArray("contracts_ending_30_days").objects().forEach {
        lines += false to "عقد ${it.optString("kind_ar")} ${it.optString("name")} ينتهي ${it.optString("contract_end")}"
    }
    a.optJSONArray("upcoming_meetings").objects().forEach {
        lines += false to "اجتماع: ${it.optString("title")} — ${it.optString("when")}"
    }
    if (lines.isEmpty()) return
    SectionTitle("التنبيهات والالتزامات")
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bad, l) in lines.take(10)) Text((if (bad) "⚠️ " else "• ") + l,
                color = if (bad) HarithColors.Red else HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WorkspaceDetail(ov: JSONObject) {
    val w = ov.optJSONObject("workspace") ?: return
    SectionTitle(w.optString("name"))
    val fin = ov.optJSONObject("finance")
    fin?.optJSONArray("budgets").objects().takeIf { it.isNotEmpty() }?.let { budgets ->
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الميزانية مقابل الفعلي", color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
                for (b in budgets) {
                    val pct = b.optInt("percent")
                    Row { Text(b.optString("category"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                        Text("${b.optString("spent_text")} / ${b.optString("budget_text")}", color = if (pct > 100) HarithColors.Red else HarithColors.Muted,
                            style = MaterialTheme.typography.bodySmall) }
                    Bar(pct, pct > 100)
                }
            }
        }
    }
    val projects = ov.optJSONArray("projects").objects()
    val kpis = ov.optJSONArray("kpis").objects()
    if (projects.isNotEmpty() || kpis.isNotEmpty()) GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (projects.isNotEmpty()) Text("المشاريع", color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
            for (p in projects) {
                Row { Text(p.optString("name"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                    Text("${arNum(p.optInt("progress"))}٪", color = Luxe.Gold, style = MaterialTheme.typography.bodySmall) }
                Bar(p.optInt("progress"), false)
                if (p.optString("budget_text").isNotBlank()) Text("المصروف ${p.optString("spent_text")} من ${p.optString("budget_text")}",
                    color = if (p.optBoolean("over_budget")) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (kpis.isNotEmpty()) Text("مؤشرات الأداء", color = HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
            for (k in kpis) {
                Row { Text(k.optString("name"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                    Text("${k.optString("current")} / ${k.optString("target")} ${k.optString("unit")}", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall) }
                if (!k.isNull("percent")) Bar(k.optInt("percent"), false)
            }
        }
    }
}

@Composable
private fun Bar(pct: Int, over: Boolean) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(HarithColors.Line)) {
        Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(if (over) HarithColors.Red else Luxe.Gold))
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = HarithColors.Fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, Luxe.Gold.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), color = Luxe.Gold, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun WsPicker(list: List<JSONObject>, selected: Long?, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in list) {
            val sel = w.optLong("id") == selected
            Text(w.optString("name"), Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (sel) Luxe.Gold else HarithColors.Surface).clickable { onPick(w.optLong("id")) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (sel) HarithColors.OnGold else HarithColors.Fg, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RecordDialog(kind: String, wsList: List<JSONObject>, defaultWs: Long?, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var ws by remember { mutableStateOf(defaultWs) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == "income") "تسجيل دخل" else "تسجيل مصروف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الجهة", color = HarithColors.Muted, style = MaterialTheme.typography.labelMedium)
                WsPicker(wsList, ws) { ws = it }
                OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(category, { category = it }, label = { Text("البند (مواد، رواتب، وقود…)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("وصف / الجهة") }, singleLine = true)
                Text("تسجيل فقط — رفيق لا يحوّل أموالًا.", color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (amount.isBlank()) return@TextButton
                onSave(JSONObject().put("kind", kind).put("amount", amount).put("category", category.ifBlank { "عام" })
                    .put("note", note).apply { ws?.let { put("workspace", it) } })
            }) { Text("تسجيل", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun InvoiceDialog(kind: String, wsList: List<JSONObject>, defaultWs: Long?, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var ws by remember { mutableStateOf(defaultWs) }
    var contact by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    val title = when (kind) { "quote" -> "عرض سعر جديد"; "bill" -> "فاتورة مورد"; else -> "فاتورة جديدة" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WsPicker(wsList.filter { it.optString("kind") != "personal" }.ifEmpty { wsList }, ws) { ws = it }
                OutlinedTextField(contact, { contact = it }, label = { Text(if (kind == "bill") "المورد" else "العميل") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("الوصف") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qty, { qty = it }, Modifier.weight(1f), label = { Text("الكمية") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(price, { price = it }, Modifier.weight(1.4f), label = { Text("سعر الوحدة") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(due, { due = it }, label = { Text(if (kind == "quote") "صالح حتى (2026-11-30)" else "الاستحقاق (اختياري)") }, singleLine = true)
                Text("تُنشأ مسودة. الضريبة تُحسب حسب الجهة. أنت من يرسلها.", color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (desc.isBlank() || price.isBlank()) return@TextButton
                onSave(JSONObject().put("kind", kind).put("contact", contact).put("title", desc)
                    .put("items", JSONArray().put(JSONObject().put("description", desc).put("qty", qty.toDoubleOrNull() ?: 1.0).put("price", price)))
                    .apply { ws?.let { put("workspace", it) }; if (due.isNotBlank()) put("due_on", due.trim()) })
            }) { Text("إنشاء مسودة", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun InvoiceActionsDialog(inv: JSONObject, onDismiss: () -> Unit, onTool: (String, JSONObject) -> Unit) {
    var pay by remember { mutableStateOf(inv.optDouble("outstanding").let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }) }
    val number = inv.optString("number")
    val isQuote = inv.optString("kind") == "quote"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${inv.optString("kind_ar")} $number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${inv.optString("contact")} · الإجمالي ${inv.optString("total_text")} · ${inv.optString("status_ar")}",
                    color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (inv.optString("status") == "draft") ActionChip("أرسلتُها بنفسي") {
                    onTool("invoice_update", JSONObject().put("invoice", number).put("status", "sent"))
                }
                if (isQuote) {
                    ActionChip("قُبل ← حوّله لفاتورة") { onTool("invoice_from_quote", JSONObject().put("quote", number)) }
                    ActionChip("رُفض") { onTool("invoice_update", JSONObject().put("invoice", number).put("status", "rejected")) }
                } else {
                    OutlinedTextField(pay, { pay = it }, label = { Text(if (inv.optString("kind") == "bill") "دفعة دفعتها للمورد" else "دفعة استلمتها") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    ActionChip("سجّل الدفعة") {
                        if (pay.isNotBlank()) onTool("invoice_update", JSONObject().put("invoice", number).put("payment", pay))
                    }
                    Text("تسجيل فقط — لا يُحوَّل أي مال.", color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
