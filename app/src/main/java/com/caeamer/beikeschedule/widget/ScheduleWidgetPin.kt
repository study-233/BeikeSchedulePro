package com.caeamer.beikeschedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

internal enum class WidgetPinResult { REQUESTED, UNSUPPORTED, FAILED }

/** REQUESTED 表示系统调用返回 true，不保证桌面已展示确认界面或完成添加。 */
internal fun requestScheduleWidgetPin(context: Context): WidgetPinResult = try {
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported) {
        WidgetPinResult.UNSUPPORTED
    } else if (manager.requestPinAppWidget(
            ComponentName(context, ScheduleWidgetReceiver::class.java), null, null,
        )) {
        WidgetPinResult.REQUESTED
    } else {
        WidgetPinResult.FAILED
    }
} catch (_: Exception) {
    WidgetPinResult.FAILED
}
