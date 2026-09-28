package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleSnapshot
import com.caeamer.beikeschedule.model.*
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class CalendarAdjustmentsTest {
    private val semester = SettingsStore.SemesterConfig("2026-2027", "1", "秋季", "2026-09-07", 4,
        listOf("2026-09-07", "2026-09-14", "2026-09-21", "2026-10-05"),
        listOf("2026-09-20", "2026-10-06", "2026-10-07", "2026-10-10"))
    private val rules = SemesterAdjustments("2026-2027", "1", listOf("2026-10-06", "2026-10-07"), listOf(
        ExtraClass("2026-09-20", "2026-10-06", "补10月6日周二课程"),
        ExtraClass("2026-10-10", "2026-10-07", "补10月7日周三课程")))
    private val config = CalendarAdjustments(1, 1, listOf(rules))
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun course(id: Long, day: Int, weeks: Set<Int> = setOf(4), source: Int = CourseEntity.SOURCE_IMPORT) = CourseEntity(
        id = id, taskId = "task$id", name = "课程$id", teacher = "教师$id", location = "教室$id",
        dayOfWeek = day, startSection = 1, endSection = 2,
        weekBitmap = SessionExpander.buildWeekBitmap(weeks, 4), colorIndex = 0, source = source)

    private fun resolve(date: String, courses: List<CourseEntity>, value: CalendarAdjustments? = config) =
        DateCourseResolver.resolve(courses, semester, value, LocalDate.parse(date))

    @Test fun maintainedFileContainsConfirmedMappingsAndRoundTrips() {
        val file = listOf(File("../data/calendar-adjustments.json"), File("data/calendar-adjustments.json")).first { it.isFile }
        val parsed = CalendarAdjustmentCodec.parse(file.readText())
        assertTrue(parsed.revision >= 1)
        assertEquals(rules, parsed.semesters.single { it.xn == "2026-2027" && it.xq == "1" })
        assertEquals(parsed, CalendarAdjustmentCodec.parse(CalendarAdjustmentCodec.encode(parsed)))
    }

    @Test fun september20UsesWeek4TuesdayNotWeek2AndKeepsOriginalAndManualCourses() {
        val rows = listOf(course(1, 2), course(2, 2, setOf(2)), course(3, 7, setOf(2)),
            course(4, 7, setOf(2), CourseEntity.SOURCE_MANUAL), course(5, 2, source = CourseEntity.SOURCE_MANUAL))
        val actual = resolve("2026-09-20", rows)
        assertEquals(setOf(1L, 3L, 4L), actual.map { it.course.id }.toSet())
        val makeup = actual.single { it.isMakeup }
        assertEquals(LocalDate.parse("2026-10-06"), makeup.sourceDate)
        assertEquals(7, makeup.displayCourse.dayOfWeek)
        assertEquals(2, makeup.course.dayOfWeek)
        assertEquals("教室1", makeup.course.location)
        assertEquals(3, WeekLayout.layoutResolved(actual.map { it.displayCourse }, emptyList()).clusters.single().size)
    }

    @Test fun october10UsesWeek4WednesdayAndSuspensionsOnlyAffectImportedCourses() {
        val rows = listOf(course(1, 3), course(2, 6), course(3, 3, source = CourseEntity.SOURCE_MANUAL))
        assertEquals(setOf(1L, 2L), resolve("2026-10-10", rows).map { it.course.id }.toSet())
        assertEquals(listOf(3L), resolve("2026-10-07", rows).map { it.course.id })
        assertTrue(resolve("2026-10-06", listOf(course(4, 2))).isEmpty())
        assertTrue(DateCourseResolver.inactive(listOf(course(5, 2, setOf(2))), semester, config,
            LocalDate.parse("2026-10-06")).isEmpty())
    }

    @Test fun hiddenAndUnscheduledCoursesAreExcludedAndOtherWeeksCannotSupplyLocation() {
        val active = course(1, 2).copy(location = "第4周教室")
        val otherWeek = active.copy(id = 2, location = "第2周教室", weekBitmap = "00100")
        val hidden = course(3, 2).copy(hidden = true)
        val unscheduled = course(4, 0)
        assertEquals(listOf("第4周教室"), resolve("2026-09-20", listOf(otherWeek, active, hidden, unscheduled)).map { it.course.location })
    }

    @Test fun multipleSourcesDoNotDuplicateSameCourseAndNeverApplyRulesRecursively() {
        val allTuesdays = course(1, 2, setOf(1, 2, 3, 4))
        val extra = rules.copy(extraClasses = rules.extraClasses + listOf(
            ExtraClass("2026-09-20", "2026-09-15"), ExtraClass("2026-09-21", "2026-09-20")))
        val value = config.copy(semesters = listOf(extra))
        assertEquals(1, resolve("2026-09-20", listOf(allTuesdays), value).size)
        assertTrue(resolve("2026-09-21", listOf(allTuesdays), value).isEmpty())
    }

    @Test fun invalidSourceDisablesWholeSemesterAndOtherSemesterDoesNotMatch() {
        val invalid = config.copy(semesters = listOf(rules.copy(extraClasses = listOf(ExtraClass("2026-09-20", "2026-09-29")))))
        assertNotNull(DateCourseResolver.applicable(semester, invalid).error)
        assertEquals(1, resolve("2026-10-06", listOf(course(1, 2)), invalid).size)
        assertNull(DateCourseResolver.applicable(semester.copy(xq = "2"), config).rules)
        assertEquals(1, resolve("2026-10-06", listOf(course(1, 2)), config.copy(semesters = emptyList())).size)
    }

    @Test fun skippedWeekGetsChronologicalPageAndWeekendVisibilityUsesRules() {
        val value = config.copy(semesters = listOf(rules.copy(extraClasses = rules.extraClasses + ExtraClass("2026-10-03", "2026-10-06"))))
        val pages = DateCourseResolver.pages(semester, value)
        assertEquals(listOf(1, 2, 3, null, 4), pages.map { it.teachingWeek })
        assertEquals(3, DateCourseResolver.defaultPage(pages, semester, LocalDate.parse("2026-10-03")))
        assertEquals((1..7).toList(), DateCourseResolver.visibleDays(pages[1], semester, value, true))
        assertEquals((1..5).toList(), DateCourseResolver.visibleDays(pages[0], semester, value, true))
        assertEquals(4, DateCourseResolver.pages(semester, null).size)
    }

    @Test fun widgetNextClassAndRemindersUseActualMakeupDate() {
        val row = course(1, 2)
        val date = LocalDate.parse("2026-09-20")
        val now = date.atTime(7, 0)
        val snapshot = ScheduleSnapshot(listOf(row), listOf(SectionTimeEntity(1, "08:00", "08:45"),
            SectionTimeEntity(2, "08:50", "09:35")), semester, adjustments = config)
        val today = TodayScheduleResolver.resolve(snapshot, now.atZone(zone))
        assertEquals(TodaySchedule.Status.CLASSES, today.status)
        assertTrue(today.courses.single().isMakeup)
        val next = NextClass.resolve(resolve(date.toString(), listOf(row)), mapOf(1 to "08:00"), semester, now)!!
        assertEquals(date, next.date)
        assertEquals(4, next.week)
        assertEquals(7, next.dayOfWeek)
        val reminders = ClassReminderScheduler.planClassReminders(listOf(row), mapOf(1 to "08:00"), semester, 15, now, zone, config)
        assertEquals(date.atTime(7, 45).atZone(zone).toInstant().toEpochMilli(), reminders.single().triggerAtMillis)
        assertTrue(ClassReminderScheduler.planClassReminders(listOf(row), mapOf(1 to "08:00"), semester,
            15, LocalDate.parse("2026-10-06").atStartOfDay(), zone, config).isEmpty())
    }

    @Test fun extraClassesWorkEvenWhenActualDateHasNoTeachingWeek() {
        val value = config.copy(semesters = listOf(rules.copy(extraClasses = listOf(ExtraClass("2026-10-03", "2026-10-06")))))
        val snapshot = ScheduleSnapshot(listOf(course(1, 2)), listOf(SectionTimeEntity(1, "08:00", "08:45")), semester, adjustments = value)
        val today = TodayScheduleResolver.resolve(snapshot, LocalDate.parse("2026-10-03").atTime(7, 0).atZone(zone))
        assertEquals(TodaySchedule.Status.CLASSES, today.status)
    }

    @Test fun rejectsInvalidDatesDuplicateRulesSelfMappingAndUnknownSchema() {
        val bad = listOf(config.copy(schemaVersion = 2), config.copy(revision = 0),
            config.copy(semesters = listOf(rules, rules)),
            config.copy(semesters = listOf(rules.copy(suspendedDates = listOf("2026-02-30")))),
            config.copy(semesters = listOf(rules.copy(suspendedDates = listOf("2026-10-06", "2026-10-06")))),
            config.copy(semesters = listOf(rules.copy(extraClasses = listOf(rules.extraClasses[0], rules.extraClasses[0])))),
            config.copy(semesters = listOf(rules.copy(extraClasses = listOf(ExtraClass("2026-10-06", "2026-10-06"))))))
        bad.forEach { assertTrue(runCatching { CalendarAdjustmentCodec.parse(CalendarAdjustmentCodec.encode(it)) }.isFailure) }
        assertTrue(runCatching { CalendarAdjustmentCodec.parse("{}") }.isFailure)
    }

    @Test fun revisionPolicyAllowsWithdrawalButRejectsRollbackAndSameRevisionChanges() {
        assertFalse(CalendarAdjustmentCodec.shouldReplace(config, config))
        assertTrue(CalendarAdjustmentCodec.shouldReplace(config, config.copy(revision = 2, semesters = emptyList())))
        assertTrue(runCatching { CalendarAdjustmentCodec.shouldReplace(config.copy(revision = 2), config) }.isFailure)
        assertTrue(runCatching { CalendarAdjustmentCodec.shouldReplace(config, config.copy(semesters = emptyList())) }.isFailure)
        val reordered = config.copy(semesters = listOf(rules.copy(suspendedDates = rules.suspendedDates.reversed(), extraClasses = rules.extraClasses.reversed())))
        assertEquals(config, CalendarAdjustmentCodec.parse(CalendarAdjustmentCodec.encode(reordered)))
    }
}
