package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.*
import org.junit.Assert.*
import org.junit.Test

class NavigationCompatibilityTest {
    @Test fun `旧栏目数字显式迁移不受新枚举顺序影响`() {
        val expected = listOf(CampusSection.FREE_ROOM, CampusSection.FREE_ROOM, CampusSection.SCORES, CampusSection.EXAMS)
        expected.forEachIndexed { index, section -> assertEquals(section, CampusSection.restore(null, index)) }
        listOf(null, -1, 99).forEach { assertEquals(CampusSection.FREE_ROOM, CampusSection.restore(null, it)) }
    }
    @Test fun `新标识优先且重复读取稳定`() {
        CampusSection.entries.forEach { section ->
            val first = CampusSection.restore(section.id, 1)
            assertEquals(section, first)
            assertEquals(first, CampusSection.restore(first.id, 3))
        }
        assertEquals(CampusSection.FREE_ROOM, CampusSection.restore("unknown", 2))
    }
    @Test fun `旧主导航恢复校园且未知页面安全回到课表`() {
        assertEquals(MainPage.CAMPUS, MainPage.restore("jw"))
        assertEquals(MainPage.SCHEDULE, MainPage.restore("unknown"))
        MainPage.entries.forEach { assertEquals(it, MainPage.restore(it.id)) }
    }
    @Test fun `快速重复导航不入栈返回保留父页新编辑使用新键`() {
        val root = openSettings(emptyList(), SettingsPage.SCHEDULE, 1)
        val edit = openSettings(root, SettingsPage.SEMESTER, 2)
        assertEquals(edit, openSettings(edit, SettingsPage.SEMESTER, 3))
        val restored = edit.map { NavigationEntry.restore(it)!! }
        assertEquals(SettingsPage.SEMESTER, restored.last().page)
        assertEquals(root, edit.dropLast(1))
        assertNotEquals(edit.last(), openSettings(root, SettingsPage.SEMESTER, 4).last())
        assertNull(NavigationEntry.restore("obsolete@1"))
        assertNull(NavigationEntry.restore("SEMESTER@invalid"))
    }
}
