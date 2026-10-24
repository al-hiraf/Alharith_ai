package com.alharith.ai

import com.alharith.ai.data.LocalStore
import com.alharith.ai.data.MemoryItem
import com.alharith.ai.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** دمج تغييرات خادم رفيق في المخزن المحلي: الأحدث يفوز، والحذف ينتقل، ولا تكرار. */
class SharedBrainSyncTest {

    @Before fun reset() { LocalStore.wipeAll() }

    @Test fun localChangesStampUpdatedAndTombstones() {
        val before = System.currentTimeMillis()
        LocalStore.addTask(TaskItem(id = 1, title = "أ"))
        val t = LocalStore.task(1)!!
        assertTrue(t.updatedAt >= before)
        assertEquals("1", t.syncRef)
        LocalStore.deleteTask(1)
        assertEquals(listOf("1"), LocalStore.pendingTombstones().map { it.ref })
    }

    @Test fun remoteNewerWinsOlderIgnored() {
        LocalStore.addTask(TaskItem(id = 5, title = "قديم", description = "وصف محلي"))
        val local = LocalStore.task(5)!!
        // تعديل أحدث من الخادم
        LocalStore.applyRemote(listOf(TaskItem(id = 0, ref = "5", title = "من تيليجرام", status = "done",
            updatedAt = local.updatedAt + 1000)), emptyList(), emptyList())
        val merged = LocalStore.task(5)!!
        assertEquals("من تيليجرام", merged.title)
        assertEquals("done", merged.status)
        assertEquals("وصف محلي", merged.description) // الحقول المحلية فقط تبقى
        // تعديل أقدم يُتجاهل
        LocalStore.applyRemote(listOf(TaskItem(id = 0, ref = "5", title = "أقدم", updatedAt = local.updatedAt - 1000)),
            emptyList(), emptyList())
        assertEquals("من تيليجرام", LocalStore.task(5)!!.title)
        assertEquals(1, LocalStore.tasks.value.size)
    }

    @Test fun serverCreatedItemsAddedOnceAndDeletionsApplied() {
        val r = TaskItem(id = 0, ref = "s12", title = "مهمة من اللوحة", updatedAt = 1000)
        LocalStore.applyRemote(listOf(r), listOf(MemoryItem(id = 0, ref = "s3", text = "يحب القهوة", updatedAt = 1)), emptyList())
        LocalStore.applyRemote(listOf(r), emptyList(), emptyList()) // نفس العنصر مرة ثانية
        assertEquals(1, LocalStore.tasks.value.size)
        assertEquals("s12", LocalStore.tasks.value[0].syncRef)
        assertEquals(1, LocalStore.memories.value.size)
        LocalStore.applyRemote(emptyList(), emptyList(), listOf("task" to "s12", "memory" to "s3"))
        assertEquals(0, LocalStore.tasks.value.size)
        assertEquals(0, LocalStore.memories.value.size)
        assertTrue(LocalStore.pendingTombstones().isEmpty()) // حذف قادم من الخادم لا يعود إليه
    }
}
