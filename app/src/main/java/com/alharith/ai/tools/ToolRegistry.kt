package com.alharith.ai.tools

import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(env: ToolEnv) {

    private val tools: List<Tool> =
        PhoneTools.tools(env) +
            SmsTools.tools(env) +
            MessagingTools.tools(env) +
            EmailTools.tools(env) +
            TimeTools.tools(env) +
            DeviceTools.tools(env) +
            FileTools.tools(env) +
            PlannerTools.tools(env) +
            WebTools.tools(env) +
            SharedBrainTools.tools(env)

    private val byName = tools.associateBy { it.name }

    fun definitions(): JSONArray = JSONArray().apply {
        tools.forEachIndexed { i, t ->
            val json = t.toJson()
            // تخزين مؤقت لتعريفات الأدوات يقلل التكلفة وزمن الاستجابة
            if (i == tools.lastIndex) json.put("cache_control", JSONObject().put("type", "ephemeral"))
            put(json)
        }
    }

    fun label(name: String) = byName[name]?.label ?: "ينفّذ"

    suspend fun run(name: String, input: JSONObject): ToolResult {
        val tool = byName[name] ?: return ToolResult.error("أداة غير معروفة: $name")
        val r = try {
            // لا تتعلق أي أداة إلى الأبد (يشمل وقت انتظار تأكيد المستخدم)
            kotlinx.coroutines.withTimeoutOrNull(120_000L) { tool.run(input) }
                ?: ToolResult.error("استغرق تنفيذ \"${tool.label}\" وقتًا أطول من المسموح فتوقف. أخبر المستخدم واقترح المحاولة مرة أخرى.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult.error("رفض النظام الإجراء لعدم وجود صلاحية: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("حدث خطأ أثناء التنفيذ: ${e.message ?: e.javaClass.simpleName}")
        }
        com.alharith.ai.data.ActivityLog.record(
            source = "رفيق",
            command = input.keys().asSequence().joinToString("، ") { k -> "$k: ${input.opt(k)}".take(80) },
            action = tool.label,
            result = r.text.lineSequence().firstOrNull().orEmpty(),
            ok = !r.isError
        )
        return r
    }
}
