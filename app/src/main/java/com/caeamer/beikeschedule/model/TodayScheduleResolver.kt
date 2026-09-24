package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.repo.ScheduleSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 只做本地计算；使用严格教学周口径，不复用页面的“下一节课”语义。 */
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
        val week = WeekResolver.teachingWeekOf(semester, date)
            ?: return empty(TodaySchedule.Status.NON_TEACHING_DAY)
        val today = CourseMerger.mergeSameSlot(
            snapshot.courses.filter { !it.hidden && !it.isUnscheduled && it.dayOfWeek == date.dayOfWeek.value },
        ).filter { it.hasClassOnWeek(week) }
        if (today.isEmpty()) return empty(TodaySchedule.Status.NO_CLASSES)

        val sections = snapshot.sectionTimes.associateBy { it.section }
        val boundaries = mutableListOf(midnight)
        val remaining = today.mapNotNull { course ->
            val start = parseTime(sections[course.startSection]?.startTime)
            val end = parseTime(sections[course.endSection]?.endTime)
            if (start == null || end == null || !end.isAfter(start) || course.endSection < course.startSection) {
                // 不猜测缺失作息：保留节次提示，不能误报“今日课程已结束”。
                TodayCourse(course, null, null, TodayCourse.Phase.UNKNOWN_TIME)
            } else {
                val startsAt = date.atTime(start).atZone(now.zone)
                val endsAt = date.atTime(end).atZone(now.zone)
                if (startsAt.isAfter(now)) boundaries += startsAt
                if (endsAt.isAfter(now)) boundaries += endsAt
                when {
                    !now.isBefore(endsAt) -> null
                    !now.isBefore(startsAt) -> TodayCourse(course, start, end, TodayCourse.Phase.ONGOING)
                    else -> TodayCourse(course, start, end, TodayCourse.Phase.UPCOMING)
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
