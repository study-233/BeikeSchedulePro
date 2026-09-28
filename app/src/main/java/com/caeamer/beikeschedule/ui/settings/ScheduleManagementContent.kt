package com.caeamer.beikeschedule.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caeamer.beikeschedule.data.local.ScheduleEntity
import com.caeamer.beikeschedule.model.ScheduleNames
import com.caeamer.beikeschedule.ui.schedule.ScheduleViewModel

@Composable
internal fun ScheduleManagementContent(viewModel: ScheduleViewModel) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val current by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.managing.collectAsStateWithLifecycle()
    val error by viewModel.settingsError.collectAsStateWithLifecycle()
    ScheduleManagementList(schedules, current.scheduleId, busy, error,
        onSwitch = { viewModel.switchSchedule(it) },
        onSaveName = { id, name, done ->
            if (id == null) viewModel.createSchedule(name, done) else viewModel.renameSchedule(id, name, done)
        },
        onClear = viewModel::clearSchedule, onDelete = viewModel::deleteSchedule,
        onClearError = viewModel::clearSettingsError)
}

@Composable
internal fun ScheduleManagementList(
    schedules: List<ScheduleEntity>, activeId: Long, busy: Boolean, error: String?,
    onSwitch: (Long) -> Unit,
    onSaveName: (Long?, String, () -> Unit) -> Unit,
    onClear: (Long, () -> Unit) -> Unit,
    onDelete: (Long, () -> Unit) -> Unit,
    onClearError: () -> Unit = {},
) {
    // 0 代表新建，null 代表对话框关闭；课表 ID 与输入均可跨 Activity 重建恢复。
    var namingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var actionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var actionName by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(onClick = {
                onClearError()
                name = ScheduleNames.available("新课表", schedules.map { it.name })
                namingId = 0
            }, enabled = !busy) { Text("新建课表") }
            Text("切换后，上课提醒和桌面小组件将使用当前课表。", style = MaterialTheme.typography.bodySmall)
        }
        items(schedules, key = { it.id }) { schedule ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(schedule.name, style = MaterialTheme.typography.titleMedium)
                    Text(schedule.semesterName.ifBlank { "尚未设置学期名称" }, style = MaterialTheme.typography.bodySmall)
                    if (schedule.id == activeId) Text("当前使用", color = MaterialTheme.colorScheme.primary)
                    Row {
                        TextButton(onClick = { onSwitch(schedule.id) }, enabled = !busy && schedule.id != activeId) { Text("切换") }
                        TextButton(onClick = { onClearError(); namingId = schedule.id; name = schedule.name }, enabled = !busy) { Text("重命名") }
                    }
                    Row {
                        TextButton(onClick = {
                            onClearError(); actionId = schedule.id; actionName = schedule.name; deleting = false
                        }, enabled = !busy) { Text("清空课程", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = {
                            onClearError(); actionId = schedule.id; actionName = schedule.name; deleting = true
                        }, enabled = !busy) { Text("删除课表", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    namingId?.let { id ->
        val validation = runCatching { ScheduleNames.validate(name, schedules.filter { it.id != id }.map { it.name }) }.exceptionOrNull()?.message
        val missing = id != 0L && schedules.none { it.id == id }
        AlertDialog(onDismissRequest = { if (!busy) namingId = null },
            title = { Text(if (id == 0L) "新建课表" else "重命名课表") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it; onClearError() }, singleLine = true, enabled = !busy,
                        label = { Text("课表名称") }, isError = validation != null || missing)
                    (if (missing) "课表已删除，请重新选择" else validation ?: error)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSaveName(id.takeIf { it != 0L }, name.trim()) { namingId = null } },
                    enabled = !busy && validation == null && !missing) { Text(if (busy) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(onClick = { namingId = null }, enabled = !busy) { Text("取消") } })
    }
    actionId?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) actionId = null },
            title = { Text(if (deleting) "删除课表“$actionName”？" else "清空“$actionName”的课程？") },
            text = {
                Column {
                    Text("将永久删除其中的全部课程，包括隐藏课程、手动课程和无固定时间课程。" +
                        if (deleting) "课表名称、学期校历和节次时间也会删除。删除最后一份课表后会创建空白默认课表。"
                        else "课表名称、学期校历和节次时间保留。")
                    Text("成绩与考试数据不受影响，此操作无法撤销。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val done = { actionId = null }
                if (deleting) onDelete(id, done) else onClear(id, done)
            }, enabled = !busy) { Text(if (deleting) "删除" else "清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { actionId = null }, enabled = !busy) { Text("取消") } })
    }
}
