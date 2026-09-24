package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 与 Widget 布局无关的今日课程结果，保留全部待展示课程供不同尺寸使用。 */
data class TodaySchedule(
    val date: LocalDate,
    val status: Status,
    val courses: List<TodayCourse> = emptyList(),
    val nextChangeAt: ZonedDateTime,
) {
    enum class Status { NEEDS_SETUP, NON_TEACHING_DAY, NO_CLASSES, FINISHED, CLASSES }
}

data class TodayCourse(
    val course: CourseEntity,
    val start: LocalTime?,
    val end: LocalTime?,
    val phase: Phase,
) {
    enum class Phase { ONGOING, UPCOMING, UNKNOWN_TIME }
}
