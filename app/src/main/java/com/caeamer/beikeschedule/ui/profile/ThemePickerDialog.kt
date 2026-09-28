package com.caeamer.beikeschedule.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.pref.SettingsStore

/** 主题点击即生效；整行只有一个可访问的选择目标，长文字随系统字号自然增高。 */
@Composable
internal fun ThemePickerDialog(
    selectedMode: SettingsStore.ThemeMode,
    onSelect: (SettingsStore.ThemeMode) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.surface,
        tonalElevation = 0.dp,
        title = { Text("主题外观", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "选择后立即生效",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Column(
                    Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsStore.ThemeMode.entries.forEach { mode ->
                        val selected = selectedMode == mode
                        val label = when (mode) {
                            SettingsStore.ThemeMode.SYSTEM -> "跟随系统"
                            SettingsStore.ThemeMode.LIGHT -> "浅色"
                            SettingsStore.ThemeMode.DARK -> "深色"
                        }
                        val icon = when (mode) {
                            SettingsStore.ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                            SettingsStore.ThemeMode.LIGHT -> Icons.Outlined.LightMode
                            SettingsStore.ThemeMode.DARK -> Icons.Outlined.DarkMode
                        }
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(shape)
                                .background(if (selected) colors.primaryContainer else colors.surface)
                                .border(1.dp, if (selected) colors.primary.copy(alpha = 0.45f) else colors.outlineVariant, shape)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(mode) })
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) colors.surface.copy(alpha = 0.6f) else colors.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                                    tint = if (selected) colors.primary else colors.onSurfaceVariant)
                            }
                            Text(
                                label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                            )
                            // 点击与选中语义由父行负责，避免 TalkBack 出现重复操作目标。
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("关闭")
            }
        },
    )
}
