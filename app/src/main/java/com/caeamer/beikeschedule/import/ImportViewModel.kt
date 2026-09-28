package com.caeamer.beikeschedule.import

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import com.caeamer.beikeschedule.model.ScheduleNames
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** 教务导入状态机。 */
sealed interface ImportUiState {
    /** 用户在 WebView 中浏览/登录。 */
    data object Browsing : ImportUiState

    /** 脚本已注入，正在抓取接口。 */
    data object Fetching : ImportUiState

    /**
     * 用户已确认，正在写库。
     *
     * 必须有这个中间态：确认按钮据此禁用，否则双击会启动两次并发导入
     * （第二次会清空并重插第一次的行，与学期配置写入交错）。
     */
    data object Committing : ImportUiState

    /**
     * 写库与学期配置都已落地，等待屏幕退出本流程。
     *
     * 必须是**独立终态**：A 组修复后的实现在成功后只回调 onDone()、状态仍停在 [Committing]，
     * 而 ViewModel 是 Activity 级的（离开组合不销毁）——同一进程内第二次进入导入页时
     * 读到 Committing，返回箭头禁用、BackHandler 吞返回、页面无 Tab，
     * 表现为**除了杀进程没有任何出口**（100% 复现）。
     *
     * 屏幕侧由 `LaunchedEffect(state)` 观察本状态后调 onDone()，这样即使写库期间
     * Activity 被重建（旧 onDone 回调写的是已被丢弃的 state），新组合也能照常退出。
     */
    data object Done : ImportUiState

    /** 抓取成功，等待用户确认写入。 */
    data class Preview(
        val semesterName: String,
        val xn: String,
        val xq: String,
        val firstMonday: String,
        /** 官方教学周日历（下标+1 = 教学周 → 周一日期）；为空则回退推算。 */
        val weekMondays: List<String>,
        /** 教务学期总教学周数（来自 queryzclist/校历）。 */
        val totalWeeks: Int,
        val courses: List<CourseEntity>,
        val sectionTimes: List<SectionTimeEntity>,
        val holidayDates: List<String> = emptyList(),
        val targetId: Long? = null,
        val createNew: Boolean = true,
        val newName: String = "",
        val nameInitialized: Boolean = false,
        val saveError: String? = null,
    ) : ImportUiState {
        fun semesterConfig() = SettingsStore.SemesterConfig(xn, xq, semesterName, firstMonday, totalWeeks, weekMondays, holidayDates)
        val scheduledCount get() = courses.count { !it.isUnscheduled }
        val unscheduledCount get() = courses.count { it.isUnscheduled }
    }

    data class Error(val message: String) : ImportUiState
}

class ImportViewModel internal constructor(
    app: Application,
    private val repo: ScheduleRepository,
    private val afterImport: suspend () -> Unit,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, ScheduleRepository(app), {
        WidgetUpdateCoordinator.requestRefresh(app)
        ClassReminderScheduler.reschedule(app)
    })

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Browsing)
    val state: StateFlow<ImportUiState> = _state
    private var requestGuard: () -> Unit = {}
    private var requestSaving: () -> Unit = {}
    private var requestFinished: (String?) -> Unit = {}
    private var requestCommitted: () -> Unit = {}
    private var commitJob: kotlinx.coroutines.Job? = null

    /** 唯一匹配才自动更新；不依据当前选中的课表猜测导入目标。 */
    suspend fun acceptSyncResult(event: ImportFetchEvent, automatic: Boolean, session: AcademicSessionViewModel) {
        val values = event.values ?: return
        session.ensureImportCurrent(event.id)
        requestGuard = { session.ensureImportCurrent(event.id) }
        requestSaving = { session.importSaving(event.id) }
        requestFinished = { session.importFinished(event.id, it) }
        requestCommitted = { session.importCommitted(event.id) }
        onFetchResult(values[0], values[1], values[2], values[3], values[4], values[5])
        val preview = state.value as? ImportUiState.Preview
        if (preview == null) {
            session.importFinished(event.id, (state.value as? ImportUiState.Error)?.message ?: "课表解析失败")
            return
        }
        try {
            val all = repo.schedules.first()
            requestGuard()
            val matches = matchingSchedules(all, preview.xn, preview.xq)
            _state.value = preview.copy(
                newName = ScheduleNames.available(preview.semesterName, all.map { it.name }), nameInitialized = true,
                createNew = matches.isEmpty() || !automatic,
                targetId = if (automatic) matches.singleOrNull()?.id else null,
            )
            if (automatic && matches.size == 1) confirmImportInternal(false, activateTarget = false)
            else session.importReview(event.id)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = e.message ?: "读取课表失败，请重试"
            _state.value = preview.copy(saveError = message)
            session.importFinished(event.id, message)
        }
    }

    fun cancelPendingImport() {
        commitJob?.cancel()
        _state.value = ImportUiState.Browsing
        requestGuard = {}; requestSaving = {}; requestFinished = {}; requestCommitted = {}
    }

    companion object {
        internal fun matchingSchedules(schedules: List<com.caeamer.beikeschedule.data.local.ScheduleEntity>, xn: String, xq: String) =
            schedules.filter { xn.isNotBlank() && xq.isNotBlank() && it.xn == xn && it.xq == xq }
    }

    val schedules = repo.schedules.retryWhen { cause, _ ->
        if (cause is CancellationException) throw cause
        delay(1500)
        true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initializeName() {
        val preview = _state.value as? ImportUiState.Preview ?: return
        if (!preview.nameInitialized && schedules.value.isNotEmpty()) {
            _state.value = preview.copy(newName = ScheduleNames.available(preview.semesterName, schedules.value.map { it.name }), nameInitialized = true)
        }
    }

    fun selectTarget(createNew: Boolean, id: Long?) {
        val preview = _state.value as? ImportUiState.Preview ?: return
        _state.value = preview.copy(createNew = createNew, targetId = id, saveError = null)
    }

    fun setName(name: String) {
        val preview = _state.value as? ImportUiState.Preview ?: return
        _state.value = preview.copy(newName = name, nameInitialized = true, saveError = null)
    }

    fun onFetchStart() {
        _state.value = ImportUiState.Fetching
    }

    /** JsBridge 回调：脚本抓取完成（可能在 WebView 线程，切到主线程状态更新即可）。 */
    fun onFetchResult(
        semester: String,
        published: String,
        courses: String,
        sections: String,
        weekDates: String,
        calendar: String,
    ) {
        try {
            if (published.trim() == "0") {
                _state.value = ImportUiState.Error("本学期课表尚未发布，请稍后再试")
                return
            }
            val (xn, xq, name) = JwParser.parseCurrentSemester(semester)
            val courseList = JwParser.parseCourses(courses)
            if (courseList.isEmpty()) {
                _state.value = ImportUiState.Error("未解析到课程，请确认已进入教务系统课表页")
                return
            }
            val weekCalendar = JwParser.parseWeekCalendar(calendar)
            // 节次时间缺失时必须中止：确认导入会先清空 section_time 再写入，空表写进去之后
            // 每门课都算不出上课时间点 —— 上课提醒会全部静默失效，课程详情也看不到起止时间。
            // 宁可让用户重抓一次，也不能静默写坏（脚本返回 {code,...} 之类无 content 的响应时就会这样）。
            val sectionTimes = JwParser.parseSectionTimes(sections)
            if (sectionTimes.isEmpty()) {
                _state.value = ImportUiState.Error("未获取到节次时间（queryKbjg 返回异常），请返回后重新抓取")
                return
            }
            _state.value = ImportUiState.Preview(
                semesterName = name.ifBlank { "$xn-$xq" },
                xn = xn,
                xq = xq,
                firstMonday = JwParser.parseFirstMonday(weekDates)
                    ?: weekCalendar.weekMondays.firstOrNull().orEmpty(),
                weekMondays = weekCalendar.weekMondays,
                holidayDates = weekCalendar.holidayDates,
                totalWeeks = weekCalendar.totalWeeks.takeIf { it > 0 } ?: 20,
                courses = courseList,
                sectionTimes = sectionTimes,
            )
        } catch (e: Exception) {
            _state.value = ImportUiState.Error("解析失败：${e.message}")
        }
    }

    fun onFetchError(message: String) {
        _state.value = ImportUiState.Error("抓取失败：$message")
    }

    fun backToBrowsing() {
        _state.value = ImportUiState.Browsing
    }

    /**
     * 页面开始加载时调用：把卡住的 [ImportUiState.Fetching] 复位。
     *
     * Fetching 只由桥回调清除，而脚本可能因重入标志直接 return、或在桥不可用的页面上
     * 静默失败——没有这一步时界面会永久停在"抓取中…"，按钮禁用，用户只剩返回键。
     */
    fun onPageStarted() {
        if (_state.value is ImportUiState.Fetching) {
            _state.value = ImportUiState.Browsing
        }
    }

    /**
     * 进入导入页前由宿主调用：清掉上一次流程留下的 [ImportUiState.Done]。
     *
     * 没有这一步，成功导入后同一进程内再进导入页会立刻被终态导航弹出去（进不去）。
     * 只清终态：Committing 表示有写库在途，绝不能重置。
     */
    fun resetIfFinished() {
        if (_state.value is ImportUiState.Done) {
            _state.value = ImportUiState.Browsing
        }
    }

    /** 失败恢复完整预览及目标选择，提交事务不可拆成课表和 DataStore 两次写入。 */
    fun confirmImport(allowDifferentSemester: Boolean = false) {
        confirmImportInternal(allowDifferentSemester, activateTarget = true)
    }

    private fun confirmImportInternal(allowDifferentSemester: Boolean, activateTarget: Boolean) {
        val preview = _state.value as? ImportUiState.Preview ?: return
        if (!preview.createNew && preview.targetId == null) return
        val guard = requestGuard
        val finished = requestFinished
        val committed = requestCommitted
        try { requestSaving(); guard() } catch (_: CancellationException) { return }
        _state.value = ImportUiState.Committing
        commitJob = viewModelScope.launch {
            try {
                repo.commitImport(
                    if (preview.createNew) null else preview.targetId,
                    preview.newName, preview.semesterConfig(), preview.courses, preview.sectionTimes,
                    allowDifferentSemester, activateTarget, guard,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = preview.copy(saveError = e.message ?: "保存失败，请重试")
                finished(e.message ?: "保存失败，请重试")
                return@launch
            }
            committed()
            // 数据已经提交；外部刷新失败不能把成功导入变成可重复提交的预览。
            try {
                afterImport()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 打开应用及每日脉冲会重新尝试排期。
            }
            guard()
            finished(null)
            _state.value = ImportUiState.Done
        }
    }
}
