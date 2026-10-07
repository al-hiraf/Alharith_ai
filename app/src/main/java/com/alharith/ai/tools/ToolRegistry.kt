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
            FileTools.tools(env)

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
            tool.run(input)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult.error("رفض النظام الإجراء لعدم وجود صلاحية: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("حدث خطأ أثناء التنفيذ: ${e.message ?: e.javaClass.simpleName}")
        }
        com.alharith.ai.data.ActivityLog.record(
            source = "الحارث",
            command = input.keys().asSequence().joinToString("، ") { k -> "$k: ${input.opt(k)}".take(80) },
            action = tool.label,
            result = r.text.lineSequence().firstOrNull().orEmpty(),
            ok = !r.isError
        )
        return r
    }
}
