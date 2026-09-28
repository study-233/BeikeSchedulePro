package com.caeamer.beikeschedule.ui.common

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.caeamer.beikeschedule.R
import com.caeamer.beikeschedule.widget.WidgetPinResult
import com.caeamer.beikeschedule.widget.requestScheduleWidgetPin

/** 两个设置入口共用系统添加请求与手动添加指引。 */
@Composable
internal fun AddScheduleWidgetRow(requestPin: (Context) -> WidgetPinResult = ::requestScheduleWidgetPin) {
    val context = LocalContext.current
    var showManualInstructions by rememberSaveable { mutableStateOf(false) }
    var requestSent by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.widget_add_title),
        summary = stringResource(if (requestSent) R.string.widget_add_requested else R.string.widget_add_summary),
        onClick = {
            requestSent = requestPin(context) == WidgetPinResult.REQUESTED
            showManualInstructions = !requestSent
        },
    )
    // 桌面返回 true 也可能没有展示确认界面，保留应用内可见反馈与手动入口。
    if (requestSent) TextButton(onClick = { showManualInstructions = true }) {
        Text(stringResource(R.string.widget_add_help))
    }
    if (showManualInstructions) AlertDialog(
        onDismissRequest = { showManualInstructions = false },
        title = { Text(stringResource(R.string.widget_add_title)) },
        text = {
            Text(stringResource(if (requestSent) R.string.widget_add_manual_help else R.string.widget_add_manual_instructions))
        },
        confirmButton = {
            TextButton(onClick = { showManualInstructions = false }) {
                Text(stringResource(R.string.widget_add_dismiss))
            }
        },
    )
}
