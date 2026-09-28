package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
                    CourseCardPreview(ScheduleAppearance(), gridWidth, days)
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
        }
    }
}
