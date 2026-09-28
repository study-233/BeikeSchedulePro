package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.SemesterDraft
import org.junit.Assert.*
import org.junit.Test

class SemesterDraftTest {
    private val original = SettingsStore.SemesterConfig(name = "旧学期", firstMonday = "2026-09-07", totalWeeks = 20)
    @Test fun `编辑草稿与丢弃不改变已保存配置`() {
        val draft = SemesterDraft.from(original).copy(name = "未保存", totalWeeks = 16)
        assertEquals("旧学期", original.name)
        assertEquals(20, original.totalWeeks)
        assertNotEquals(SemesterDraft.from(original), draft)
        assertEquals("旧学期", SemesterDraft.from(original).name)
    }
    @Test fun `保存只合并可编辑字段并保护最新官方校历`() {
        val draft = SemesterDraft.from(original).copy(name = " 新名称 ", firstMonday = "2026-09-14", totalWeeks = 16)
        val latest = original.copy(xn = "2026", xq = "2", weekMondays = List(22) { "week_$it" },
            holidayDates = listOf("2026-09-25"))
        val saved = draft.applyTo(latest)
        assertEquals("新名称", saved.name)
        assertEquals("2026-09-14", saved.firstMonday)
        assertEquals(22, saved.totalWeeks)
        assertEquals(latest.weekMondays, saved.weekMondays)
        assertEquals(latest.holidayDates, saved.holidayDates)
        assertEquals(latest.xn, saved.xn)
        assertEquals(latest.xq, saved.xq)
    }
}
