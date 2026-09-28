package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.pref.ScheduledAlarm
import com.caeamer.beikeschedule.reminder.TodoReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegacyTodoCleanupTest {
    @Test fun `清理包含未来已到点与旧格式并且重复执行幂等`() = runBlocking {
        var records = listOf(ScheduledAlarm(7_000_001, 1), ScheduledAlarm(7_000_002, Long.MAX_VALUE), ScheduledAlarm(7_000_003, null))
        val cancelled = mutableListOf<Int>()
        TodoReminderScheduler.retireRecordedAlarms(records, { cancelled += it }, { records = it })
        assertEquals(listOf(7_000_001, 7_000_002, 7_000_003), cancelled)
        assertTrue(records.isEmpty())
        TodoReminderScheduler.retireRecordedAlarms(records, { fail("空记录不能再次取消") }, { records = it })
        assertTrue(records.isEmpty())
    }
    @Test fun `取消失败只保留待重试记录`() = runBlocking {
        var records = listOf(ScheduledAlarm(7_000_001, 1), ScheduledAlarm(7_000_002, 2))
        TodoReminderScheduler.retireRecordedAlarms(records, { if (it == 7_000_002) throw SecurityException() }, { records = it })
        assertEquals(listOf(7_000_002), records.map { it.requestCode })
        TodoReminderScheduler.retireRecordedAlarms(records, {}, { records = it })
        assertTrue(records.isEmpty())
    }
    @Test fun `通知清理不依赖闹钟记录且保留课程考试通知`() {
        val notifications = listOf(
            TodoReminderScheduler.PostedNotification("todo_reminder", "legacy", 42),
            TodoReminderScheduler.PostedNotification("todo_reminder", null, 7_000_001),
            TodoReminderScheduler.PostedNotification("class_reminder", null, 1),
            TodoReminderScheduler.PostedNotification("exam_reminder", null, 8_000_001),
        )
        val removed = mutableListOf<Pair<String?, Int>>()
        TodoReminderScheduler.retireNotifications(notifications) { tag, id -> removed += tag to id }
        assertEquals(listOf("legacy" to 42, null to 7_000_001), removed)
    }
}
