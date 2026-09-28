package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** 周次使用有界网格；大字号减少列数，打开时让正在查看的周位于可见区域。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeekPickerSheet(
    totalWeeks: Int,
    selectedWeek: Int,
    currentWeek: Int?,
    currentWeekLabel: String,
    onSelectWeek: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    pageLabels: List<String> = (1..totalWeeks).map { "第 $it 周" },
) {
    val colors = MaterialTheme.colorScheme
    val gridMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择周次", style = MaterialTheme.typography.titleLarge)
                    Text("正在查看${pageLabels.getOrNull(selectedWeek - 1).orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onDismissRequest) { Icon(Icons.Default.Close, "关闭周次选择") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = ((maxWidth.value + 8f) / (76f * fontScale + 8f)).toInt().coerceIn(1, 5)
                val gridState = rememberLazyGridState(
                    initialFirstVisibleItemIndex = ((selectedWeek - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)) / columns) * columns,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = gridMaxHeight).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items((1..totalWeeks).toList(), key = { it }) { week ->
                        val selected = week == selectedWeek
                        val current = week == currentWeek
                        val shape = RoundedCornerShape(14.dp)
                        val label = when {
                            current -> currentWeekLabel
                            selected -> "查看中"
                            else -> ""
                        }
                        Column(
                            Modifier.fillMaxWidth().clip(shape)
                                .background(if (selected) colors.primary else colors.surfaceVariant)
                                .border(1.dp, if (current) colors.primary else colors.surfaceVariant, shape)
                                .selectable(selected, role = Role.RadioButton, onClick = { onSelectWeek(week) })
                                .semantics {
                                    stateDescription = listOfNotNull(
                                        "正在查看".takeIf { selected },
                                        currentWeekLabel.takeIf { current },
                                    ).joinToString("，")
                                }
                                .heightIn(min = 68.dp).padding(horizontal = 4.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Text(pageLabels.getOrNull(week - 1).orEmpty(), style = MaterialTheme.typography.titleMedium,
                                color = if (selected) colors.onPrimary else colors.onSurface)
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = if (selected) colors.onPrimary else colors.primary)
                        }
                    }
                }
            }
            if (currentWeek != null && currentWeek in 1..totalWeeks) {
                TextButton(onClick = { onSelectWeek(currentWeek) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (currentWeekLabel) {
                        "待开学" -> "查看开学周 · ${pageLabels[currentWeek - 1]}"
                        "假期后" -> "查看假期后教学周 · ${pageLabels[currentWeek - 1]}"
                        else -> "回到本周 · ${pageLabels[currentWeek - 1]}"
                    })
                }
            }
        }
    }
}
