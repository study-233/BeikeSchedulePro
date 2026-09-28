package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 课表一天列的布局计算（从 ScheduleScreen 里抽出来的纯函数，便于单测）。
 *
 * 输出两类课程：
 * - [DayLayout.clusters]：本周有课、且节次区间互相重叠的课程分簇，簇内并排窄列渲染；
 * - [DayLayout.inactives]：本周没课的课程（单双周的另一半、还没到的调课周），
 *   在"不与任何本周课程重叠"的空位里整宽淡化显示。
 */
object WeekLayout {

    /**
     * @param courses 课表渲染用的合并后课程（见 CourseMerger）
     * @param day 星期几 1..7
     * @param week 当前查看的教学周
     * @param hideInactive 开启后不再返回 [DayLayout.inactives]（设置页「隐藏本周不上的课」）
     */
    fun layoutDay(
        courses: List<CourseEntity>,
        day: Int,
        week: Int,
        hideInactive: Boolean,
    ): DayLayout {
        val actives = courses
            .filter { it.dayOfWeek == day && it.hasClassOnWeek(week) }
            .sortedBy { it.startSection }

        return layoutResolved(actives, if (hideInactive) emptyList() else
            courses.filter { it.dayOfWeek == day && !it.hasClassOnWeek(week) })
    }

    /** 输入已经按实际日期与来源教学日解析，不再用显示周过滤补课。 */
    fun layoutResolved(activeCourses: List<CourseEntity>, inactiveCourses: List<CourseEntity>): DayLayout {
        val actives = activeCourses.sortedBy { it.startSection }

        // 冲突簇：含传递重叠（A-B-C 链式同簇）。按 startSection 升序单趟扫描时，
        // "任意两门本周有课且重叠的课程必在同一簇"这一不变量成立 ——
        // 后处理的课程必然能命中先处理课程所在的簇。
        val clusters = mutableListOf<MutableList<CourseEntity>>()
        actives.forEach { course ->
            val cluster = clusters.firstOrNull { cl -> cl.any { sectionsOverlap(it, course) } }
            if (cluster != null) cluster += course else clusters += mutableListOf(course)
        }

        // 非本周课程：只在与所有本周课程、以及已放入的其他非本周课程都不重叠时才显示
        val inactives = inactiveCourses
            .fold(mutableListOf<CourseEntity>()) { shown, course ->
                val blocked = actives.any { sectionsOverlap(it, course) } ||
                    shown.any { sectionsOverlap(it, course) }
                if (!blocked) shown += course
                shown
            }

        return DayLayout(clusters, inactives)
    }

    /** 判断两门课的节次区间是否重叠。 */
    internal fun sectionsOverlap(a: CourseEntity, b: CourseEntity): Boolean =
        a.startSection <= b.endSection && b.startSection <= a.endSection
}

/**
 * 一天列的布局：clusters = 本周冲突簇（簇内并排窄列）；inactives = 淡化展示的本周没课的课程。
 */
data class DayLayout(
    val clusters: List<List<CourseEntity>>,
    val inactives: List<CourseEntity>,
)
