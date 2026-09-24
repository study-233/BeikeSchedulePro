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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    ) : ImportUiState {
        val scheduledCount get() = courses.count { !it.isUnscheduled }
        val unscheduledCount get() = courses.count { it.isUnscheduled }
    }

    data class Error(val message: String) : ImportUiState
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Browsing)
    val state: StateFlow<ImportUiState> = _state

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

    /**
     * 确认导入：覆盖式写入课程与节次时间（含清除示例数据，单事务），再写学期配置。
     *
     * 三处之前的缺陷：
     * 1. 课程写入与 clearSampleData 分属两个事务，中途被杀会留下"新课已写入、示例仍在"；
     * 2. 整个流程没有 try/catch，任何异常（磁盘满、Room/DataStore IO 失败）都会逃出
     *    viewModelScope.launch 直接崩进程，且流程不结束；
     * 3. 状态在写库期间仍是 Preview，按钮不禁用 → 双击可并发跑两次导入。
     *
     * 现在：先置 [ImportUiState.Committing] 让按钮禁用并挡住重入，课程与学期配置各自
     * 尽力写入，失败落到 Error、成功落到 [ImportUiState.Done]，由屏幕侧统一退出流程。
     */
    fun confirmImport() {
        if (_state.value !is ImportUiState.Preview) return
        val preview = _state.value as ImportUiState.Preview
        _state.value = ImportUiState.Committing
        viewModelScope.launch {
            try {
                // 先写课程（单事务，含清除示例）；失败则学期配置不动，避免"新课配旧学期"
                repo.commitImport(preview.courses, preview.sectionTimes)
                val previous = repo.settings.semester.first()
                repo.settings.saveSemester(
                    previous.copy(
                        xn = preview.xn,
                        xq = preview.xq,
                        name = preview.semesterName,
                        firstMonday = preview.firstMonday,
                        totalWeeks = preview.totalWeeks,
                        weekMondays = preview.weekMondays,
                    )
                )
                _state.value = ImportUiState.Done
                WidgetUpdateCoordinator.requestRefresh(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 课程可能已写入、学期配置未写入：明确告诉用户发生了什么，而不是静默
                _state.value = ImportUiState.Error(
                    "保存失败：${e.message ?: e.javaClass.simpleName}。请重试；若反复失败，请重新抓取后再导入。",
                )
            }
        }
    }
}
