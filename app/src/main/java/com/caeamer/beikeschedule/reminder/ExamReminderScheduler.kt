package com.caeamer.beikeschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 考前提醒调度：为未来 30 天内、能解析出日期的每场考试排两个闹钟——
 * 考前一天 20:00 与开考前 1 小时（无开始时间则只排前者）。
 *
 * 与上课提醒共用 [ReminderAlarmScheduler] 的"先算后换 + 只取消仍在未来的闹钟"策略
 * （旧实现同样是先无条件取消再只重排未来的，会丢掉"已到点但系统还没投递"的那条）；
 * requestCode 固定使用 8_000_000 段，与上课提醒、每日脉冲隔离。
 */
object ExamReminderScheduler {

    const val EXAM_CHANNEL_ID = "exam_reminder"
    const val ACTION_EXAM_REMIND = "io.github.study233.beikeschedulepro.action.EXAM_REMIND"
    const val EXTRA_NAME = "name"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_SEAT = "seat"
    const val EXTRA_TITLE_PREFIX = "titlePrefix"
    private const val REQUEST_CODE_BASE = 8_000_000
    private const val SCHEDULE_DAYS = 30

    /**
     * 重排串行化。考试重排有 4 个并发入口（每日脉冲 IO 线程、开机广播、抓取成功后、
     * 清除成绩缓存），而 reschedule 内部"读记录 → 取消/设置/写记录"有多个挂起点：
     * 交错执行会出现"清除缓存那次清空了记录，而抓取那次刚设好的闹钟还在 AlarmManager 里"
     * —— 用户看不到考试数据却仍收到旧提醒，正是 C9 修复要避免的症状。
     * 上课/日程调度一直有这把锁，考试此前漏了。
     */
    private val rescheduleMutex = Mutex()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            EXAM_CHANNEL_ID, "考试提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "考试前一天与开考前提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 一条待排的考试提醒（纯数据，便于单测；PendingIntent 由调用方按需构造）。 */
    internal data class PlannedExamReminder(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val exam: ExamEntity,
        val titlePrefix: String,
    )

    /**
     * 考试数据变化/开机/每日脉冲时调用：按最新数据全量重排。
     *
     * @param cancelDueAlarms 为 true 时连"已到点但系统还没投递"的闹钟也一并取消。
     *   只用于**用户显式清空考试数据**的场景（清除成绩缓存）：那时用户明确要求别再提醒，
     *   "再弹最后一次带过期地点与座位号的考试提醒"才是 bug。
     *   其余情况（日常重排、脉冲、开机）必须保持 false，否则会丢掉 Doze 下尚未投递的提醒。
     *
     *   此前本方法没有这个参数，而 [ReminderAlarmScheduler.apply] 的默认值是 false，
     *   于是"清空考试数据"后那条已到点的考试闹钟仍会弹出——与上课提醒（那边传了
     *   `!enabled`）的行为不一致。
     */
    suspend fun reschedule(context: Context, cancelDueAlarms: Boolean = false) {
        rescheduleMutex.withLock {
            // 切到 IO：下面全是 Room/DataStore 读 + 每条闹钟一次 binder 调用
            // （PendingIntent 构造 + setExact），窗口内几十条时在主线程会明显掉帧
            withContext(Dispatchers.IO) {
                val repo = ScheduleRepository(context)
                val settings = repo.settings

                // —— 先算 ——
                val exams = repo.exams.first()
                val now = LocalDateTime.now()
                val planned = planExamReminders(exams, now, ZoneId.systemDefault())
                val recorded = settings.examScheduledAlarms.first()

                // —— 后换 ——
                ReminderAlarmScheduler.apply(
                    context = context,
                    action = ACTION_EXAM_REMIND,
                    recorded = recorded,
                    planned = planned.map { p ->
                        ReminderAlarmScheduler.PlannedAlarm(
                            requestCode = p.requestCode,
                            triggerAtMillis = p.triggerAtMillis,
                            pendingIntent = pendingIntent(context, p),
                        )
                    },
                    cancelDueAlarms = cancelDueAlarms,
                    persist = { settings.saveExamScheduledAlarms(it) },
                )
            }
        }
    }

    /**
     * 纯函数：算出未来 30 天内要排的考试提醒。
     * 每场考试最多两条：考前一天 20:00、开考前 1 小时（需能解析出开始时间）。
     */
    internal fun planExamReminders(
        exams: List<ExamEntity>,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedExamReminder> {
        val today = now.toLocalDate()
        val result = mutableListOf<PlannedExamReminder>()
        exams.forEach { exam ->
            if (!exam.hasDate) return@forEach
            val date = runCatching { LocalDate.parse(exam.ksrq) }.getOrNull() ?: return@forEach
            if (date.isBefore(today) || date.isAfter(today.plusDays(SCHEDULE_DAYS.toLong()))) return@forEach

            // 考前一天 20:00
            val dayBefore = LocalDateTime.of(date.minusDays(1), LocalTime.of(20, 0))
            if (dayBefore.isAfter(now)) {
                result += PlannedExamReminder(
                    requestCode = requestCodeOf(exam, dayBefore = true),
                    triggerAtMillis = ReminderAlarmScheduler.toEpochMillis(dayBefore, zone),
                    exam = exam,
                    titlePrefix = "明天考试",
                )
            }
            // 开考前 1 小时（需要解析出开始时间）
            val start = runCatching { LocalTime.parse(exam.kssj) }.getOrNull() ?: return@forEach
            val oneHourBefore = LocalDateTime.of(date, start).minusHours(1)
            if (oneHourBefore.isAfter(now)) {
                result += PlannedExamReminder(
                    requestCode = requestCodeOf(exam, dayBefore = false),
                    triggerAtMillis = ReminderAlarmScheduler.toEpochMillis(oneHourBefore, zone),
                    exam = exam,
                    titlePrefix = "即将考试",
                )
            }
        }
        return result
    }

    // 8_000_000 段：examId*2(+1)，与上课提醒的 requestCode 空间隔离
    private fun requestCodeOf(exam: ExamEntity, dayBefore: Boolean): Int =
        REQUEST_CODE_BASE + (exam.id * 2).toInt() + if (dayBefore) 0 else 1

    private fun examTimeText(exam: ExamEntity): String = when {
        exam.kssj.isNotBlank() && exam.jssj.isNotBlank() -> "${exam.ksrq} ${exam.kssj}-${exam.jssj}"
        exam.ksrq.isNotBlank() -> exam.ksrq
        else -> exam.kssjms
    }

    private fun pendingIntent(context: Context, plan: PlannedExamReminder): PendingIntent {
        val exam = plan.exam
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_EXAM_REMIND)
            .putExtra(EXTRA_NAME, exam.kcmc)
            .putExtra(EXTRA_TIME_TEXT, examTimeText(exam))
            .putExtra(EXTRA_LOCATION, exam.cdmc)
            .putExtra(EXTRA_SEAT, exam.zwh)
            .putExtra(EXTRA_TITLE_PREFIX, plan.titlePrefix)
            .putExtra(ReminderAlarmScheduler.EXTRA_REQUEST_CODE, plan.requestCode)
        return PendingIntent.getBroadcast(
            context, plan.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
