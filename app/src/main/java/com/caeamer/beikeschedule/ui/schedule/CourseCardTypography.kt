package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.WeekUtils

/** 测量和绘制使用同一 TextStyle，避免“高度够了但实际字体仍被裁切”。 */
internal class CourseCardTypography(fontScale: Float) {
    val name = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = (CourseCardLayout.NAME_SIZE * fontScale).sp,
        lineHeight = (CourseCardLayout.NAME_LINE_HEIGHT * fontScale).sp,
        fontWeight = FontWeight.Medium,
        lineBreak = LineBreak.Heading,
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    private val detailScale = CourseCardLayout.detailScale(fontScale)
    val detail = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = (CourseCardLayout.DETAIL_SIZE * detailScale).sp,
        lineHeight = (CourseCardLayout.DETAIL_LINE_HEIGHT * detailScale).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val location = detail.copy(fontSize = (CourseCardLayout.LOCATION_SIZE * detailScale).sp)
}

/** TextMeasurer 的有界缓存按文本、样式、宽度、字体与密度复用排版结果。 */
internal class CourseCardMeasurer(
    private val textMeasurer: TextMeasurer,
    private val typography: CourseCardTypography,
    private val density: Density,
) {
    /** widthPx 为去掉卡片外间距后的实际卡片宽度；内边距按绘制时同样方式取整。 */
    fun measure(course: CourseEntity, widthPx: Int): CourseCardLayout.Measurement {
        val textWidth = (widthPx - with(density) { CourseCardLayout.PADDING.dp.roundToPx() } * 2).coerceAtLeast(1)
        fun height(text: String, style: TextStyle, lines: Int): Float =
            textMeasurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = lines,
                constraints = Constraints(maxWidth = textWidth)).size.height / density.density
        val location = CourseCardLayout.location(course)
        val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
        val details = (if (location.isNotBlank()) height(location, typography.location, 1) else 0f) +
            (if (course.teacher.isNotBlank()) height(course.teacher.trim(), typography.detail, 1) else 0f) +
            (if (oddEven.isNotEmpty()) height("[$oddEven]", typography.detail, 1) else 0f)
        return CourseCardLayout.Measurement(CourseCardLayout.span(course),
            height(course.name, typography.name, CourseCardLayout.nameLines(CourseCardLayout.span(course))), details)
    }
}

@Composable
internal fun rememberCourseCardMeasurer(fontScale: Float): CourseCardMeasurer {
    // 在课表宿主创建，所有 Pager 页共用，避免每个周页重新创建一份缓存。
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val density = LocalDensity.current
    return remember(textMeasurer, fontScale, density) {
        CourseCardMeasurer(textMeasurer, CourseCardTypography(fontScale), density)
    }
}
