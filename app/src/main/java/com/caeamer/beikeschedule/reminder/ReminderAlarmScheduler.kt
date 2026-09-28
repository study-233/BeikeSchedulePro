package com.caeamer.beikeschedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.caeamer.beikeschedule.data.pref.ScheduledAlarm
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * 上课提醒与考试提醒共用的闹钟落地层。
 *
 * 关键约束（这里是"提醒偶尔不弹"的根因所在，改动前务必读完）：
 *
 * 1. **只取消"触发时刻仍在未来"的闹钟。**
 *    已到点但系统还没投递的闹钟绝不能取消：AlarmManager 在 Doze 下会把投递推迟到维护窗口，
 *    而每日脉冲（非精确闹钟，同样会被推迟）和"打开 App"都可能恰好在这个窗口里触发一次重排。
 *    旧实现无条件取消全部、再只重排"仍在未来"的，那条已到点的提醒就被取消且不再重排 ——
 *    v1.1.7 修掉了"提醒触发时重排"这个入口，但脉冲/开 App 仍能踩进同一窗口，所以还会偶发丢。
 *
 * 2. **取消 + 设置 + 写回记录必须是不可中断的一整段。**
 *    旧实现里 `cancelRecorded()` 先把记录清空落盘，随后还有多个挂起点（读设置/课程/节次）。
 *    而重排由 viewModelScope 的收集器驱动 —— 用户点开 App 后立刻返回就会取消协程，
 *    停在"旧闹钟全取消、新闹钟一个没排、记录也清空"的中间态。这里用 NonCancellable 包住整段。
 *
 * 3. **先算后换。** 所有读取与计算都在进入下面这段之前完成，进入后只做纯粹的替换。
 */
internal object ReminderAlarmScheduler {

    const val EXTRA_REQUEST_CODE = "requestCode"

    /**
     * 已到点闹钟在记录里的保留时长。
     * 6 小时足以覆盖 Doze 维护窗口导致的投递延迟；超过它说明这条提醒已经过了投递窗口，
     * 从记录里移除（避免记录无界增长，也避免误取消早已弹过的旧码）。
     */
    private const val DUE_RECORD_GRACE_MS = 6 * 60 * 60 * 1000L

    /** 本轮要排的一条闹钟（已构造好 PendingIntent 与触发时刻）。 */
    data class PlannedAlarm(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val pendingIntent: PendingIntent,
    )

    /**
     * 这一轮该取消哪些已记录闹钟（纯函数，可单测）。
     *
     * @param plannedCodes 本轮计划要排的 requestCode 集合
     * @param nowMillis 当前时刻
     * @param cancelDueAlarms 为 true 时连"已到点"的也一起取消 —— 用于关闭提醒或当前课表失效：
     *   那时用户明确要求别提醒，"再弹最后一次"才是 bug；其余情况已到点的必须留给系统投递。
     * @param forceCancelCodes 定向取消集合：即便已到点也要取消。用于"用户明确表达了不要这条提醒"
     *   的语义（如今天已打卡的日程），比 cancelDueAlarms 窄，不会误伤同批其他待投递提醒。
     */
    internal fun alarmsToCancel(
        recorded: List<ScheduledAlarm>,
        plannedCodes: Set<Int>,
        nowMillis: Long,
        cancelDueAlarms: Boolean = false,
        forceCancelCodes: Set<Int> = emptySet(),
    ): List<ScheduledAlarm> = recorded.filter { alarm ->
        when {
            // 用户关闭提醒或切换/清空课表 → 全部取消（含已到点的）
            cancelDueAlarms -> true
            // 定向取消（打卡/明确不要这条）→ 即便已到点也取消
            alarm.requestCode in forceCancelCodes -> true
            // 旧格式记录没有触发时刻：仍在本轮计划里的先留着（紧接着会被重新设置覆盖，
            // setExact 对同 requestCode 是替换语义），只有确实不再需要的才取消。
            alarm.triggerAtMillis == null -> alarm.requestCode !in plannedCodes
            // 仍在未来 → 可以安全取消，稍后按最新数据重排
            else -> alarm.triggerAtMillis > nowMillis
            // 已到点/已投递 → 一律不动，交给系统
        }
    }

    /**
     * 原子替换：取消该取消的 → 设置新闹钟 → 写回记录。
     * 调用方必须先把所有数据读好（先算后换），本函数只做替换。
     */
    suspend fun apply(
        context: Context,
        action: String,
        recorded: List<ScheduledAlarm>,
        planned: List<PlannedAlarm>,
        cancelDueAlarms: Boolean = false,
        forceCancelCodes: Set<Int> = emptySet(),
        persist: suspend (List<ScheduledAlarm>) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val plannedCodes = planned.map { it.requestCode }.toSet()
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        withContext(NonCancellable) {
            alarmsToCancel(recorded, plannedCodes, now, cancelDueAlarms, forceCancelCodes).forEach { stale ->
                PendingIntent.getBroadcast(
                    context, stale.requestCode,
                    Intent(context, ReminderReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )?.let { alarmManager.cancel(it) }
            }
            planned.forEach { setAlarm(alarmManager, it.triggerAtMillis, it.pendingIntent) }
            // 记录 = 本轮排上的 + **已到点但还在宽限期内**的旧闹钟。
            //
            // 已到点的那批一律不动（交给系统投递，见 alarmsToCancel），但记录不能马上丢：
            // 记录是后续取消的唯一线索，丢了之后"用户打卡/明确关掉提醒"就再也取消不到
            // 那条正在 Doze 队列里的闹钟。超出宽限期（投递窗口已过）才真正移除。
            val plannedRecords = planned.map { ScheduledAlarm(it.requestCode, it.triggerAtMillis) }
            val dueKept = recorded.filter { old ->
                !cancelDueAlarms && old.requestCode !in forceCancelCodes &&
                    old.triggerAtMillis != null &&
                    old.triggerAtMillis <= now &&
                    old.triggerAtMillis > now - DUE_RECORD_GRACE_MS
            }
            persist((plannedRecords + dueKept).distinctBy { it.requestCode })
        }
    }

    private fun setAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, pending: PendingIntent) {
        // Android 14+ 精确闹钟权限可能被系统收回，拿不到时退化为非精确（允许几分钟后延）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    /** LocalDateTime → 触发时刻（epoch 毫秒），统一走系统时区。 */
    fun toEpochMillis(dateTime: java.time.LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()
}
