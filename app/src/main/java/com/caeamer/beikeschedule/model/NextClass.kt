package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "下一节课"解析：仅取今天尚未开始的最早一节课，包含明确配置的调休补课。
 *
 * 口径（用户确认）：
 * - 只看今天——今天课上完就不标记，不跨天指向明天的课；
 * - 正在进行的课不算下一节（已开始的课跳过，取之后最早的一节）；
 * - 普通课程按今天的教学周匹配；补课使用来源日期的教学周，但上课时刻仍在今天。
 */
object NextClass {

    /** 命中的下一节课：courseId 用于与课表卡片（合并后）匹配。 */
    data class Target(
        val courseId: Long,
        val dayOfWeek: Int,
        val week: Int,
        val startTime: String,
        val date: java.time.LocalDate? = null,
    )

    /** 调休与普通课程均由统一日期解析器筛选；这里只比较实际日期的上课时间。 */
    fun resolve(occurrences: List<CourseOccurrence>, sectionStartTimes: Map<Int, String>,
                semester: com.caeamer.beikeschedule.data.pref.SettingsStore.SemesterConfig, now: LocalDateTime): Target? =
        occurrences.asSequence().filter { it.date == now.toLocalDate() }
            .mapNotNull { occurrence ->
                val start = sectionStartTimes[occurrence.course.startSection]?.let {
                    runCatching { LocalTime.parse(it) }.getOrNull()
                } ?: return@mapNotNull null
                if (start.isAfter(now.toLocalTime())) occurrence to start else null
            }.minByOrNull { it.second }?.let { (occurrence, start) ->
                WeekResolver.teachingWeekOf(semester, occurrence.sourceDate)?.let { week ->
                    Target(occurrence.course.id, occurrence.date.dayOfWeek.value, week, start.toString(), occurrence.date)
                }
            }

    /**
     * @param courses 课表渲染用的合并后课程（与卡片 id 一致，见 CourseMerger）
     * @param sectionStartTimes 小节号 → 开始时间（"08:00"）
     * @param todayTeachingWeek 今天所属教学周（严格口径，假期/开学前/学期后为 null）
     * @param now 当前时间
     */
    fun resolve(
        courses: List<CourseEntity>,
        sectionStartTimes: Map<Int, String>,
        todayTeachingWeek: Int?,
        now: LocalDateTime,
    ): Target? {
        val week = todayTeachingWeek ?: return null
        val day = now.toLocalDate().dayOfWeek.value
        val nowTime = now.toLocalTime()
        return courses.asSequence()
            .filter { !it.isUnscheduled && it.dayOfWeek == day && it.hasClassOnWeek(week) }
            .mapNotNull { course ->
                // 节次时间缺失/格式异常时跳过该课，不让它阻断其他候选
                val start = sectionStartTimes[course.startSection]
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null
                if (start.isAfter(nowTime)) course to start else null
            }
            .minByOrNull { it.second }
            ?.let { (course, start) -> Target(course.id, day, week, start.toString()) }
    }
}
