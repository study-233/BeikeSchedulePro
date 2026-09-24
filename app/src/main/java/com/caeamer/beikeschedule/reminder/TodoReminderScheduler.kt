package com.caeamer.beikeschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.TodoPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 日程提醒调度：为未来 [SCHEDULE_DAYS] 天内出现的每个日程事项排一个闹钟提醒
 * （触发时刻 = 计划时间点 − 该事项自定义的提前分钟数）。
 *
 * 与上课/考试提醒共用 [ReminderAlarmScheduler] 的"先算后换 + 只取消仍在未来的闹钟"策略。
 * requestCode 固定使用 [7M,8M) 段，与上课提醒（[0,7M)）、考试提醒（8M 段）、
 * 每日脉冲（9M）隔离，通知 ID 也不会互相覆盖。
 *
 * 重排触发：todo 表任何变更（新增/编辑/删除/打卡）、每日脉冲、开机。日程无总开关，
 * 每个事项自带 remindMinutes，故没有"用户主动关闭"场景，cancelDueAlarms 恒 false。
 */
object TodoReminderScheduler {

    const val CHANNEL_ID = "todo_reminder"
    const val ACTION_TODO_REMIND = "io.github.study233.beikeschedulepro.action.TODO_REMIND"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_MINUTES = "minutes"
    private const val REQUEST_CODE_BASE = 7_000_000
    private const val REQUEST_CODE_RANGE = 1_000_000
    private const val SCHEDULE_DAYS = 8

    /** 重排串行化：与上课/考试调度同理，多处并发触发会互相覆盖记录。 */
    private val rescheduleMutex = Mutex()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "日程提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "日程事项开始前按各自提前量提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 一条待排的日程提醒（纯数据，便于单测；PendingIntent 由调用方按需构造）。 */
    internal data class PlannedTodoReminder(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val title: String,
        val timeText: String,
        val minutes: Int,
    )

    /** todo 表变化时调用：按最新数据全量重排。 */
    suspend fun reschedule(context: Context) = rescheduleMutex.withLock {
        // 切到 IO：下面全是 Room/DataStore 读 + 每条闹钟一次 binder 调用
        withContext(Dispatchers.IO) {
            val repo = ScheduleRepository(context)
            val settings = repo.settings

            // —— 先算：所有读取与计算都在取消任何闹钟之前完成 ——
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            val todos = repo.todos.first()
            val planned = planTodoReminders(todos, now, zone)
            val recorded = settings.todoScheduledAlarms.first()

            // 定向取消：今天已打卡事项的提醒码。Doze 下"已到点但还没投递"的闹钟不会被
            // 常规重排取消（那是刻意的，避免丢提醒），但打卡的语义就是"别再提醒了"，
            // 所以这批要连已到点的一起取消——只取消这批码，不误伤同批其他待投递提醒。
            val today = now.toLocalDate()
            val forceCancel = todos
                .filter { it.isDoneToday(today.toString()) && TodoPlanner.occursOn(it, today) }
                .map { requestCodeOf(it, today) }
                .toSet()

            // —— 后换：不可中断地取消 + 设置 + 写回 ——
            ReminderAlarmScheduler.apply(
                context = context,
                action = ACTION_TODO_REMIND,
                recorded = recorded,
                planned = planned.map { p ->
                    ReminderAlarmScheduler.PlannedAlarm(
                        requestCode = p.requestCode,
                        triggerAtMillis = p.triggerAtMillis,
                        pendingIntent = todoPendingIntent(context, p),
                    )
                },
                cancelDueAlarms = false,
                forceCancelCodes = forceCancel,
                persist = { settings.saveTodoScheduledAlarms(it) },
            )
        }
    }

    /**
     * 纯函数：算出未来 [SCHEDULE_DAYS] 天内要排的日程提醒。
     *
     * 触发时刻 = 计划时间点 − 事项自定义提前分钟数；只排仍在 [now] 之后的。
     * 同一事项的重复出现日各自独立（不同日期 → 不同 requestCode → 各自的闹钟）。
     */
    internal fun planTodoReminders(
        todos: List<TodoEntity>,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedTodoReminder> {
        val today = now.toLocalDate()
        val result = mutableListOf<PlannedTodoReminder>()
        todos.forEach { todo ->
            TodoPlanner.upcomingReminders(todo, today, SCHEDULE_DAYS, now).forEach { trigger ->
                result += PlannedTodoReminder(
                    requestCode = requestCodeOf(todo, trigger.toLocalDate()),
                    triggerAtMillis = ReminderAlarmScheduler.toEpochMillis(trigger, zone),
                    title = todo.title,
                    timeText = todo.time,
                    minutes = todo.remindMinutes,
                )
            }
        }
        return result.sortedBy { it.triggerAtMillis }
    }

    /**
     * 提醒的 requestCode = hash(事项 id + 出现日期)，落在 [REQUEST_CODE_BASE, +RANGE)。
     * 内容寻址所以跨轮次稳定；同一 (事项,日期) 永远得到同一个码，改时间也能精确取消。
     *
     * 碰撞概率：本段只有 1e6 个槽，8 天窗口 × 20 个每天重复的事项 = 160 个码时
     * p ≈ 1.3%（生日近似 n(n-1)/2m）。碰撞会让后设置的闹钟覆盖前一个（少弹一条且无日志）。
     * 当前量级（个位数事项）远低于此，先记录在案。
     */
    internal fun requestCodeOf(todo: TodoEntity, date: LocalDate): Int =
        REQUEST_CODE_BASE + Math.floorMod("${todo.id}@$date".hashCode(), REQUEST_CODE_RANGE)

    private fun todoPendingIntent(
        context: Context,
        plan: PlannedTodoReminder,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_TODO_REMIND)
            .putExtra(EXTRA_TITLE, plan.title)
            .putExtra(EXTRA_TIME_TEXT, plan.timeText)
            .putExtra(EXTRA_MINUTES, plan.minutes)
            .putExtra(ReminderAlarmScheduler.EXTRA_REQUEST_CODE, plan.requestCode)
        return PendingIntent.getBroadcast(
            context, plan.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
