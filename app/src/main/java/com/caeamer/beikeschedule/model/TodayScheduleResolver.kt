package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.repo.ScheduleSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 只做本地计算；与课表共用日期排期，另保留正在上课和全天结束等 Widget 状态。 */
object TodayScheduleResolver {
    fun resolve(snapshot: ScheduleSnapshot, now: ZonedDateTime): TodaySchedule {
        val date = now.toLocalDate()
        val midnight = date.plusDays(1).atStartOfDay(now.zone)
        fun empty(status: TodaySchedule.Status) = TodaySchedule(
            date = date, status = status, nextChangeAt = midnight,
        )
        val semester = snapshot.semester
        val configured = if (semester.weekMondays.isNotEmpty()) {
            semester.weekMondays.any { parseDate(it) != null }
        } else {
            parseDate(semester.firstMonday) != null && semester.totalWeeks > 0
        }
        if (!configured || snapshot.courses.isEmpty()) return empty(TodaySchedule.Status.NEEDS_SETUP)
        val today = DateCourseResolver.resolve(snapshot.courses, semester, snapshot.adjustments, date)
        if (today.isEmpty()) return empty(if (WeekResolver.teachingWeekOf(semester, date) == null)
            TodaySchedule.Status.NON_TEACHING_DAY else TodaySchedule.Status.NO_CLASSES)

        val sections = snapshot.sectionTimes.associateBy { it.section }
        val boundaries = mutableListOf(midnight)
        val remaining = today.mapNotNull { occurrence ->
            val course = occurrence.displayCourse
            val start = parseTime(sections[course.startSection]?.startTime)
            val end = parseTime(sections[course.endSection]?.endTime)
            if (start == null || end == null || !end.isAfter(start) || course.endSection < course.startSection) {
                // 不猜测缺失作息：保留节次提示，不能误报“今日课程已结束”。
                TodayCourse(course, null, null, TodayCourse.Phase.UNKNOWN_TIME, occurrence.isMakeup)
            } else {
                val startsAt = date.atTime(start).atZone(now.zone)
                val endsAt = date.atTime(end).atZone(now.zone)
                if (startsAt.isAfter(now)) boundaries += startsAt
                if (endsAt.isAfter(now)) boundaries += endsAt
                when {
                    !now.isBefore(endsAt) -> null
                    !now.isBefore(startsAt) -> TodayCourse(course, start, end, TodayCourse.Phase.ONGOING, occurrence.isMakeup)
                    else -> TodayCourse(course, start, end, TodayCourse.Phase.UPCOMING, occurrence.isMakeup)
                }
            }
        }.sortedWith(
            compareBy<TodayCourse> { it.phase.ordinal }
                .thenBy { it.start ?: LocalTime.MAX }
                .thenBy { it.end ?: LocalTime.MAX }
                .thenBy { it.course.startSection }
                .thenBy { it.course.id },
        )
        return TodaySchedule(
            date = date,
            status = if (remaining.isEmpty()) TodaySchedule.Status.FINISHED else TodaySchedule.Status.CLASSES,
            courses = remaining,
            nextChangeAt = boundaries.minBy { it.toInstant() },
        )
    }

    private fun parseTime(value: String?): LocalTime? =
        value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}
