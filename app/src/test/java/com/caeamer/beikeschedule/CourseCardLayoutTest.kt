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

    @Test fun `辅助字号缓慢缩放且不重置原偏好`() {
        assertEquals(0.9f, CourseCardLayout.detailScale(0.8f), 0.001f)
        assertEquals(1f, CourseCardLayout.detailScale(1f), 0.001f)
        assertEquals(1.3f, CourseCardLayout.detailScale(1.6f), 0.001f)
    }

    @Test fun `单双节和跨节课程有不同标题行数上限`() {
        assertEquals(2, CourseCardLayout.nameLines(1))
        assertEquals(4, CourseCardLayout.nameLines(2))
        assertEquals(6, CourseCardLayout.nameLines(4))
    }

    @Test fun `空教师和占位地点不占行`() {
        val empty = course.copy(teacher = " ", location = "【校本部】-")
        assertEquals(0, CourseCardLayout.detailLines(empty))
        assertEquals("", CourseCardLayout.location(empty))
        assertEquals(2, CourseCardLayout.detailLines(course))
    }

    @Test fun `按实际标题高度计算而非一律预留最大行数`() {
        val short = CourseCardLayout.Measurement(2, 15f, 26f)
        val long = CourseCardLayout.Measurement(2, 60f, 26f)
        assertEquals(45f, long.cardHeight - short.cardHeight, 0.001f)
        val emptyDetails = CourseCardLayout.Measurement(2, 15f, 0f)
        assertEquals(26f + CourseCardLayout.DETAIL_GAP, short.cardHeight - emptyDetails.cardHeight, 0.001f)
    }

    @Test fun `整周按所需高度最大的节次统一对齐`() {
        val measured = listOf(CourseCardLayout.Measurement(1, 30f, 39f),
            CourseCardLayout.Measurement(2, 60f, 39f), CourseCardLayout.Measurement(4, 90f, 26f))
        val unit = CourseCardLayout.minimumUnitHeight(measured)
        measured.forEach { assertTrue(unit * it.span >= it.cardHeight + CourseCardLayout.OUTER_GAP * 2) }
        assertEquals(measured.first().unitHeight, unit, 0.001f)
    }

    @Test fun `第十三节按末节显示且空课表不强制滚动`() {
        assertEquals(1, CourseCardLayout.span(course.copy(startSection = 13, endSection = 13)))
        assertEquals(0f, CourseCardLayout.minimumUnitHeight(emptyList()), 0f)
    }
}
