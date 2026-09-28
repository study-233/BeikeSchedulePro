package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
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
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.SectionMap
import com.caeamer.beikeschedule.model.WeekUtils

/** 测量和绘制使用同一 TextStyle，避免“高度够了但实际字体仍被裁切”。 */
internal class CourseCardTypography(fontScale: Float) {
    val name = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = (CourseCardLayout.NAME_SIZE * fontScale).sp,
        lineHeight = (CourseCardLayout.NAME_LINE_HEIGHT * fontScale).sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Heading,
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    private val detailScale = CourseCardLayout.detailScale(fontScale)
    val detail = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        fontSize = (CourseCardLayout.DETAIL_SIZE * detailScale).sp,
        lineHeight = (CourseCardLayout.DETAIL_LINE_HEIGHT * detailScale).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val location = detail.copy(
        fontFamily = FontFamily.Default,
        fontSize = (CourseCardLayout.LOCATION_SIZE * detailScale).sp,
        // 避免 Text 继承宿主字距，导致实际换行与测量结果不同。
        letterSpacing = 0.sp,
        textAlign = TextAlign.Center,
    )
}

/** 左侧时间栏的测量与绘制共用明确行高，避免继承正文行高而无法压缩。 */
internal object SectionColumnTypography {
    val label = TextStyle(
        fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val time = label.copy(fontSize = 9.sp, lineHeight = 12.sp)
}

/** 测量与绘制共用：先判断完整地点能否单行显示，再按楼名和房间号分行。 */
internal fun measureCourseLocation(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    location: String,
    textWidthPx: Int,
): TextLayoutResult {
    val constraints = Constraints(maxWidth = textWidthPx.coerceAtLeast(1))
    val singleLine = textMeasurer.measure(
        location, style, softWrap = false, maxLines = 1,
        overflow = TextOverflow.Clip, constraints = constraints,
    )
    val text = if (singleLine.didOverflowWidth) CourseCardLayout.locationForWrapping(location) else location
    // 不限制地点行数；长楼名、窄冲突列及已有换行的地点也能计入完整高度。
    return textMeasurer.measure(text, style, overflow = TextOverflow.Clip, constraints = constraints)
}

/** TextMeasurer 的有界缓存按文本、样式、宽度、字体与密度复用排版结果。 */
internal class CourseCardMeasurer(
    private val textMeasurer: TextMeasurer,
    private val typography: CourseCardTypography,
    private val density: Density,
) {
    data class TextLayout(val nameLines: Int, val location: String)
    private data class Details(val location: String, val heightPx: Int)

    private fun textWidth(widthPx: Int): Int =
        (widthPx - with(density) { CourseCardLayout.PADDING.dp.roundToPx() } * 2).coerceAtLeast(1)

    private fun height(text: String, style: TextStyle, lines: Int, widthPx: Int): Int =
        textMeasurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = lines,
            constraints = Constraints(maxWidth = widthPx)).size.height

    private fun details(course: CourseEntity, widthPx: Int): Details {
        val location = CourseCardLayout.location(course)
        val locationLayout = location.takeIf { it.isNotBlank() }?.let {
            measureCourseLocation(textMeasurer, typography.location, it, widthPx)
        }
        val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
        val height = (locationLayout?.size?.height ?: 0) +
            (if (course.teacher.isNotBlank()) height(course.teacher.trim(), typography.detail, 1, widthPx) else 0) +
            (if (oddEven.isNotEmpty()) height("[$oddEven]", typography.detail, 1, widthPx) else 0)
        return Details(locationLayout?.layoutInput?.text?.text.orEmpty(), height)
    }

    /** widthPx 为去掉卡片外间距后的实际卡片宽度；内边距按绘制时同样方式取整。 */
    fun measure(course: CourseEntity, widthPx: Int): CourseCardLayout.Measurement {
        val width = textWidth(widthPx)
        return CourseCardLayout.Measurement(
            CourseCardLayout.span(course),
            height(course.name, typography.name, 1, width) / density.density,
            details(course, width).heightPx / density.density,
        )
    }

    /** 按最终卡片高度给标题分配剩余空间；尝试各行数时包含省略号的实际排版。 */
    fun layout(course: CourseEntity, widthPx: Int, heightPx: Int): TextLayout {
        val width = textWidth(widthPx)
        val details = details(course, width)
        val padding = with(density) { CourseCardLayout.PADDING.dp.roundToPx() } * 2
        val gap = if (details.heightPx > 0) with(density) { CourseCardLayout.DETAIL_GAP.dp.roundToPx() } else 0
        val available = (heightPx - padding - gap - details.heightPx).coerceAtLeast(0)
        val lines = (CourseCardLayout.nameLines(CourseCardLayout.span(course)) downTo 1).firstOrNull {
            height(course.name, typography.name, it, width) <= available
        } ?: 1
        return TextLayout(lines, details.location)
    }

    fun minimumTimeUnitHeight(sectionTimes: List<SectionTimeEntity>): Float {
        val timeMap = sectionTimes.associateBy { it.section }
        val width = with(density) { CourseCardLayout.TIME_COLUMN_WIDTH.dp.roundToPx() }
        fun timeHeight(text: String): Int = textMeasurer.measure(
            text, SectionColumnTypography.time, constraints = Constraints(maxWidth = width),
        ).size.height
        return SectionMap.BIG_SECTIONS.mapIndexed { index, range ->
            val heightPx = height(SectionMap.BIG_NAMES[index], SectionColumnTypography.label, 1, width) +
                (timeMap[range.first]?.let { timeHeight(it.startTime) } ?: 0) +
                (timeMap[range.last]?.let { timeHeight(it.endTime) } ?: 0)
            (heightPx / density.density + 2f) / range.count()
        }.maxOrNull() ?: 0f
    }
}

@Composable
internal fun rememberCourseCardMeasurer(fontScale: Float, cacheSize: Int = 512): CourseCardMeasurer {
    // 在课表宿主创建，所有 Pager 页共用，避免每个周页重新创建一份缓存。
    val textMeasurer = rememberTextMeasurer(cacheSize = cacheSize)
    val density = LocalDensity.current
    return remember(textMeasurer, fontScale, density) {
        CourseCardMeasurer(textMeasurer, CourseCardTypography(fontScale), density)
    }
}
