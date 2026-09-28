package com.caeamer.beikeschedule.import

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.caeamer.beikeschedule.data.local.ScheduleEntity
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.ScheduleNames
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** 教务导入页：WebView 登录 → 自动注入脚本抓取 → 预览确认入库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onDone: () -> Unit,
    onRetry: () -> Unit,
    viewModel: ImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    LaunchedEffect(schedules, state is ImportUiState.Preview) { viewModel.initializeName() }
    val academicSession: AcademicSessionViewModel = viewModel()
    val syncState by academicSession.state.collectAsState()

    // 返回退出导入；预览中的“重新抓取”是显式的新请求，成绩同步可继续完成。
    // 本页是 Launcher Activity 里的一个 composable 分支（不是独立 Activity），
    // 不拦返回键的话系统返回会直接 finish 掉 Activity，登录会话与预览一起丢。
    //
    // Committing/Done 态必须吞掉返回：写库中退出会双触发退出流程；
    // Done 态则由下面的 LaunchedEffect 统一负责退出，返回键不该插队。
    BackHandler {
        when (state) {
            is ImportUiState.Preview, is ImportUiState.Error -> onDone()
            is ImportUiState.Committing, is ImportUiState.Done -> Unit
            else -> onDone()
        }
    }

    // 写库完成 → 退出导入流程。
    // 用状态驱动而不是在 confirmImport 里回调 onDone():写库期间 Activity 若被重建
    // （转屏/深色切换/低内存回收），旧组合的 onDone 写的是已被丢弃的 showImport state，
    // 导航会丢；观察状态的 effect 在新组合里会重新触发，一定能退出。
    LaunchedEffect(state) {
        if (state is ImportUiState.Done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从教务系统导入") },
                navigationIcon = {
                    // 与 BackHandler 同理：写库中（含其转瞬终态 Done）退出会打断流程
                    IconButton(
                        onClick = onDone,
                        enabled = state !is ImportUiState.Committing && state !is ImportUiState.Done,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AcademicSyncStatus(syncState.gradesRequest, { academicSession.retry(AcademicTask.GRADES) }, syncState.browserPhase.label)
            Box(Modifier.weight(1f)) {
                when (val s = state) {
                    is ImportUiState.Browsing, is ImportUiState.Fetching -> {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text("登录后自动获取课表、成绩与考试")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }

                    is ImportUiState.Preview -> ImportPreview(
                        preview = s,
                        schedules = schedules,
                        onConfirm = viewModel::confirmImport,
                        onTarget = viewModel::selectTarget,
                        onName = viewModel::setName,
                        onBack = onRetry,
                    )

                    // 写库中：只显示进度，不提供任何可重入的入口（确认按钮已不可达）。
                    // 宿主继续承载未完成的成绩请求，不阻塞课表保存。
                    // Done 与 Committing 同屏：Done 只是转瞬态，上面的 LaunchedEffect 随即退出流程。
                    is ImportUiState.Committing, is ImportUiState.Done -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (s is ImportUiState.Done) "课表已更新" else "正在保存课表…",
                            textAlign = TextAlign.Center,
                        )
                    }

                    is ImportUiState.Error -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("导入失败", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onRetry) { Text("返回重试") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportPreview(
    preview: ImportUiState.Preview,
    schedules: List<ScheduleEntity>,
    onConfirm: (Boolean) -> Unit,
    onTarget: (Boolean, Long?) -> Unit,
    onName: (String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmDifferent by rememberSaveable { mutableStateOf(false) }
    val target = schedules.firstOrNull { it.id == preview.targetId }
    val nameError = if (preview.createNew) runCatching {
        ScheduleNames.validate(preview.newName, schedules.map { it.name })
    }.exceptionOrNull()?.message else null
    val valid = schedules.isNotEmpty() && preview.nameInitialized &&
        if (preview.createNew) nameError == null else target != null
    val different = target?.let { ScheduleRepository.isDifferentSemester(it, preview.semesterConfig()) } == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("抓取成功", style = MaterialTheme.typography.headlineSmall)
        Text("学期：${preview.semesterName}")
        Text("课程：${preview.scheduledCount} 门（无固定时间课程 ${preview.unscheduledCount} 门）")
        Text("开学日期：${preview.firstMonday.ifBlank { "未识别，请导入后在设置中填写" }}")
        Text(if (preview.weekMondays.isNotEmpty()) "已获取 ${preview.weekMondays.size} 周官方校历"
            else "未获取官方校历，将按开学日期逐周推算")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(preview.createNew, { confirmDifferent = false; onTarget(true, null) }, label = { Text("新建课表") })
            FilterChip(!preview.createNew, { confirmDifferent = false; onTarget(false, null) }, label = { Text("更新已有课表") })
        }
        if (preview.createNew) {
            OutlinedTextField(preview.newName, onName, label = { Text("课表名称") },
                singleLine = true, isError = nameError != null, modifier = Modifier.fillMaxWidth())
            nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("导入为一份新课表，已有课表保留。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("选择要更新的课表")
            schedules.forEach { schedule ->
                OutlinedButton(onClick = { confirmDifferent = false; onTarget(false, schedule.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (schedule.id == preview.targetId) "✓ " else "") + schedule.name +
                        schedule.semesterName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                }
            }
            if (preview.targetId != null && target == null) Text("目标课表已删除，请重新选择", color = MaterialTheme.colorScheme.error)
            Text("仅覆盖所选课表的教务课程、校历和节次时间，保留手动课程及教务课程隐藏状态，清除其中的示例课程。对教务课程的修改会被覆盖。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (schedules.isEmpty()) Text("正在读取课表列表…")
        preview.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("重新抓取") }
            Button(onClick = { if (!preview.createNew && different) confirmDifferent = true else onConfirm(false) },
                enabled = valid) { Text("确认导入") }
        }
    }
    if (confirmDifferent && target != null) AlertDialog(
        onDismissRequest = { confirmDifferent = false }, title = { Text("跨学期更新课表？") },
        text = { Text("“${target.name}”的学期为“${target.semesterName}”，将更新为“${preview.semesterName}”。原有教务课程将被替换，手动课程保留。") },
        confirmButton = { TextButton(onClick = { confirmDifferent = false; onConfirm(true) }) { Text("确认更新") } },
        dismissButton = { TextButton(onClick = { confirmDifferent = false }) { Text("取消") } },
    )
}
