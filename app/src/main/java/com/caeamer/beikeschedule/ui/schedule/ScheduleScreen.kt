package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.CourseCardLayout
import com.caeamer.beikeschedule.model.ScheduleAppearance
import com.caeamer.beikeschedule.ui.settings.ScheduleAppearanceViewModel
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import com.caeamer.beikeschedule.model.CourseMerger
import com.caeamer.beikeschedule.model.NextClass
import com.caeamer.beikeschedule.model.SectionMap
import com.caeamer.beikeschedule.model.SessionExpander
import com.caeamer.beikeschedule.model.WeekLayout
import com.caeamer.beikeschedule.model.DateCourseResolver
import com.caeamer.beikeschedule.model.ScheduleWeekPage
import com.caeamer.beikeschedule.model.CalendarAdjustments
import com.caeamer.beikeschedule.model.WeekUtils
import com.caeamer.beikeschedule.ui.common.rememberNow
import com.caeamer.beikeschedule.ui.theme.CourseColors
import java.time.LocalDate

private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 无固定时间弹层从第几门课起固定用 Expanded 锚点。
 * 半屏大约能放下 4 门（标题 + 每门两行文字 + 卡片内边距 + 8dp 间距），
 * 到第 5 门就一定需要滚动了，此时半展开锚点会引发"滚动 ↔ 弹层高度"自激抖动。
 */
private const val SCROLLABLE_SHEET_MIN_ITEMS = 5


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onImportClick: () -> Unit,
    onSettings: () -> Unit,
    onManage: () -> Unit = onSettings,
    onCourseDetail: (CourseEntity, Int) -> Unit,
    editCourseId: Long? = null,
    onEditConsumed: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(),
) {
    // withLifecycle：退到后台停止收集（WhileSubscribed 才能在后台真正停流）
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearanceViewModel: ScheduleAppearanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val savedAppearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
    val appearance = savedAppearance ?: ScheduleAppearance()
    val cardMeasurer = rememberCourseCardMeasurer(appearance.fontScale)
    val chromeColor = if (appearance.backgroundFile.isNotEmpty()) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    } else Color.Transparent
    val operationError by viewModel.settingsError.collectAsStateWithLifecycle()
    val hideWeekend by viewModel.hideWeekend.collectAsStateWithLifecycle()
    val hideInactiveCourses by viewModel.hideInactiveCourses.collectAsStateWithLifecycle()

    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val managing by viewModel.managing.collectAsStateWithLifecycle()
    var switchExpanded by remember { mutableStateOf(false) }
    var editingScheduleId by remember { mutableStateOf<Long?>(null) }
    var weekMenuExpanded by remember { mutableStateOf(false) }
    // 多时段课程编辑：存该课的全部行（同「名字+来源」），传给编辑框加载全部时段
    var editCourseGroup by remember { mutableStateOf<List<CourseEntity>?>(null) }
    var prefillSession by remember { mutableStateOf<SessionExpander.Session?>(null) }
    // 编辑框与其中的半填表单不做 rememberSaveable：SessionState 目前没有 Saver，
    // 只恢复"打开"标志会得到"对话框回来了、输入全丢"的假恢复，比关掉更糟（记录在案）。
    var showEditDialog by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    // 长按空白格后待激活的"添加课程"格子（周几, 大节下标）
    var pendingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 无固定时间课程弹层
    var showUnscheduledSheet by rememberSaveable { mutableStateOf(false) }
    // "下一节课"图钉：每分钟重算一次，跨过上课点后自动前移（无需重进页面）。
    // 只在 RESUMED 走时钟，且唤醒点对齐整分钟（见 rememberNow 注释）。
    val now = rememberNow()

    LaunchedEffect(state.scheduleId) {
        showEditDialog = false
        editCourseGroup = null
        prefillSession = null
        showUnscheduledSheet = false
        weekMenuExpanded = false
        pendingSlot = null
    }
    val totalWeeks = state.semester.totalWeeks

    /**
     * 取某张卡片对应的**全部存储行**（同课程名 + 同来源）。
     *
     * 卡片是 CourseMerger 的合并结果，只带基准行的 id；而教务单双周/调课拆行、
     * 手动多时段课都有多行。隐藏/删除/编辑都必须按这个组来做。
     */
    fun groupOf(course: CourseEntity): List<CourseEntity> =
        state.courses.filter { it.name == course.name && it.source == course.source }

    LaunchedEffect(editCourseId, state.loaded) {
        if (editCourseId != null && state.loaded) {
            state.courses.firstOrNull { it.id == editCourseId }?.let {
                editCourseGroup = groupOf(it)
                editingScheduleId = state.scheduleId; showEditDialog = true
            }
            onEditConsumed()
        }
    }

    // 其它**手动课程**已占用的名字（排除本次编辑的这些行）：编辑框据此禁止重名，
    // 否则两张同名卡会在隐藏/删除/编辑时互相连坐（groupOf 以 name+source 为键）。
    val manualNamesInUse = remember(state.courses, editCourseGroup) {
        val editingIds = editCourseGroup.orEmpty().map { it.id }.toSet()
        state.courses
            .filter { it.source == CourseEntity.SOURCE_MANUAL && it.id !in editingIds }
            .map { it.name.trim() }
            .toSet()
    }
    // 下一节课使用实际日期，包含校历假期中的明确补课；卡片 id 与当天合并结果一致。
    val nextClass = remember(state.scheduledCourses, state.sectionTimes, now, state.semester, state.adjustments) {
        NextClass.resolve(
            occurrences = DateCourseResolver.resolve(state.scheduledCourses, state.semester, state.adjustments, now.toLocalDate()),
            sectionStartTimes = state.sectionTimes.associate { it.section to it.startTime },
            semester = state.semester,
            now = now,
        )
    }
    val schedulePager = key(state.scheduleId, state.scheduleVersion) { rememberSchedulePager(
        loaded = state.loaded,
        selectedWeek = state.selectedPage + 1,
        totalWeeks = state.pages.size,
        onWeekSelected = { viewModel.selectPage(it - 1) },
    ) }
    val pagerState = schedulePager.state
    val pagerReady = schedulePager.ready
    val displayedPage = if (pagerReady) pagerState.currentPage else state.selectedPage
    val pageInfo = state.pages.getOrNull(displayedPage) ?: ScheduleWeekPage(null, 1)
    val visibleDays = DateCourseResolver.visibleDays(pageInfo, state.semester, state.adjustments, hideWeekend)
    LaunchedEffect(displayedPage) { pendingSlot = null }

    Scaffold(
        modifier = Modifier.background(SolidColor(Color.Transparent)),
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            // 自定义矮顶栏（替代 TopAppBar 64dp 大留白），内容单行紧凑排列
            // 外层 Scaffold 已不消费状态栏 inset（contentWindowInsets=0），故这里自行 statusBarsPadding
            // 透明部分透出宿主的自定义背景。
            Surface(color = chromeColor) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Box {
                            TextButton(onClick = { switchExpanded = true }, enabled = state.loaded && !managing) {
                                Text(state.scheduleName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge)
                                Icon(Icons.Default.ArrowDropDown, "切换课表")
                            }
                            DropdownMenu(switchExpanded, { switchExpanded = false }) {
                                schedules.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.name + if (item.id == state.scheduleId) " · 当前使用" else "") },
                                        onClick = { switchExpanded = false; viewModel.switchSchedule(item.id) })
                                }
                                DropdownMenuItem(text = { Text("课表管理") }, onClick = { switchExpanded = false; onManage() })
                            }
                        }
                        Text(state.semester.name.ifBlank { todayStatusLine(state, now.toLocalDate()) },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(2.dp))
                    TextButton(onClick = { weekMenuExpanded = true }, enabled = state.loaded) {
                        Text(pageInfo.label)
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "选择周次",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.currentPage != null && displayedPage != state.currentPage) {
                        IconButton(onClick = {
                            viewModel.showCurrentWeek()
                        }) {
                            Icon(Icons.Default.DateRange, contentDescription = when {
                                state.beforeStart -> "查看开学周"
                                state.inHoliday && state.pages.getOrNull(state.currentPage ?: -1)?.teachingWeek != null -> "查看假期后教学周"
                                else -> "回到本周"
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(moreExpanded, { moreExpanded = false }) {
                            DropdownMenuItem(text = { Text("课表管理") }, onClick = { moreExpanded = false; onManage() })
                            DropdownMenuItem(text = { Text("导入课表") }, onClick = { moreExpanded = false; onImportClick() })
                            DropdownMenuItem(text = { Text("添加课程") }, onClick = {
                                moreExpanded = false; prefillSession = null; editCourseGroup = null; editingScheduleId = state.scheduleId; showEditDialog = true
                            })
                            DropdownMenuItem(text = { Text("无固定时间课程") }, onClick = { moreExpanded = false; showUnscheduledSheet = true })
                            DropdownMenuItem(text = { Text("课表设置") }, onClick = { moreExpanded = false; onSettings() })
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            operationError?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::clearSettingsError) { Text("知道了") }
                }
            }
            if (!state.loaded || !pagerReady) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (state.courses.isEmpty()) {
                EmptyState(
                    onLoadSample = { viewModel.loadSampleData() },
                    onImportClick = onImportClick,
                    onAdd = {
                        editingScheduleId = state.scheduleId; showEditDialog = true
                    },
                )
            } else {
                Surface(color = chromeColor) {
                    DateRow(
                        page = pageInfo,
                        semester = state.semester,
                        adjustments = state.adjustments,
                        today = now.toLocalDate(),
                        days = visibleDays,
                    )
                }
                if (state.inHoliday && state.nextWeekMonday != null && !pageInfo.contains(now.toLocalDate())) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "假期中 · ${state.nextWeekMonday} 进入第${state.currentWeek}周",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                state.adjustmentError?.let { Text(it, Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    WeekGrid(
                        week = page + 1,
                        page = state.pages[page],
                        semester = state.semester,
                        adjustments = state.adjustments,
                        courses = state.scheduledCourses,
                        sectionTimes = state.sectionTimes,
                        days = DateCourseResolver.visibleDays(state.pages[page], state.semester, state.adjustments, hideWeekend),
                        pendingSlot = pendingSlot,
                        // 只在用户正看今天所在的日期页时标记。
                        nextClassId = nextClass?.courseId.takeIf { state.pages[page].contains(now.toLocalDate()) },
                        nextClassDay = nextClass?.dayOfWeek,
                        hideInactiveCourses = hideInactiveCourses,
                        appearance = appearance,
                        cardMeasurer = cardMeasurer,
                        onSlotLongPress = { day, big -> if (state.pages[page].teachingWeek != null) pendingSlot = day to big },
                        onSlotClick = { day, big ->
                            if (state.pages[page].teachingWeek != null && pendingSlot == day to big) {
                                prefillSession = SessionExpander.Session(day, setOf(big))
                                editingScheduleId = state.scheduleId; showEditDialog = true
                            }
                            pendingSlot = null
                        },
                        onCourseClick = { onCourseDetail(it, page + 1) },
                    )
                }
            }
        }
    }

    if (weekMenuExpanded && state.loaded) {
        WeekPickerSheet(
            totalWeeks = state.pages.size,
            selectedWeek = displayedPage + 1,
            currentWeek = state.currentPage?.plus(1),
            pageLabels = state.pages.map { if (it.teachingWeek == null) "${it.label}\n${it.dateRange}" else it.label },
            currentWeekLabel = when {
                state.pages.getOrNull(state.currentPage ?: -1)?.teachingWeek == null -> "本周"
                state.beforeStart -> "待开学"
                state.inHoliday && state.pages.getOrNull(state.currentPage ?: -1)?.teachingWeek != null -> "假期后"
                else -> "本周"
            },
            onSelectWeek = { week ->
                weekMenuExpanded = false
                // 离散选周直接定位；也适用于没有挂载 Pager 的空课表。
                viewModel.selectPage(week - 1)
            },
            onDismissRequest = { weekMenuExpanded = false },
        )
    }

    if (showEditDialog && editingScheduleId == state.scheduleId) {
        CourseEditDialog(
            initialRows = editCourseGroup.orEmpty(),
            saving = managing,
            error = operationError,
            totalWeeks = totalWeeks,
            prefill = prefillSession,
            manualNamesInUse = manualNamesInUse,
            onDismiss = {
                showEditDialog = false
                prefillSession = null
                editCourseGroup = null
            },
            onSave = { rows ->
                viewModel.saveCourses(editingScheduleId ?: state.scheduleId, rows, replaceIds = editCourseGroup?.map { it.id }) {
                    showEditDialog = false
                    prefillSession = null
                    editCourseGroup = null
                }
            },
        )
    }

    if (showUnscheduledSheet) {
        UnscheduledSheet(
            courses = state.unscheduledCourses,
            onDismiss = { showUnscheduledSheet = false },
            onCourseClick = { course ->
                showUnscheduledSheet = false
                editCourseGroup = state.courses.filter {
                    it.name == course.name && it.source == course.source
                }.ifEmpty { listOf(course) }
                editingScheduleId = state.scheduleId; showEditDialog = true
            },
        )
    }


}

@Composable
private fun EmptyState(onLoadSample: () -> Unit, onImportClick: () -> Unit, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有课程", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "从教务系统一键导入，或先手动添加 / 载入示例课表看看效果",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onImportClick) { Text("从教务系统导入") }
        TextButton(onClick = onLoadSample) { Text("载入示例课表") }
        TextButton(onClick = onAdd) { Text("手动添加课程") }
    }
}

/** 顶栏学期名下的小字：今天日期 + 学期状态（未开学/第N周/假期中/已放假）。 */
private fun todayStatusLine(state: ScheduleUiState, today: LocalDate): String {
    val dateText = "${today.monthValue}月${today.dayOfMonth}日 周${"一二三四五六日"[today.dayOfWeek.value - 1]}"
    val status = when {
        state.pages.getOrNull(state.currentPage ?: -1)?.let { it.teachingWeek == null && it.contains(today) } == true -> "调休周"
        // locateWeek 的显示语义"未开学视为第1周"用 beforeStart 区分，不能只看 currentWeek
        state.beforeStart -> "未开学"
        state.inHoliday -> "假期中"
        state.currentWeek != null -> "第${state.currentWeek}周"
        state.afterEnd -> "已放假"
        else -> "未开学"
    }
    return "$dateText · $status"
}

/** 日期行使用页面的真实周一；额外调休周不借用来源日期，今天优先高亮。 */
@Composable
private fun DateRow(page: ScheduleWeekPage, semester: SettingsStore.SemesterConfig,
                    adjustments: CalendarAdjustments?, today: LocalDate, days: List<Int>) {
    val monday = page.monday
    val rules = remember(semester, adjustments) { DateCourseResolver.applicable(semester, adjustments).rules }
    val holidayDates = remember(semester.holidayDates) { semester.holidayDates.toSet() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(SECTION_COL_WIDTH))
        days.forEach { day ->
            val date = monday?.plusDays((day - 1).toLong())
            val isToday = date == today
            val isHoliday = date != null && date.toString() in holidayDates
            val isMakeup = rules?.extraClasses?.any { it.date == date?.toString() } == true
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 今天优先高亮；学校标记的放假日期使用浅色背景。
                Column(
                    modifier = Modifier.background(
                        when {
                            isToday -> MaterialTheme.colorScheme.primary
                            isMakeup -> MaterialTheme.colorScheme.tertiaryContainer
                            isHoliday -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(10.dp),
                    ).padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "周${WEEKDAY_NAMES[day - 1]}",
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (date != null) {
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            fontSize = 10.sp,
                            color = if (isToday) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isMakeup) Text("补课", fontSize = 9.sp,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

private val SECTION_COL_WIDTH = CourseCardLayout.TIME_COLUMN_WIDTH.dp

/** 一周课表网格：左节次列 + N 天列，课程块按节次绝对定位；同周重叠课程并排窄列显示；空白格长按可添加课程。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekGrid(
    week: Int,
    page: ScheduleWeekPage,
    semester: SettingsStore.SemesterConfig,
    adjustments: CalendarAdjustments?,
    courses: List<CourseEntity>,
    sectionTimes: List<SectionTimeEntity>,
    days: List<Int>,
    pendingSlot: Pair<Int, Int>?,
    /** 下一节课的卡片 id（null=不标记）；仅当本页包含今天时传入。 */
    nextClassId: Long?,
    nextClassDay: Int?,
    /** 开启后不再显示"本周暂时不上"的淡化课（设置页开关）。 */
    hideInactiveCourses: Boolean,
    appearance: ScheduleAppearance,
    cardMeasurer: CourseCardMeasurer,
    onSlotLongPress: (day: Int, big: Int) -> Unit,
    onSlotClick: (day: Int, big: Int) -> Unit,
    onCourseClick: (CourseEntity) -> Unit,
) {
    val timeMap = remember(sectionTimes) { sectionTimes.associateBy { it.section } }
    val occurrences = remember(courses, days, page, semester, adjustments) {
        days.associateWith { day -> page.monday?.plusDays(day - 1L)?.let {
            DateCourseResolver.resolve(courses, semester, adjustments, it)
        }.orEmpty() }
    }
    val dayLayouts = remember(occurrences, courses, days, page, semester, adjustments, hideInactiveCourses) {
        days.associateWith { day ->
            if (page.monday == null) return@associateWith WeekLayout.layoutDay(
                courses.groupBy { it.source }.values.flatMap(CourseMerger::mergeSameSlot),
                day, page.teachingWeek ?: 1, hideInactiveCourses)
            val inactive = if (hideInactiveCourses) emptyList() else page.monday?.plusDays(day - 1L)?.let {
                DateCourseResolver.inactive(courses, semester, adjustments, it)
            }.orEmpty()
            WeekLayout.layoutResolved(occurrences.getValue(day).map { it.displayCourse }, inactive)
        }
    }
    val makeupIds = remember(occurrences) { occurrences.mapValues { (_, items) -> items.filter { it.isMakeup }.map { it.course.id }.toSet() } }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // weight 列分配存在 1px 舍入，取较窄列宽测量，确保任何列都不会少算行数。
        val dayWidthPx = (constraints.maxWidth - with(density) { SECTION_COL_WIDTH.roundToPx() }) / days.size
        val outerGapPx = with(density) { CourseCardLayout.OUTER_GAP.dp.roundToPx() } * 2
        val minimumUnit = remember(dayLayouts, makeupIds, dayWidthPx, outerGapPx, cardMeasurer) {
            val measurements = dayLayouts.values.flatMap { day ->
                day.clusters.flatMap { cluster ->
                    cluster.map { cardMeasurer.measure(if (it.id in makeupIds[it.dayOfWeek].orEmpty()) it.copy(name = "补课 · ${it.name}") else it,
                        (dayWidthPx / cluster.size - outerGapPx).coerceAtLeast(1)) }
                } + day.inactives.map { cardMeasurer.measure(it, (dayWidthPx - outerGapPx).coerceAtLeast(1)) }
            }
            CourseCardLayout.minimumUnitHeight(measurements).dp
        }
        val minimumTimeUnit = remember(sectionTimes, cardMeasurer) {
            cardMeasurer.minimumTimeUnitHeight(sectionTimes).dp
        }
        val gridHeight = CourseCardLayout.gridHeight(
            maxHeight.value, appearance.sectionHeightPercent, maxOf(minimumUnit, minimumTimeUnit).value,
        ).dp
        // 显式有限高度供课程绝对定位使用；日期栏在滚动容器之外，背景由宿主固定铺底。
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(gridHeight)) {
                // 节次列
                // 节次列：按 6 大节显示（一~六 + 起止时间），行高按小节数加权
                Column(
                    Modifier.width(SECTION_COL_WIDTH).fillMaxHeight().background(
                        if (appearance.backgroundFile.isNotEmpty()) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        else Color.Transparent,
                    ),
                ) {
                    SectionMap.BIG_SECTIONS.forEachIndexed { index, range ->
                        Column(
                            modifier = Modifier.weight(range.count().toFloat()).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(SectionMap.BIG_NAMES[index], style = SectionColumnTypography.label,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            timeMap[range.first]?.let {
                                Text(it.startTime, style = SectionColumnTypography.time,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            timeMap[range.last]?.let {
                                Text(it.endTime, style = SectionColumnTypography.time,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // N 天列（隐藏周末时为 5 天）
                days.forEach { day ->
                    // 冲突簇（本周重叠 → 并排窄列）与非本周淡化课的分拣逻辑见 WeekLayout（纯函数，有单测）。
                    // 开启"隐藏本周不上的课"后 inactives 为空，网格只留本周真正要上的课。
                    val dayLayout = dayLayouts.getValue(day)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // 空白格交互层（最底层）：长按出 +，点击 + 打开预填的添加课程框，点其他格取消
                        Column(Modifier.fillMaxSize()) {
                            SectionMap.BIG_SECTIONS.forEachIndexed { big, range ->
                                Box(
                                    modifier = Modifier
                                        .weight(range.count().toFloat())
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            enabled = page.teachingWeek != null,
                                            onClick = { onSlotClick(day, big) },
                                            onLongClick = { onSlotLongPress(day, big) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (page.teachingWeek != null && pendingSlot == day to big) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(20.dp),
                                            shadowElevation = 2.dp,
                                        ) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = "添加课程",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(6.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 大节分隔线：贴合当前背景色（浅色=白/暗色=深），避免产生突兀的"黑框/暗带"
                        Column(Modifier.fillMaxSize()) {
                            SectionMap.BIG_SECTIONS.forEach { range ->
                                Box(
                                    Modifier
                                        .weight(range.count().toFloat())
                                        .fillMaxWidth()
                                        .padding(vertical = 0.5.dp)
                                        .alpha(0.5f)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                                )
                            }
                        }
                        // 课程块层：冲突簇并排窄列，簇与簇、以及非本周课程各自独占整列宽
                        dayLayout.clusters.forEach { cluster ->
                            Row(Modifier.fillMaxSize()) {
                                cluster.forEach { course ->
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        CourseCard(
                                            course = course,
                                            week = week,
                                            active = true,
                                            fontScale = appearance.fontScale,
                                            isNext = course.id == nextClassId && day == nextClassDay,
                                            isMakeup = course.id in makeupIds[day].orEmpty(),
                                            onClick = { onCourseClick(course) },
                                        )
                                    }
                                }
                            }
                        }
                        dayLayout.inactives.forEach { course ->
                            CourseCard(
                                course = course,
                                week = week,
                                active = false,
                                fontScale = appearance.fontScale,
                                isNext = false,
                                onClick = { onCourseClick(course) },
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CourseCard(
    course: CourseEntity,
    active: Boolean,
    week: Int,
    fontScale: Float,
    /** 是否为下一节课：细描边和右上角蓝点，不挤占标题宽度。 */
    isNext: Boolean,
    isMakeup: Boolean = false,
    onClick: () -> Unit,
) {
    val sources = LocalCourseSources.current
    val sourceKey = courseSourceKey(course, week)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.98f else 1f,
        androidx.compose.animation.core.tween(com.caeamer.beikeschedule.ui.common.AppMotion.STATE), label = "coursePress")
    androidx.compose.runtime.DisposableEffect(sourceKey) { onDispose { sources.bounds.remove(sourceKey) } }
    val colors = CourseColors.card(course.colorIndex, MaterialTheme.colorScheme.background.luminance() < 0.5f, active)
    // 13 节特殊加课钳制到第 12 节区间显示（网格按 12 小节排版）
    val clampedStart = course.startSection.coerceIn(1, SectionMap.TOTAL_SMALL_SECTIONS)
    val span = CourseCardLayout.span(course)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(androidx.compose.ui.Alignment.TopCenter)
            .coursePosition(clampedStart, span)
            .padding(CourseCardLayout.OUTER_GAP.dp)
            .onGloballyPositioned { sources.bounds[sourceKey] = it.boundsInRoot() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (sources.selected == sourceKey) 0f else 1f),
    ) {
        Surface(
            color = colors.background,
            shape = RoundedCornerShape(6.dp),
            border = if (isNext) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .semantics {
                    stateDescription = when {
                        isNext -> "下一节课"
                        isMakeup -> "调休补课"
                        !active -> "非本周课程"
                        else -> "本周课程"
                    }
                }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            CourseCardText(if (isMakeup) course.copy(name = "补课 · ${course.name}") else course,
                colors.title, fontScale, colors.location, colors.detail)
        }
        // 蓝点放在上边缘，不再为整段标题预留 15dp；“下一节课”由卡片语义播报。
        if (isNext) {
            Box(Modifier.align(Alignment.TopEnd).offset(y = (-3).dp).size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/**
 * 课程块定位：按小节数测量与放置；起止边界分别取整，与节次列保持对齐。
 * 不能用 fillMaxHeight+偏移的组合——fillMaxHeight 会先压缩约束，导致偏移量被等比缩小。
 */
private fun Modifier.coursePosition(startSection: Int, span: Int): Modifier =
    this.layout { measurable, constraints ->
        val unit = constraints.maxHeight.toFloat() / SectionMap.TOTAL_SMALL_SECTIONS
        val top = (unit * (startSection - 1)).roundToInt()
        val height = ((unit * (startSection - 1 + span)).roundToInt() - top).coerceAtLeast(1)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height),
        )
        layout(placeable.width, placeable.height) {
            placeable.place(0, top)
        }
    }

/** 无固定时间课程弹层（实验周/网课等）：同名行去重、LazyColumn 稳定滚动不截断、卡片可点击进入编辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnscheduledSheet(
    courses: List<CourseEntity>,
    onDismiss: () -> Unit,
    onCourseClick: (CourseEntity) -> Unit,
) {
    // 教务对"单周调课/单双周拆分"的同名课程会拆多行（如 电子技术实验 + 电子技术实验【实验】）
    // 注意：remember 必须在 ModalBottomSheet 外，sheet 内容 lambda 里放 remember 会导致内容叠加重影
    val distinctCourses = remember(courses) { courses.distinctBy { it.name } }

    // 「往下使劲翻会抽搐」的根因：默认半展开锚点下，列表滚到尽头后剩余速度去拖动弹层，
    // 弹层变高 → 列表一起变高 → 不再可滚 → 弹层回落到半展开 → 列表又可滚 → 再触发，
    // 形成自激回路，实测表现为内容以约 6Hz、±20dp 整体上下抖动（弹层自身边缘不动）。
    // 三层一起钉死回路：
    //   1. 列表长到需要滚动时固定 Expanded 锚点（弹层不再改高度，也就不会重新测量列表）；
    //   2. 列表高度钉在弹层内容区（fillMaxHeight），不随滚动状态变化；
    //   3. 关掉列表自身的 overscroll 回弹（拉伸/辉光），避免它在列表尽头与嵌套滚动互相喂招。
    // 列表很短（不需要滚动）时保留半展开与默认 overscroll，观感更轻，也不存在该回路。
    val scrollable = distinctCourses.size >= SCROLLABLE_SHEET_MIN_ITEMS
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = scrollable)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyColumn(
                modifier = if (scrollable) {
                    Modifier.fillMaxWidth().fillMaxHeight()
                } else {
                    Modifier.fillMaxWidth()
                },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text("无固定时间课程", style = MaterialTheme.typography.titleMedium) }
                if (distinctCourses.isEmpty()) {
                    item {
                        Text(
                            "没有无固定时间课程",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(distinctCourses, key = { it.id }) { course ->
                    val (bg, fg) = CourseColors.of(course.colorIndex, MaterialTheme.colorScheme.background.luminance() < 0.5f)
                    Surface(
                        color = bg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCourseClick(course) },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(course.name, fontSize = 14.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                WeekUtils.describe(course.weekBitmap),
                                fontSize = 12.sp,
                                color = fg.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
    }
}
