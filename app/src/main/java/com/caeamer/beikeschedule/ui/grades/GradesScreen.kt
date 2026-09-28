package com.caeamer.beikeschedule.ui.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.ui.freeroom.FreeRoomScreen
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.ui.common.rememberNow
import com.caeamer.beikeschedule.model.timeLabel
import com.caeamer.beikeschedule.model.hasEnded
import com.caeamer.beikeschedule.model.CampusSection
import com.caeamer.beikeschedule.ui.common.AppSegments
import com.caeamer.beikeschedule.ui.common.PageHeader
import com.caeamer.beikeschedule.ui.common.AppMotion
import com.caeamer.beikeschedule.ui.settings.SettingsViewModel
import com.caeamer.beikeschedule.ui.freeroom.FreeRoomViewModel
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale 无关的两位小数（DecimalFormat 跟随系统 locale，部分地区会输出 "91,50"）。 */
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

/** 教务 Tab：成绩/考试分段 + 加权/GPA 双模式 + 学期筛选 + 课程勾选 + 学分进度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(viewModel: GradesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val examEditor by viewModel.examEditor.collectAsStateWithLifecycle()
    val examMessage by viewModel.examMessage.collectAsStateWithLifecycle()
    val examReminderRetry by viewModel.examReminderRetry.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(examMessage) {
        examMessage?.let {
            val result = snackbar.showSnackbar(it, actionLabel = if (examReminderRetry) "重试提醒" else null)
            viewModel.dismissExamMessage()
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.retryExamReminders()
        }
    }
    val academicSession: com.caeamer.beikeschedule.import.AcademicSessionViewModel = viewModel()
    val syncState by academicSession.state.collectAsStateWithLifecycle()
    val sectionHolder = rememberSaveableStateHolder()
    val rooms: FreeRoomViewModel? = if (state.section == CampusSection.FREE_ROOM) viewModel() else null
    val context = androidx.compose.ui.platform.LocalContext.current
    var moreExpanded by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var showRefreshConfirm by remember { mutableStateOf(false) }
    var detailGrade by remember { mutableStateOf<GradeEntity?>(null) }

    Scaffold(
        // 外层 Scaffold 不消费系统栏 inset（见 MainActivity），这里也不消费：
        // 内层默认会把导航栏高度再算一遍，列表末尾多出一段空白（与 ProfileScreen 口径一致）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            PageHeader("校园") {
                if (state.section == CampusSection.EXAMS) TextButton(
                    onClick = { viewModel.openExamEditor() }, enabled = !examEditor.saving,
                ) { Text("添加考试") }
                IconButton(enabled = (state.section == CampusSection.FREE_ROOM || syncState.gradesRequest?.active != true) && state.section != null, onClick = {
                    if (state.section == CampusSection.FREE_ROOM) rooms?.refresh() else showRefreshConfirm = true
                }) { Icon(Icons.Default.Refresh, if (state.section == CampusSection.FREE_ROOM) "刷新空教室" else "刷新成绩与考试") }
                Box {
                    IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(moreExpanded, { moreExpanded = false }) {
                        listOf("评教系统" to SettingsViewModel.PINGJIAO_URL, "大创 / SRTP" to SettingsViewModel.SRTP_URL).forEach { (label, url) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                moreExpanded = false
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                    .onFailure { android.widget.Toast.makeText(context, "未找到可打开网页的应用", android.widget.Toast.LENGTH_SHORT).show() }
                            })
                        }
                        DropdownMenuItem(text = { Text("数据来源说明") }, onClick = { moreExpanded = false; showSource = true })
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showRefreshConfirm) {
                AlertDialog(
                    onDismissRequest = { showRefreshConfirm = false },
                    title = { Text("重新抓取") },
                    text = { Text("将进入教务系统重新抓取成绩、GPA、考试安排与学业进度，当前本地数据会保留到抓取成功。是否继续？") },
                    confirmButton = {
                        TextButton(onClick = {
                            showRefreshConfirm = false
                            academicSession.startGrades()
                        }) { Text("继续") }
                    },
                    dismissButton = { TextButton(onClick = { showRefreshConfirm = false }) { Text("取消") } },
                )
            }
            // 栏目内容保留原生状态；唯一教务 WebView 由 Activity 宿主承载。
            Column(Modifier.fillMaxSize()) {
                SectionTabs(section = state.section, onSelect = viewModel::setSection)

                if (state.section == CampusSection.SCORES || state.section == CampusSection.EXAMS) {
                    com.caeamer.beikeschedule.import.AcademicSyncStatus(syncState.gradesRequest, academicSession::startGrades)
                }
                sectionHolder.SaveableStateProvider(state.section?.id ?: "loading") {
                when {
                    // 分段偏好还没从 DataStore 读到（冷启动最初几帧）：只显示上面的分段行。
                    // 不能先用"无课教室"占位——那会立刻把 FreeRoomViewModel 创建出来并
                    // 发出 4 个外网请求，而用户上次停的可能是成绩段，这些请求白做。
                    state.section == null -> Unit

                    // 无课教室的数据来自校外平台，与教务会话无关，独立成页
                    CampusSection.FREE_ROOM == state.section -> rooms?.let { FreeRoomScreen(it) }

                    // 考试分段自带 error/未抓取态：此前不传 error，失败提示在考试段永远看不到，
                    // 而"从未抓取"也被说成"本学期暂无考试安排"
                    CampusSection.EXAMS == state.section -> ExamListContent(
                        exams = state.examsSorted,
                        onAdd = { viewModel.openExamEditor() },
                        onEdit = viewModel::openExamEditor,
                        editingEnabled = !examEditor.saving,
                        error = state.error,
                        fetchedAt = state.fetchedAt,
                        onFetch = academicSession::startGrades,
                        onDismissError = viewModel::dismissError,
                    )

                    state.grades.isEmpty() && state.exams.all { it.isManual } ->
                        NoGradesYet(error = state.error, onFetch = academicSession::startGrades)

                    else -> GradesContent(
                        state = state,
                        onModeChange = { viewModel.setScoreMode(it) },
                        onSchoolYearFilter = { viewModel.setSchoolYearFilter(it) },
                        onSemesterFilter = { viewModel.setSemesterFilter(it) },
                        onToggleCourse = { viewModel.toggleExcluded(it) },
                        onErrorDismiss = { viewModel.dismissError() },
                        onGradeClick = { detailGrade = it },
                        onToggleHideScores = { viewModel.toggleHideScores() },
                    )
                }
                }
            }
        }
    }

    examEditor.draft?.let { draft ->
        androidx.compose.runtime.key(draft.id) {
            ExamEditDialog(draft, examEditor.saving, examEditor.error, viewModel::updateExamDraft,
                viewModel::saveExam, viewModel::deleteExam, viewModel::closeExamEditor)
        }
    }

    if (showSource) AlertDialog(onDismissRequest = { showSource = false }, title = { Text("数据来源") },
        text = { Text("空教室数据来自贝壳教学平台，仅供实时查询参考；成绩、教务考试与学籍信息来自学校教务系统；手动考试由你录入，均保存在本机。") },
        confirmButton = { TextButton(onClick = { showSource = false }) { Text("关闭") } })

    detailGrade?.let { grade ->
        CourseGradeDetailSheet(grade = grade, hideScores = state.hideScores, onDismiss = { detailGrade = null })
    }
}

/**
 * 教务 Tab 的分段切换：空教室 | 成绩 | 考试。
 *
 * 必须在所有内容分支之上渲染（包括抓取 WebView 与空态），否则用户会失去切换能力。
 */
@Composable
private fun SectionTabs(section: CampusSection?, onSelect: (CampusSection) -> Unit) {
    AppSegments(CampusSection.entries.map { it to it.title }, section, onSelect)
}

@Composable
private fun NoGradesYet(error: String?, onFetch: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有成绩数据", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "登录教务系统即可自动获取成绩、GPA 与考试安排",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
        Button(onClick = onFetch) { Text("去获取") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradesContent(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onSchoolYearFilter: (String) -> Unit,
    onSemesterFilter: (String) -> Unit,
    onToggleCourse: (String) -> Unit,
    onErrorDismiss: () -> Unit,
    onGradeClick: (GradeEntity) -> Unit,
    onToggleHideScores: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
        item { ScoreCard(state, onModeChange, onToggleCourse, onToggleHideScores) }
        item(key = "grade_filters") {
            // 按学年或学期打开分组选择面板，沿用现有互斥筛选规则。
            if (state.semesters.isNotEmpty()) {
                GradeFilterField(
                    semesterLabel = state.semesterFilter.ifBlank { "全部学期" },
                    schoolYearLabel = state.schoolYearFilter.ifBlank { "全部学年" },
                    semesters = listOf("" to "全部学期") + state.semestersOfSchoolYear.map { it to it },
                    schoolYears = listOf("" to "全部学年") + state.schoolYears.map { it to it },
                    selectedSemester = state.semesterFilter,
                    selectedSchoolYear = state.schoolYearFilter,
                    onSemesterSelect = onSemesterFilter,
                    onSchoolYearSelect = onSchoolYearFilter,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

        }
        // 抓取失败的提示放在**列表最前**：此前它是最后一个 item，而 54 门课约 3000dp 高，
        // 不滚到底根本看不到，用户以为刷新成功了
        if (state.error != null) {
            item(key = "error_banner") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onErrorDismiss) { Text("知道了") }
                }
            }
        }
        if (state.grouped.isEmpty()) item(key = "no_filtered_grades") {
            Text("当前筛选下没有成绩", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.grouped.forEach { (semester, grades) ->
            item(key = "header_$semester") {
                val failed = state.failedBySemester[semester] ?: 0
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        semester,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (failed > 0) {
                        Text(
                            "$failed 门未通过",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // key 带上行 id：同课同学期两条同 bkcx 行（服务端是否会出现未确认）会撞 key，
            // 而 LazyColumn 的重复 key 是直接抛异常崩溃（无课教室那边修过同类问题）
            items(grades, key = { "${it.id}@${it.kcdm}@${it.bkcx}" }) { grade ->
                Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(AppMotion.STATE))) {
                    GradeRow(
                        grade = grade,
                        hideScores = state.hideScores,
                        coursePassed = grade.kcdm in state.passedKcdm,
                        onClick = { onGradeClick(grade) },
                    )
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        if (state.creditRows.isNotEmpty() || state.gradProgress != null) {
            item(key = "credit_progress") { CreditProgressCard(state) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 学分修读进度折叠卡：毕业总进度 + 分类别"要求（教务）vs 已完成（本地成绩汇总）"。 */
@Composable
private fun CreditProgressCard(state: GradesUiState) {
    // rememberSaveable：LazyColumn 的 item 滚出屏幕会被销毁，普通 remember 会把
    // 用户刚展开的卡片又收回（滑到底再滑回来就"自己合上了"）
    var expanded by rememberSaveable { mutableStateOf(false) }
    val progress = state.gradProgress

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(tween(AppMotion.STATE))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("学分修读进度", style = MaterialTheme.typography.titleSmall)
                    progress?.let {
                        Text(
                            "已修 ${fmt2(it.ywcxf)}/${fmt2(it.yqxf)} 学分 · 已过 ${it.ywcms}/${it.yqms} 门",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    // 箭头随展开状态变化：恒为右箭头看不出"点过之后会怎样"
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                progress?.let { p ->
                    ProgressRow(
                        label = "毕业总要求",
                        completed = p.ywcxf,
                        required = p.yqxf,
                        highlight = true,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 类别行可能较多，限高 + 可滚动
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(tween(AppMotion.STATE)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.creditRows.forEach { row ->
                        ProgressRow(
                            label = row.category.kclbmc +
                                if (row.category.kcxzmc.isNotBlank()) "（${row.category.kcxzmc}）" else "",
                            completed = row.completed,
                            required = row.category.yqxf,
                            transfer = row.category.yzhxf,
                            highlight = false,
                        )
                    }
                }
                Text(
                    "已完成学分按本地成绩单汇总，口径与教务网一致",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String, completed: Double, required: Double, transfer: Double = 0.0, highlight: Boolean) {
    val fraction = if (required > 0) (completed / required).toFloat().coerceIn(0f, 1f) else 0f
    val over = required > 0 && completed > required
    val barColor = when {
        over -> MaterialTheme.colorScheme.tertiary
        highlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${fmt2(completed)}/${fmt2(required)}" + if (transfer > 0) "（含转移 ${fmt2(transfer)}）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** 考试安排列表：按日期分组 + 倒计时徽章 + 座位号。 */
@Composable
internal fun ExamListContent(
    exams: List<ExamEntity>,
    onAdd: () -> Unit,
    onEdit: (ExamEntity) -> Unit,
    editingEnabled: Boolean,
    error: String?,
    fetchedAt: Long,
    onFetch: () -> Unit,
    onDismissError: () -> Unit,
) {
    // rememberNow：跨午夜后倒计时/"已结束"要跟着变（组合期读 now() 不会刷新）
    val now = rememberNow()
    val today = now.toLocalDate()
    var showPastExams by rememberSaveable { mutableStateOf(false) }
    if (exams.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // "从未抓取"与"抓过但本学期没排考"必须区分：前者应引导去抓取，
            // 后者才是真的"暂无"。此前一律说"本学期暂无考试安排"，用户会信以为真。
            val neverFetched = fetchedAt == 0L
            Text(
                if (neverFetched) "还没有考试数据" else "本学期暂无考试安排",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (neverFetched) "可手动添加考试，或登录教务系统获取安排"
                else "可手动添加考试，教务网排考后也可刷新获取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, enabled = editingEnabled) { Text("添加考试") }
            if (neverFetched) TextButton(onClick = onFetch) { Text("从教务获取") }
        }
        return
    }
    val future = exams.filterNot { it.hasEnded(now) }
    val past = exams.filter { it.hasEnded(now) }
    val grouped = future.groupBy { it.ksrq.ifBlank { "时间待定" } }
        .toSortedMap(compareBy { key -> runCatching { LocalDate.parse(key) }.getOrNull() ?: LocalDate.MAX })

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
        // 抓取失败的提示必须在列表**顶部**：此前错误行只挂在成绩页列表末尾，
        // 而考试段根本没有 error 入口，用户以为刷新成功了。
        if (error != null) {
            item(key = "exam_error") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text("知道了") }
                }
            }
        }
        grouped.forEach { (date, dayExams) ->
            item(key = "exam_header_$date") {
                val countdown = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(date)) }.getOrNull()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        date,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (countdown != null && countdown >= 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                if (countdown == 0L) "今天" else "D-$countdown",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            items(dayExams, key = { it.id }) { exam ->
                ExamRow(exam, passed = countdownDays(exam, today) < 0, onEdit = onEdit, editingEnabled = editingEnabled)
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (past.isNotEmpty()) {
            item(key = "past_exams") {
                TextButton(onClick = { showPastExams = !showPastExams }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (showPastExams) "收起已结束考试" else "已结束考试（${past.size}）")
                }
            }
            if (showPastExams) items(past, key = { "past_${it.id}" }) { ExamRow(it, passed = true, onEdit = onEdit, editingEnabled = editingEnabled) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun countdownDays(exam: ExamEntity, today: LocalDate): Long =
    runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(exam.ksrq)) }.getOrDefault(Long.MAX_VALUE)

@Composable
private fun ExamRow(exam: ExamEntity, passed: Boolean, onEdit: (ExamEntity) -> Unit, editingEnabled: Boolean) {
    // 已结束的考试淡化：用 onSurfaceVariant 而不是整体 alpha 0.45
    // （alpha 会把正文对比度压到约 2.8:1，低于 WCAG AA 小字号 4.5:1）
    val textColor = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    Row(
        Modifier.fillMaxWidth()
            .then(if (exam.isManual) Modifier.clickable(enabled = editingEnabled,
                onClickLabel = "编辑考试", onClick = { onEdit(exam) }) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                exam.kcmc,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (exam.isManual) Text("手动", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            val timeText = exam.timeLabel()
            Text(
                listOfNotNull(if (passed) exam.ksrq else null, timeText, exam.cdmc.ifBlank { null }, exam.kslx.ifBlank { null })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (exam.zwh.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    exam.zwh,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 单科成绩详情弹层：排名/考核方式/性质/学分/正考补考/学院/学期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseGradeDetailSheet(grade: GradeEntity, hideScores: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(grade.kcmc, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (hideScores) "***" else grade.zzcj,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (grade.isFailed && !hideScores) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "分",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (grade.pm.isNotBlank() && grade.zrs.isNotBlank()) {
                // 排名与总人数属隐私信息，隐藏成绩时一并掩码
                DetailRow("课程排名", if (hideScores) "***" else "${grade.pm} / ${grade.zrs}")
            }
            if (grade.khfs.isNotBlank()) DetailRow("考核方式", grade.khfs)
            DetailRow("课程性质", grade.kcxz)
            if (grade.kclb.isNotBlank()) DetailRow("课程类别", grade.kclb)
            DetailRow("学分", if (hideScores) "***" else "${fmt2(grade.xf)}")
            if (grade.bkcx.isNotBlank()) DetailRow("考试类型", grade.bkcx)
            if (grade.yxmc.isNotBlank()) DetailRow("开课学院", grade.yxmc)
            DetailRow("学期", grade.xnxqmc)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** GPA/加权成绩卡片：模式切换 + 学期筛选 + 课程勾选 + 隐私开关。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScoreCard(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onToggleCourse: (String) -> Unit,
    onToggleHideScores: () -> Unit,
) {
    // 同上：用 rememberSaveable 防止滚出屏幕后被重置
    var showCourseSelector by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(tween(AppMotion.STATE))) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.scoreMode == ScoreMode.WEIGHTED) "加权成绩" else "累计 GPA",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                // 模式切换：两个并列选项用 SegmentedButton（Switch 的开/关语义不贴切）
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.scoreMode == ScoreMode.WEIGHTED,
                        onClick = { onModeChange(ScoreMode.WEIGHTED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("加权", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(
                        selected = state.scoreMode == ScoreMode.GPA,
                        onClick = { onModeChange(ScoreMode.GPA) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("GPA", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // 主数值（隐藏时显示 ***；右侧小眼睛切换，默认隐藏保护隐私）
            Row(verticalAlignment = Alignment.Bottom) {
                val value = when {
                    state.hideScores -> "***"
                    state.scoreMode == ScoreMode.WEIGHTED -> state.weightedResult?.let { fmt2(it.score) } ?: "—"
                    else -> state.localGpa?.let { fmt2(it.gpa) } ?: "—"
                }
                Text(
                    value,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.scoreMode == ScoreMode.WEIGHTED) "分" else "GPA",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleHideScores, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(
                        if (state.hideScores) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (state.hideScores) "显示成绩" else "隐藏成绩",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 副信息
            val subtitleText = if (state.scoreMode == ScoreMode.WEIGHTED) {
                val r = state.weightedResult
                if (r != null) {
                    // 门数与学分数同样属于隐私信息，隐藏成绩时一并掩码
                    // （此前掩码只加在 GPA 分支，加权分支仍在明文显示"纳入 N 门 · 共 X 学分"）
                    if (state.hideScores) {
                        "只看必修课 · 已隐藏明细"
                    } else {
                        // "已排除 N 门"用当前筛选下实际可见的排除数，而不是全局 excludedKcdm.size
                        // （在全部学期排除 4 门后筛到某学期，可能只有 1 门在该筛选内）
                        val excludedShown = state.weightEligible.count { !it.second }
                        "纳入 ${r.courseCount} 门必修 · 共 ${r.totalCredits} 学分" +
                            if (excludedShown > 0) "（已排除 $excludedShown 门）" else ""
                    }
                } else "没有可计算的必修课数字成绩"
            } else {
                // GPA 为本地 4.0 制计算；教务网排名口径是平均学分绩，仍展示作参考。
                // 排名与学分数同样属于隐私信息，隐藏成绩时一并掩码（此前只掩码了分数本身，
                // 排名与"纳入 N 门 · 共 X 学分"仍会泄露）。
                val g = state.localGpa
                val rankText = state.gpa
                    ?.takeIf { it.hasRank && !state.hideScores }
                    ?.let { "专业排名 ${it.rank}/${it.totalStudents}（平均学分绩口径） · " }
                    ?: ""
                if (g != null) {
                    val detail = if (state.hideScores) "" else "满绩 4.0 · 纳入 ${g.courseCount} 门 · 共 ${fmt2(g.credits)} 学分"
                    rankText + detail
                } else "没有可计算的数字成绩"
            }
            Text(
                subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.fetchedAt > 0) {
                    "更新于 " + Instant.ofEpochMilli(state.fetchedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )

            // 加权模式：课程勾选入口（行样式，与"我的"页列表行呼应，替代居中大按钮）
            if (state.scoreMode == ScoreMode.WEIGHTED && state.weightEligible.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { showCourseSelector = !showCourseSelector }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.hideScores) "自定义纳入计算的课程" else "自定义纳入计算的课程（${state.weightEligible.count { it.second }}/${state.weightEligible.size}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (showCourseSelector) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }

            }
        }
    }
    if (showCourseSelector) {
        GradeCourseSelectorSheet(
            courses = state.weightEligible,
            hideScores = state.hideScores,
            onToggleCourse = onToggleCourse,
            onDismiss = { showCourseSelector = false },
        )
    }
}

/** 成绩单行：分数按 hideScores 掩码；挂科标红按"这门课最终是否通过"判定。 */
@Composable
private fun GradeRow(
    grade: GradeEntity,
    hideScores: Boolean,
    /** 该课最终是否已通过（补考/重修通过后为正考行也不该标红）。 */
    coursePassed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grade.kcmc, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            // 排名与学分同样属于隐私信息：详情弹层（课程排名/学分）、GPA 卡片、勾选列表
            // 都随小眼睛掩码，唯独这里此前漏了 —— 点了隐藏后分数变成 ***，
            // 每行却仍然明写"排名 12/120 · 4.0学分"。
            val rankText = if (!hideScores && grade.pm.isNotBlank() && grade.zrs.isNotBlank()) {
                " · 排名 ${grade.pm}/${grade.zrs}"
            } else {
                ""
            }
            val creditText = if (hideScores) "" else " · ${grade.xf}学分"
            Text(
                listOf(grade.kcxz, grade.kclb, if (grade.bkcx.isNotBlank() && grade.bkcx != "正考") grade.bkcx else null)
                    .filterNotNull().filter { it.isNotBlank() }
                    .joinToString(" · ") + creditText + rankText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (hideScores) "***" else grade.zzcj,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (grade.isFailed && !coursePassed && !hideScores) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
