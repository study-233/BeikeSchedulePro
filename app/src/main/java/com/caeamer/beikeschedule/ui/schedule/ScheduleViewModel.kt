package com.caeamer.beikeschedule.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.AppSession
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.model.WeekResolver
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class ScheduleUiState(
    val courses: List<CourseEntity> = emptyList(),
    val sectionTimes: List<SectionTimeEntity> = emptyList(),
    val semester: SettingsStore.SemesterConfig = SettingsStore.SemesterConfig(),
    val selectedWeek: Int = 1,
    val currentWeek: Int? = null,
    /** 今天是否处于被跳过的假期周（如国庆）；此时 currentWeek 指向假期后第一个教学周。 */
    val inHoliday: Boolean = false,
    /** 假期提示：假期后第一个教学周的周一日期。 */
    val nextWeekMonday: String? = null,
    /** 开学前（显示语义仍视为第 1 周，但状态文案应显示"未开学"）。 */
    val beforeStart: Boolean = false,
    /** 学期已结束（currentWeek=null）。 */
    val afterEnd: Boolean = false,
    val loaded: Boolean = false,
) {
    /**
     * 未隐藏的有固定时间课程。
     * 用 val 在构造时算一次，而不是 `get()`：`get()` 每次读取都新建一个 List，
     * 下游 `remember(state.scheduledCourses)` / `items(list)` 的键于是每次都变，
     * 白做整轮过滤与 diff（无固定时间弹层的抽搐就与这种不稳定列表有关）。
     */
    val scheduledCourses: List<CourseEntity> = courses.filter { !it.isUnscheduled && !it.hidden }
    /** 未隐藏的无固定时间课程。 */
    val unscheduledCourses: List<CourseEntity> = courses.filter { it.isUnscheduled && !it.hidden }
    /** 已隐藏的课程（教务导入课程可隐藏，供学期设置里恢复）。 */
    val hiddenCourses: List<CourseEntity> = courses.filter { it.hidden }
    val hasSample: Boolean = courses.any { it.source == CourseEntity.SOURCE_SAMPLE }
}

/**
 * 提醒排期诊断信息（设置页展示）：让"到底排上了没有 / 下次什么时候响"不用抓 logcat 就能看到。
 * 系统层面的通知开关、精确闹钟权限是同步查询，放在设置页组合时现算，保证每次打开都是最新值。
 */
data class ReminderScheduleInfo(
    val scheduledCount: Int = 0,
    /** 最近一次提醒的触发时刻（epoch 毫秒）；没有未来提醒时为 null。 */
    val nextTriggerAtMillis: Long? = null,
)

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    /**
     * 用户选中的教学周；**null = 还没选过 / 需要重新定位到当前周**。
     *
     * 每个前台会话（首次冷启动，或退出 App 后再进入）都会被打回 null，
     * 于是重新解析为当前周；App 内切 Tab 回来、旋转屏幕都不会打回，用户的选择得以保留。
     *
     * 不能用"selectedWeek == 1"当"还没选过"的哨兵：用户主动选第 1 周与尚未初始化
     * 无法区分，而 `repo.settings.semester` 是 DataStore 流，任何一次设置写入
     * （切主题、改提醒开关、切换隐藏周末）都会让它重新发射，于是下面的初始化逻辑
     * 会把用户选的第 1 周改写成当前周——表现为"点一下设置开关，课表自己跳回本周"。
     */
    private val selectedWeek = MutableStateFlow<Int?>(null)

    /**
     * 前台会话序号（镜像 [AppSession.epoch]）。
     *
     * 必须作为 uiState 的一个输入：`selectedWeek` 是 MutableStateFlow，**等值写入不发射**，
     * 而"重新进入"时它本来就是 null（上次重进后没有手动选过周）——只写 null 不会让
     * combine 重跑，`locateWeek(today)` 于是仍用上一次组合时的日期求值，
     * 跨天/跨周重进会停在旧周（顶栏、日期行、网格全是旧的）。
     * 会话序号变化必然带来一次重算，这才让"重进定位当前周"真正成立。
     */
    private val sessionEpoch = MutableStateFlow(AppSession.epoch.value)

    val uiState: StateFlow<ScheduleUiState> = combine(
        repo.courses,
        repo.sectionTimes,
        repo.settings.semester,
        selectedWeek,
        sessionEpoch,
    ) { courses, sections, semester, week, _ ->
        val location = WeekResolver.locateWeek(semester)
        // 未选过时（首次启动 / 重新进入 App）按 WeekResolver.defaultWeek 落位，否则用用户的选择
        val resolved = week ?: WeekResolver.defaultWeek(location, semester.totalWeeks)
        ScheduleUiState(
            courses = courses,
            sectionTimes = sections,
            semester = semester,
            selectedWeek = resolved.coerceIn(1, semester.totalWeeks),
            currentWeek = location.week,
            inHoliday = location.isHoliday,
            nextWeekMonday = location.nextWeekMonday,
            beforeStart = location.beforeStart,
            afterEnd = location.afterEnd,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    val reminderEnabled: StateFlow<Boolean> = repo.settings.reminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reminderMinutes: StateFlow<Int> = repo.settings.reminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val themeMode: StateFlow<SettingsStore.ThemeMode> = repo.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val hideWeekend: StateFlow<Boolean> = repo.settings.hideWeekend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 「隐藏本周不上的课」：与「我的」Tab 里的开关共用同一个 DataStore 键，两边即时同步。 */
    val hideInactiveCourses: StateFlow<Boolean> = repo.settings.hideInactiveCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 提醒排期状态：已排上的未来闹钟数量与最近一次触发时刻（设置页 diagnostics 用）。 */
    val reminderSchedule: StateFlow<ReminderScheduleInfo> = repo.settings.reminderScheduledAlarms
        .map { alarms ->
            val now = System.currentTimeMillis()
            val future = alarms.mapNotNull { it.triggerAtMillis }.filter { it > now }
            ReminderScheduleInfo(future.size, future.minOrNull())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReminderScheduleInfo())

    fun setHideWeekend(hidden: Boolean) {
        viewModelScope.launch { repo.settings.setHideWeekend(hidden) }
    }

    fun setHideInactiveCourses(hidden: Boolean) {
        viewModelScope.launch { repo.settings.setHideInactiveCourses(hidden) }
    }

    fun setReminder(enabled: Boolean, minutes: Int) {
        viewModelScope.launch { repo.settings.setReminder(enabled, minutes) }
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch {
            repo.settings.setThemeMode(mode)
            refreshWidget()
        }
    }

    init {
        // 每个前台会话（含首次冷启动）都把选中周打回"未选"，由 uiState 重新解析为当前周。
        // 冷启动 epoch 从 0 开始、StateFlow 立即发射，因此首次启动同样走这条路径。
        //
        // 之前这里是"collect semester，且 selectedWeek 为 null 时写入当前周"，有两个毛病：
        //   1. 只能覆盖冷启动，App 挂后台再回来不会重新定位；
        //   2. 它要等 DataStore 异步读盘，必然晚于 Pager 的第一帧回写
        //      （见 ScheduleScreen 里的 drop(1)），于是每次启动都被初始页 0
        //      抢先写成"第 1 周"，且写完后非空，定位永远不再发生。
        viewModelScope.launch {
            AppSession.epoch.collect { epoch ->
                selectedWeek.value = null
                // 等值写入不发射（见 sessionEpoch 注释），这里显式推进会话序号，
                // 保证 uiState 一定会重算一次"今天是第几周"
                sessionEpoch.value = epoch
            }
        }
        // 课程/节次/学期/提醒设置任一变化 → 全量重排上课提醒闹钟。
        // 必须按值去重：reschedule() 内部会把已排 requestCode 写回 DataStore(REMINDER_CODES)，
        // 而下面几个设置流都源自同一个 DataStore.data，写任何键都会让它们重新发射（map 不去重），
        // 不去重就会形成「重排→写 codes→重新发射→重排」的自激循环，闹钟被反复取消重设。
        // 节次时间必须在键里：重新导入只改节次不改课程时，ReminderKey 不含它会被去重抑制，
        // 当天提醒仍按旧时刻触发（此前只靠次日脉冲自愈）。节次表无 DataStore 回写，无自激风险。
        viewModelScope.launch {
            combine(
                repo.courses,
                repo.sectionTimes,
                repo.settings.semester,
                repo.settings.reminderEnabled,
                repo.settings.reminderMinutes,
            ) { courses, sections, semester, enabled, minutes ->
                ReminderKey(courses, sections, semester, enabled, minutes)
            }.distinctUntilChanged().collect {
                // 重排失败只允许"本轮不重排"：异常逃出 viewModelScope 会直接崩进程
                // （SettingsStore 的 DataStore 读可能抛 IOException、精确闹钟权限
                // 也可能在 check-then-act 窗口里被收回）
                runCatching { ClassReminderScheduler.reschedule(getApplication()) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /** 重排触发条件的值快照：用于过滤 DataStore 的无关键写入（见 init 注释）。 */
    private data class ReminderKey(
        val courses: List<CourseEntity>,
        val sectionTimes: List<SectionTimeEntity>,
        val semester: SettingsStore.SemesterConfig,
        val enabled: Boolean,
        val minutes: Int,
    )

    /** Widget 点击时显式回到当前周，即使 Activity 已经在前台。 */
    fun showCurrentWeek() {
        viewModelScope.launch {
            val semester = repo.settings.semester.first()
            selectedWeek.value = WeekResolver.defaultWeek(WeekResolver.locateWeek(semester), semester.totalWeeks)
        }
    }

    private fun refreshWidget() {
        WidgetUpdateCoordinator.requestRefresh(getApplication())
    }

    fun selectWeek(week: Int) {
        selectedWeek.value = week
    }

    fun saveCourse(course: CourseEntity) {
        viewModelScope.launch {
            if (course.id == 0L) repo.addManualCourse(course) else repo.updateCourse(course)
            refreshWidget()
        }
    }

    /**
     * 批量保存一门课：编辑场景先删除被替换的全部旧行，再插入展开后的全部时段行（单事务）。
     * 多时段课程编辑：传入该课程的所有行（同名同源），先删旧行再插入新行。
     */
    fun saveCourses(courses: List<CourseEntity>, replaceIds: List<Long>?) {
        viewModelScope.launch {
            repo.replaceCourses(replaceIds.orEmpty(), courses)
            refreshWidget()
        }
    }

    /** 按名字+源加载一门课的全部行（多时段课程整体编辑用）。 */
    fun observeCourseByName(sources: List<Int>, name: String) =
        repo.observeCourseByName(sources, name)

    /** 隐藏/恢复教务导入课程。 */
    fun setCourseHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch {
            repo.setCourseHidden(id, hidden)
            refreshWidget()
        }
    }

    /**
     * 批量隐藏/恢复一组课程行（同一张卡片对应的全部存储行）。
     *
     * 单行的 [setCourseHidden] 只够处理"一行 = 一张卡"的简单课程；教务单双周/调课拆行
     * 与手动多时段课都是多行合并成一张卡，只改一行会让卡片继续留在网格上。
     */
    fun setCoursesHidden(ids: List<Long>, hidden: Boolean) {
        viewModelScope.launch {
            repo.setCoursesHidden(ids, hidden)
            refreshWidget()
        }
    }

    fun deleteCourse(id: Long) {
        viewModelScope.launch {
            repo.deleteCourse(id)
            refreshWidget()
        }
    }

    /** 从 assets 载入示例课表；若未设置开学日期，则把本周一设为第 1 周周一便于立即查看。 */
    fun loadSampleData() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val coursesJson = ctx.assets.open("sample/courses.json").bufferedReader().use { it.readText() }
            val sectionsJson = ctx.assets.open("sample/sections.json").bufferedReader().use { it.readText() }
            repo.loadSampleData(JwParser.parseCourses(coursesJson), JwParser.parseSectionTimes(sectionsJson))
            val semester = repo.settings.semester.first()
            if (semester.firstMonday.isBlank()) {
                val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                repo.settings.saveSemester(
                    semester.copy(
                        name = if (semester.name.isBlank()) "示例学期" else semester.name,
                        firstMonday = monday.toString(),
                    )
                )
            }
            refreshWidget()
        }
    }

    fun clearSampleData() {
        viewModelScope.launch {
            repo.clearSampleData()
            refreshWidget()
        }
    }

    fun saveSemester(config: SettingsStore.SemesterConfig) {
        viewModelScope.launch {
            repo.settings.saveSemester(config)
            refreshWidget()
        }
    }
}
