package com.caeamer.beikeschedule.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.TodayScheduleResolver
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.util.UUID

/** 周期/边界只入队一次刷新，避免“边界任务重排时取消自己”。 */
class ScheduleWidgetTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (WidgetUpdateCoordinator.requestRefresh(applicationContext)) Result.success() else Result.retry()
}

/** 不访问教务系统；每次运行都重新读取本地数据和当前时间。 */
class ScheduleWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!WidgetUpdateCoordinator.hasWidgets(applicationContext)) return Result.success()
            val widget = ScheduleWidget()
            val ids = GlanceAppWidgetManager(applicationContext).getGlanceIds(ScheduleWidget::class.java)
            val revision = UUID.randomUUID().toString()
            for (id in ids) {
                // Glance 会话可能仍在运行：改变刷新键，让现有组合也重新读库和计算时间。
                // Preferences 只保存这个标记，不复制课程或登录信息。
                updateAppWidgetState(applicationContext, id) { it[ScheduleWidget.RefreshRevision] = revision }
                widget.update(applicationContext, id)
            }
            val snapshot = ScheduleRepository(applicationContext).getScheduleSnapshot()
            val today = TodayScheduleResolver.resolve(snapshot, ZonedDateTime.now())
            WidgetUpdateCoordinator.scheduleBoundary(applicationContext, today.nextChangeAt)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScheduleWidget", "Widget update failed: " + e.javaClass.simpleName)
            Result.retry()
        }
    }
}
