package com.caeamer.beikeschedule.ui.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.caeamer.beikeschedule.ui.settings.ScheduleAppearanceDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 学期设置：学期名 / 开学日期 / 总周数 / 上课提醒 / 隐藏课程恢复 / 示例数据清除（主题在设置 Tab）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsDialog(
    current: SettingsStore.SemesterConfig,
    hasSample: Boolean,
    hiddenCourses: List<CourseEntity>,
    reminderEnabled: Boolean,
    reminderMinutes: Int,
    hideWeekend: Boolean,
    /** 提醒排期诊断（已排数量 / 最近一次触发时刻）。 */
    reminderSchedule: ReminderScheduleInfo = ReminderScheduleInfo(),
    onDismiss: () -> Unit,
    onSave: (SettingsStore.SemesterConfig) -> Unit,
    onReminderChange: (enabled: Boolean, minutes: Int) -> Unit,
    onHideWeekendChange: (Boolean) -> Unit,
    onClearSample: () -> Unit,
    onRestoreCourse: (Long) -> Unit,
    onRequestNotificationPermission: (onGranted: () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(current.name) }
    var firstMonday by remember { mutableStateOf(current.firstMonday) }
    var totalWeeks by remember { mutableIntStateOf(current.totalWeeks) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // 系统层面的开关是同步查询，每次打开设置页现算，保证是最新值
    val notificationsBlocked = remember { notificationsBlocked(context) }
    val exactAlarmBlocked = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // —— 学期 ——
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("学期名（如 2026-2027-1）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (firstMonday.isBlank()) "选择开学日期（第 1 周周一）"
                        else "开学日期：$firstMonday",
                    )
                }
                // 下拉必须包含当前值：导入会写入校历自己的总周数，若不在枚举里，
                // ExposedDropdownMenuBox 会渲染成空白（看起来像"没选"）
                DropdownField(
                    label = "总周数",
                    options = (listOf(16, 18, 20, 22, 25) + totalWeeks)
                        .distinct().sorted().map { it to "${it}周" },
                    selected = totalWeeks, onSelect = { totalWeeks = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 总周数不能小于官方校历的长度：否则 currentWeek 可能超过 totalWeeks，
                // 周次菜单里的"（本周）"永远不出现，且超出部分的课会被静默截断
                val minWeeks = current.weekMondays.size
                if (minWeeks > 0 && totalWeeks < minWeeks) {
                    Text(
                        "总周数不能小于官方教学周日历的 $minWeeks 周，保存时会被自动校正为 $minWeeks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    if (current.weekMondays.isNotEmpty()) {
                        "教学周日历：来自教务系统（${current.weekMondays.size} 周，含放假跳周），" +
                            "日期与当前周以官方日历为准，上方开学日期仅作备用"
                    } else {
                        "教学周日历：未导入，按开学日期逐周推算；从教务导入课表后自动获取官方日历"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // —— 显示 ——
                OutlinedButton(onClick = { showAppearance = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("课表外观 · 字号与背景")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("隐藏周末", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "周末无课时收窄网格，工作日列更宽",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = hideWeekend,
                        onCheckedChange = { onHideWeekendChange(it) },
                    )
                }

                HorizontalDivider()

                // —— 上课提醒 ——
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("上课提醒", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { want ->
                            if (want) {
                                onRequestNotificationPermission { onReminderChange(true, reminderMinutes) }
                            } else {
                                onReminderChange(false, reminderMinutes)
                            }
                        },
                    )
                }
                if (reminderEnabled) {
                    DropdownField(
                        label = "提前提醒",
                        options = listOf(5, 10, 15, 20, 30, 45).map { it to "$it 分钟" },
                        selected = reminderMinutes,
                        onSelect = { onReminderChange(true, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // —— 提醒状态诊断 ——
                    // 这个功能出过两次"偶发不提醒"。把"排上了没有 / 下次什么时候响 / 通知是不是被系统关了"
                    // 直接摆到界面上，下次出问题不用再抓 logcat 或 dumpsys。
                    if (notificationsBlocked) {
                        Text(
                            "通知已被系统关闭，上课提醒不会弹出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { openNotificationSettings(context) }) { Text("去开启通知") }
                    } else {
                        Text(
                            reminderStatusText(reminderSchedule),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (exactAlarmBlocked) {
                        Text(
                            "系统未授予精确闹钟权限，提醒可能延迟几分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = {
                            // 隐式 Intent 一律兜住：个别 ROM 没有这个设置页
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("去开启精确闹钟") }
                    }
                }

                HorizontalDivider()

                // —— 隐藏课程（三类课程都可隐藏，此处恢复）——
                Text("隐藏的课程", style = MaterialTheme.typography.titleSmall)
                if (hiddenCourses.isEmpty()) {
                    Text(
                        "暂无隐藏课程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    hiddenCourses.forEach { course ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                course.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            // 来源标注：自定义课/示例课恢复后才能删除，教务课恢复后也只能再隐藏
                            Text(
                                "· " + courseSourceLabel(course),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onRestoreCourse(course.id) }) { Text("恢复") }
                        }
                    }
                    Text(
                        "自定义课 / 示例课需先恢复，才能在课表里删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // —— 主题在底部 Tab「设置」中 ——
                if (hasSample) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        onClearSample()
                        onDismiss()
                    }) {
                        Text("清除示例课表", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 总周数不得小于官方校历长度（见上方提示）：静默校正比让"（本周）"消失好
                val safeWeeks = totalWeeks.coerceAtLeast(current.weekMondays.size.coerceAtLeast(1))
                onSave(
                    current.copy(
                        name = name.trim(),
                        firstMonday = firstMonday,
                        totalWeeks = safeWeeks,
                    ),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showAppearance) {
        ScheduleAppearanceDialog(onDismiss = { showAppearance = false })
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(firstMonday) }.getOrNull()
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        firstMonday = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 隐藏课程列表里的来源标注。 */
private fun courseSourceLabel(course: CourseEntity): String = when (course.source) {
    CourseEntity.SOURCE_IMPORT -> "教务"
    CourseEntity.SOURCE_SAMPLE -> "示例"
    else -> "自定义"
}

/** 通知是否被系统挡掉：应用级通知开关（含权限）被关，或「上课提醒」渠道被设为"关闭"。 */
private fun notificationsBlocked(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
    val channel = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(ClassReminderScheduler.CHANNEL_ID)
    return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
}

private fun openNotificationSettings(context: Context) {
    // 隐式 Intent 一律兜住：个别 ROM / 精简系统没有这个设置页
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 诊断文案："已排 N 个提醒 · 最近一次：明天 07:45"。 */
private fun reminderStatusText(info: ReminderScheduleInfo): String {
    val next = info.nextTriggerAtMillis ?: return "当前没有需要提醒的课（未开学 / 假期中 / 本学期已结束）"
    return "已排 ${info.scheduledCount} 个提醒 · 最近一次：${formatTrigger(next)}"
}

private fun formatTrigger(millis: Long): String {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val hhmm = "%02d:%02d".format(dt.hour, dt.minute)
    val day = dt.toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "今天 $hhmm"
        today.plusDays(1) -> "明天 $hhmm"
        else -> "${day.monthValue}月${day.dayOfMonth}日 $hhmm"
    }
}
