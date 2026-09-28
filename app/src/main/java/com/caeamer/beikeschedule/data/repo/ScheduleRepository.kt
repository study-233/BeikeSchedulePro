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
import com.caeamer.beikeschedule.data.local.ScheduleEntity
import com.caeamer.beikeschedule.data.local.ScheduleStateEntity
import com.caeamer.beikeschedule.data.local.ActiveScheduleRecord
import com.caeamer.beikeschedule.model.ScheduleNames
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** UI 唯一数据入口。 */
class ScheduleRepository internal constructor(
    private val db: AppDatabase,
    val settings: SettingsStore,
    private val defaultSections: () -> List<SectionTimeEntity>,
    private val legacySemester: suspend () -> SettingsStore.SemesterConfig = { settings.semester.first() },
) {
    constructor(context: Context) : this(
        AppDatabase.get(context), SettingsStore(context),
        { com.caeamer.beikeschedule.import.parser.JwParser.parseSectionTimes(
            context.applicationContext.assets.open("sample/sections.json").bufferedReader().use { it.readText() }
        ) },
    )

    private val scheduleDao = db.scheduleDao()
    private val courseDao = db.courseDao()
    private val sectionTimeDao = db.sectionTimeDao()
    private val gradeDao = db.gradeDao()
    private val examDao = db.examDao()
    private val todoDao = db.todoDao()

    /** Room 的事务关系查询一次提供完整课表，不组合不同课表的独立流。 */
    val currentSchedule: Flow<ScheduleSnapshot> = flow {
        ensureInitialized()
        emitAll(scheduleDao.observeCurrent().map { requireNotNull(it).snapshot() })
    }.distinctUntilChanged()
    val schedules: Flow<List<ScheduleEntity>> = flow {
        ensureInitialized()
        emitAll(scheduleDao.observeAll())
    }
    val grades: Flow<List<GradeEntity>> = gradeDao.observeAll()
    val exams: Flow<List<ExamEntity>> = examDao.observeAll()
    val todos: Flow<List<TodoEntity>> = todoDao.observeAll()

    /** 读取失败不落迁移标记；中断后再次进入可重试，不覆盖已经完成搬迁的配置。 */
    internal suspend fun ensureInitialized() = initializationMutex.withLock {
        if (scheduleDao.state()?.initialized == true) return@withLock
        val legacy = legacySemester()
        db.withTransaction {
            val state = scheduleDao.state()
            if (state?.initialized == true) return@withTransaction
            val existing = state?.let { scheduleDao.get(it.activeScheduleId) }
            val id = if (existing != null) {
                scheduleDao.update(existing.withSemester(legacy))
                existing.id
            } else {
                val id = scheduleDao.insert(ScheduleEntity(name = "默认课表", createdAt = System.currentTimeMillis())
                    .withSemester(if (legacy == SettingsStore.SemesterConfig()) legacy.copy(firstMonday = LocalDate.now()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()) else legacy))
                sectionTimeDao.insertAll(defaultSections().map { it.copy(scheduleId = id) })
                id
            }
            scheduleDao.saveState((state ?: ScheduleStateEntity(activeScheduleId = id)).copy(initialized = true))
        }
    }

    private fun ActiveScheduleRecord.snapshot() = ScheduleSnapshot(
        details.courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.id })),
        details.sections.sortedBy { it.section }, details.schedule.semester(),
        details.schedule.id, details.schedule.name, state.reminderVersion,
    )

    suspend fun getScheduleSnapshot(): ScheduleSnapshot {
        ensureInitialized()
        return requireNotNull(scheduleDao.current()).snapshot()
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        ensureInitialized()
        return db.withTransaction { block() }
    }

    private suspend fun requireSchedule(id: Long) =
        requireNotNull(scheduleDao.get(id)) { "课表已删除，请重新选择" }

    private suspend fun activate(id: Long, invalidate: Boolean = false) {
        requireSchedule(id)
        val state = requireNotNull(scheduleDao.state())
        if (state.activeScheduleId != id || invalidate) {
            scheduleDao.saveState(state.copy(activeScheduleId = id, reminderVersion = state.reminderVersion + 1))
        }
    }

    private suspend fun invalidateIfCurrent(id: Long) {
        if (scheduleDao.state()?.activeScheduleId == id) activate(id, invalidate = true)
    }

    suspend fun availableName(base: String): String {
        ensureInitialized()
        return ScheduleNames.available(base, scheduleDao.getAll().map { it.name })
    }

    private suspend fun checkedName(name: String, excluding: Long? = null): String =
        ScheduleNames.validate(name, scheduleDao.getAll().filter { it.id != excluding }.map { it.name })

    private suspend fun insertEmpty(name: String): Long {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val id = scheduleDao.insert(ScheduleEntity(name = checkedName(name),
            createdAt = System.currentTimeMillis(), firstMonday = monday.toString()))
        sectionTimeDao.insertAll(defaultSections().map { it.copy(scheduleId = id) })
        return id
    }

    suspend fun createSchedule(name: String): Long = write {
        val id = insertEmpty(name)
        activate(id)
        id
    }

    suspend fun switchSchedule(id: Long) = write { activate(id) }

    suspend fun renameSchedule(id: Long, name: String) = write {
        scheduleDao.update(requireSchedule(id).copy(name = checkedName(name, id)))
    }

    suspend fun clearSchedule(id: Long) = write {
        requireSchedule(id)
        courseDao.clear(id)
        invalidateIfCurrent(id)
    }

    suspend fun deleteSchedule(id: Long) = write {
        requireSchedule(id)
        val wasActive = scheduleDao.state()?.activeScheduleId == id
        courseDao.clear(id)
        sectionTimeDao.clear(id)
        scheduleDao.delete(id)
        if (wasActive) {
            val next = scheduleDao.getAll().firstOrNull()?.id ?: insertEmpty("默认课表")
            activate(next)
        }
    }

    suspend fun saveSemester(id: Long, config: SettingsStore.SemesterConfig) = write {
        scheduleDao.update(requireSchedule(id).withSemester(config))
    }

    suspend fun saveSemesterDraft(id: Long, draft: com.caeamer.beikeschedule.model.SemesterDraft) = write {
        val schedule = requireSchedule(id)
        scheduleDao.update(schedule.withSemester(draft.applyTo(schedule.semester())))
    }

    suspend fun replaceGrades(grades: List<GradeEntity>) = db.withTransaction {
        gradeDao.clear()
        gradeDao.insertAll(grades)
    }

    /** 教务全量刷新/清缓存均保留手动考试。自增 ID 不复用被替换记录的提醒身份。 */
    suspend fun replaceImportedExams(exams: List<ExamEntity>) = db.withTransaction {
        examDao.clearImported()
        examDao.insertAll(exams.map { it.copy(id = 0, source = ExamEntity.SOURCE_IMPORT) })
    }

    suspend fun saveManualExam(draft: com.caeamer.beikeschedule.model.ExamDraft): Long = db.withTransaction {
        val exam = draft.toEntity()
        if (draft.id == 0L) {
            examDao.insert(exam)
        } else {
            require(examDao.get(draft.id)?.isManual == true) { "考试已不存在或不是手动考试" }
            check(examDao.update(exam) == 1) { "考试保存失败" }
            draft.id
        }
    }

    suspend fun deleteManualExam(id: Long) = db.withTransaction {
        require(examDao.get(id)?.isManual == true) { "考试已不存在或不是手动考试" }
        check(examDao.deleteManual(id) == 1) { "考试删除失败" }
    }

    /** 新建/更新、学期与切换是一个事务；仅覆盖目标的导入课程，保留手动课程和隐藏状态。 */
    suspend fun commitImport(
        targetId: Long?, name: String, semester: SettingsStore.SemesterConfig,
        courses: List<CourseEntity>, sectionTimes: List<SectionTimeEntity>,
        allowDifferentSemester: Boolean = false,
    ): Long = write {
        val target = targetId?.let { requireSchedule(it) }
        require(target == null || !isDifferentSemester(target, semester) || allowDifferentSemester) {
            "导入学期与目标课表不同，请确认后重试"
        }
        val id = target?.id ?: scheduleDao.insert(ScheduleEntity(name = checkedName(name),
            createdAt = System.currentTimeMillis()).withSemester(semester))
        val hiddenBefore = courseDao.getBySource(id, CourseEntity.SOURCE_IMPORT)
            .filter { it.hidden }.map { it.taskId to it.name }.toSet()
        courseDao.deleteBySource(id, CourseEntity.SOURCE_IMPORT)
        courseDao.insertAll(assignImportColors(courses).map {
            it.copy(id = 0, scheduleId = id, source = CourseEntity.SOURCE_IMPORT,
                hidden = (it.taskId to it.name) in hiddenBefore)
        })
        sectionTimeDao.clear(id)
        sectionTimeDao.insertAll(sectionTimes.map { it.copy(scheduleId = id) })
        courseDao.deleteBySource(id, CourseEntity.SOURCE_SAMPLE)
        scheduleDao.update(requireSchedule(id).withSemester(semester))
        activate(id, invalidate = true)
        id
    }

    /** 草稿带明确课表 ID；不允许拿另一份课表的行进行删除、编辑或分组恢复。 */
    suspend fun replaceCourses(scheduleId: Long, deleteIds: List<Long>, inserts: List<CourseEntity>) = write {
        requireSchedule(scheduleId)
        require(courseDao.getByIds(scheduleId, deleteIds).size == deleteIds.distinct().size) { "课程已变更，请重新打开" }
        deleteIds.forEach { courseDao.deleteById(scheduleId, it) }
        courseDao.insertAll(inserts.map { it.copy(id = 0, scheduleId = scheduleId) })
    }

    suspend fun addManualCourse(scheduleId: Long, course: CourseEntity) = write {
        requireSchedule(scheduleId)
        courseDao.insert(course.copy(id = 0, scheduleId = scheduleId, source = CourseEntity.SOURCE_MANUAL, taskId = ""))
    }

    suspend fun updateCourse(scheduleId: Long, course: CourseEntity) = write {
        requireSchedule(scheduleId)
        require(courseDao.getByIds(scheduleId, listOf(course.id)).isNotEmpty()) { "课程已变更，请重新打开" }
        courseDao.update(course.copy(scheduleId = scheduleId))
    }

    suspend fun deleteCourse(scheduleId: Long, id: Long) = write {
        requireSchedule(scheduleId)
        courseDao.deleteById(scheduleId, id)
    }

    suspend fun setCoursesHidden(scheduleId: Long, ids: List<Long>, hidden: Boolean) = write {
        requireSchedule(scheduleId)
        courseDao.setHiddenForIds(scheduleId, ids, hidden)
    }

    fun observeCourseByName(scheduleId: Long, sources: List<Int>, name: String): Flow<List<CourseEntity>> = flow {
        ensureInitialized()
        emitAll(courseDao.observeByNames(scheduleId, sources, name))
    }

    suspend fun loadSampleData(scheduleId: Long, courses: List<CourseEntity>, sectionTimes: List<SectionTimeEntity>) = write {
        val schedule = requireSchedule(scheduleId)
        courseDao.deleteBySource(scheduleId, CourseEntity.SOURCE_SAMPLE)
        courseDao.insertAll(courses.map { it.copy(source = CourseEntity.SOURCE_SAMPLE, id = 0, scheduleId = scheduleId) })
        if (sectionTimeDao.getAll(scheduleId).isEmpty()) sectionTimeDao.insertAll(sectionTimes.map { it.copy(scheduleId = scheduleId) })
        if (schedule.firstMonday.isBlank()) {
            scheduleDao.update(schedule.copy(firstMonday = LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()))
        }
    }

    suspend fun clearSampleData(scheduleId: Long) = write {
        requireSchedule(scheduleId)
        courseDao.deleteBySource(scheduleId, CourseEntity.SOURCE_SAMPLE)
    }

    suspend fun upsertTodo(todo: TodoEntity) = todoDao.upsert(todo)
    suspend fun deleteTodo(id: Long) = todoDao.deleteById(id)
    suspend fun setTodoDone(id: Long, doneDate: String) = todoDao.setDoneDate(id, doneDate)

    companion object {
        private val initializationMutex = Mutex()

        fun isDifferentSemester(target: ScheduleEntity, semester: SettingsStore.SemesterConfig): Boolean =
            if (target.xn.isNotBlank() && target.xq.isNotBlank()) {
                target.xn != semester.xn || target.xq != semester.xq
            } else target.semesterName.isNotBlank() && target.semesterName != semester.name

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
