package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TodayScheduleResolverTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 9, 7)
    private val semester = SettingsStore.SemesterConfig(firstMonday = monday.toString(), totalWeeks = 2)
    private val sections = listOf(
        SectionTimeEntity(1, "08:00", "08:45"),
        SectionTimeEntity(2, "08:50", "09:35"),
        SectionTimeEntity(3, "09:55", "10:40"),
        SectionTimeEntity(4, "10:50", "11:35"),
        SectionTimeEntity(5, "13:30", "14:15"),
        SectionTimeEntity(6, "14:20", "15:05"),
        SectionTimeEntity(13, "21:10", "21:55"),
    )

    private fun course(id: Long = 1, start: Int = 1, end: Int = 2, day: Int = 1) = CourseEntity(
        id = id, taskId = "task$id", name = "课程$id", teacher = "教师", location = "【校本部】教学楼101",
        dayOfWeek = day, startSection = start, endSection = end, weekBitmap = "011",
        colorIndex = 1, source = CourseEntity.SOURCE_IMPORT,
    )

    private fun snapshot(
        courses: List<CourseEntity> = listOf(course()),
        times: List<SectionTimeEntity> = sections,
        config: SettingsStore.SemesterConfig = semester,
    ) = ScheduleSnapshot(courses, times, config)

    private fun now(hour: Int, minute: Int = 0) = monday.atTime(hour, minute).atZone(zone)
    private fun resolve(data: ScheduleSnapshot = snapshot(), at: ZonedDateTime = now(8)) =
        TodayScheduleResolver.resolve(data, at)

    @Test fun `未导入或学期未配置时提示设置`() {
        assertEquals(TodaySchedule.Status.NEEDS_SETUP, resolve(snapshot(courses = emptyList())).status)
        assertEquals(TodaySchedule.Status.NEEDS_SETUP, resolve(snapshot(config = SettingsStore.SemesterConfig())).status)
        assertEquals(TodaySchedule.Status.NEEDS_SETUP, resolve(snapshot(config = semester.copy(firstMonday = "bad"))).status)
    }

    @Test fun `上课前保留课程并安排开始边界`() {
        val result = resolve(at = now(7, 59))
        assertEquals(TodayCourse.Phase.UPCOMING, result.courses.single().phase)
        assertEquals(now(8), result.nextChangeAt)
    }

    @Test fun `开始时刻及上课期间都显示正在上课`() {
        for (at in listOf(now(8), now(9, 34))) {
            val result = resolve(at = at)
            assertEquals(TodayCourse.Phase.ONGOING, result.courses.single().phase)
            assertEquals(LocalTime.of(8, 0), result.courses.single().start)
            assertEquals(LocalTime.of(9, 35), result.courses.single().end)
            assertEquals(now(9, 35), result.nextChangeAt)
        }
    }

    @Test fun `结束时刻立即切换到下一门`() {
        val result = resolve(snapshot(listOf(course(), course(2, 3, 4))), now(9, 35))
        assertEquals(listOf(2L), result.courses.map { it.course.id })
        assertEquals(TodayCourse.Phase.UPCOMING, result.courses.single().phase)
        assertEquals(now(9, 55), result.nextChangeAt)
    }

    @Test fun `全部结束不跳转到明天`() {
        val result = resolve(snapshot(listOf(course(), course(2, day = 2))), now(22))
        assertEquals(TodaySchedule.Status.FINISHED, result.status)
        assertTrue(result.courses.isEmpty())
        assertEquals(monday.plusDays(1).atStartOfDay(zone), result.nextChangeAt)
    }

    @Test fun `今天没有课与课已结束是不同状态`() {
        assertEquals(TodaySchedule.Status.NO_CLASSES, resolve(snapshot(listOf(course(day = 2)))).status)
    }

    @Test fun `排除隐藏未排课和非本周课程`() {
        val result = resolve(snapshot(listOf(
            course(1).copy(hidden = true),
            course(2, day = 0),
            course(3).copy(weekBitmap = "001"),
        )))
        assertEquals(TodaySchedule.Status.NO_CLASSES, result.status)
    }

    @Test fun `位图下标一对应第一教学周`() {
        assertEquals(1, resolve(snapshot(listOf(course().copy(weekBitmap = "01")))).courses.size)
        assertEquals(TodaySchedule.Status.NO_CLASSES, resolve(snapshot(listOf(course().copy(weekBitmap = "10")))).status)
    }

    @Test fun `单独的手动及示例课程也能展示`() {
        for (source in listOf(CourseEntity.SOURCE_MANUAL, CourseEntity.SOURCE_SAMPLE)) {
            assertEquals(source, resolve(snapshot(listOf(course().copy(source = source)))).courses.single().course.source)
        }
    }

    @Test fun `同名同时段先合并周次并保留有效地点`() {
        val placeholder = course().copy(location = "【校本部】-", teacher = "", weekBitmap = "010")
        val otherWeek = course(2).copy(name = placeholder.name, weekBitmap = "001")
        val result = resolve(snapshot(listOf(placeholder, otherWeek)))
        assertEquals(1, result.courses.size)
        assertEquals(otherWeek.location, result.courses.single().course.location)
        assertEquals(otherWeek.teacher, result.courses.single().course.teacher)
    }

    @Test fun `隐藏行不能通过合并重新出现`() {
        val hidden = course().copy(hidden = true, weekBitmap = "010")
        val visible = course(2).copy(name = hidden.name, weekBitmap = "001")
        assertEquals(TodaySchedule.Status.NO_CLASSES, resolve(snapshot(listOf(hidden, visible))).status)
    }

    @Test fun `冲突课程分别保留且顺序稳定`() {
        val result = resolve(snapshot(listOf(course(2), course(1), course(3, 3, 4))), now(8, 30))
        assertEquals(listOf(1L, 2L, 3L), result.courses.map { it.course.id })
        assertEquals(listOf(TodayCourse.Phase.ONGOING, TodayCourse.Phase.ONGOING, TodayCourse.Phase.UPCOMING),
            result.courses.map { it.phase })
    }

    @Test fun `正在上的课程优先于未开始的课程`() {
        val result = resolve(snapshot(listOf(course(2, 3, 4), course(1))), now(9))
        assertEquals(1L, result.courses.first().course.id)
    }

    @Test fun `保留所有剩余课程供布局计算还有几门`() {
        val result = resolve(snapshot(listOf(course(1), course(2, 3, 4), course(3, 5, 6))), now(7))
        assertEquals(3, result.courses.size)
    }

    @Test fun `作息缺失不丢课不误报结束`() {
        val result = resolve(snapshot(times = emptyList()), now(23))
        assertEquals(TodaySchedule.Status.CLASSES, result.status)
        assertEquals(TodayCourse.Phase.UNKNOWN_TIME, result.courses.single().phase)
        assertNull(result.courses.single().start)
        assertNull(result.courses.single().end)
    }

    @Test fun `作息非法或倒置时保留节次提示`() {
        for (times in listOf(
            listOf(SectionTimeEntity(1, "bad", "08:45"), SectionTimeEntity(2, "08:50", "09:35")),
            listOf(SectionTimeEntity(1, "10:00", "10:45"), SectionTimeEntity(2, "08:50", "09:35")),
        )) {
            assertEquals(TodayCourse.Phase.UNKNOWN_TIME, resolve(snapshot(times = times)).courses.single().phase)
        }
    }

    @Test fun `缺时课程排在可判定时间的课程后`() {
        val result = resolve(snapshot(listOf(course(1, 11, 12), course(2, 3, 4))))
        assertEquals(listOf(2L, 1L), result.courses.map { it.course.id })
    }

    @Test fun `第十三小节保留真实结束时间`() {
        val result = resolve(snapshot(listOf(course(1, 13, 13))), now(21, 30))
        assertEquals(LocalTime.of(21, 55), result.courses.single().end)
        assertEquals(TodayCourse.Phase.ONGOING, result.courses.single().phase)
        assertEquals(now(21, 55), result.nextChangeAt)
    }

    @Test fun `开学前和结束后不借用第一周课程`() {
        for (date in listOf(monday.minusDays(7), monday.plusWeeks(2))) {
            assertEquals(TodaySchedule.Status.NON_TEACHING_DAY, resolve(at = date.atTime(8, 0).atZone(zone)).status)
        }
    }

    @Test fun `官方校历跳过的假期不显示后续周课程`() {
        val config = semester.copy(weekMondays = listOf("2026-09-07", "2026-09-21"))
        val data = snapshot(config = config)
        assertEquals(TodaySchedule.Status.NON_TEACHING_DAY, resolve(data, monday.plusWeeks(1).atTime(8, 0).atZone(zone)).status)
        assertEquals(1, resolve(data, monday.plusWeeks(2).atTime(8, 0).atZone(zone)).courses.size)
    }

    @Test fun `有官方校历时不依赖首周字符串`() {
        val config = semester.copy(firstMonday = "", weekMondays = listOf("2026-09-07", "2026-09-14"))
        assertEquals(1, resolve(snapshot(config = config)).courses.size)
    }

    @Test fun `周末课程正常展示`() {
        val sunday = monday.plusDays(6).atTime(8, 0).atZone(zone)
        assertEquals(7, resolve(snapshot(listOf(course(day = 7))), sunday).courses.single().course.dayOfWeek)
    }

    @Test fun `跨午夜后重新按当天筛选`() {
        val data = snapshot(listOf(course(), course(2, day = 2)))
        val result = resolve(data, monday.plusDays(1).atStartOfDay(zone))
        assertEquals(listOf(2L), result.courses.map { it.course.id })
        assertEquals(monday.plusDays(1), result.date)
    }

    @Test fun `采用传入时区的日期而不是进程默认时区`() {
        val china = monday.atTime(0, 30).atZone(zone)
        val losAngeles = china.withZoneSameInstant(ZoneId.of("America/Los_Angeles"))
        assertFalse(china.toLocalDate() == losAngeles.toLocalDate())
        assertEquals(TodaySchedule.Status.NON_TEACHING_DAY, resolve(at = losAngeles).status)
        assertEquals(TodaySchedule.Status.CLASSES, resolve(at = china).status)
    }

    @Test fun `下次午夜按时区计算而不是固定二十四小时`() {
        val dst = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ZoneId.of("America/New_York"))
        val data = snapshot(config = semester.copy(firstMonday = "2026-03-02"))
        val result = resolve(data, dst)
        assertEquals(23L, Duration.between(dst, result.nextChangeAt).toHours())
    }

    @Test fun `下一次变化取所有候选课程的最早边界`() {
        val data = snapshot(listOf(course(1, 1, 6), course(2, 3, 4), course(3, 5, 6)))
        assertEquals(now(9, 55), resolve(data, now(9)).nextChangeAt)
        assertEquals(now(11, 35), resolve(data, now(10)).nextChangeAt)
    }
}
