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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val scheduleId: Long = 0,
    val scheduleName: String = "课表",
    val scheduleVersion: Long = 0,
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
    private val settingsErrorState = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = settingsErrorState
    private val savingSemesterState = MutableStateFlow(false)
    val savingSemester: StateFlow<Boolean> = savingSemesterState
    fun clearSettingsError() { settingsErrorState.value = null }

    private fun changeSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                settingsErrorState.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                settingsErrorState.value = e.message ?: "未能保存设置，请重试"
            }
        }
    }

    fun saveSemesterDraft(scheduleId: Long, draft: com.caeamer.beikeschedule.model.SemesterDraft, onSaved: () -> Unit) {
        if (savingSemesterState.value) return
        savingSemesterState.value = true
        viewModelScope.launch {
            try {
                repo.saveSemesterDraft(scheduleId, draft)
                settingsErrorState.value = null
                refreshWidget()
                onSaved()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                settingsErrorState.value = "学期保存失败，修改已保留，请重试"
            } finally { savingSemesterState.value = false }
        }
    }


    /** 选择周次绑定课表身份；null 表示按当前日期定位，不把第 1 周当作未选择。 */
    private val selectedWeek = MutableStateFlow<Triple<Long, Long, Int>?>(null)

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

    private val snapshot = repo.currentSchedule.retryWhen { cause, _ ->
        if (cause is CancellationException) throw cause
        settingsErrorState.value = "无法读取课表，正在重试"
        delay(1500)
        true
    }.onEach {
        if (settingsErrorState.value == "无法读取课表，正在重试") settingsErrorState.value = null
    }
    val schedules = repo.schedules.retryWhen { cause, _ ->
        if (cause is CancellationException) throw cause
        settingsErrorState.value = "无法读取课表列表，正在重试"
        delay(1500)
        true
    }.onEach {
        if (settingsErrorState.value == "无法读取课表列表，正在重试") settingsErrorState.value = null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ScheduleUiState> = combine(snapshot, selectedWeek, sessionEpoch) { data, selection, _ ->
        val semester = data.semester
        val location = WeekResolver.locateWeek(semester)
        val week = selection?.takeIf { it.first == data.scheduleId && it.second == data.reminderVersion }?.third
        val resolved = week ?: WeekResolver.defaultWeek(location, semester.totalWeeks)
        ScheduleUiState(
            scheduleId = data.scheduleId, scheduleName = data.scheduleName, scheduleVersion = data.reminderVersion,
            courses = data.courses, sectionTimes = data.sectionTimes, semester = semester,
            selectedWeek = resolved.coerceIn(1, semester.totalWeeks), currentWeek = location.week,
            inHoliday = location.isHoliday, nextWeekMonday = location.nextWeekMonday,
            beforeStart = location.beforeStart, afterEnd = location.afterEnd, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    private val managingState = MutableStateFlow(false)
    val managing: StateFlow<Boolean> = managingState

    private fun manage(onSaved: () -> Unit = {}, block: suspend () -> Unit) {
        if (managingState.value) return
        managingState.value = true
        viewModelScope.launch {
            try {
                block()
                settingsErrorState.value = null
                onSaved()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                settingsErrorState.value = e.message ?: "操作失败，请重试"
            } finally { managingState.value = false }
        }
    }

    fun createSchedule(name: String, onSaved: () -> Unit) = manage(onSaved) { repo.createSchedule(name) }
    fun renameSchedule(id: Long, name: String, onSaved: () -> Unit) = manage(onSaved) { repo.renameSchedule(id, name) }
    fun switchSchedule(id: Long, onSaved: () -> Unit = {}) = manage(onSaved) { repo.switchSchedule(id) }
    fun clearSchedule(id: Long, onSaved: () -> Unit) = manage(onSaved) { repo.clearSchedule(id) }
    fun deleteSchedule(id: Long, onSaved: () -> Unit) = manage(onSaved) { repo.deleteSchedule(id) }

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
        changeSetting { repo.settings.setHideWeekend(hidden) }
    }

    fun setHideInactiveCourses(hidden: Boolean) {
        changeSetting { repo.settings.setHideInactiveCourses(hidden) }
    }

    fun setReminder(enabled: Boolean, minutes: Int) {
        changeSetting { repo.settings.setReminder(enabled, minutes) }
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
        viewModelScope.launch {
            AppSession.epoch.collect { epoch ->
                selectedWeek.value = null
                // 等值写入不发射（见 sessionEpoch 注释），这里显式推进会话序号，
                // 保证 uiState 一定会重算一次"今天是第几周"
                sessionEpoch.value = epoch
            }
        }
        // 只观察一致快照；切换同时刷新桌面和提醒，记录回写不会触发自激循环。
        viewModelScope.launch {
            combine(snapshot, repo.settings.reminderEnabled, repo.settings.reminderMinutes, sessionEpoch) { data, enabled, minutes, epoch ->
                Triple(data, enabled, minutes) to epoch
            }.distinctUntilChanged().collect { _ ->
                refreshWidget()
                try {
                    ClassReminderScheduler.reschedule(getApplication())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    settingsErrorState.value = "课表已保存，上课提醒暂未更新，请重新打开应用重试"
                }
            }
        }
    }

    /** Widget 点击时显式回到当前周，即使 Activity 已经在前台。 */
    fun showCurrentWeek() {
        changeSetting {
            val data = repo.getScheduleSnapshot()
            selectedWeek.value = Triple(data.scheduleId, data.reminderVersion, WeekResolver.defaultWeek(WeekResolver.locateWeek(data.semester), data.semester.totalWeeks))
        }
    }

    private fun refreshWidget() {
        WidgetUpdateCoordinator.requestRefresh(getApplication())
    }

    fun selectWeek(week: Int) {
        val state = uiState.value
        if (state.loaded) selectedWeek.value = Triple(state.scheduleId, state.scheduleVersion, week)
    }

    fun saveCourses(scheduleId: Long, courses: List<CourseEntity>, replaceIds: List<Long>?, onSaved: () -> Unit = {}) {
        manage(onSaved) { repo.replaceCourses(scheduleId, replaceIds.orEmpty(), courses) }
    }

    fun setCoursesHidden(scheduleId: Long, ids: List<Long>, hidden: Boolean) {
        changeSetting { repo.setCoursesHidden(scheduleId, ids, hidden) }
    }

    /** 示例只在用户明确点击后写入当前课表。 */
    fun loadSampleData() {
        val id = uiState.value.scheduleId
        manage {
            val ctx = getApplication<Application>()
            val courses = ctx.assets.open("sample/courses.json").bufferedReader().use { it.readText() }
            val sections = ctx.assets.open("sample/sections.json").bufferedReader().use { it.readText() }
            repo.loadSampleData(id, JwParser.parseCourses(courses), JwParser.parseSectionTimes(sections))
        }
    }

    fun clearSampleData() {
        val id = uiState.value.scheduleId
        manage { repo.clearSampleData(id) }
    }
}
