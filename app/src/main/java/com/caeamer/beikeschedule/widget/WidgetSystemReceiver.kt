package com.caeamer.beikeschedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 与提醒 Receiver 分开，时间变化后重新安排 Widget 的下一处课程边界。 */
class WidgetSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WidgetUpdateCoordinator.requestRefresh(context)
        }
    }
}
