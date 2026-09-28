package com.caeamer.beikeschedule.ui.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.GradeEntity

/** 固定标题和关闭入口，内容区域有界滚动，使用中性色表面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭$title") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f, fill = false)) { content() }
        }
    }
}

@Composable
internal fun GradeCourseSelectorSheet(
    courses: List<Pair<GradeEntity, Boolean>>,
    hideScores: Boolean,
    onToggleCourse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    SelectionSheet(
        title = "纳入计算的课程",
        subtitle = if (hideScores) "勾选后立即重算 · 成绩明细已隐藏"
            else "已纳入 ${courses.count { it.second }} / ${courses.size} 门 · 勾选后立即重算",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = listHeight),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(courses, key = { it.first.kcdm }) { (grade, included) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (included) colors.primaryContainer.copy(alpha = 0.5f) else colors.surfaceVariant)
                        .toggleable(included, role = Role.Checkbox, onValueChange = { onToggleCourse(grade.kcdm) })
                        .heightIn(min = 72.dp).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(grade.kcmc, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(grade.xnxqmc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        // 与主卡片一致：隐藏时不泄露分数、学分、已纳入门数。
                        Text(if (hideScores) "学分与成绩已隐藏" else "${grade.xf} 学分 · ${grade.zzcj} 分",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Checkbox(checked = included, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
internal fun GradeFilterField(
    semesterLabel: String,
    schoolYearLabel: String,
    semesters: List<Pair<String, String>>,
    schoolYears: List<Pair<String, String>>,
    selectedSemester: String,
    selectedSchoolYear: String,
    onSemesterSelect: (String) -> Unit,
    onSchoolYearSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = when {
        selectedSemester.isNotBlank() -> semesterLabel
        selectedSchoolYear.isNotBlank() -> schoolYearLabel
        else -> "全部学年 · 全部学期"
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "筛选成绩") { expanded = true }
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("成绩范围", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (expanded) {
        val listHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        SelectionSheet("筛选成绩", "按学年或具体学期查看，点选立即应用", { expanded = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "school_years") {
                    FilterOptions("学年", schoolYears, selectedSchoolYear, "小学期单独列出") {
                        onSchoolYearSelect(it)
                        expanded = false
                    }
                }
                item(key = "semesters") {
                    FilterOptions("学期", semesters, selectedSemester) {
                        onSemesterSelect(it)
                        expanded = false
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterOptions(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    description: String? = null,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val checked = value == selected
                val colors = MaterialTheme.colorScheme
                Row(Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (checked) colors.primaryContainer else colors.surfaceVariant)
                    .selectable(checked, role = Role.RadioButton, onClick = { onSelect(value) })
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(checked, onClick = null, modifier = Modifier.size(20.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = if (checked) colors.onPrimaryContainer else colors.onSurface)
                }
            }
        }
    }
}
