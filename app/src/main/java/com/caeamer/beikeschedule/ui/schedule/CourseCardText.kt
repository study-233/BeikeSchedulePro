package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.WeekUtils

/** 实际卡片与外观预览共用：标题在上居中，辅助信息靠下居中。 */
@Composable
internal fun CourseCardText(
    course: CourseEntity,
    color: Color,
    fontScale: Float,
    locationColor: Color = color,
    detailColor: Color = color,
) {
    val typography = remember(fontScale) { CourseCardTypography(fontScale) }
    val measurer = rememberCourseCardMeasurer(fontScale, cacheSize = 32)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = measurer.layout(course, constraints.maxWidth, constraints.maxHeight)
        Column(Modifier.fillMaxSize().padding(CourseCardLayout.PADDING.dp)) {
            Text(
                course.name,
                style = typography.name,
                color = color,
                maxLines = layout.nameLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("course_name"),
            )
            Spacer(Modifier.weight(1f))
            if (CourseCardLayout.detailLines(course) > 0) Spacer(Modifier.height(CourseCardLayout.DETAIL_GAP.dp))
            if (layout.location.isNotBlank()) {
                Text(
                    layout.location, style = typography.location, color = locationColor,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth().testTag("course_location"),
                )
            }
            if (course.teacher.isNotBlank()) {
                Text(
                    course.teacher.trim(), style = typography.detail, color = detailColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("course_teacher"),
                )
            }
            val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
            if (oddEven.isNotEmpty()) {
                Text(
                    "[$oddEven]", style = typography.detail, color = detailColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("course_weeks"),
                )
            }
        }
    }
}
