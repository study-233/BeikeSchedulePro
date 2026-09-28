package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.ExamDraft
import com.caeamer.beikeschedule.model.hasEnded
import com.caeamer.beikeschedule.model.timeLabel
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.reminder.ReminderAlarmScheduler
import com.caeamer.beikeschedule.data.pref.ScheduledAlarm
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ExamDraftTest {
    private val draft = ExamDraft(name = " 数学 ", date = "2026-10-02", start = "09:00", end = "11:00")

    @Test fun `名称必填 其他内容可逐步补全`() {
        assertNotNull(ExamDraft(name = "  ").validationError())
        listOf(ExamDraft(name = "数学"), draft.copy(start = "", end = ""), draft.copy(end = ""), draft).forEach {
            assertNull(it.validationError())
            assertEquals("数学", it.toEntity().kcmc)
            assertTrue(it.toEntity().isManual)
        }
    }

    @Test fun `拒绝缺少日期 缺少开始时间 无效日期及跨日时段`() {
        listOf(draft.copy(date = ""), draft.copy(start = ""), draft.copy(date = "2026-02-30"),
            draft.copy(start = "25:00"), draft.copy(end = "24:00"), draft.copy(end = "09:00"),
            draft.copy(end = "08:00")).forEach {
            assertNotNull(it.validationError())
            assertTrue(runCatching { it.toEntity() }.isFailure)
        }
    }

    @Test fun `清空上级时间同时清空依赖项 草稿可无损恢复`() {
        assertEquals(draft.copy(date = "", start = "", end = ""), draft.withDate(""))
        assertEquals(draft.copy(start = "", end = ""), draft.withStart(""))
        val full = draft.copy(id = 12, location = "逸夫楼", seat = "09", type = "补考", note = "带计算器")
        assertEquals(full, ExamDraft.restore(full.savedFields()))
        assertEquals(full.copy(name = "数学"), ExamDraft.from(full.toEntity()))
    }

    @Test fun `开始已知时显示部分时段 未知结束保留到当天结束`() {
        val partial = draft.copy(end = "").toEntity()
        assertEquals("09:00 开始 · 结束待定", partial.timeLabel())
        assertEquals("时间待定", draft.withDate("").toEntity().timeLabel())
        assertFalse(partial.hasEnded(LocalDateTime.parse("2026-10-02T23:59")))
        assertTrue(partial.hasEnded(LocalDateTime.parse("2026-10-03T00:00")))
    }

    @Test fun `提醒按已知信息安排 过去考试不安排`() {
        val now = LocalDateTime.parse("2026-10-01T12:00")
        val zone = ZoneId.of("Asia/Shanghai")
        fun count(value: ExamDraft) = ExamReminderScheduler.planExamReminders(listOf(value.toEntity()), now, zone).size
        assertEquals(0, count(draft.withDate("")))
        assertEquals(1, count(draft.withStart("")))
        assertEquals(2, count(draft.copy(end = "")))
        assertEquals(2, count(draft))
        assertEquals(0, count(draft.copy(date = "2026-09-30")))
    }

    @Test fun `定向取消修改和删除考试 保留其他到点提醒`() {
        val edited = draft.copy(id = 1).toEntity()
        val kept = draft.copy(id = 2).toEntity()
        val now = LocalDateTime.parse("2026-10-01T12:00")
        val allCodes = ExamReminderScheduler.planExamReminders(
            listOf(edited, kept, draft.copy(id = 3).toEntity()), now, ZoneId.of("Asia/Shanghai"))
            .map { it.requestCode }.toSet()
        val keptCodes = ExamReminderScheduler.planExamReminders(listOf(kept), now, ZoneId.of("Asia/Shanghai"))
            .map { it.requestCode }.toSet()
        val forced = ExamReminderScheduler.obsoleteReminderCodes(listOf(edited, kept), allCodes, setOf(edited.id))
        assertEquals(allCodes - keptCodes, forced)
        val recorded = allCodes.map { ScheduledAlarm(it, 100L) }
        assertEquals(forced, ReminderAlarmScheduler.alarmsToCancel(recorded, emptySet(), 200L,
            forceCancelCodes = forced).map { it.requestCode }.toSet())
        // 清缓存/成功空导入后只剩手动考试，保留其已到点提醒。
        assertEquals(allCodes - keptCodes,
            ExamReminderScheduler.obsoleteReminderCodes(listOf(kept), allCodes, emptySet()))
    }
}
