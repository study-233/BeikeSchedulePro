package com.caeamer.beikeschedule.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.SemesterDraft
import com.caeamer.beikeschedule.model.SettingsPage
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.ui.common.*
import com.caeamer.beikeschedule.ui.schedule.DropdownField
import com.caeamer.beikeschedule.ui.schedule.ScheduleViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 两个入口都由宿主持有同一个 ScheduleViewModel 和外观 ViewModel。 */
@Composable
fun ScheduleSettingsScreen(page: SettingsPage, viewModel: ScheduleViewModel,
                           appearanceViewModel: ScheduleAppearanceViewModel, onBack: () -> Unit,
                           onNavigate: (SettingsPage) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.settingsError.collectAsStateWithLifecycle()
    val hideWeekend by viewModel.hideWeekend.collectAsStateWithLifecycle()
    val hideInactive by viewModel.hideInactiveCourses.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // 学期编辑自行接管返回并处理草稿。
    BackHandler(enabled = LocalPageActive.current && (page != SettingsPage.SEMESTER || !state.loaded), onBack = onBack)
    if (page == SettingsPage.SEMESTER && state.loaded) {
        val saving by viewModel.savingSemester.collectAsStateWithLifecycle()
        val editingScheduleId = rememberSaveable { state.scheduleId }
        val schedules by viewModel.schedules.collectAsStateWithLifecycle()
        if (schedules.isNotEmpty() && schedules.none { it.id == editingScheduleId }) {
            Column {
                PageHeader("学期与校历", onBack)
                Text("课表已删除，请返回后重新选择。", Modifier.padding(24.dp))
            }
            BackHandler(enabled = LocalPageActive.current, onBack = onBack)
            return
        }
        val editingSemester = schedules.firstOrNull { it.id == editingScheduleId }?.semester() ?: state.semester
        SemesterEditor(editingSemester, saving, error,
            adjustmentContent = { CalendarAdjustmentSettings(viewModel, editingSemester) },
            onSave = { draft -> viewModel.saveSemesterDraft(editingScheduleId, draft, onBack) },
            onBack = { viewModel.clearSettingsError(); onBack() })
        return
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(page.title, onBack)
        error?.let {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::clearSettingsError) { Text("知道了") }
            }
        }
        if (!state.loaded) CircularProgressIndicator(Modifier.padding(24.dp))
        else when (page) {
            SettingsPage.MANAGE -> ScheduleManagementContent(viewModel)
            SettingsPage.DISPLAY -> ScheduleAppearanceContent(
                hideWeekend = hideWeekend,
                viewModel = appearanceViewModel,
                displayControls = {
                    SettingsRow("隐藏周末", "显示五天课表", checked = hideWeekend,
                        onClick = { viewModel.setHideWeekend(!hideWeekend) })
                    SettingsRow("隐藏非本周课程", "关闭时以较淡颜色显示", checked = hideInactive,
                        onClick = { viewModel.setHideInactiveCourses(!hideInactive) })
                    HorizontalDivider()
                },
            )
            SettingsPage.REMINDER -> ReminderSettings(viewModel)
            SettingsPage.HIDDEN -> Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.hiddenCourses.isEmpty()) Text("没有隐藏的课程", Modifier.padding(24.dp))
                SettingsGroup("恢复后重新显示，并按提醒设置安排上课提醒") {
                    state.hiddenCourses.distinctBy { it.name to it.source }.forEach { course ->
                        SettingsRow(course.name, "点击恢复", onClick = {
                            val ids = state.courses.filter { it.name == course.name && it.source == course.source }.map { it.id }
                            viewModel.setCoursesHidden(state.scheduleId, ids, false)
                        })
                    }
                }
            }
            SettingsPage.SCHEDULE -> Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsGroup("课表") {
                    SettingsRow("课表管理", state.scheduleName, { onNavigate(SettingsPage.MANAGE) })
                    SettingsRow("学期与校历", "学期名称、备用开学日期与总周数", { onNavigate(SettingsPage.SEMESTER) })
                    SettingsRow("课表显示", "字号、背景与课程显示", { onNavigate(SettingsPage.DISPLAY) })
                    AddScheduleWidgetRow()
                    SettingsRow("上课提醒", "提前时间与权限状态", { onNavigate(SettingsPage.REMINDER) })
                    SettingsRow("隐藏课程", "恢复已隐藏的课程", { onNavigate(SettingsPage.HIDDEN) })
                }
                if (state.hasSample) SettingsGroup("示例数据") {
                    SettingsRow("清除示例课表", onClick = { confirmClear = true }, destructive = true)
                }
            }
            else -> Unit
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("清除示例课表") },
        text = { Text("只删除示例课程，保留导入和手动添加的课程。") },
        confirmButton = { TextButton(onClick = { viewModel.clearSampleData(); confirmClear = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SemesterEditor(current: SettingsStore.SemesterConfig, saving: Boolean, error: String?,
                            onSave: (SemesterDraft) -> Unit, onBack: () -> Unit,
                            adjustmentContent: @Composable () -> Unit = {}) {
    val originalName by rememberSaveable { mutableStateOf(current.name) }
    val originalDate by rememberSaveable { mutableStateOf(current.firstMonday) }
    val originalWeeks by rememberSaveable { mutableIntStateOf(current.totalWeeks) }
    var name by rememberSaveable { mutableStateOf(originalName) }
    var firstMonday by rememberSaveable { mutableStateOf(originalDate) }
    var weeks by rememberSaveable { mutableIntStateOf(originalWeeks) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val dirty = name != originalName || firstMonday != originalDate || weeks != originalWeeks
    val back = { if (!saving) { if (dirty) confirmDiscard = true else onBack() } }
    BackHandler(enabled = LocalPageActive.current, onBack = back)
    Column(Modifier.fillMaxSize()) {
        PageHeader("学期与校历", back) {
            TextButton(onClick = { onBack() }, enabled = !saving) { Text("取消") }
            TextButton(onClick = { onSave(SemesterDraft(name, firstMonday, weeks)) }, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("学期名") }, singleLine = true,
                enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickDate = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (firstMonday.isBlank()) "选择备用开学日期（第 1 周周一）" else "备用开学日期：$firstMonday")
            }
            DropdownField("总周数", (listOf(16, 18, 20, 22, 25) + weeks + current.totalWeeks).distinct().sorted().map { it to "${it}周" },
                weeks, { if (!saving) weeks = it }, Modifier.fillMaxWidth())
            Text(if (current.weekMondays.isEmpty()) "尚未导入官方校历，按备用开学日期逐周推算。"
                else "已导入 ${current.weekMondays.size} 周官方校历；日期和教学周以官方校历为准，开学日期仅作备用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (weeks < current.weekMondays.size) Text("保存时总周数将校正为至少 ${current.weekMondays.size} 周。",
                color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            adjustmentContent()
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃修改？") }, text = { Text("未保存的学期修改将被丢弃。") },
        confirmButton = { TextButton(onClick = { onBack() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    if (pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            LocalDate.parse(firstMonday).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { firstMonday = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                pickDate = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("取消") } }) { DatePicker(picker) }
    }
}

@Composable
private fun CalendarAdjustmentSettings(viewModel: ScheduleViewModel, semester: SettingsStore.SemesterConfig) {
    val cache by viewModel.adjustmentStatus.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshingAdjustments.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val config = remember(cache?.body) {
        cache?.body?.takeIf { it.isNotBlank() }?.let {
            runCatching { com.caeamer.beikeschedule.model.CalendarAdjustmentCodec.parse(it) }.getOrNull()
        }
    }
    val applied = remember(config, semester) { com.caeamer.beikeschedule.model.DateCourseResolver.applicable(semester, config) }
    val rules = config?.semesters?.firstOrNull { it.xn == semester.xn && it.xq == semester.xq }
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起调休安排" else "查看调休安排") }
    if (expanded) {
        Text("由项目维护者提供 · ${config?.let { "版本 ${it.revision}" } ?: "尚无配置"}", style = MaterialTheme.typography.bodyMedium)
        Text(cache?.status ?: "尚未获取调休配置", style = MaterialTheme.typography.bodySmall)
        cache?.lastCheckedAt?.takeIf { it > 0 }?.let {
            val time = Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            Text("最近检查：$time", style = MaterialTheme.typography.bodySmall)
        }
        applied.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (rules == null || (rules.suspendedDates.isEmpty() && rules.extraClasses.isEmpty())) Text("当前学期暂无调休安排")
        rules?.suspendedDates?.forEach { Text("$it · 常规教务课程停课") }
        rules?.extraClasses?.forEach {
            Text("${it.date} · 补 ${it.sourceDate} 的课程" + if (it.note.isNotBlank()) "\n${it.note}" else "")
        }
        Text("仅调整教务课程，补课追加到当天；手动课程不受影响。离线使用最近有效配置。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = viewModel::refreshAdjustments, enabled = !refreshing) {
            Text(if (refreshing) "正在刷新…" else "立即刷新")
        }
    }
}

@Composable
private fun ReminderSettings(viewModel: ScheduleViewModel) {
    val enabled by viewModel.reminderEnabled.collectAsStateWithLifecycle()
    val minutes by viewModel.reminderMinutes.collectAsStateWithLifecycle()
    val schedule by viewModel.reminderSchedule.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationsBlocked = remember(permissionEpoch) {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(ClassReminderScheduler.CHANNEL_ID)
        !NotificationManagerCompat.from(context).areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE
    }
    val exactBlocked = remember(permissionEpoch) { !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    val currentMinutes by rememberUpdatedState(minutes)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionEpoch++
        if (granted) viewModel.setReminder(true, currentMinutes)
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingsGroup("上课提醒") {
            SettingsRow("上课提醒", checked = enabled, onClick = {
                if (!enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else viewModel.setReminder(!enabled, minutes)
            })
            if (enabled) {
                Box(Modifier.padding(16.dp)) {
                    DropdownField("提前时间", (listOf(5, 10, 15, 20, 30, 45) + minutes).distinct().sorted().map { it to "$it 分钟" },
                        minutes, { viewModel.setReminder(true, it) }, Modifier.fillMaxWidth())
                }
                SettingsRow("提醒状态", if (schedule.scheduledCount > 0) "提前 $minutes 分钟 · 已安排 ${schedule.scheduledCount} 次提醒"
                    else "当前没有待提醒的课程")
            }
        }
        if (notificationsBlocked || exactBlocked) SettingsGroup("需要处理") {
            if (notificationsBlocked) SettingsRow("通知未开启", "前往系统设置开启应用及上课提醒通知", {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            })
            if (exactBlocked) SettingsRow("精确闹钟未授权", "当前提醒可能延迟，点击设置", {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            })
        }
    }
}
