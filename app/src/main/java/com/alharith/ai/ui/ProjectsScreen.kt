package com.alharith.ai.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * إدارة المشاريع داخل التطبيق: قائمة المشاريع بصحتها ونسب إنجازها، إنشاء مشروع من قالب جاهز
 * (مراحل ومهام ومواعيد واعتماديات)، لوحة مهام بأعمدة (للتنفيذ/قيد التنفيذ/بانتظار/مكتملة)،
 * المراحل والجدول الزمني، عبء الفريق، وتعديل المهمة كاملًا مع تسجيل الوقت.
 * البيانات في خادم رفيق؛ كل تعديل يمر بأدوات الخادم (السجل والصلاحيات).
 */
private val COLS = listOf("todo" to "للتنفيذ", "doing" to "قيد التنفيذ", "waiting" to "بانتظار", "done" to "مكتملة")

@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    if (openId != null) ProjectDetail(openId!!, onBack = { openId = null; reload++ })
    else ProjectList(reload, onBack, onOpen = { openId = it }, onReload = { reload++ })
}

// ———————————————————————————— قائمة المشاريع
@Composable
private fun ProjectList(reload: Int, onBack: () -> Unit, onOpen: (Long) -> Unit, onReload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var workload by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var templates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var showTemplate by remember { mutableStateOf(false) }
    var showBlank by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var linkedTick by remember { mutableIntStateOf(0) }
    var myTasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var showJoin by remember { mutableStateOf(false) }
    var memberTask by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(reload, linkedTick) {
        if (!Prefs.sharedBrainReady) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try {
            projects = SharedBrain.get("/api/projects").optJSONArray("items").objs()
            workload = SharedBrain.get("/api/workload").optJSONArray("items").objs()
            templates = SharedBrain.get("/api/pm/templates").optJSONArray("items").objs()
            myTasks = runCatching { SharedBrain.get("/api/team/my-tasks").optJSONArray("items").objs() }.getOrDefault(emptyList())
        } catch (e: Exception) { error = e.message ?: "تعذّر الوصول للخادم: تأكد أن رفيق يعمل في Termux وأن الإنترنت متصل، ثم اضغط ↻" }
        loading = false
    }

    fun create(tool: String, args: JSONObject) {
        scope.launch {
            busy = true
            try {
                val r = SharedBrain.tool(tool, args)
                toast = r.optString("text").ifBlank { "تم" }
                val id = r.optJSONObject("data")?.optLong("id") ?: 0L
                if (id > 0) onOpen(id) else onReload()
            } catch (e: Exception) { toast = "✗ ${e.message}" }
            busy = false
        }
    }

    Screen("المشاريع", onBack, onRefresh = if (Prefs.sharedBrainReady) onReload else null) {
        if (!Prefs.sharedBrainReady) {
            NotLinkedPm()
            PairCard(Modifier.padding(horizontal = 16.dp)) { linkedTick++ }
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("أو: وصلك رابط دعوة لفريق مشروع؟", color = HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Chip("👥 انضم لفريق", primary = true) { showJoin = true }
            }
            if (showJoin) JoinTeamDialog(onDismiss = { showJoin = false }) { showJoin = false; linkedTick++ }
            return@Screen
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("+ مشروع من قالب", primary = true) { showTemplate = true }
                Chip("+ مشروع فارغ") { showBlank = true }
                Chip("👥 انضم لفريق") { showJoin = true }
            }
            if (myTasks.isNotEmpty()) {
                Title("مهامي في الفريق")
                for (mt in myTasks.take(20)) GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { memberTask = mt },
                    RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(mt.optString("title"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(mt.optString("project"), mt.optString("column_ar"),
                            mt.optString("due").takeIf { it.isNotBlank() }?.let { "⏰ $it" }).joinToString(" · "),
                            color = if (mt.optBoolean("overdue")) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (busy) Text("رفيق يجهّز المشروع…", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
            Toast(toast)
            if (loading && projects.isEmpty()) Loading()
            if (error.contains("404")) UpdateServerCard()
            else if (error.isNotBlank()) Text("✗ $error", color = HarithColors.Red, style = MaterialTheme.typography.bodyMedium)
            if (!loading && projects.isEmpty() && error.isBlank()) GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("لا مشاريع بعد", color = HarithColors.Fg, style = MaterialTheme.typography.titleMedium)
                    Text("ابدأ من قالب جاهز (فيلا/إنشاء، تطبيق، فعالية، حملة تسويقية) فيُبنى المشروع كاملًا بمراحله ومهامه ومواعيده، ثم عدّل ما تريد.",
                        color = HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            for (p in projects) ProjectCard(p) { onOpen(p.optLong("id")) }

            if (workload.isNotEmpty()) {
                Title("عبء الفريق (كل المشاريع)")
                WorkloadCard(workload)
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showTemplate) TemplateDialog(templates, onDismiss = { showTemplate = false }) { args ->
        showTemplate = false; create("project_from_template", args)
    }
    if (showBlank) BlankProjectDialog(onDismiss = { showBlank = false }) { args ->
        showBlank = false; create("create_project", args)
    }
    if (showJoin && Prefs.sharedBrainReady) JoinTeamDialog(onDismiss = { showJoin = false }) { pid ->
        showJoin = false; if (pid > 0) onOpen(pid) else onReload()
    }
    memberTask?.let { mt ->
        MemberTaskDialog(mt, onDismiss = { memberTask = null }) { col, mins ->
            memberTask = null
            scope.launch {
                toast = try {
                    if (mins > 0) SharedBrain.post("/api/team/tasks/${mt.optLong("id")}/log", JSONObject().put("minutes", mins))
                    if (col != mt.optString("column")) SharedBrain.post("/api/team/tasks/${mt.optLong("id")}/move", JSONObject().put("column", col))
                    "حُفظ"
                } catch (e: Exception) { "✗ ${e.message}" }
                onReload()
            }
        }
    }
}

@Composable
private fun ProjectCard(p: JSONObject, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.optString("name"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.optString("health").isNotBlank()) HealthChip(p.optString("health"), p.optString("health_ar"))
            }
            if (p.optString("role") == "member") Text("👥 فريق — صاحب المشروع: ${p.optString("owner_name")}", color = Luxe.Gold,
                style = MaterialTheme.typography.labelMedium)
            else if (p.optInt("members") > 0) Text("👥 ${arNum(p.optInt("members"))} في الفريق", color = HarithColors.Muted,
                style = MaterialTheme.typography.labelMedium)
            val meta = listOf(
                p.optString("workspace").takeIf { it.isNotBlank() && it != "null" },
                p.optString("owner").takeIf { it.isNotBlank() && it != "null" }?.let { "المدير: $it" },
                dates(p.optString("start_on"), p.optString("end_on")),
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
            val pct = p.optInt("progress")
            Bar(pct)
            Text("الإنجاز ${arNum(pct)}٪ · ${arNum(p.optInt("tasks_done"))} من ${arNum(p.optInt("tasks_total"))} مهمة",
                color = HarithColors.Fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ———————————————————————————— تفاصيل المشروع
@Composable
private fun ProjectDetail(id: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var project by remember { mutableStateOf<JSONObject?>(null) }
    var board by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var timeline by remember { mutableStateOf<JSONObject?>(null) }
    var health by remember { mutableStateOf<JSONObject?>(null) }
    var team by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tab by rememberSaveable { mutableStateOf("board") }
    var editTask by remember { mutableStateOf<JSONObject?>(null) }
    var msDialog by remember { mutableStateOf<JSONObject?>(null) }   // {} = جديدة
    var editProject by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("owner") }
    var me by remember { mutableStateOf(0L) }
    var teamInfo by remember { mutableStateOf<JSONObject?>(null) }
    var memberTask by remember { mutableStateOf<JSONObject?>(null) }
    var invite by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(reload) {
        loading = true; error = ""
        try {
            project = SharedBrain.get("/api/projects/$id").optJSONObject("project")
            val bd = SharedBrain.get("/api/projects/$id/board")
            board = bd.optJSONArray("columns").objs()
            role = bd.optString("role").ifBlank { "owner" }; me = bd.optLong("me")
            teamInfo = runCatching { SharedBrain.get("/api/projects/$id/team") }.getOrNull()
            timeline = SharedBrain.get("/api/projects/$id/timeline")
            val h = SharedBrain.get("/api/projects/$id/health")
            health = h.optJSONObject("health"); team = h.optJSONArray("workload").objs()
        } catch (e: Exception) { error = e.message ?: "تعذّر الوصول للخادم: تأكد أن رفيق يعمل في Termux وأن الإنترنت متصل، ثم اضغط ↻" }
        loading = false
    }

    fun run(tool: String, args: JSONObject, onFail: ((String) -> Unit)? = null) {
        scope.launch {
            toast = try { SharedBrain.tool(tool, args).optString("text").ifBlank { "تم" } }
            catch (e: Exception) { onFail?.invoke(e.message ?: ""); "✗ ${e.message}" }
            reload++
        }
    }
    val allTasks = board.flatMap { it.optJSONArray("tasks").objs() }
    val milestones = timeline?.optJSONArray("milestones").objs()
    val owner = role != "member"
    val members = teamInfo?.optJSONArray("members").objs()
    fun canEdit(t: JSONObject) = owner || (t.optLong("assignee_user_id") == me && me > 0)
    fun memberMove(t: JSONObject, to: String) {
        scope.launch {
            toast = try { SharedBrain.post("/api/team/tasks/${t.optLong("id")}/move", JSONObject().put("column", to)).optString("text").ifBlank { "تم" } }
            catch (e: Exception) { "✗ ${e.message}" }
            reload++
        }
    }

    Screen(project?.optString("name") ?: "المشروع", onBack, onRefresh = { reload++ }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading && project == null) Loading()
            if (error.contains("404")) UpdateServerCard()
            else if (error.isNotBlank()) Text("✗ $error", color = HarithColors.Red, style = MaterialTheme.typography.bodyMedium)
            project?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(p.optString("owner").takeIf { it.isNotBlank() && it != "null" }?.let { "المدير: $it" },
                        p.optString("owner_name").takeIf { !owner && it.isNotBlank() }?.let { "صاحب المشروع: $it" },
                        dates(p.optString("start_on"), p.optString("end_on"))).joinToString(" · ").ifBlank { "بدون تواريخ" },
                        Modifier.weight(1f), color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                    if (owner) Chip("تعديل") { editProject = true }
                    else Text("عضو في الفريق", color = Luxe.Gold, style = MaterialTheme.typography.labelMedium)
                }
            }
            health?.let { h -> HealthCard(h) }
            Toast(toast)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, l) in listOf("board" to "اللوحة", "phases" to "المراحل والجدول",
                    "team" to (if (members.isNotEmpty()) "الفريق (${arNum(members.size)})" else "الفريق"))) Tab(l, tab == k) { tab = k }
            }

            when (tab) {
                "board" -> {
                    if (owner) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newTask, { newTask = it }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("مهمة جديدة في «للتنفيذ»…") })
                        Chip("إضافة", primary = true) {
                            val t = newTask.trim(); if (t.isEmpty()) return@Chip
                            newTask = ""; run("add_task", JSONObject().put("title", t).put("project", id.toString()))
                        }
                    }
                    Text(if (owner) "اسحب اللوحة يمينًا ويسارًا · الأسهم تنقل المهمة بين الأعمدة · اضغط المهمة لتعديلها"
                        else "مهامك محاطة بالذهبي: تنقلها بالأسهم وتسجّل وقتك بالضغط عليها. بقية المهام للاطلاع.",
                        color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in board) KanbanColumn(c, me, ::canEdit, onOpen = { t -> if (owner) editTask = t else if (canEdit(t)) memberTask = t }) { task, to ->
                            if (owner) run("task_move", JSONObject().put("task", task.optLong("id")).put("column", to)) else memberMove(task, to)
                        }
                    }
                }
                "phases" -> PhasesTab(timeline, allTasks, owner, onEdit = { msDialog = it }, onAdd = { msDialog = JSONObject() },
                    onToggle = { m -> run("milestone_update", JSONObject().put("id", m.optLong("id"))
                        .put("status", if (m.optString("status") == "done") "open" else "done")) })
                "team" -> TeamTab(teamInfo, owner, me, team,
                    onInvite = {
                        scope.launch {
                            try { invite = SharedBrain.post("/api/projects/$id/invite") } catch (e: Exception) { toast = "✗ ${e.message}" }
                        }
                    },
                    onAddUser = { uname ->
                        scope.launch {
                            toast = try { SharedBrain.post("/api/projects/$id/members", JSONObject().put("username", uname)).optString("message").ifBlank { "تم" } }
                            catch (e: Exception) { "✗ ${e.message}" }
                            reload++
                        }
                    },
                    onRemove = { uid ->
                        scope.launch {
                            toast = try { SharedBrain.delete("/api/projects/$id/members/$uid"); "تم" } catch (e: Exception) { "✗ ${e.message}" }
                            if (uid == me) onBack() else reload++
                        }
                    })
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    memberTask?.let { mt ->
        MemberTaskDialog(mt, onDismiss = { memberTask = null }) { col, mins ->
            memberTask = null
            scope.launch {
                toast = try {
                    if (mins > 0) SharedBrain.post("/api/team/tasks/${mt.optLong("id")}/log", JSONObject().put("minutes", mins))
                    if (col != mt.optString("column")) SharedBrain.post("/api/team/tasks/${mt.optLong("id")}/move", JSONObject().put("column", col))
                    "حُفظ"
                } catch (e: Exception) { "✗ ${e.message}" }
                reload++
            }
        }
    }
    invite?.let { inv -> InviteDialog(inv, project?.optString("name").orEmpty()) { invite = null } }
    editTask?.let { t ->
        TaskEditor(t, milestones, allTasks, members, onDismiss = { editTask = null },
            onSave = { args -> editTask = null; run("update_task", args) },
            onLog = { mins, note -> run("log_time", JSONObject().put("task", t.optLong("id")).put("minutes", mins).put("note", note)) })
    }
    msDialog?.let { m ->
        MilestoneDialog(m, onDismiss = { msDialog = null }) { args ->
            msDialog = null
            if (m.has("id")) run("milestone_update", args.put("id", m.optLong("id")))
            else run("milestone_add", args.put("project", id.toString()))
        }
    }
    if (editProject) project?.let { p ->
        ProjectEditDialog(p, onDismiss = { editProject = false }) { body ->
            editProject = false
            scope.launch {
                toast = try { SharedBrain.patch("/api/projects/$id", body); "حُفظ المشروع" } catch (e: Exception) { "✗ ${e.message}" }
                reload++
            }
        }
    }
}

@Composable
private fun HealthCard(h: JSONObject) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthChip(h.optString("level"), h.optString("level_ar"))
                Spacer(Modifier.width(8.dp))
                val reasons = h.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                Text(if (reasons.isEmpty()) "كل شيء على المسار" else reasons.joinToString(" · "), Modifier.weight(1f),
                    color = HarithColors.Fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Modifier.weight(1f), "${arNum(h.optInt("progress"))}٪", "الإنجاز")
                Tile(Modifier.weight(1f), if (h.isNull("time_elapsed")) "—" else "${arNum(h.optInt("time_elapsed"))}٪", "الوقت المنقضي")
                Tile(Modifier.weight(1f), arNum(h.optInt("overdue")), "متأخرة", alert = h.optInt("overdue") > 0)
                Tile(Modifier.weight(1f), if (h.isNull("budget_percent")) "—" else "${arNum(h.optInt("budget_percent"))}٪", "الميزانية",
                    alert = h.optInt("budget_percent") > 100)
            }
        }
    }
}

@Composable
private fun KanbanColumn(c: JSONObject, me: Long, canEdit: (JSONObject) -> Boolean, onOpen: (JSONObject) -> Unit, onMove: (JSONObject, String) -> Unit) {
    val cid = c.optString("id")
    val idx = COLS.indexOfFirst { it.first == cid }
    val tasks = c.optJSONArray("tasks").objs()
    Column(Modifier.width(270.dp).clip(RoundedCornerShape(16.dp)).background(HarithColors.Surface.copy(alpha = 0.6f))
        .border(1.dp, HarithColors.Line, RoundedCornerShape(16.dp)).padding(10.dp).heightIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.optString("title"), Modifier.weight(1f), color = if (cid == "done") HarithColors.Green else HarithColors.Fg,
                style = MaterialTheme.typography.titleSmall)
            Text(arNum(tasks.size), Modifier.clip(CircleShape).background(HarithColors.SurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
                color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (tasks.isEmpty()) Text("فارغ", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
        for (t in tasks.take(60)) {
            val overdue = t.optBoolean("overdue")
            val mine = me > 0 && t.optLong("assignee_user_id") == me
            val editable = canEdit(t)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarithColors.Bg.copy(alpha = if (editable) 0.55f else 0.3f))
                .border(if (mine) 1.5.dp else 1.dp, if (overdue) HarithColors.Red else if (mine) Luxe.Gold else HarithColors.Line, RoundedCornerShape(12.dp))
                .clickable { onOpen(t) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.optString("title"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(t.optString("assignee").takeIf { it.isNotBlank() }?.let { "👤 $it" },
                    t.optString("due").takeIf { it.isNotBlank() }?.let { "⏰ $it" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = if (overdue) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                t.optJSONObject("blocked_by")?.let { b ->
                    Text("⛓ ينتظر: ${b.optString("title")}", color = Luxe.Gold, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                val est = t.optInt("estimate_min"); val spent = t.optInt("spent_min")
                if (est > 0 || spent > 0) Text("⏱ ${hours(spent)} من ${if (est > 0) hours(est) else "—"}",
                    color = if (est in 1 until spent) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (editable) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // الاتجاه من اليمين لليسار: العمود التالي على اليسار
                    if (idx > 0) IconButton(onClick = { onMove(t, COLS[idx - 1].first) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "إلى ${COLS[idx - 1].second}", tint = HarithColors.Muted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (idx in 0 until COLS.size - 1) IconButton(onClick = { onMove(t, COLS[idx + 1].first) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "إلى ${COLS[idx + 1].second}", tint = Luxe.Gold, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhasesTab(tl: JSONObject?, tasks: List<JSONObject>, owner: Boolean, onEdit: (JSONObject) -> Unit, onAdd: () -> Unit, onToggle: (JSONObject) -> Unit) {
    val ms = tl?.optJSONArray("milestones").objs()
    val today = tl?.optString("today").orEmpty()
    if (owner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("+ مرحلة", primary = true, onClick = onAdd) }
    if (ms.isEmpty()) Text("لا مراحل بعد. أضف مرحلة، أو أنشئ المشروع من قالب ليأتي بمراحله.", color = HarithColors.Muted,
        style = MaterialTheme.typography.bodyMedium)
    // شريط زمني مبسّط: موقع كل مرحلة على امتداد المشروع، والخط الذهبي = اليوم
    val range = tl?.optJSONArray("range")
    val r0 = range?.optString(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val r1 = range?.optString(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val span = if (r0 != null && r1 != null) (r1.toEpochDay() - r0.toEpochDay()).coerceAtLeast(1) else 0L
    fun frac(d: String): Float? = runCatching { LocalDate.parse(d.take(10)) }.getOrNull()?.let { x ->
        if (span == 0L || r0 == null) null else ((x.toEpochDay() - r0.toEpochDay()).toFloat() / span).coerceIn(0f, 1f)
    }
    val todayF = frac(today)
    for (m in ms) {
        val late = m.optBoolean("late"); val done = m.optString("status") == "done"
        GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = owner) { onEdit(m) }, RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.optString("title"), Modifier.weight(1f), color = if (late) HarithColors.Red else Luxe.Gold,
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (done) "✓ مكتملة" else "إكمال", Modifier.clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (done) HarithColors.Green else HarithColors.Line, RoundedCornerShape(12.dp))
                        .clickable(enabled = owner) { onToggle(m) }.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (done) HarithColors.Green else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(listOfNotNull(dates(m.optString("start"), m.optString("due")), "${arNum(m.optInt("tasks"))} مهمة",
                    if (late) "متأخرة" else null).joinToString(" · "),
                    color = if (late) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                // موقع المرحلة على الجدول الكلي
                val a = frac(m.optString("start").ifBlank { m.optString("due") }); val b = frac(m.optString("due").ifBlank { m.optString("start") })
                if (a != null && b != null) Box(Modifier.fillMaxWidth().height(14.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(HarithColors.SurfaceHigh))
                    Row(Modifier.fillMaxWidth()) {
                        if (a > 0f) Spacer(Modifier.weight(a))
                        val w = (b - a).coerceAtLeast(0.02f)
                        Box(Modifier.weight(w).height(14.dp).clip(RoundedCornerShape(7.dp))
                            .background((if (late) HarithColors.Red else Luxe.Gold).copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(m.optInt("progress") / 100f).height(14.dp).background(if (late) HarithColors.Red else Luxe.Gold))
                        }
                        if (1f - a - w > 0.001f) Spacer(Modifier.weight(1f - a - w))
                    }
                    if (todayF != null) Row(Modifier.fillMaxWidth()) {
                        if (todayF > 0f) Spacer(Modifier.weight(todayF))
                        Box(Modifier.width(2.dp).height(14.dp).background(Luxe.GoldLight))
                        if (todayF < 1f) Spacer(Modifier.weight(1f - todayF))
                    }
                }
                Text("إنجاز المرحلة ${arNum(m.optInt("progress"))}٪", color = HarithColors.Fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (ms.isNotEmpty()) Text("الشريط يبين موقع كل مرحلة من مدة المشروع كاملة · الخط الفاتح = اليوم · الأحمر = متأخرة",
        color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
    val noMs = tasks.count { it.isNull("milestone_id") || it.optLong("milestone_id") == 0L }
    if (noMs > 0 && ms.isNotEmpty()) Text("${arNum(noMs)} مهمة بلا مرحلة — حدّد مرحلتها من تعديل المهمة.", color = HarithColors.Muted,
        style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun WorkloadCard(rows: List<JSONObject>) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (w in rows) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.optString("assignee"), Modifier.weight(1f), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                Text("${arNum(w.optInt("open"))} مفتوحة", color = HarithColors.Fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                if (w.optInt("overdue") > 0) Text(" · ${arNum(w.optInt("overdue"))} متأخرة", color = HarithColors.Red, style = MaterialTheme.typography.labelMedium)
                val est = w.optDouble("estimate_hours", 0.0)
                if (est > 0) Text(" · ${arNum(fmt(est))} س", color = HarithColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ———————————————————————————— الحوارات
@Composable
private fun TemplateDialog(templates: List<JSONObject>, onDismiss: () -> Unit, onCreate: (JSONObject) -> Unit) {
    var tpl by remember { mutableStateOf(templates.firstOrNull()?.optString("id") ?: "construction") }
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var owner by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text("مشروع من قالب جاهز", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in templates) {
                    val sel = t.optString("id") == tpl
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .border(if (sel) 1.5.dp else 1.dp, if (sel) Luxe.Gold else HarithColors.Line, RoundedCornerShape(12.dp))
                        .clickable { tpl = t.optString("id") }.padding(10.dp)) {
                        Text(t.optString("name"), color = if (sel) Luxe.Gold else HarithColors.Fg, style = MaterialTheme.typography.titleSmall)
                        val ph = t.optJSONArray("phases")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                        Text("${arNum(ph.size)} مراحل · ${arNum(t.optInt("tasks"))} مهمة · ~${arNum(t.optInt("days"))} يوم",
                            color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                        if (sel) Text(ph.joinToString(" ← "), color = HarithColors.Fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("اسم المشروع") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("تاريخ البداية (سنة-شهر-يوم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(owner, { owner = it }, label = { Text("المسؤول (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(budget, { budget = it }, label = { Text("الميزانية (اختياري)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("تُنشأ المراحل والمهام بمواعيدها واعتمادياتها، وتقدر تعدّل أي شيء بعدها.", color = HarithColors.Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = JSONObject().put("template", tpl).put("start", start.trim())
                if (name.isNotBlank()) a.put("name", name.trim())
                if (owner.isNotBlank()) a.put("owner", owner.trim())
                if (budget.isNotBlank()) a.put("budget", budget.trim())
                onCreate(a)
            }) { Text("أنشئ المشروع", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}

@Composable
private fun BlankProjectDialog(onDismiss: () -> Unit, onCreate: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text("مشروع جديد", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(goal, { goal = it }, label = { Text("الهدف") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("البداية (سنة-شهر-يوم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text("النهاية (سنة-شهر-يوم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(owner, { owner = it }, label = { Text("مدير المشروع") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val a = JSONObject().put("name", name.trim())
                if (goal.isNotBlank()) a.put("goal", goal.trim())
                if (start.isNotBlank()) a.put("start", start.trim())
                if (end.isNotBlank()) a.put("end", end.trim())
                if (owner.isNotBlank()) a.put("owner", owner.trim())
                onCreate(a)
            }) { Text("إنشاء", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}

@Composable
private fun ProjectEditDialog(p: JSONObject, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    fun s(k: String) = p.optString(k).takeIf { it != "null" }.orEmpty()
    var name by remember { mutableStateOf(s("name")) }
    var owner by remember { mutableStateOf(s("owner")) }
    var start by remember { mutableStateOf(s("start_on")) }
    var end by remember { mutableStateOf(s("end_on")) }
    var status by remember { mutableStateOf(s("status").ifBlank { "active" }) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text("تعديل المشروع", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(owner, { owner = it }, label = { Text("مدير المشروع") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("البداية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text("النهاية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, l) in listOf("active" to "نشط", "paused" to "متوقف", "done" to "منتهٍ", "archived" to "مؤرشف")) Tab(l, status == k) { status = k }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(JSONObject().put("name", name.trim()).put("owner", owner.trim()).put("start_on", start.trim())
                    .put("end_on", end.trim()).put("status", status))
            }) { Text("حفظ", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}

@Composable
private fun MilestoneDialog(m: JSONObject, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var title by remember { mutableStateOf(m.optString("title")) }
    var start by remember { mutableStateOf(m.optString("start")) }
    var due by remember { mutableStateOf(m.optString("due")) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text(if (m.has("id")) "تعديل المرحلة" else "مرحلة جديدة", color = HarithColors.Fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("اسم المرحلة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("البداية (سنة-شهر-يوم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(due, { due = it }, label = { Text("النهاية (سنة-شهر-يوم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(JSONObject().put("title", title.trim()).put("start", start.trim()).put("due", due.trim()))
            }) { Text("حفظ", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}

@Composable
private fun TaskEditor(t: JSONObject, milestones: List<JSONObject>, tasks: List<JSONObject>, members: List<JSONObject>, onDismiss: () -> Unit,
                       onSave: (JSONObject) -> Unit, onLog: (Int, String) -> Unit) {
    val tid = t.optLong("id")
    var title by remember { mutableStateOf(t.optString("title")) }
    var assignee by remember { mutableStateOf(t.optString("assignee")) }
    var assigneeUser by remember { mutableStateOf(t.optLong("assignee_user_id")) }
    var start by remember { mutableStateOf(t.optString("start").take(10)) }
    var due by remember { mutableStateOf(t.optString("due")) }
    var est by remember { mutableStateOf(t.optInt("estimate_min").takeIf { it > 0 }?.let { fmt(it / 60.0) } ?: "") }
    var tags by remember { mutableStateOf(t.optJSONArray("tags")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } } ?: "") }
    var priority by remember { mutableStateOf(t.optString("priority").ifBlank { "normal" }) }
    var ms by remember { mutableStateOf(t.optLong("milestone_id")) }
    var dep by remember { mutableStateOf(t.optLong("depends_on")) }
    var col by remember { mutableStateOf(t.optString("column").ifBlank { "todo" }) }
    var logMin by remember { mutableStateOf("") }
    var cancel by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text("تعديل المهمة", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth())
                Label("العمود")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, l) in COLS) Tab(l, col == k) { col = k }
                }
                if (members.isNotEmpty()) {
                    Label("المسؤول من الفريق (تصله المهمة وإشعارها في تطبيقه)")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tab("بدون", assigneeUser == 0L) { assigneeUser = 0L }
                        for (m in members) Tab(m.optString("name"), assigneeUser == m.optLong("user_id")) {
                            assigneeUser = m.optLong("user_id"); assignee = m.optString("name")
                        }
                    }
                }
                OutlinedTextField(assignee, { assignee = it; assigneeUser = 0L }, label = { Text(if (members.isNotEmpty()) "أو اكتب اسم مسؤول من خارج الفريق" else "المسؤول عن التنفيذ") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("البداية") }, singleLine = true)
                    OutlinedTextField(due, { due = it }, Modifier.weight(1f), label = { Text("الموعد") }, singleLine = true)
                }
                Text("التاريخ بصيغة 2026-11-30، ويمكن إضافة الساعة: 2026-11-30 14:00", color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(est, { est = it }, Modifier.weight(1f), label = { Text("التقدير (ساعات)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(tags, { tags = it }, Modifier.weight(1f), label = { Text("وسوم") }, singleLine = true)
                }
                Label("الأولوية")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, l) in listOf("low" to "منخفضة", "normal" to "عادية", "high" to "مرتفعة", "urgent" to "عاجلة")) Tab(l, priority == k) { priority = k }
                }
                if (milestones.isNotEmpty()) {
                    Label("المرحلة")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tab("بدون", ms == 0L) { ms = 0L }
                        for (m in milestones) Tab(m.optString("title"), ms == m.optLong("id")) { ms = m.optLong("id") }
                    }
                }
                val others = tasks.filter { it.optLong("id") != tid }
                if (others.isNotEmpty()) {
                    Label("تبدأ بعد انتهاء (اعتمادية)")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tab("لا شيء", dep == 0L) { dep = 0L }
                        val sel = others.firstOrNull { it.optLong("id") == dep }
                        // المختارة أولًا ثم البقية
                        for (o in listOfNotNull(sel) + others.filter { it !== sel }.take(40))
                            Tab(o.optString("title").take(26), dep == o.optLong("id")) { dep = o.optLong("id") }
                    }
                }
                Label("الوقت المصروف: ${hours(t.optInt("spent_min"))}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(logMin, { logMin = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("دقائق عمل") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Chip("سجّل الوقت") { logMin.toIntOrNull()?.takeIf { it > 0 }?.let { onLog(it, ""); logMin = "" } }
                }
                Text(if (cancel) "ستُلغى المهمة عند الحفظ" else "إلغاء المهمة", Modifier.clickable { cancel = !cancel }.padding(vertical = 4.dp),
                    color = HarithColors.Red, style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // نرسل ما تغيّر فقط (حتى لا يُعاد ضبط وقت الإكمال أو التذكيرات دون داعٍ)
                val a = JSONObject().put("id", tid)
                fun ch(k: String, now: String, was: String) { if (now.trim() != was.trim()) a.put(k, now.trim()) }
                ch("title", title, t.optString("title"))
                if (assigneeUser != t.optLong("assignee_user_id") && assigneeUser > 0) a.put("assignee_user", assigneeUser)
                else ch("assignee", assignee, t.optString("assignee"))
                ch("start", start, t.optString("start").take(10)); ch("due", due, t.optString("due"))
                ch("tags", tags, t.optJSONArray("tags")?.let { x -> (0 until x.length()).joinToString(",") { x.optString(it) } } ?: "")
                ch("priority", priority, t.optString("priority").ifBlank { "normal" })
                if (ms != t.optLong("milestone_id")) a.put("milestone", if (ms > 0) ms.toString() else "")
                if (dep != t.optLong("depends_on")) a.put("depends_on", dep)
                val oldEst = t.optInt("estimate_min").takeIf { it > 0 }?.let { fmt(it / 60.0) } ?: ""
                if (est.trim() != oldEst) est.replace('٫', '.').toDoubleOrNull()?.let { a.put("estimate_hours", it) }
                if (cancel) a.put("status", "cancelled") else if (col != t.optString("column").ifBlank { "todo" }) a.put("column", col)
                if (a.length() > 1) onSave(a) else onDismiss()
            }) { Text("حفظ", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق", color = HarithColors.Muted) } })
}

// ———————————————————————————— الفريق
@Composable
private fun TeamTab(info: JSONObject?, owner: Boolean, me: Long, workload: List<JSONObject>,
                    onInvite: () -> Unit, onAddUser: (String) -> Unit, onRemove: (Long) -> Unit) {
    if (info == null) {
        Text("ميزة الفريق تحتاج تحديث الخادم.", color = HarithColors.Muted, style = MaterialTheme.typography.bodyMedium)
        UpdateServerCard()
        return
    }
    val members = info.optJSONArray("members").objs()
    var uname by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<JSONObject?>(null) }
    if (owner) GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), strong = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("أضف أعضاء للفريق", color = Luxe.Gold, style = MaterialTheme.typography.titleSmall)
            Text("أرسل رابط الدعوة لأي شخص: يفتحه في تطبيق رفيق أو المتصفح، ينشئ حسابه، وينضم. العضو يرى المشروع كاملًا ويعدّل مهامه فقط، ولا يرى المالية.",
                color = HarithColors.Fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            Chip("🔗 رابط دعوة", primary = true, onClick = onInvite)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(uname, { uname = it.trim() }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("أو اسم مستخدم موجود") })
                Chip("إضافة") { if (uname.isNotBlank()) { onAddUser(uname); uname = "" } }
            }
        }
    }
    Title("الأعضاء")
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in members) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.optString("name"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                    Text("${m.optString("role_ar")}، ${arNum(m.optInt("open"))} مفتوحة" +
                        (if (m.optInt("overdue") > 0) " · ${arNum(m.optInt("overdue"))} متأخرة" else ""),
                        color = if (m.optInt("overdue") > 0) HarithColors.Red else HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                val uid = m.optLong("user_id")
                if (m.optString("role") != "owner" && (owner || uid == me))
                    Text(if (uid == me) "مغادرة" else "إزالة", Modifier.clip(RoundedCornerShape(12.dp)).clickable { confirmRemove = m }
                        .padding(horizontal = 10.dp, vertical = 6.dp), color = HarithColors.Red, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (workload.isNotEmpty()) { Title("عبء العمل في المشروع"); WorkloadCard(workload) }
    Title("نشاط المشروع")
    val acts = info.optJSONArray("activity").objs()
    if (acts.isEmpty()) Text("لا نشاط بعد.", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
    for (a in acts.take(40)) Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("${a.optString("actor")} ${a.optString("action")}", color = HarithColors.Fg.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        Text(a.optString("when"), color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
    confirmRemove?.let { m ->
        val self = m.optLong("user_id") == me
        AlertDialog(onDismissRequest = { confirmRemove = null }, containerColor = HarithColors.Surface,
            title = { Text(if (self) "مغادرة الفريق؟" else "إزالة ${m.optString("name")}؟", color = HarithColors.Fg) },
            text = { Text(if (self) "لن ترى هذا المشروع بعد الآن." else "مهامه تبقى في المشروع لكن تُفك عن حسابه.", color = HarithColors.Muted) },
            confirmButton = { TextButton(onClick = { confirmRemove = null; onRemove(m.optLong("user_id")) }) { Text("تأكيد", color = HarithColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("إلغاء", color = HarithColors.Muted) } })
    }
}

@Composable
private fun InviteDialog(inv: JSONObject, projectName: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val link = inv.optString("link")
    val msg = "انضم لفريق مشروع «$projectName» في رفيق:\n$link\n\nافتح الرابط، أو في تطبيق رفيق: المشاريع ← «انضم لفريق» والصق الرابط."
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text("رابط دعوة الفريق", color = HarithColors.Fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(link, color = Luxe.Gold, style = MaterialTheme.typography.bodySmall)
                Text("الرمز ${inv.optString("code")} · صالح حتى ${inv.optString("expires")} · حتى ${arNum(inv.optInt("max_uses"))} أشخاص",
                    color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (inv.optBoolean("local_only")) Text("تنبيه: الوصول من الإنترنت غير مفعّل على خادمك، فالرابط لا يعمل خارج جوالك. فعّله بإعادة أمر التثبيت واختيار y.",
                    color = HarithColors.Red, style = MaterialTheme.typography.labelSmall)
                Text("رابط الإنترنت المجاني يتغيّر إذا أُعيد تشغيل الخادم؛ عندها أرسل رابط دعوة جديدًا.",
                    color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg)
                }, "إرسال الدعوة").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("مشاركة", color = Luxe.Gold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(msg)) }) { Text("نسخ", color = HarithColors.Fg) }
                TextButton(onClick = onDismiss) { Text("إغلاق", color = HarithColors.Muted) }
            }
        })
}

@Composable
private fun MemberTaskDialog(t: JSONObject, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var col by remember { mutableStateOf(t.optString("column").ifBlank { "todo" }) }
    var logMin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = HarithColors.Surface,
        title = { Text(t.optString("title"), color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val meta = listOfNotNull(t.optString("project").takeIf { it.isNotBlank() },
                    t.optString("due").takeIf { it.isNotBlank() }?.let { "الموعد: $it" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (t.optString("notes").isNotBlank()) Text(t.optString("notes"), color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
                t.optJSONObject("blocked_by")?.let { Text("⛓ تنتظر: ${it.optString("title")}", color = Luxe.Gold, style = MaterialTheme.typography.labelSmall) }
                Label("الحالة")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, l) in COLS) Tab(l, col == k) { col = k }
                }
                Label("وقتك المسجل: ${hours(t.optInt("spent_min"))}")
                OutlinedTextField(logMin, { logMin = it.filter(Char::isDigit).take(4) }, label = { Text("أضف دقائق عمل") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(col, logMin.toIntOrNull() ?: 0) }) { Text("حفظ", color = Luxe.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق", color = HarithColors.Muted) } })
}

/** الانضمام لفريق برابط دعوة: ينشئ حسابك على خادم صاحب المشروع ويربط التطبيق به. */
@Composable
private fun JoinTeamDialog(onDismiss: () -> Unit, onJoined: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Prefs.userName) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = HarithColors.Surface,
        title = { Text("انضم لفريق مشروع", color = HarithColors.Fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(link, { link = it.trim() }, label = { Text("الصق رابط الدعوة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("اسمك كما يظهر للفريق") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("اسم مستخدم بالإنجليزية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة مرور (٨ أحرف على الأقل)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("احفظ اسم المستخدم وكلمة المرور: تدخل بهما لوحة التحكم من المتصفح أيضًا. ملاحظة: مهام التطبيق الشخصية ستُحفظ في حسابك على هذا الخادم (خادم صاحب المشروع).", color = HarithColors.Muted,
                    style = MaterialTheme.typography.labelSmall)
                if (Prefs.sharedBrainReady) Text("تنبيه: تطبيقك مربوط الآن بخادم. الانضمام لفريق على خادم آخر ينقل الربط إليه.",
                    color = Luxe.Gold, style = MaterialTheme.typography.labelSmall)
                if (busy) Text("جارٍ الانضمام…", color = HarithColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (err.isNotBlank()) Text("✗ $err", color = HarithColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && link.isNotBlank() && user.length >= 3 && pass.isNotBlank(), onClick = {
                busy = true; err = ""
                scope.launch {
                    val r = com.alharith.ai.data.Pairing.join(link, name.trim(), user, pass)
                    busy = false
                    if (r.first == null) onJoined(r.second) else err = r.first!!
                }
            }) { Text("انضم", color = Luxe.Gold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء", color = HarithColors.Muted) } })
}

// ———————————————————————————— عناصر صغيرة
@Composable
private fun Screen(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(HarithColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "رجوع", tint = HarithColors.Fg) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "تحديث", tint = HarithColors.Fg) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

/** يظهر عندما يكون الخادم على الجوال أقدم من التطبيق (صفحات المشاريع الجديدة غير موجودة فيه) */
@Composable
private fun UpdateServerCard() {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val cmd = "curl -fsSL https://raw.githubusercontent.com/al-hiraf/Alharith_ai/main/server/install-termux.sh | bash"
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("خادمك يحتاج تحديث", color = Luxe.Gold, style = MaterialTheme.typography.titleMedium)
            Text("التطبيق أحدث من الخادم الذي على جوالك، لذلك صفحات إدارة المشاريع غير موجودة فيه بعد. التحديث لا يمسح بياناتك:",
                color = HarithColors.Fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            Text("١) اضغط «انسخ الأمر»\n٢) افتح Termux والصقه ثم Enter\n٣) انتظر حتى تظهر ✅ تم، ثم ارجع هنا واضغط تحديث ↻",
                color = HarithColors.Fg, style = MaterialTheme.typography.bodyMedium)
            Chip(if (copied) "✓ نُسخ" else "انسخ الأمر", primary = true) {
                clip.setText(androidx.compose.ui.text.AnnotatedString(cmd)); copied = true
            }
        }
    }
}

@Composable
private fun NotLinkedPm() {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.alharith.ai.R.drawable.rafiq_mark), null, Modifier.size(110.dp))
        Text("إدارة المشاريع تعمل مع خادم رفيق", style = MaterialTheme.typography.titleLarge, color = HarithColors.Fg, textAlign = TextAlign.Center)
        Text("لوحة المهام، المراحل والجدول الزمني، القوالب الجاهزة، الفريق وتسجيل الوقت — تُحفظ في خادمك وتتزامن مع لوحة التحكم. اربطه برمز من 6 أرقام:",
            style = MaterialTheme.typography.bodyMedium, color = HarithColors.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HealthChip(level: String, label: String) {
    val c = when (level) { "red" -> HarithColors.Red; "amber" -> Luxe.Gold; else -> HarithColors.Green }
    Text(label.ifBlank { "—" }, Modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = c, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Tile(modifier: Modifier, value: String, label: String, alert: Boolean = false) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(HarithColors.Bg.copy(alpha = 0.5f)).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = if (alert) HarithColors.Red else Luxe.Gold, style = MaterialTheme.typography.titleMedium)
        Text(label, color = HarithColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun Bar(pct: Int) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(HarithColors.SurfaceHigh)) {
        Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Luxe.Gold))
    }
}

@Composable
private fun Chip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(18.dp))
        .then(if (primary) Modifier.background(Luxe.Gold) else Modifier.border(1.dp, Luxe.Gold.copy(alpha = 0.6f), RoundedCornerShape(18.dp)))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (primary) HarithColors.OnGold else Luxe.Gold, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Luxe.Gold else HarithColors.Surface)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) HarithColors.OnGold else HarithColors.Fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
}

@Composable
private fun Title(t: String) = Text(t, color = HarithColors.Fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Label(t: String) = Text(t, color = HarithColors.Muted, style = MaterialTheme.typography.labelMedium)

@Composable
private fun Toast(t: String) {
    if (t.isNotBlank()) Text(t, color = if (t.startsWith("✗")) HarithColors.Red else HarithColors.Green, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Luxe.Gold) }

private fun dates(a: String, b: String): String? {
    val x = a.takeIf { it.isNotBlank() && it != "null" }; val y = b.takeIf { it.isNotBlank() && it != "null" }
    return when { x != null && y != null -> "$x ← $y"; x != null -> "من $x"; y != null -> "حتى $y"; else -> null }
}

private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.US, "%.1f", d)
private fun hours(min: Int): String = if (min < 60) "${arNum(min)} د" else "${arNum(fmt(min / 60.0))} س"

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
