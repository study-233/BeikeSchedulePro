package com.caeamer.beikeschedule.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

val LocalPageActive = staticCompositionLocalOf { true }

/** Compose 的动画时钟自动应用系统 duration scale；手势位移不乘时长系数。 */
object AppMotion {
    const val PAGE = 200
    const val SECONDARY = 220
    const val DETAIL = 280
    const val STATE = 180
}
object AppLayout {
    val PagePadding = 16.dp
    val GroupShape = RoundedCornerShape(18.dp)
}

@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            } else Spacer(Modifier.width(8.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            actions()
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppLayout.PagePadding, vertical = 8.dp)) {
        Text(title, Modifier.padding(start = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = AppLayout.GroupShape, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().animateContentSize(tween(AppMotion.STATE)), content = content)
        }
    }
}

@Composable
fun SettingsRow(title: String, summary: String? = null, onClick: (() -> Unit)? = null,
                checked: Boolean? = null, destructive: Boolean = false) {
    val interaction = when {
        onClick == null -> Modifier
        checked != null -> Modifier.toggleable(checked, role = Role.Switch) { onClick() }
        else -> Modifier.clickable(onClick = onClick)
    }
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 56.dp).drawBehind {
        drawLine(divider, Offset(16.dp.toPx(), size.height), Offset(size.width - 16.dp.toPx(), size.height), 1.dp.toPx())
    }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked != null) Switch(checked, onCheckedChange = null)
        else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun <T> AppSegments(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val width = maxWidth / options.size
        val index = options.indexOfFirst { it.first == selected }
        val offset by animateDpAsState(width * index.coerceAtLeast(0), tween(AppMotion.STATE), label = "segment")
        if (index >= 0) Box(Modifier.offset(x = offset).width(width).height(48.dp).padding(3.dp)
            .clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface))
        Row {
            options.forEach { (value, label) ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected == value, role = Role.Tab, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
