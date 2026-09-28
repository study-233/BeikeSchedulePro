package com.caeamer.beikeschedule.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.pref.ScheduledAlarm
import com.caeamer.beikeschedule.data.pref.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 日程已下线。只保留旧 PendingIntent 身份及幂等清理，不再创建任何日程排程。 */
object TodoReminderScheduler {
    const val CHANNEL_ID = "todo_reminder"
    const val ACTION_TODO_REMIND = "io.github.study233.beikeschedulepro.action.TODO_REMIND"
    internal data class PostedNotification(val channel: String?, val tag: String?, val id: Int)
    internal fun retireNotifications(active: List<PostedNotification>, cancel: (String?, Int) -> Unit) {
        active.filter { it.channel == CHANNEL_ID }.forEach { runCatching { cancel(it.tag, it.id) } }
    }
    private val cleanupMutex = Mutex()

    suspend fun cleanup(context: Context) = cleanupMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val settings = SettingsStore(context)
            // 通知清理不依赖记录能否读出，也不依赖通知权限。
            val notifications = context.getSystemService(NotificationManager::class.java)
            runCatching {
                retireNotifications(notifications.activeNotifications.map {
                    PostedNotification(it.notification.channelId, it.tag, it.id)
                }, cancel = { tag, id -> notifications.cancel(tag, id) })
            }
            val recorded = settings.todoScheduledAlarms.first()
            val alarms = context.getSystemService(AlarmManager::class.java)
            retireRecordedAlarms(recorded, cancel = { code ->
                val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_TODO_REMIND)
                val pending = PendingIntent.getBroadcast(context, code, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) {
                    alarms.cancel(pending)
                    pending.cancel()
                }
            }, persist = settings::saveTodoScheduledAlarms)
        }
    }

    /** 仅在成功取消后移除记录；失败的码下次启动/开机/升级继续尝试。 */
    internal suspend fun retireRecordedAlarms(recorded: List<ScheduledAlarm>, cancel: (Int) -> Unit,
                                               persist: suspend (List<ScheduledAlarm>) -> Unit) {
        val remaining = recorded.distinctBy { it.requestCode }.filter { alarm ->
            runCatching { cancel(alarm.requestCode) }.isFailure
        }
        persist(remaining)
    }

    /** 旧版本通知编号区间，保留供兼容隔离测试；不用于创建新闹钟。 */
    internal fun requestCodeOf(todo: TodoEntity, date: LocalDate): Int =
        7_000_000 + Math.floorMod("${todo.id}@$date".hashCode(), 1_000_000)
}
