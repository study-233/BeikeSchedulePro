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

    private val BUILDING_ROOM = Regex("^(.+(?:楼|馆))\\s*([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?:室)?)$")

    /** 仅供地点放不下一行时使用；无法确定楼名与房间号的边界则保留原文自然换行。 */
    fun locationForWrapping(location: String): String {
        val match = BUILDING_ROOM.matchEntire(location) ?: return location
        val room = match.groupValues[2]
        if (room.none { it in '0'..'9' }) return location
        return "${match.groupValues[1]}\n$room"
    }

    fun detailLines(course: CourseEntity): Int =
        (if (location(course).isNotBlank()) 1 else 0) +
            (if (course.teacher.isNotBlank()) 1 else 0) +
            (if (WeekUtils.oddEvenLabel(course.weekBitmap).isNotEmpty()) 1 else 0)

    /** 最小高度只预留一行课名及完整辅助信息，以 dp 表示，不重复乘字号。 */
    data class Measurement(val span: Int, val nameHeight: Float, val detailsHeight: Float) {
        val cardHeight: Float get() = nameHeight + detailsHeight + PADDING * 2 +
            (if (detailsHeight > 0f) DETAIL_GAP else 0f) + 2f // 节次边界、内边距取整余量
        val unitHeight: Float get() = (cardHeight + OUTER_GAP * 2) / span.coerceAtLeast(1)
    }

    fun minimumUnitHeight(measurements: List<Measurement>): Float = measurements.maxOfOrNull { it.unitHeight } ?: 0f

    /** 高度比例基于可用网格区域；必要信息及左侧时间栏决定压缩下限。 */
    fun gridHeight(viewportHeight: Float, heightPercent: Int, minimumUnitHeight: Float): Float = maxOf(
        viewportHeight.coerceAtLeast(0f) * heightPercent.coerceIn(80, 160) / 100f,
        minimumUnitHeight.coerceAtLeast(0f) * SectionMap.TOTAL_SMALL_SECTIONS,
    )
}
