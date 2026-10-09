package com.alharith.ai

import com.alharith.ai.brain.GeminiClient
import com.alharith.ai.brain.OpenAIClient
import com.alharith.ai.data.ReminderItem
import com.alharith.ai.data.TaskItem
import com.alharith.ai.service.Reminders
import com.alharith.ai.tools.Arabic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** اختبارات المنطق الأساسي: تحويل المحادثة لكل مزوّد، التذكيرات، المهام، ومطابقة العربية. */
class CoreLogicTest {

    /** محادثة نموذجية: طلب ← استدعاء أداة ← نتيجة الأداة */
    private fun sampleHistory(): JSONArray = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "اتصل بأحمد"))
        .put(JSONObject().put("role", "assistant").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "لحظة"))
            .put(JSONObject().put("type", "tool_use").put("id", "call_1").put("name", "make_call")
                .put("input", JSONObject().put("target", "أحمد")).put("gemini_sig", "SIG123"))))
        .put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "tool_result").put("tool_use_id", "call_1").put("content", "جارٍ الاتصال"))))

    // ———————————— OpenAI والمزوّدون المتوافقون معه

    @Test fun openai_convertsToolConversation() {
        val out = OpenAIClient().convertMessages("نظام", sampleHistory(), supportsFiles = false)
        assertEquals("system", out.getJSONObject(0).getString("role"))
        assertEquals("اتصل بأحمد", out.getJSONObject(1).getString("content"))
        val asst = out.getJSONObject(2)
        assertEquals("assistant", asst.getString("role"))
        assertEquals("لحظة", asst.getString("content"))
        val call = asst.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("make_call", call.getJSONObject("function").getString("name"))
        assertEquals("أحمد", JSONObject(call.getJSONObject("function").getString("arguments")).getString("target"))
        val tool = out.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals("جارٍ الاتصال", tool.getString("content"))
    }

    @Test fun openai_textOnlyPartsBecomePlainString() {
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "مرفق"))
            .put(JSONObject().put("type", "text").put("text", "سؤال"))))
        val msg = OpenAIClient().convertMessages("s", h, false).getJSONObject(1)
        assertTrue(msg.get("content") is String)
        assertEquals("مرفق\nسؤال", msg.getString("content"))
    }

    @Test fun openai_pdfReplacedWithNoteWhenUnsupported() {
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "document").put("source", JSONObject().put("media_type", "application/pdf").put("data", "AAA")))
            .put(JSONObject().put("type", "text").put("text", "لخص"))))
        val msg = OpenAIClient().convertMessages("s", h, false).getJSONObject(1)
        assertTrue(msg.getString("content").contains("PDF"))
    }

    @Test fun openai_parsesToolCallResponse() {
        val resp = JSONObject("""{"choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"abc","type":"function","function":{"name":"create_task","arguments":"{\"title\":\"إرسال العرض\"}"}}]},
            "finish_reason":"tool_calls"}]}""")
        val r = OpenAIClient().toInternal(resp)
        assertEquals("tool_use", r.getString("stop_reason"))
        val c = r.getJSONArray("content")
        assertEquals(1, c.length())
        val tu = c.getJSONObject(0)
        assertEquals("tool_use", tu.getString("type"))
        assertEquals("abc", tu.getString("id"))
        assertEquals("إرسال العرض", tu.getJSONObject("input").getString("title"))
    }

    @Test fun openai_parsesTextResponse() {
        val r = OpenAIClient().toInternal(JSONObject("""{"choices":[{"message":{"content":"تم."},"finish_reason":"stop"}]}"""))
        assertEquals("end_turn", r.getString("stop_reason"))
        assertEquals("تم.", r.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    // ———————————— Gemini

    @Test fun gemini_convertsToolConversationWithSignature() {
        val out = GeminiClient().convertMessages(sampleHistory())
        assertEquals("user", out.getJSONObject(0).getString("role"))
        val model = out.getJSONObject(1)
        assertEquals("model", model.getString("role"))
        val fcPart = model.getJSONArray("parts").getJSONObject(1)
        assertEquals("make_call", fcPart.getJSONObject("functionCall").getString("name"))
        assertEquals("SIG123", fcPart.getString("thoughtSignature"))
        val fr = out.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("make_call", fr.getString("name"))
        assertEquals("جارٍ الاتصال", fr.getJSONObject("response").getString("result"))
    }

    @Test fun gemini_dropsEmptyParameterSchemas() {
        val tools = JSONArray()
            .put(JSONObject().put("name", "get_device_status").put("description", "d")
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(JSONObject().put("name", "search_notes").put("description", "d")
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))))
        val out = GeminiClient().convertTools(tools)
        assertFalse(out.getJSONObject(0).has("parameters"))
        assertTrue(out.getJSONObject(1).has("parameters"))
    }

    @Test fun gemini_parsesResponseSkippingThoughts() {
        val resp = JSONObject("""{"candidates":[{"content":{"role":"model","parts":[
            {"text":"أفكر…","thought":true},
            {"text":"حاضر"},
            {"functionCall":{"name":"create_reminder","args":{"text":"اتصل بمحمد"}},"thoughtSignature":"S1"}
        ]},"finishReason":"STOP"}]}""")
        val r = GeminiClient().toInternal(resp)
        assertEquals("tool_use", r.getString("stop_reason"))
        val c = r.getJSONArray("content")
        assertEquals("حاضر", c.getJSONObject(0).getString("text"))
        val tu = c.getJSONObject(1)
        assertEquals("create_reminder", tu.getString("name"))
        assertEquals("S1", tu.getString("gemini_sig"))
        assertEquals("اتصل بمحمد", tu.getJSONObject("input").getString("text"))
    }

    // ———————————— التذكيرات المتكررة

    private fun at(d: LocalDateTime) = d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun reminders_nextTimes() {
        val base = LocalDateTime.of(2026, 10, 8, 9, 0) // الخميس
        assertEquals(at(base.plusDays(1)), Reminders.nextTime(ReminderItem(1, "x", at(base), "daily")))
        assertEquals(at(base.plusWeeks(1)), Reminders.nextTime(ReminderItem(1, "x", at(base), "weekly")))
        assertEquals(at(base.plusMonths(1)), Reminders.nextTime(ReminderItem(1, "x", at(base), "monthly")))
        assertNull(Reminders.nextTime(ReminderItem(1, "x", at(base), "none")))
        // أيام العمل: بعد الخميس يأتي الأحد (نتجاوز الجمعة والسبت)
        val next = Reminders.nextTime(ReminderItem(1, "x", at(base), "weekdays"))!!
        val nd = java.time.Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
        assertEquals(DayOfWeek.SUNDAY, nd.dayOfWeek)
    }

    // ———————————— المهام

    @Test fun tasks_overdueLogic() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()
        assertTrue(TaskItem(1, "a", due = yesterday).isOverdue)
        assertFalse(TaskItem(2, "b", due = tomorrow).isOverdue)
        assertFalse(TaskItem(3, "c", due = yesterday, status = "done").isOverdue)
        assertFalse(TaskItem(4, "d").isOverdue)
        assertEquals(0, TaskItem(5, "e", priority = "urgent").priorityRank)
    }

    @Test fun tasks_jsonRoundTrip() {
        val t = TaskItem(7, "مراجعة العقد", priority = "high", due = "2026-10-09T10:00", project = "دكتور لوب",
            subtasks = listOf(com.alharith.ai.data.SubTask("قراءة", true)))
        val back = TaskItem.fromJson(t.toJson())
        assertEquals(t, back)
    }

    // ———————————— مطابقة الأسماء العربية (أخطاء التعرف الصوتي)

    @Test fun arabic_matching() {
        assertTrue(Arabic.matches("أحمد محمد", "احمد"))
        assertTrue(Arabic.matches("فاطمة", "فاطمه"))
        assertTrue(Arabic.matches("عبدالله الإدريسي", "الادريسي"))
        assertFalse(Arabic.matches("خالد", "أحمد"))
    }
}

/** اختبارات الاعتمادية: إعادة المحاولة وتصنيف الأخطاء */
class ReliabilityTest {
    @Test fun errorClassification() {
        assertTrue(com.alharith.ai.brain.ClaudeException("x", -1).retryable)   // انقطاع شبكة
        assertTrue(com.alharith.ai.brain.ClaudeException("x", 429).retryable)  // تجاوز الحد
        assertTrue(com.alharith.ai.brain.ClaudeException("x", 503).retryable)  // ضغط على الخادم
        assertFalse(com.alharith.ai.brain.ClaudeException("x", 401).retryable) // مفتاح خاطئ: لا فائدة من الإعادة
        assertFalse(com.alharith.ai.brain.ClaudeException("x", 400).retryable)
    }

    @Test fun retriesTemporaryErrorsThenSucceeds() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val r = com.alharith.ai.brain.AI.withRetry(attempts = 3, firstDelayMs = 1) {
            calls++
            if (calls < 3) throw com.alharith.ai.brain.ClaudeException("busy", 503)
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(3, calls)
    }

    @Test fun doesNotRetryPermanentErrors() = kotlinx.coroutines.runBlocking {
        var calls = 0
        try {
            com.alharith.ai.brain.AI.withRetry(attempts = 3, firstDelayMs = 1) {
                calls++
                throw com.alharith.ai.brain.ClaudeException("bad key", 401)
            }
        } catch (_: com.alharith.ai.brain.ClaudeException) {}
        assertEquals(1, calls)
    }

    @Test fun givesUpAfterMaxAttempts() = kotlinx.coroutines.runBlocking {
        var calls = 0
        try {
            com.alharith.ai.brain.AI.withRetry(attempts = 3, firstDelayMs = 1) {
                calls++
                throw com.alharith.ai.brain.ClaudeException("down", -1)
            }
        } catch (_: com.alharith.ai.brain.ClaudeException) {}
        assertEquals(3, calls)
    }
}
