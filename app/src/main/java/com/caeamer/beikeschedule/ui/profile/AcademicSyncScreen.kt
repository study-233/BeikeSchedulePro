package com.caeamer.beikeschedule.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.import.*
import com.caeamer.beikeschedule.ui.common.*
import com.caeamer.beikeschedule.ui.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AcademicSyncScreen(onBack: () -> Unit, onManualImport: () -> Unit,
                       session: AcademicSessionViewModel = viewModel(), settings: SettingsViewModel = viewModel()) {
    val state by session.state.collectAsStateWithLifecycle()
    val times by session.syncTimes.collectAsStateWithLifecycle(emptyMap())
    val student by settings.studentProfile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = LocalPageActive.current, onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        PageHeader("账号与数据", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (student.isLoggedIn) student.xm.ifBlank { "教务数据" } else "连接教务系统", style = MaterialTheme.typography.titleLarge)
            Text("一次同步课表、成绩、GPA、考试、学籍、学业进度与公告。优先复用登录会话，失效时自动进入统一身份认证。",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = session::startSync, enabled = !state.active && !state.clearing, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.needsBrowser) state.browserPhase.label else if (state.active) "正在同步…" else if (student.isLoggedIn) "一键同步" else "登录并同步")
            }
            if (AcademicTask.entries.any { state[it]?.phase == AcademicPhase.FAILED }) {
                OutlinedButton(onClick = session::retryFailed, enabled = !state.clearing) { Text("只重试失败项") }
            }
            AcademicTask.entries.forEach { task ->
                val request = state[task]
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(task.title, style = MaterialTheme.typography.titleSmall)
                            Text(when (request?.phase) {
                                AcademicPhase.WAITING -> state.browserPhase.label
                                AcademicPhase.RUNNING -> "正在获取"
                                AcademicPhase.REVIEW -> "等待选择课表目标"
                                AcademicPhase.SAVING -> "正在保存"
                                AcademicPhase.SUCCESS -> "已更新"
                                AcademicPhase.FAILED -> "更新失败"
                                AcademicPhase.CANCELLED -> "已取消"
                                null -> if ((times[task.name] ?: 0L) > 0) "已缓存" else "尚未同步"
                            }, style = MaterialTheme.typography.labelMedium)
                        }
                        Text("最近成功：${syncTimeLabel(times[task.name] ?: 0L)}", style = MaterialTheme.typography.bodySmall)
                        request?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (request?.phase == AcademicPhase.FAILED) TextButton(onClick = { session.retry(task) }, enabled = !state.clearing) { Text("重试${task.title}") }
                    }
                }
            }
            OutlinedButton(onClick = onManualImport, enabled = !state.active && !state.clearing, modifier = Modifier.fillMaxWidth()) {
                Text("手动选择课表导入目标")
            }
            SettingsGroup("本地缓存与登录") {
                SettingsRow("清除成绩缓存", "成绩、GPA、教务考试与学业进度；保留手动考试", { if (!state.clearing) confirmation = "grades" }, destructive = true)
                SettingsRow("清除公告缓存", "下次同步时重新获取公告", { if (!state.clearing) confirmation = "notices" }, destructive = true)
                SettingsRow("退出教务登录", "保留本地课表与缓存", { if (!state.clearing) confirmation = "logout" }, destructive = true)
            }
            if (state.clearing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.maintenanceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(when (action) { "grades" -> "清除成绩缓存"; "notices" -> "清除公告缓存"; else -> "退出教务登录" }) },
            text = { Text(when (action) {
                "grades" -> "删除成绩、GPA、教务考试与学业进度，并取消教务考试提醒。保留手动考试及其提醒、学籍和全部课表。"
                "notices" -> "删除本机公告列表与分页缓存，取消正在进行的公告获取。"
                else -> "清除教务登录会话并取消待处理同步。本地课表、成绩、考试和公告保留。"
            }) },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    "grades" -> session.clearGradesCache()
                    "notices" -> session.clearNoticeCache()
                    else -> { session.cancelForLogout(); clearJwSession(context) }
                }
                confirmation = null
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}

internal fun syncTimeLabel(time: Long): String = if (time <= 0) "暂无记录" else
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
