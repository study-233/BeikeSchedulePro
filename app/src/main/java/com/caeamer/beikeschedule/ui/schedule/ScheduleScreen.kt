package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.caeamer.beikeschedule.model.WeekResolver
import com.caeamer.beikeschedule.model.WeekUtils
import com.caeamer.beikeschedule.ui.common.rememberNow
import com.caeamer.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 无固定时间弹层从第几门课起固定用 Expanded 锚点。
 * 半屏大约能放下 4 门（标题 + 每门两行文字 + 卡片内边距 + 8dp 间距），
 * 到第 5 门就一定需要滚动了，此时半展开锚点会引发"滚动 ↔ 弹层高度"自激抖动。
 */
private const val SCROLLABLE_SHEET_MIN_ITEMS = 5

/** 网格底部可滚动的 FAB 避让空间（40dp 按钮 + 16dp 边距）。 */
private val FAB_CLEARANCE = 56.dp

/** 日期所属教学周（严格口径：开学前/假期跳周/学期后返回 null），与提醒排期同一套判定。 */
private fun teachingWeekOf(semester: SettingsStore.SemesterConfig, date: LocalDate): Int? =
    WeekResolver.teachingWeekOf(semester, date)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onImportClick: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(),
) {
    // withLifecycle：退到后台停止收集（WhileSubscribed 才能在后台真正停流）
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearanceViewModel: ScheduleAppearanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val savedAppearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
    val appearance = savedAppearance ?: ScheduleAppearance()
    val chromeColor = if (appearance.backgroundFile.isNotEmpty()) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    } else Color.Transparent
    val reminderEnabled by viewModel.reminderEnabled.collectAsStateWithLifecycle()
    val reminderMinutes by viewModel.reminderMinutes.collectAsStateWithLifecycle()
    val hideWeekend by viewModel.hideWeekend.collectAsStateWithLifecycle()
    val hideInactiveCourses by viewModel.hideInactiveCourses.collectAsStateWithLifecycle()
    val reminderSchedule by viewModel.reminderSchedule.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 开启上课提醒前需要先拿到通知权限（Android 13+）
    var pendingEnableReminder by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnableReminder) viewModel.setReminder(true, reminderMinutes)
        pendingEnableReminder = false
    }

    var weekMenuExpanded by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<CourseEntity?>(null) }
    // 多时段课程编辑：存该课的全部行（同「名字+来源」），传给编辑框加载全部时段
    var editCourseGroup by remember { mutableStateOf<List<CourseEntity>?>(null) }
    var prefillSession by remember { mutableStateOf<SessionExpander.Session?>(null) }
    // 编辑框与其中的半填表单不做 rememberSaveable：SessionState 目前没有 Saver，
    // 只恢复"打开"标志会得到"对话框回来了、输入全丢"的假恢复，比关掉更糟（记录在案）。
    var showEditDialog by remember { mutableStateOf(false) }
    // 下面两个对话框的全部内容都从 state 现读，旋转后恢复打开态是安全的
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 长按空白格后待激活的"添加课程"格子（周几, 大节下标）
    var pendingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 无固定时间课程弹层
    var showUnscheduledSheet by rememberSaveable { mutableStateOf(false) }
    // "下一节课"图钉：每分钟重算一次，跨过上课点后自动前移（无需重进页面）。
    // 只在 RESUMED 走时钟，且唤醒点对齐整分钟（见 rememberNow 注释）。
    val now = rememberNow()

    val totalWeeks = state.semester.totalWeeks
    val visibleDays = if (hideWeekend) (1..5).toList() else (1..7).toList()

    /**
     * 取某张卡片对应的**全部存储行**（同课程名 + 同来源）。
     *
     * 卡片是 CourseMerger 的合并结果，只带基准行的 id；而教务单双周/调课拆行、
     * 手动多时段课都有多行。隐藏/删除/编辑都必须按这个组来做。
     */
    fun groupOf(course: CourseEntity): List<CourseEntity> =
        state.courses.filter { it.name == course.name && it.source == course.source }

    // 其它**手动课程**已占用的名字（排除本次编辑的这些行）：编辑框据此禁止重名，
    // 否则两张同名卡会在隐藏/删除/编辑时互相连坐（groupOf 以 name+source 为键）。
    val manualNamesInUse = remember(state.courses, editCourseGroup) {
        val editingIds = editCourseGroup.orEmpty().map { it.id }.toSet()
        state.courses
            .filter { it.source == CourseEntity.SOURCE_MANUAL && it.id !in editingIds }
            .map { it.name.trim() }
            .toSet()
    }
    // 下一节课：仅今天（严格教学周内）尚未开始的最早一节；卡片 id 与合并后课程一致
    val nextClassId = remember(state.scheduledCourses, state.sectionTimes, now, state.semester) {
        NextClass.resolve(
            courses = CourseMerger.mergeSameSlot(state.scheduledCourses),
            sectionStartTimes = state.sectionTimes.associate { it.section to it.startTime },
            todayTeachingWeek = teachingWeekOf(state.semester, now.toLocalDate()),
            now = now,
        )?.courseId
    }
    val pagerState = rememberPagerState(
        // 用 state.selectedWeek（已 coerce 进 1..totalWeeks）而非 currentWeek 作初值：
        // 旋转屏幕重建本页时，用 currentWeek 会把正在看第 5 周的用户甩回第 8 周，
        // 随后下面的 LaunchedEffect 又把 selectedWeek 覆盖成 8，用户的选择被无声丢弃。
        initialPage = (state.selectedWeek - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)),
        pageCount = { totalWeeks },
    )

    // Pager 滑动 → 同步选中周
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            // 丢掉首帧：那是 rememberPagerState 的初始页（0），不是用户的滑动结果。
            // 直接回写会把"首次定位当前周"覆盖成第 1 周——DataStore 异步读盘必然晚于这一帧，
            // 于是每次启动课表都停在第 1 页，且写完后 selectedWeek 非空、定位永不发生。
            .drop(1)
            .collect { viewModel.selectWeek(it + 1) }
    }
    // 选中周变化（含学期设置改动后重新定位）→ Pager 跟随。
    // 此前只以 currentWeek 为键：DataStore 写入让 selectedWeek 变成当前周时 Pager 不动，
    // 于是出现"顶栏显示第 8 周、网格里是第 1 周的卡片与日期"的失步。
    //
    // 重新进入 App（新前台会话）时 ViewModel 会把 selectedWeek 打回当前周，走的就是这条路径。
    // 这里刻意用 scrollToPage 瞬间落位而非 animateScrollToPage：重进 App 应该第一眼就是本周，
    // 而不是让用户看着它从第 1 周一路滑到第 16 周。
    LaunchedEffect(state.selectedWeek) {
        val target = (state.selectedWeek - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }

    // 暗色/浅色都用整屏渐变（深色版见 CourseColors.scheduleGradientDark），由 MainActivity 统一铺底，本页透明
    Scaffold(
        modifier = Modifier.background(SolidColor(Color.Transparent)),
        containerColor = Color.Transparent,
        topBar = {
            // 自定义矮顶栏（替代 TopAppBar 64dp 大留白），内容单行紧凑排列
            // 外层 Scaffold 已不消费状态栏 inset（contentWindowInsets=0），故这里自行 statusBarsPadding
            // 透明，透出 MainActivity 的整屏渐变背景
            Surface(color = chromeColor) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(58.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 学期名（下挂今天日期与周次状态）可点击 → 学期设置
                    // 标题只占按钮以外的剩余宽度，避免「回到本周」出现时挤压末尾的导入按钮。
                    TextButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                text = state.semester.name.ifBlank { "贝壳课表" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = todayStatusLine(state, now.toLocalDate()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "学期设置",
                            modifier = Modifier.width(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    TextButton(onClick = { weekMenuExpanded = true }) {
                        Text("第${state.selectedWeek}周")
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "选择周次",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = weekMenuExpanded,
                        onDismissRequest = { weekMenuExpanded = false },
                    ) {
                        (1..totalWeeks).forEach { w ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "第${w}周" + when {
                                            w != state.currentWeek -> ""
                                            state.inHoliday -> "（假期后）"
                                            else -> "（本周）"
                                        },
                                        fontWeight = if (w == state.currentWeek) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    weekMenuExpanded = false
                                    scope.launch { pagerState.animateScrollToPage(w - 1) }
                                },
                            )
                        }
                    }
                    if (state.currentWeek != null && state.selectedWeek != state.currentWeek) {
                        IconButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(state.currentWeek!! - 1) }
                        }) {
                            Icon(Icons.Default.DateRange, contentDescription = "回到本周")
                        }
                    }
                    if (state.unscheduledCourses.isNotEmpty()) {
                        IconButton(onClick = { showUnscheduledSheet = true }) {
                            Icon(Icons.Default.Notes, contentDescription = "无固定时间课程")
                        }
                    }
                    IconButton(onClick = onImportClick) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "从教务系统导入")
                    }
                }
            }
        },
        floatingActionButton = {
            // 小号 FAB：56dp 默认尺寸在课表页喧宾夺主，40dp + 默认阴影足够
            SmallFloatingActionButton(onClick = {
                prefillSession = null
                showEditDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "添加课程", modifier = Modifier.size(20.dp))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loaded && state.courses.isEmpty()) {
                EmptyState(
                    onLoadSample = { viewModel.loadSampleData() },
                    onImportClick = onImportClick,
                    onAdd = {
                        showEditDialog = true
                    },
                )
            } else {
                Surface(color = chromeColor) {
                    DateRow(
                        week = state.selectedWeek,
                        semester = state.semester,
                        today = now.toLocalDate(),
                        days = visibleDays,
                    )
                }
                if (state.inHoliday && state.nextWeekMonday != null) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "假期中 · ${state.nextWeekMonday} 进入第${state.currentWeek}周",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    WeekGrid(
                        week = page + 1,
                        courses = state.scheduledCourses,
                        sectionTimes = state.sectionTimes,
                        days = visibleDays,
                        pendingSlot = pendingSlot,
                        // 只在用户正看"今天所在教学周"时标记，翻到其他周不误导
                        nextClassId = nextClassId.takeIf { page + 1 == teachingWeekOf(state.semester, now.toLocalDate()) },
                        hideInactiveCourses = hideInactiveCourses,
                        appearance = appearance,
                        onSlotLongPress = { day, big -> pendingSlot = day to big },
                        onSlotClick = { day, big ->
                            if (pendingSlot == day to big) {
                                prefillSession = SessionExpander.Session(day, setOf(big))
                                showEditDialog = true
                            }
                            pendingSlot = null
                        },
                        onCourseClick = { detailCourse = it },
                    )
                }
            }
        }
    }

    detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            sectionTimes = state.sectionTimes,
            isSample = course.source == CourseEntity.SOURCE_SAMPLE,
            isImported = course.source == CourseEntity.SOURCE_IMPORT,
            onDismiss = { detailCourse = null },
            onEdit = {
                detailCourse = null
                // 多时段课程：加载同名同源的全部行（编辑框回显全部时段）
                editCourseGroup = groupOf(course).ifEmpty { listOf(course) }
                showEditDialog = true
            },
            // 隐藏/删除必须作用于**整组合并行**，不能只用卡片 id。
            // 卡片来自 CourseMerger.mergeSameSlot，它的 id 是基准行的 id；教务单双周/
            // 调课拆行与手动多时段课都有 N 行，只改一行会让卡片原样留在网格上——
            // 用户看到的是"点了隐藏没反应"。
            onDelete = {
                viewModel.saveCourses(emptyList(), replaceIds = groupOf(course).map { it.id })
                detailCourse = null
            },
            onHide = {
                viewModel.setCoursesHidden(groupOf(course).map { it.id }, true)
                detailCourse = null
            },
        )
    }

    if (showEditDialog) {
        CourseEditDialog(
            initialRows = editCourseGroup.orEmpty(),
            totalWeeks = totalWeeks,
            prefill = prefillSession,
            manualNamesInUse = manualNamesInUse,
            onDismiss = {
                showEditDialog = false
                prefillSession = null
                editCourseGroup = null
            },
            onSave = { rows ->
                viewModel.saveCourses(rows, replaceIds = editCourseGroup?.map { it.id })
                showEditDialog = false
                prefillSession = null
                editCourseGroup = null
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
                showEditDialog = true
            },
        )
    }

    if (showSettings) {
        SemesterSettingsDialog(
            current = state.semester,
            hasSample = state.hasSample,
            hiddenCourses = state.hiddenCourses,
            reminderEnabled = reminderEnabled,
            reminderMinutes = reminderMinutes,
            hideWeekend = hideWeekend,
            reminderSchedule = reminderSchedule,
            onDismiss = { showSettings = false },
            onSave = { viewModel.saveSemester(it) },
            onReminderChange = { enabled, minutes -> viewModel.setReminder(enabled, minutes) },
            onHideWeekendChange = { viewModel.setHideWeekend(it) },
            onClearSample = { viewModel.clearSampleData() },
            // 恢复也必须按整组：隐藏是按合并组做的（一张卡 N 行），只恢复一行会留下
            // 一张"残废"卡片（例如只剩第 7 周有课），且隐藏列表里还有同名项要反复点。
            onRestoreCourse = { id ->
                state.courses.firstOrNull { it.id == id }
                    ?.let { row -> viewModel.setCoursesHidden(groupOf(row).map { it.id }, false) }
                    ?: viewModel.setCourseHidden(id, false)
            },
            onRequestNotificationPermission = { onGranted ->
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    onGranted()
                } else {
                    pendingEnableReminder = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
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
        // locateWeek 的显示语义"未开学视为第1周"用 beforeStart 区分，不能只看 currentWeek
        state.beforeStart -> "未开学"
        state.inHoliday -> "假期中"
        state.currentWeek != null -> "第${state.currentWeek}周"
        state.afterEnd -> "已放假"
        else -> "未开学"
    }
    return "$dateText · $status"
}

/** 顶部日期行：左格对齐节次列，N 天列；周一日期统一走 WeekResolver.weekMonday（校历优先，
 *  非周一开学日期会被归一化，见那里的注释），今天用主题色实心胶囊高亮。 */
@Composable
private fun DateRow(week: Int, semester: SettingsStore.SemesterConfig, today: LocalDate, days: List<Int>) {
    val monday = remember(semester, week) { WeekResolver.weekMonday(semester, week) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(SECTION_COL_WIDTH))
        days.forEach { day ->
            val date = monday?.plusDays((day - 1).toLong())
            val isToday = date == today
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 今天：主题色实心胶囊 + 白字，一眼定位
                Column(
                    modifier = Modifier.background(
                        if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
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
                }
            }
        }
    }
}

private val SECTION_COL_WIDTH = 36.dp

/** 一周课表网格：左节次列 + N 天列，课程块按节次绝对定位；同周重叠课程并排窄列显示；空白格长按可添加课程。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekGrid(
    week: Int,
    courses: List<CourseEntity>,
    sectionTimes: List<SectionTimeEntity>,
    days: List<Int>,
    pendingSlot: Pair<Int, Int>?,
    /** 下一节课的卡片 id（null=不标记）；仅当本页正是今天所在教学周时由调用方传入。 */
    nextClassId: Long?,
    /** 开启后不再显示"本周暂时不上"的淡化课（设置页开关）。 */
    hideInactiveCourses: Boolean,
    appearance: ScheduleAppearance,
    onSlotLongPress: (day: Int, big: Int) -> Unit,
    onSlotClick: (day: Int, big: Int) -> Unit,
    onCourseClick: (CourseEntity) -> Unit,
) {
    val timeMap = remember(sectionTimes) { sectionTimes.associateBy { it.section } }
    // 同名同段多行（教务单周调课/单双周拆分）先合并成一张卡，再进冲突聚类
    val mergedCourses = remember(courses) { CourseMerger.mergeSameSlot(courses) }
    val dayLayouts = remember(mergedCourses, days, week, hideInactiveCourses) {
        days.associateWith { WeekLayout.layoutDay(mergedCourses, it, week, hideInactiveCourses) }
    }
    val visibleCourses = remember(dayLayouts) {
        dayLayouts.values.flatMap { it.clusters.flatten() + it.inactives }
    }
    val density = LocalDensity.current
    val minimumUnit = with(density) {
        CourseCardLayout.minimumUnitHeight(
            visibleCourses,
            (13 * appearance.fontScale).sp.toDp().value,
            (11 * appearance.fontScale).sp.toDp().value,
        ).dp
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gridHeight = maxOf(
            (maxHeight - FAB_CLEARANCE).coerceAtLeast(0.dp),
            minimumUnit * SectionMap.TOTAL_SMALL_SECTIONS,
        )
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
                            Text(SectionMap.BIG_NAMES[index], fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            timeMap[range.first]?.let {
                                Text(it.startTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            timeMap[range.last]?.let {
                                Text(it.endTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            onClick = { onSlotClick(day, big) },
                                            onLongClick = { onSlotLongPress(day, big) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (pendingSlot == day to big) {
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
                                            active = true,
                                            fontScale = appearance.fontScale,
                                            isNext = course.id == nextClassId,
                                            onClick = { onCourseClick(course) },
                                        )
                                    }
                                }
                            }
                        }
                        dayLayout.inactives.forEach { course ->
                            CourseCard(
                                course = course,
                                active = false,
                                fontScale = appearance.fontScale,
                                isNext = false,
                                onClick = { onCourseClick(course) },
                            )
                        }
                    }
                }
            }
            // 滚到最底部时，末节课程可以完整避开悬浮添加按钮。
            Spacer(Modifier.height(FAB_CLEARANCE))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CourseCard(
    course: CourseEntity,
    active: Boolean,
    fontScale: Float,
    /** 是否为"下一节课"（今天尚未开始的最早一节）：右上角叠加图钉徽标。 */
    isNext: Boolean,
    onClick: () -> Unit,
) {
    // 本周/非本周都用课程本色：非本周整体淡化（灰底会被误认为本周有课，用户明确要求回退）
    val (bg, fg) = CourseColors.of(course.colorIndex)
    // 13 节特殊加课钳制到第 12 节区间显示（网格按 12 小节排版）
    val clampedStart = course.startSection.coerceIn(1, SectionMap.TOTAL_SMALL_SECTIONS)
    val span = CourseCardLayout.span(course)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(androidx.compose.ui.Alignment.TopCenter)
            .coursePosition(clampedStart, span)
            .padding(1.dp),
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(if (active) 1f else 0.3f)
                .clickable(onClick = onClick),
        ) {
            CourseCardText(course, fg, fontScale, isNext)
        }
        // 下一节课图钉徽标：右上角圆形叠标，不占卡片内文字行高
        if (isNext) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                shadowElevation = 2.dp,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(2.dp),
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "下一节课",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(2.dp).size(11.dp),
                )
            }
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
                    val (bg, fg) = CourseColors.of(course.colorIndex)
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
