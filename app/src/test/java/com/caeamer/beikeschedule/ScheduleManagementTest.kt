package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.ClassReminderIdentity
import com.caeamer.beikeschedule.model.ScheduleNames
import org.junit.Assert.*
import org.junit.Test

class ScheduleManagementTest {
    @Test fun `课表名称去空白且不接受重名或空名`() {
        assertEquals("春季课表", ScheduleNames.validate(" 春季课表 ", listOf("秋季课表")))
        assertTrue(runCatching { ScheduleNames.validate(" \n ", emptyList()) }.isFailure)
        assertTrue(runCatching { ScheduleNames.validate(" 秋季课表 ", listOf("秋季课表")) }.isFailure)
        assertEquals("新课表 3", ScheduleNames.available("新课表", listOf("新课表", "新课表 2")))
    }

    @Test fun `切换清空和切回同一课表后旧提醒均失效`() {
        assertTrue(ClassReminderIdentity.matches(1, 2, 1, 2))
        assertFalse(ClassReminderIdentity.matches(1, 2, 2, 3))
        assertFalse(ClassReminderIdentity.matches(1, 2, 1, 4))
        assertFalse(ClassReminderIdentity.matches(-1, -1, 1, 1))
        assertNotEquals(ClassReminderIdentity.key(1, 2), ClassReminderIdentity.key(1, 4))
    }
}
