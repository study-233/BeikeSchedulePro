package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.ui.schedule.CourseDetailOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CourseDetailTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val course = CourseEntity(id = 1, taskId = "", name = "大学物理", teacher = "张老师", location = "教学楼301",
        dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "011111111", colorIndex = 0, source = CourseEntity.SOURCE_MANUAL)

    @Test fun missingSourceStillClosesAndCallsBackOnce() {
        var shown by mutableStateOf(true)
        var bounds by mutableStateOf<Rect?>(Rect(8f, 80f, 120f, 240f))
        var closed = 0
        compose.setContent {
            MaterialTheme {
                if (shown) CourseDetailOverlay(course, "1:1:1:1:2", bounds, emptyList(), 1f,
                    onClosed = { closed++; shown = false }, onEdit = {}, onHide = {}, onDelete = {})
            }
        }
        compose.onNodeWithTag("course_detail_panel").assertIsDisplayed()
        compose.runOnIdle { bounds = null }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("course_detail_panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun downwardDragFromHandleDismisses() {
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown) CourseDetailOverlay(course, "1:1:1:1:2", null, emptyList(), 1f,
                    onClosed = { shown = false }, onEdit = {}, onHide = {}, onDelete = {})
            }
        }
        val panel = compose.onNodeWithTag("course_detail_panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("course_detail_handle").performTouchInput {
            swipe(center, center.copy(y = center.y + panel.height * 0.4f), durationMillis = 300)
        }
        compose.onNodeWithTag("course_detail_panel").assertDoesNotExist()
    }

    @Test fun shortDetailHasNoLargeBlankAreaBelowActions() {
        var allowedBottomSpace = 0f
        compose.setContent {
            allowedBottomSpace = with(LocalDensity.current) { 32.dp.toPx() }
            MaterialTheme {
                CourseDetailOverlay(course, "short", null, emptyList(), 1f,
                    onClosed = {}, onEdit = {}, onHide = {}, onDelete = {})
            }
        }
        val panel = compose.onNodeWithTag("course_detail_panel").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText("隐藏后可在“我的 → 隐藏课程”恢复")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("短详情应随内容收紧，而非保留整屏空白", panel.bottom - hint.bottom <= allowedBottomSpace)
    }

    @Test fun longDetailCanScrollToActionsWhileCloseRemainsVisible() {
        compose.setContent {
            MaterialTheme {
                CourseDetailOverlay(course.copy(name = "很长的课程名称".repeat(60)), "long", null, emptyList(), 1f,
                    onClosed = {}, onEdit = {}, onHide = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("隐藏").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
    }
}
