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

    @Test fun `地点在楼名与房间号之间拆分并保留房间号格式`() {
        val cases = mapOf(
            "机械楼720" to "机械楼\n720",
            "逸夫楼402" to "逸夫楼\n402",
            "教学楼A-301" to "教学楼\nA-301",
            "信息楼301B" to "信息楼\n301B",
            "教学楼 301室" to "教学楼\n301室",
            "体育馆102" to "体育馆\n102",
            "材料科学与工程实验楼A101" to "材料科学与工程实验楼\nA101",
        )
        cases.forEach { (location, expected) ->
            assertEquals(expected, CourseCardLayout.locationForWrapping(location))
        }
        assertEquals("教学楼\n301", CourseCardLayout.locationForWrapping(CourseCardLayout.location(course)))
        assertEquals("【校本部】教学楼301", course.location)
    }

    @Test fun `无法可靠拆分的地点及已有换行保留原文`() {
        listOf("", "操场", "机械楼", "教学楼A座", "教学楼A座301", "A301", "实验中心302", "逸夫楼\n402").forEach {
            assertEquals(it, CourseCardLayout.locationForWrapping(it))
        }
    }

    @Test fun `最小高度预留一行标题和完整辅助信息`() {
        val short = CourseCardLayout.Measurement(2, 15f, 26f)
        val longLocation = CourseCardLayout.Measurement(2, 15f, 65f)
        assertEquals(39f, longLocation.cardHeight - short.cardHeight, 0.001f)
        val emptyDetails = CourseCardLayout.Measurement(2, 15f, 0f)
        assertEquals(26f + CourseCardLayout.DETAIL_GAP, short.cardHeight - emptyDetails.cardHeight, 0.001f)
    }

    @Test fun `默认适应一屏且手动高度按可用区域缩放`() {
        assertEquals(600f, CourseCardLayout.gridHeight(600f, 100, 20f), 0.001f)
        assertEquals(480f, CourseCardLayout.gridHeight(600f, 80, 20f), 0.001f)
        assertEquals(960f, CourseCardLayout.gridHeight(600f, 160, 20f), 0.001f)
        assertEquals(400f, CourseCardLayout.gridHeight(400f, 100, 20f), 0.001f)
    }

    @Test fun `必要信息超过目标高度时允许滚动并保留跨节比例`() {
        val measured = listOf(CourseCardLayout.Measurement(1, 24f, 70f),
            CourseCardLayout.Measurement(2, 24f, 45f), CourseCardLayout.Measurement(4, 24f, 30f))
        val minimum = CourseCardLayout.minimumUnitHeight(measured)
        val grid = CourseCardLayout.gridHeight(600f, 80, minimum)
        assertTrue(grid > 600f)
        measured.forEach { assertTrue(grid / 12 * it.span >= it.cardHeight + CourseCardLayout.OUTER_GAP * 2) }
    }

    @Test fun `空课表受时间栏最小高度保护且默认不额外增高`() {
        assertEquals(600f, CourseCardLayout.gridHeight(600f, 100, 21f), 0.001f)
        assertEquals(252f, CourseCardLayout.gridHeight(120f, 100, 21f), 0.001f)
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
