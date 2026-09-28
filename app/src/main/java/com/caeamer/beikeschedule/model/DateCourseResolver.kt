package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** 原始课程保持不变；实际日期与来源教学日期分别传递到界面和提醒。 */
data class CourseOccurrence(
    val course: CourseEntity,
    val date: LocalDate,
    val sourceDate: LocalDate,
    val note: String = "",
) {
    val isMakeup: Boolean get() = date != sourceDate
    val displayCourse: CourseEntity get() = course.copy(dayOfWeek = date.dayOfWeek.value)
}

data class AppliedAdjustments(val rules: SemesterAdjustments? = null, val error: String? = null)

data class ScheduleWeekPage(val monday: LocalDate?, val teachingWeek: Int?) {
    val label: String get() = teachingWeek?.let { "第${it}周" } ?: "调休周"
    val dateRange: String get() = monday?.let { "${it.monthValue}/${it.dayOfMonth}–${it.plusDays(6).monthValue}/${it.plusDays(6).dayOfMonth}" }.orEmpty()
    fun contains(date: LocalDate): Boolean = monday?.let { date >= it && date <= it.plusDays(6) } == true
}

object DateCourseResolver {
    fun applicable(semester: SettingsStore.SemesterConfig, config: CalendarAdjustments?): AppliedAdjustments {
        val rules = config?.semesters?.firstOrNull { it.xn == semester.xn && it.xq == semester.xq }
            ?: return AppliedAdjustments()
        val invalid = rules.extraClasses.firstOrNull {
            WeekResolver.teachingWeekOf(semester, LocalDate.parse(it.sourceDate)) == null
        }
        return if (invalid == null) AppliedAdjustments(rules)
        else AppliedAdjustments(error = "无法确定 ${invalid.sourceDate} 的教学周，本学期调休暂未应用；请检查学期与校历。")
    }

    fun resolve(courses: List<CourseEntity>, semester: SettingsStore.SemesterConfig,
                config: CalendarAdjustments?, date: LocalDate): List<CourseOccurrence> {
        val rules = applicable(semester, config).rules
        val visible = courses.filter { !it.hidden && !it.isUnscheduled }
        fun original(source: LocalDate, importedOnly: Boolean): List<CourseEntity> {
            val week = WeekResolver.teachingWeekOf(semester, source) ?: return emptyList()
            return visible.filter { it.dayOfWeek == source.dayOfWeek.value && it.hasClassOnWeek(week) &&
                (!importedOnly || it.source == CourseEntity.SOURCE_IMPORT) }.map { row ->
                // 保留既有占位信息兜底，但有明确教师/地点的当周行不能被其他周覆盖。
                val siblings = visible.filter { it.source == row.source && it.name == row.name &&
                    it.dayOfWeek == row.dayOfWeek && it.startSection == row.startSection && it.endSection == row.endSection }
                row.copy(
                    location = if (CourseMerger.plausibleLocation(row.location)) row.location else
                        siblings.firstOrNull { CourseMerger.plausibleLocation(it.location) }?.location ?: row.location,
                    teacher = row.teacher.ifBlank { siblings.firstOrNull { it.teacher.isNotBlank() }?.teacher.orEmpty() },
                )
            }
        }
        val candidates = original(date, false)
            .filterNot { it.source == CourseEntity.SOURCE_IMPORT && date.toString() in rules?.suspendedDates.orEmpty() }
            .map { CourseOccurrence(it, date, date) }.toMutableList()
        rules?.extraClasses?.filter { it.date == date.toString() }?.forEach { rule ->
            val source = LocalDate.parse(rule.sourceDate)
            candidates += original(source, true).map { CourseOccurrence(it, date, source, rule.note) }
        }
        // 先按原始课程身份去重，再合并同一天同一来源教学日期的教务拆行。
        // 不提前合并整学期课程，否则其他周的教师、地点会污染补课日期。
        val unique = candidates.distinctBy { listOf(it.course.scheduleId, if (it.course.id == 0L) it.course else it.course.id,
            it.course.startSection, it.course.endSection) }
        return unique.groupBy { Triple(it.course.source, it.sourceDate, it.note) }.values.flatMap { group ->
            CourseMerger.mergeSameSlot(group.map { it.course }).map { merged ->
                group.first().copy(course = merged)
            }
        }.sortedWith(compareBy({ it.course.startSection }, { it.course.id }))
    }

    /** 仅普通教学日允许淡化非本周课程；停课日禁止教务占位，手动课程保持原行为。 */
    fun inactive(courses: List<CourseEntity>, semester: SettingsStore.SemesterConfig,
                 config: CalendarAdjustments?, date: LocalDate): List<CourseEntity> {
        val week = WeekResolver.teachingWeekOf(semester, date) ?: return emptyList()
        val suspended = date.toString() in applicable(semester, config).rules?.suspendedDates.orEmpty()
        return courses.filter { !it.hidden && !it.isUnscheduled && it.dayOfWeek == date.dayOfWeek.value &&
            !it.hasClassOnWeek(week) && !(suspended && it.source == CourseEntity.SOURCE_IMPORT) }
            .groupBy { it.source }.values.flatMap(CourseMerger::mergeSameSlot)
    }

    fun pages(semester: SettingsStore.SemesterConfig, config: CalendarAdjustments?): List<ScheduleWeekPage> {
        val teaching = (1..semester.totalWeeks.coerceAtLeast(1)).map { ScheduleWeekPage(WeekResolver.weekMonday(semester, it), it) }
        val extra = applicable(semester, config).rules?.extraClasses.orEmpty().map {
            LocalDate.parse(it.date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }.distinct().filter { monday -> teaching.none { it.monday == monday } }.map { ScheduleWeekPage(it, null) }
        return (teaching + extra).sortedBy { it.monday ?: LocalDate.MAX }
    }

    fun defaultPage(pages: List<ScheduleWeekPage>, semester: SettingsStore.SemesterConfig, today: LocalDate): Int {
        val actual = pages.indexOfFirst { it.contains(today) }
        if (actual >= 0) return actual
        val week = WeekResolver.defaultWeek(WeekResolver.locateWeek(semester, today), semester.totalWeeks)
        return pages.indexOfFirst { it.teachingWeek == week }.coerceAtLeast(0)
    }

    fun visibleDays(page: ScheduleWeekPage, semester: SettingsStore.SemesterConfig,
                    config: CalendarAdjustments?, hideWeekend: Boolean): List<Int> {
        val makeup = applicable(semester, config).rules?.extraClasses.orEmpty().any { page.contains(LocalDate.parse(it.date)) }
        return (1..if (hideWeekend && !makeup) 5 else 7).toList()
    }
}
