package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/** 卡片与预览共用的排版参数；文字的实际高度由 Compose 按列宽测量后传入。 */
object CourseCardLayout {
    const val PADDING = 3f
    const val OUTER_GAP = 1f
    const val DETAIL_GAP = 4f
    const val TIME_COLUMN_WIDTH = 36f
    const val NAME_SIZE = 12f
    const val NAME_LINE_HEIGHT = 15f
    const val LOCATION_SIZE = 10.5f
    const val DETAIL_SIZE = 10f
    const val DETAIL_LINE_HEIGHT = 13f

    fun detailScale(fontScale: Float): Float = 1f + (fontScale - 1f) * 0.5f

    fun span(course: CourseEntity): Int {
        val start = course.startSection.coerceIn(1, SectionMap.TOTAL_SMALL_SECTIONS)
        val end = course.endSection.coerceIn(start, SectionMap.TOTAL_SMALL_SECTIONS)
        return end - start + 1
    }

    fun nameLines(span: Int): Int = when {
        span <= 1 -> 2
        span == 2 -> 4
        else -> 6
    }

    fun location(course: CourseEntity): String = CourseMerger.stripCampusPrefix(course.location)
        .takeUnless { it == "-" }.orEmpty()

    fun detailLines(course: CourseEntity): Int =
        (if (location(course).isNotBlank()) 1 else 0) +
            (if (course.teacher.isNotBlank()) 1 else 0) +
            (if (WeekUtils.oddEvenLabel(course.weekBitmap).isNotEmpty()) 1 else 0)

    /** 各高度以 dp 表示，已包含应用字号与系统字体缩放，不重复乘字号。 */
    data class Measurement(val span: Int, val nameHeight: Float, val detailsHeight: Float) {
        val cardHeight: Float get() = nameHeight + detailsHeight + PADDING * 2 +
            (if (detailsHeight > 0f) DETAIL_GAP else 0f) + 2f // 节次边界、内边距取整余量
        val unitHeight: Float get() = (cardHeight + OUTER_GAP * 2) / span.coerceAtLeast(1)
    }

    fun minimumUnitHeight(measurements: List<Measurement>): Float = measurements.maxOfOrNull { it.unitHeight } ?: 0f
}
