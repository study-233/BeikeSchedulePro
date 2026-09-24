package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.ui.schedule.CourseCardText
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

    private fun show(value: CourseEntity, scale: Float = 1f, systemScale: Float = 1f, width: Int = 96) {
        compose.setContent {
            val density = Density(LocalDensity.current.density, systemScale)
            CompositionLocalProvider(LocalDensity provides density) {
                val height = with(density) {
                    CourseCardLayout.minimumUnitHeight(
                        listOf(value), (13 * scale).sp.toDp().value, (11 * scale).sp.toDp().value,
                    ) * CourseCardLayout.span(value)
                }
                MaterialTheme {
                    Box(Modifier.width(width.dp).height(height.dp)) {
                        CourseCardText(value, Color.Black, scale)
                    }
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
    fun teacherFollowsNameWhenLocationIsMissing() {
        show(course.copy(location = ""))
        compose.onNodeWithTag("course_location").assertDoesNotExist()
        val name = compose.onNodeWithTag("course_name").fetchSemanticsNode().boundsInRoot
        val teacher = compose.onNodeWithTag("course_teacher").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(name.bottom, teacher.top, 1f)
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
}
