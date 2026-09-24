package com.caeamer.beikeschedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** 只调度本地刷新；没有 Widget 时不创建任务，不影响导入和编辑结果。 */
object WidgetUpdateCoordinator {
    private const val TAG = "schedule-widget"
    private const val REFRESH = "schedule-widget-refresh"
    private const val PERIODIC = "schedule-widget-periodic"
    private const val BOUNDARY = "schedule-widget-boundary"

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, ScheduleWidgetReceiver::class.java),
        ).isNotEmpty()

    /** 调度故障只记录类型，不能将已保存的课程操作误报成失败。 */
    fun requestRefresh(context: Context): Boolean = try {
        val app = context.applicationContext
        if (hasWidgets(app)) {
            val work = WorkManager.getInstance(app)
            work.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScheduleWidgetTriggerWorker>(15, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build(),
            )
            // REPLACE 合并尚未完成的刷新，保证最后一次修改不会被 KEEP 丢掉。
            work.enqueueUniqueWork(
                REFRESH, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduleWidgetWorker>().addTag(TAG).build(),
            )
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Widget refresh enqueue failed: " + e.javaClass.simpleName)
        false
    }

    internal fun scheduleBoundary(context: Context, nextChangeAt: ZonedDateTime) {
        if (!hasWidgets(context)) return
        val delay = Duration.between(ZonedDateTime.now(), nextChangeAt).toMillis().coerceAtLeast(1_000L)
        // 边界 Worker 只发出刷新请求；实际渲染 Worker 不会替换并取消自己。
        WorkManager.getInstance(context).enqueueUniqueWork(
            BOUNDARY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduleWidgetTriggerWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build(),
        )
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
        } catch (e: Exception) {
            Log.w(TAG, "Widget cancellation failed: " + e.javaClass.simpleName)
        }
    }
}
