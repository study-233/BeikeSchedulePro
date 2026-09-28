package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.ScheduleAppearance
import com.caeamer.beikeschedule.ui.schedule.CourseCardPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CourseCardPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heightControlUpdatesPreviewAndKeepsConflictCoursesAligned() {
        var heightPercent by mutableIntStateOf(80)
        compose.setContent {
            val density = LocalDensity.current
            MaterialTheme {
                CourseCardPreview(
                    ScheduleAppearance(fontPercent = 80, sectionHeightPercent = heightPercent),
                    with(density) { 360.dp.roundToPx() }, 7,
                )
            }
        }
        val compact = compose.onNodeWithTag("preview_card_0_0").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { heightPercent = 160 }
        val tall = compose.onNodeWithTag("preview_card_0_0").fetchSemanticsNode().boundsInRoot.height
        assertTrue(tall > compact)
        val conflict = compose.onNodeWithTag("preview_card_2_0").fetchSemanticsNode().boundsInRoot.height
        assertEquals(tall, conflict, 1f)
        compose.runOnIdle { heightPercent = 80 }
        assertEquals(compact, compose.onNodeWithTag("preview_card_0_0").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun previewFollowsRealDayWidthAndConflictColumnsWhenWeekendSettingChanges() {
        var days by mutableStateOf(5)
        var gridWidth = 0
        var timeWidth = 0
        var gap = 0
        compose.setContent {
            val density = LocalDensity.current
            timeWidth = with(density) { CourseCardLayout.TIME_COLUMN_WIDTH.dp.roundToPx() }
            gap = with(density) { CourseCardLayout.OUTER_GAP.dp.roundToPx() } * 2
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    gridWidth = constraints.maxWidth
                    CourseCardPreview(ScheduleAppearance(fontPercent = 160), gridWidth, days)
                }
            }
        }
        var previousWidth = Float.MAX_VALUE
        for (dayCount in listOf(5, 7)) {
            compose.runOnIdle { days = dayCount }
            val normal = compose.onNodeWithTag("preview_card_0_0").fetchSemanticsNode().boundsInRoot
            val narrow = compose.onNodeWithTag("preview_card_2_0").fetchSemanticsNode().boundsInRoot
            val dayWidth = (gridWidth - timeWidth) / dayCount
            assertEquals((dayWidth - gap).toFloat(), normal.width, 1f)
            assertEquals((dayWidth / 2 - gap).toFloat(), narrow.width, 1f)
            assertEquals(normal.height, narrow.height, 1f)
            assertTrue(normal.width < previousWidth)
            previousWidth = normal.width
            val locations = compose.onAllNodesWithTag("course_location", useUnmergedTree = true)
            locations.assertCountEquals(4)
            repeat(4) { index ->
                val layouts = mutableListOf<TextLayoutResult>()
                locations[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue(!layout.didOverflowHeight)
                repeat(layout.lineCount) { assertTrue(!layout.isLineEllipsized(it)) }
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            }
        }
    }
}
