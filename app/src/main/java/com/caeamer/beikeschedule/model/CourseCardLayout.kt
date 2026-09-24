package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/** 与卡片文本共用的行数和高度规则；高度输入由 Compose 按当前系统字体缩放换算。 */
object CourseCardLayout {
    fun span(course: CourseEntity): Int {
        val start = course.startSection.coerceIn(1, SectionMap.TOTAL_SMALL_SECTIONS)
        val end = course.endSection.coerceIn(start, SectionMap.TOTAL_SMALL_SECTIONS)
        return end - start + 1
    }

    fun nameLines(span: Int): Int = when {
        span <= 1 -> 1
        span == 2 -> 3
        else -> 4
    }

    fun location(course: CourseEntity): String = CourseMerger.stripCampusPrefix(course.location)
        .takeUnless { it == "-" }.orEmpty()

    fun detailLines(course: CourseEntity): Int =
        (if (location(course).isNotBlank()) 1 else 0) +
            (if (course.teacher.isNotBlank()) 1 else 0) +
            (if (WeekUtils.oddEvenLabel(course.weekBitmap).isNotEmpty()) 1 else 0)

    fun minimumUnitHeight(
        courses: List<CourseEntity>,
        nameLineHeightDp: Float,
        detailLineHeightDp: Float,
    ): Float = courses.maxOfOrNull { course ->
        val span = span(course)
        // 6dp 文字内边距 + 2dp 卡片外间距 + 2dp 测量舍入余量。
        (nameLines(span) * nameLineHeightDp + detailLines(course) * detailLineHeightDp + 10f) / span
    } ?: 0f
}
