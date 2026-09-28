package com.caeamer.beikeschedule.ui.grades

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.model.ExamDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 草稿归 ViewModel 所有；弹窗只负责选择器与展示，不直接写库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamEditDialog(
    draft: ExamDraft,
    saving: Boolean,
    error: String?,
    onChange: (ExamDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val enabled = !saving
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(if (draft.id == 0L) "添加考试" else "编辑考试") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("仅名称必填，日期和时间可稍后补充。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) },
                    label = { Text("考试名称") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("exam_name"))
                ExamPickerField("日期", draft.date, enabled, { picker = "date" }, { onChange(draft.withDate("")) })
                ExamPickerField("开始时间", draft.start, enabled && draft.date.isNotEmpty(),
                    { picker = "start" }, { onChange(draft.withStart("")) })
                ExamPickerField("结束时间", draft.end, enabled && draft.start.isNotEmpty(),
                    { picker = "end" }, { onChange(draft.copy(end = "")) })
                OutlinedTextField(draft.location, { onChange(draft.copy(location = it)) },
                    label = { Text("地点（选填）") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.seat, { onChange(draft.copy(seat = it)) },
                    label = { Text("座位号（选填）") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.type, { onChange(draft.copy(type = it)) },
                    label = { Text("考试类型（选填）") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.note, { onChange(draft.copy(note = it)) },
                    label = { Text("备注（选填）") }, minLines = 2, enabled = enabled, modifier = Modifier.fillMaxWidth())
                if (draft.id != 0L) TextButton(onClick = { confirmDelete = true }, enabled = enabled) {
                    Text("删除考试", color = MaterialTheme.colorScheme.error)
                }
                Text("已知日期时提醒前一天 20:00；已知开始时间时另提醒开考前 1 小时。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("exam_error")) }
                TextButton(onClick = onSave, enabled = enabled, modifier = Modifier.testTag("exam_save")) {
                    Text(if (saving) "处理中…" else "保存")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (enabled) confirmDelete = false },
        title = { Text("删除考试？") },
        text = { Text("将删除“${draft.name}”并取消这场考试的提醒。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, enabled = enabled) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = enabled) { Text("保留") } },
    )
    if (picker == "date") {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.takeIf { it.isNotEmpty() }?.let {
            LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        })
        DatePickerDialog(
            onDismissRequest = { picker = null },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { onChange(draft.withDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
                picker = null
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } },
        ) { DatePicker(state) }
    }
    if (picker == "start" || picker == "end") {
        val selectingStart = picker == "start"
        val initial = (if (selectingStart) draft.start else draft.end).takeIf { it.isNotEmpty() }
            ?.let(LocalTime::parse) ?: draft.start.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (selectingStart) "选择开始时间" else "选择结束时间") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state) } },
            confirmButton = { TextButton(onClick = {
                val value = LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                onChange(if (selectingStart) draft.withStart(value) else draft.copy(end = value))
                picker = null
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExamPickerField(label: String, value: String, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("$label：${value.ifEmpty { "待定" }}")
        }
        if (value.isNotEmpty()) TextButton(onClick = onClear, enabled = enabled) { Text("清空$label") }
    }
}
