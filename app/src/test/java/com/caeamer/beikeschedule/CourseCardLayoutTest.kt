package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseCardLayoutTest {
    private val course = CourseEntity(
        taskId = "", name = "大学物理", teacher = "张老师", location = "【校本部】教学楼301",
        dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "011111111",
        colorIndex = 0, source = CourseEntity.SOURCE_MANUAL,
    )

    @Test
    fun `单节课程也为地点和教师保留空间`() {
        val single = course.copy(endSection = 1)
        assertEquals(2, CourseCardLayout.detailLines(single))
        assertTrue(CourseCardLayout.minimumUnitHeight(listOf(single), 13f, 11f) >= 13 + 22 + 8)
    }

    @Test
    fun `空教师和占位地点不占行`() {
        val empty = course.copy(teacher = " ", location = "【校本部】-")
        assertEquals(0, CourseCardLayout.detailLines(empty))
        assertEquals("", CourseCardLayout.location(empty))
        assertTrue(
            CourseCardLayout.minimumUnitHeight(listOf(empty), 13f, 11f) <
                CourseCardLayout.minimumUnitHeight(listOf(course), 13f, 11f),
        )
    }

    @Test
    fun `增大字号和系统行高后仍能容纳每一张卡片`() {
        val courses = listOf(course, course.copy(startSection = 5, endSection = 5))
        val normal = CourseCardLayout.minimumUnitHeight(courses, 13f, 11f)
        val enlarged = CourseCardLayout.minimumUnitHeight(courses, 13f * 1.6f * 2, 11f * 1.6f * 2)
        assertTrue(enlarged > normal)
        courses.forEach {
            val span = CourseCardLayout.span(it)
            val required = CourseCardLayout.nameLines(span) * 13f * 1.6f * 2 +
                CourseCardLayout.detailLines(it) * 11f * 1.6f * 2 + 8
            assertTrue(enlarged * span >= required)
        }
    }

    @Test
    fun `第十三节按末节显示且空课表不强制滚动`() {
        assertEquals(1, CourseCardLayout.span(course.copy(startSection = 13, endSection = 13)))
        assertEquals(0f, CourseCardLayout.minimumUnitHeight(emptyList(), 13f, 11f), 0f)
    }
}
