package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.WeekUtils

/** 实际卡片与外观预览共用，地点和教师始终各预留一行。 */
@Composable
internal fun CourseCardText(
    course: CourseEntity,
    color: Color,
    fontScale: Float,
    isNext: Boolean = false,
) {
    val detailStyle = TextStyle(
        fontSize = (9 * fontScale).sp,
        lineHeight = (11 * fontScale).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    Column(Modifier.padding(3.dp)) {
        Text(
            course.name,
            style = TextStyle(
                fontSize = (10 * fontScale).sp,
                lineHeight = (13 * fontScale).sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = color,
            maxLines = CourseCardLayout.nameLines(CourseCardLayout.span(course)),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("course_name")
                .then(if (isNext) Modifier.padding(end = 15.dp) else Modifier),
        )
        val location = CourseCardLayout.location(course)
        if (location.isNotBlank()) {
            Text(
                location, style = detailStyle, color = color.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("course_location"),
            )
        }
        if (course.teacher.isNotBlank()) {
            Text(
                course.teacher.trim(), style = detailStyle, color = color.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("course_teacher"),
            )
        }
        val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
        if (oddEven.isNotEmpty()) {
            Text(
                "[$oddEven]", style = detailStyle, color = color.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("course_weeks"),
            )
        }
    }
}
