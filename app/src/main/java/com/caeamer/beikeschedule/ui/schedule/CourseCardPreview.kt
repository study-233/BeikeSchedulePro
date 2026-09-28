package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.ScheduleAppearance
import com.caeamer.beikeschedule.ui.theme.CourseColors

/** 预览真实日列宽，包含短名、长名与两门冲突课，不再用固定 96dp 宽掩盖碎行问题。 */
@Composable
internal fun CourseCardPreview(appearance: ScheduleAppearance, gridWidthPx: Int, days: Int) {
    val density = LocalDensity.current
    val measurer = rememberCourseCardMeasurer(appearance.fontScale)
    val dayWidth = ((gridWidthPx - with(density) { CourseCardLayout.TIME_COLUMN_WIDTH.dp.roundToPx() }) / days).coerceAtLeast(1)
    val gap = with(density) { CourseCardLayout.OUTER_GAP.dp.roundToPx() } * 2
    val measurements = remember(dayWidth, gap, measurer) {
        previewCourses.mapIndexed { index, course ->
            measurer.measure(course, (dayWidth / (if (index < 2) 1 else 2)) - gap)
        }
    }
    val height = (CourseCardLayout.minimumUnitHeight(measurements) * 2).coerceAtLeast(96f).dp
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shape = RoundedCornerShape(8.dp)) {
            Text("${days}天视图 · 短课名 / 长课名 / 冲突课程", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { group ->
                Row(Modifier.width(with(density) { dayWidth.toDp() }).height(height)) {
                    val courses = if (group < 2) listOf(previewCourses[group]) else previewCourses.drop(2)
                    courses.forEachIndexed { position, course ->
                        val colors = CourseColors.card(course.colorIndex, dark)
                        Surface(
                            color = colors.background, shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(CourseCardLayout.OUTER_GAP.dp)
                                .testTag("preview_card_${group}_$position"),
                        ) {
                            CourseCardText(course, colors.title, appearance.fontScale, colors.location, colors.detail)
                        }
                    }
                }
            }
        }
    }
}

private val previewCourse = CourseEntity(
    id = 0, taskId = "", name = "博弈论入门", teacher = "张老师", location = "逸夫楼402",
    dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "011111111111111111111",
    colorIndex = 2, source = CourseEntity.SOURCE_SAMPLE,
)
private val previewCourses = listOf(
    previewCourse,
    previewCourse.copy(name = "矿物加工技术新进展", teacher = "孙老师", location = "土木楼402", colorIndex = 5),
    previewCourse.copy(name = "大学物理", colorIndex = 1),
    previewCourse.copy(name = "高等数学", weekBitmap = "010101010101010101010", colorIndex = 3),
)
