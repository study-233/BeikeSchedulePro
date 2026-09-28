package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.ui.schedule.CourseCardText
import com.caeamer.beikeschedule.ui.schedule.rememberCourseCardMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CourseCardTextTest {
    @get:Rule val compose = createComposeRule()

    private val course = CourseEntity(
        taskId = "", name = "大学物理", teacher = "张老师", location = "【校本部】教学楼301",
        dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "010101010",
        colorIndex = 0, source = CourseEntity.SOURCE_MANUAL,
    )

    private fun show(value: CourseEntity, scale: Float = 1f, systemScale: Float = 1f, width: Int = 96, extraHeight: Int = 0) {
        compose.setContent { TestCard(value, scale, systemScale, width, extraHeight) }
    }

    @Composable
    private fun TestCard(value: CourseEntity, scale: Float, systemScale: Float, width: Int, extraHeight: Int = 0) {
        val density = Density(LocalDensity.current.density, systemScale)
        CompositionLocalProvider(LocalDensity provides density) {
            val measurer = rememberCourseCardMeasurer(scale)
            val measured = measurer.measure(value, with(density) { width.dp.roundToPx() })
            MaterialTheme {
                Box(Modifier.width(width.dp).height((measured.cardHeight + extraHeight).dp).testTag("card")) {
                    CourseCardText(value, Color.Black, scale)
                }
            }
        }
    }

    @Test
    fun teacherIsBelowLocationAndAboveWeekLabel() {
        show(course)
        val tags = listOf("course_name", "course_location", "course_teacher", "course_weeks")
        val bounds = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top) }
        compose.onNodeWithTag("course_teacher").assertTextEquals("张老师")
    }

    @Test
    fun missingTeacherDoesNotLeaveATextNode() {
        show(course.copy(teacher = " "))
        compose.onNodeWithTag("course_teacher").assertDoesNotExist()
    }

    @Test
    fun teacherStaysAtBottomWhenLocationIsMissing() {
        show(course.copy(location = "", weekBitmap = "011111111"), extraHeight = 60)
        compose.onNodeWithTag("course_location").assertDoesNotExist()
        val name = compose.onNodeWithTag("course_name").fetchSemanticsNode().boundsInRoot
        val teacher = compose.onNodeWithTag("course_teacher").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        assertTrue(teacher.top > name.bottom)
        assertEquals(with(compose.density) { 3.dp.toPx() }, card.bottom - teacher.bottom, 1f)
        assertTrue(teacher.top > card.center.y)
    }

    @Test
    fun allTextIsCenteredAndDetailsStayInTheLowerRegion() {
        show(course.copy(name = "矿物加工技术新进展"), width = 66, extraHeight = 100)
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        listOf("course_name", "course_location", "course_teacher", "course_weeks").forEach { tag ->
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            repeat(layout.lineCount) { line ->
                val textCenter = (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f
                assertEquals(layout.size.width / 2f, textCenter, 1f)
            }
            if (tag != "course_name") {
                assertTrue(node.fetchSemanticsNode().boundsInRoot.top > card.center.y)
            }
        }
    }

    @Test
    fun longTeacherIsOneEllipsizedLineInAShortCourseWithLargeFonts() {
        show(
            course.copy(endSection = 1, teacher = "张老师、李老师、王老师、赵老师"),
            scale = 1.6f, systemScale = 2f, width = 64,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("course_teacher").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        compose.onNodeWithTag("course_weeks").assertIsDisplayed()
    }
    @Test
    fun balancedChineseTitlesDoNotLeaveASingleCharacterOnLastLine() {
        var name by mutableStateOf("博弈论入门")
        compose.setContent { TestCard(course.copy(name = name), 1f, 1f, 66) }
        listOf("博弈论入门", "矿物加工技术新进展").forEach { title ->
            compose.runOnIdle { name = title }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("course_name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(title, layout.layoutInput.text.text)
            assertTrue(layout.lineCount > 1)
            val last = layout.lineCount - 1
            assertTrue(layout.getLineEnd(last, visibleEnd = true) - layout.getLineStart(last) > 1)
            assertTrue(!layout.isLineEllipsized(last))
        }
    }

    @Test
    fun actualMeasuredHeightKeepsAllInformationAcrossWidthsAndFontScales() {
        data class Case(val width: Int, val scale: Float, val systemScale: Float, val span: Int)
        var scenario by mutableStateOf(Case(66, 1f, 1f, 2))
        compose.setContent {
            val c = scenario
            TestCard(course.copy(name = "矿物加工技术新进展", endSection = c.span), c.scale, c.systemScale, c.width)
        }
        // 约 360dp 屏幕五天、七天及两课冲突的列宽。
        for (width in listOf(62, 44, 21)) for (scale in listOf(0.8f, 1f, 1.6f)) {
            for (systemScale in listOf(1f, 2f)) for (span in listOf(1, 2, 4)) {
                compose.runOnIdle { scenario = Case(width, scale, systemScale, span) }
                val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
                val rows = listOf("course_name", "course_location", "course_teacher", "course_weeks")
                    .map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
                rows.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top) }
                assertTrue(rows.last().bottom <= card.bottom)
            }
        }
    }

}
