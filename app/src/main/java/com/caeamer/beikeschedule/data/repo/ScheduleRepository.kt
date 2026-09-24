package com.caeamer.beikeschedule.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** UI 唯一数据入口。 */
class ScheduleRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val courseDao = db.courseDao()
    private val sectionTimeDao = db.sectionTimeDao()
    private val gradeDao = db.gradeDao()
    private val examDao = db.examDao()
    private val todoDao = db.todoDao()
    val settings = SettingsStore(context)

    val courses: Flow<List<CourseEntity>> = courseDao.observeAll()
    val sectionTimes: Flow<List<SectionTimeEntity>> = sectionTimeDao.observeAll()
    val grades: Flow<List<GradeEntity>> = gradeDao.observeAll()
    val exams: Flow<List<ExamEntity>> = examDao.observeAll()
    val todos: Flow<List<TodoEntity>> = todoDao.observeAll()

    /** 课程与作息在同一读事务内获取；学期配置仍由 DataStore 独立提供。 */
    suspend fun getScheduleSnapshot(): ScheduleSnapshot {
        val (courses, sections) = db.withTransaction {
            courseDao.getAll() to sectionTimeDao.getAll()
        }
        return ScheduleSnapshot(courses, sections, settings.semester.first())
    }

    /** 覆盖式写入成绩（全量刷新语义）。单事务保证不会出现"清空后未写入"的中间态。 */
    suspend fun replaceGrades(grades: List<GradeEntity>) = db.withTransaction {
        gradeDao.clear()
        gradeDao.insertAll(grades)
    }

    /** 覆盖式写入考试安排（仅当前学期）。 */
    suspend fun replaceExams(exams: List<ExamEntity>) = db.withTransaction {
        examDao.clear()
        examDao.insertAll(exams)
    }

    /**
     * 确认导入的落库步骤：覆盖教务课程 + 节次时间（可选清除示例课表），**一个事务**。
     *
     * **保留用户的隐藏状态**：教务课程"只能隐藏不能删除"是产品承诺（用户会隐藏十几门
     * 不关心的课让网格清爽），而本方法先全删再全插、新行的 `hidden` 取默认 false ——
     * 此前每次重新导入都会把所有已隐藏课程原地复活，且没有任何提示。
     *
     * 匹配键用 (taskId, 课程名)：taskId（教务 RWH）单独不够——同一任务号会对应多行
     * （单双周/调课拆行）。**导入课的数据本身仍以教务为准**（用户改过的名字/地点/周次
     * 会被新数据覆盖），这是与用户确认过的口径；预览页需要说明这一点。
     *
     * 此前调用方把它和 clearSampleData 分两次调用，两次事务之间进程被杀会留下
     * "新课程已写入、示例课仍在"的状态，`hasSample` 据此误判；现在合并为一个事务。
     * 学期配置写在 DataStore（跨存储无法并入本事务），由调用方在其后单独写入。
     *
     * @param clearSample 是否同时清除示例课表（真实导入为 true；示例数据是一次性引导内容）
     */
    suspend fun commitImport(
        courses: List<CourseEntity>,
        sectionTimes: List<SectionTimeEntity>,
        clearSample: Boolean = true,
    ) = db.withTransaction {
        val hiddenBefore = courseDao.getBySource(CourseEntity.SOURCE_IMPORT)
            .filter { it.hidden }
            .map { it.taskId to it.name }
            .toSet()
        courseDao.deleteBySource(CourseEntity.SOURCE_IMPORT)
        val restored = assignImportColors(courses).map { course ->
            if ((course.taskId to course.name) in hiddenBefore) course.copy(hidden = true) else course
        }
        courseDao.insertAll(restored)
        sectionTimeDao.clear()
        sectionTimeDao.insertAll(sectionTimes)
        if (clearSample) courseDao.deleteBySource(CourseEntity.SOURCE_SAMPLE)
    }

    /** 编辑替换：同一事务内删除旧行并插入展开后的新行，中途失败不会丢课。 */
    suspend fun replaceCourses(deleteIds: List<Long>, inserts: List<CourseEntity>) =
        db.withTransaction {
            deleteIds.forEach { courseDao.deleteById(it) }
            courseDao.insertAll(inserts)
        }

    /**
     * 教务课程颜色去重：
     * - 同名课程（多时段）共享同一颜色；
     * - 有原始 XB 色值（且在色板索引内）的课程优先保留该色；
     * - 同一色值被多门不同课程占用时，后者顺延到下一个未占用的色板下标；
     * - 无固定时间课程（原 99999/无 KEY）不再用 name 哈希撞色，统一走分配。
     */
    suspend fun addManualCourse(course: CourseEntity) =
        courseDao.insert(course.copy(source = CourseEntity.SOURCE_MANUAL, taskId = ""))

    /** 原样插入课程行（保留 source，用于编辑展开后的多行写回）。 */
    suspend fun insertCourses(courses: List<CourseEntity>) = courseDao.insertAll(courses)

    /** 更新单行课程（手动课程编辑走保存替换时较少用；编辑展开用 insertCourses+deleteCourse）。 */
    suspend fun updateCourse(course: CourseEntity) = courseDao.update(course)

    /** 删除一门课的指定 id（手动课程删除；编辑替换旧行时也用它）。 */
    suspend fun deleteCourse(id: Long) = courseDao.deleteById(id)

    // —— 日程 ——

    /** 新增或更新一条日程（id=0 为新增，Room 自增生主键）。 */
    suspend fun upsertTodo(todo: TodoEntity) = todoDao.upsert(todo)

    /** 删除一条日程。 */
    suspend fun deleteTodo(id: Long) = todoDao.deleteById(id)

    /** 打卡 / 取消打卡：只更新"最近完成日期"，重复任务次日自然复活。 */
    suspend fun setTodoDone(id: Long, doneDate: String) = todoDao.setDoneDate(id, doneDate)

    /** 隐藏/恢复教务导入课程（隐藏 = 不显示但保留；手动/示例删除用 deleteCourse）。 */
    suspend fun setCourseHidden(id: Long, hidden: Boolean) = courseDao.setHidden(id, hidden)

    /** 整组隐藏/恢复：单条 UPDATE，不会出现"同一张卡一半隐藏一半显示"的中间态。 */
    suspend fun setCoursesHidden(ids: List<Long>, hidden: Boolean) =
        courseDao.setHiddenForIds(ids, hidden)

    /** 按源 + 课程名取全部行（含隐藏），用于多时段课程的整体编辑。 */
    fun observeCourseByName(sources: List<Int>, name: String): Flow<List<CourseEntity>> =
        courseDao.observeByNames(sources, name)

    /** 载入示例课表（assets 内置的真实教务样本），source=SOURCE_SAMPLE 便于一键清除。 */
    suspend fun loadSampleData(courses: List<CourseEntity>, sectionTimes: List<SectionTimeEntity>) =
        db.withTransaction {
            courseDao.deleteBySource(CourseEntity.SOURCE_SAMPLE)
            courseDao.insertAll(courses.map { it.copy(source = CourseEntity.SOURCE_SAMPLE, id = 0) })
            if (sectionTimeDao.getAll().isEmpty()) {
                sectionTimeDao.insertAll(sectionTimes)
            }
        }

    suspend fun clearSampleData() = courseDao.deleteBySource(CourseEntity.SOURCE_SAMPLE)

    companion object {
        /**
         * 教务课程颜色去重（纯函数，导入插入前调用）：
         * - 同名课程（多时段）共享同一颜色；
         * - 有原始 XB 色值（且在色板索引内）的课程优先保留该色；
         * - 同一色值被多门不同课程占用时，后者顺延到下一个未占用的色板下标；
         * - 无固定时间课程（原 99999/无 KEY）不再用 name 哈希撞色，统一走分配。
         */
        internal fun assignImportColors(courses: List<CourseEntity>): List<CourseEntity> {
            val paletteSize = 10 // 与 CourseColors.basePalette 尺寸一致
            val usedColors = mutableMapOf<Int, String>() // colorIndex -> 首次占用的课程名
            val nameColor = mutableMapOf<String, Int>()   // 课程名 -> 分配到的色
            val result = mutableListOf<CourseEntity>()

            fun pick(original: Int, courseName: String): Int {
                val norm = CourseColors.importedOf(original)
                // 同名已分配，复用
                nameColor[courseName]?.let { return it }
                // 优先尝试教务原始色（未被他课占用），否则顺延到未占用色
                val candidates = sequenceOf(norm) + (0 until paletteSize)
                for (c in candidates) {
                    val holder = usedColors[c]
                    if (holder == null || holder == courseName) {
                        usedColors[c] = courseName
                        nameColor[courseName] = c
                        return c
                    }
                }
                // 全部占满（理论上不可能，paletteSize 大于课程数），取模兜底
                // floorMod：abs() 对 Int.MIN_VALUE 会溢出为负，这里必须用数学取模
                val fallback = Math.floorMod(courseName.hashCode(), paletteSize)
                usedColors[fallback] = courseName
                nameColor[courseName] = fallback
                return fallback
            }

            courses.forEach { course ->
                val color = if (course.colorIndex == CourseEntity.COLOR_UNSCHEDULED) {
                    pick(Math.floorMod(course.name.hashCode(), paletteSize), course.name)
                } else {
                    pick(course.colorIndex, course.name)
                }
                result += course.copy(colorIndex = color)
            }
            return result
        }

        /**
         * 由第 1 周周一日期推算今天处于第几周；不在学期范围内返回 null。
         * firstMonday 格式 yyyy-MM-dd。
         */
        /**
         * 由第 1 周周一日期推算今天处于第几周；不在学期范围内返回 null。
         * firstMonday 格式 yyyy-MM-dd。
         *
         * **开学前必须返回 null**：`ChronoUnit.DAYS.between` 在开学前 1~6 天得到 -1..-6，
         * 而 Int 除法向零截断使 `-3 / 7 == 0`，于是 `0 + 1 == 1` 会返回"第 1 周"。
         * 此前这条路径让上课提醒在开学前 6 天就开始为第 1 周的课排期。
         */
        fun currentWeek(firstMonday: String, totalWeeks: Int, today: LocalDate = LocalDate.now()): Int? {
            if (firstMonday.isBlank()) return null
            val start = runCatching { LocalDate.parse(firstMonday) }.getOrNull() ?: return null
            val monday = if (start.dayOfWeek == DayOfWeek.MONDAY) {
                start
            } else {
                start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }
            if (today.isBefore(monday)) return null
            val week = (ChronoUnit.DAYS.between(monday, today) / 7 + 1).toInt()
            return if (week in 1..totalWeeks) week else null
        }

        /**
         * 今天在学期中的位置。
         * @param week 当前教学周；假期中为下一教学周；学期结束后为 null
         * @param isHoliday 今天是否处于被跳过的假期周（如国庆）
         * @param nextWeekMonday 假期时下一教学周的周一日期（用于提示）
         */
        data class WeekLocation(
            val week: Int?,
            val isHoliday: Boolean,
            val nextWeekMonday: String?,
            /** 开学前（显示语义仍视为第 1 周，便于课表默认定位，但状态文案应显示"未开学"）。 */
            val beforeStart: Boolean = false,
            /** 学期已结束（week=null）。 */
            val afterEnd: Boolean = false,
        )

        /**
         * 用官方教学周日历定位今天：周→周一映射精确反映长假跳周（如国庆周不占序号）。
         * weekMondays 下标+1 = 教学周。未开学视为第 1 周（beforeStart=true）；学期结束返回 week=null（afterEnd=true）。
         */
        fun locateWeek(weekMondays: List<String>, today: LocalDate = LocalDate.now()): WeekLocation {
            val mondays = weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (mondays.isEmpty()) return WeekLocation(null, false, null)
            if (today.isBefore(mondays.first())) return WeekLocation(1, false, null, beforeStart = true)
            mondays.forEachIndexed { i, monday ->
                val sunday = monday.plusDays(6)
                if (!today.isBefore(monday) && !today.isAfter(sunday)) {
                    return WeekLocation(i + 1, false, null)
                }
                // 本周日与下周一之间的空隙 = 被跳过的假期周
                if (i + 1 < mondays.size && today.isAfter(sunday) && today.isBefore(mondays[i + 1])) {
                    return WeekLocation(i + 2, true, mondays[i + 1].toString())
                }
            }
            return WeekLocation(null, false, null, afterEnd = true)
        }

        /**
         * 严格判定日期属于第几教学周：开学前、假期跳周、学期结束后都返回 null。
         * 用于上课提醒排期（显示场景的"未开学视为第1周"语义在这里不适用）。
         */
        fun teachingWeekOf(weekMondays: List<String>, date: LocalDate): Int? {
            val mondays = weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (mondays.isEmpty() || date.isBefore(mondays.first())) return null
            mondays.forEachIndexed { i, monday ->
                if (!date.isBefore(monday) && !date.isAfter(monday.plusDays(6))) return i + 1
            }
            return null
        }
    }
}
