package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.model.SectionMap
import com.caeamer.beikeschedule.model.WeekUtils

private const val WEEKDAY_NAMES = "一二三四五六日"

/** 同层详情面板内容，保留所有来源的编辑、隐藏和删除规则。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseDetailContent(
    course: CourseEntity,
    sectionTimes: List<SectionTimeEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(course.name, style = MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (course.isUnscheduled) {
                    Text("无固定上课时间", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                } else {
                    val start = sectionTimes.firstOrNull { it.section == course.startSection }?.startTime
                    val end = sectionTimes.firstOrNull { it.section == course.endSection }?.endTime
                    // 脏数据不作为星期数组下标，沿用原来的安全回退。
                    val day = WEEKDAY_NAMES.getOrNull(course.dayOfWeek - 1) ?: "?"
                    Text("周$day · ${SectionMap.describeBigSections(course.startSection, course.endSection)}",
                        style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                    if (start != null && end != null) {
                        Text("$start – $end", style = MaterialTheme.typography.titleLarge, color = colors.onPrimaryContainer)
                    }
                }
                Text("周数：${WeekUtils.describe(course.weekBitmap)}", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimaryContainer)
            }
        }
        if (course.location.isNotBlank() || course.teacher.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (course.location.isNotBlank()) DetailInfo(Icons.Default.LocationOn, "上课地点", course.location)
                if (course.teacher.isNotBlank()) DetailInfo(Icons.Default.Person, "授课教师", course.teacher)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑")
                }
                OutlinedButton(onClick = onHide, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.VisibilityOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("隐藏")
                }
                // 导入课程仅可隐藏；自定义和示例课程保留删除能力。
                if (course.source != CourseEntity.SOURCE_IMPORT) {
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (course.source == CourseEntity.SOURCE_SAMPLE) "删除示例" else "删除")
                    }
                }
            }
            Text("隐藏后可在“我的 → 隐藏课程”恢复", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailInfo(icon: ImageVector, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
