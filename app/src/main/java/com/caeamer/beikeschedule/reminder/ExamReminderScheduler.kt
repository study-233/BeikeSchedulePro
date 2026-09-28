package com.caeamer.beikeschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.model.timeLabel
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
     * @param changedExamIds 手动编辑/删除的考试，连已到点的旧提醒也定向取消。
     * 已不在数据库中的考试（教务替换/清缓存）自动取消；其余考试保留 Doze 待投递提醒。
     */
    suspend fun reschedule(context: Context, changedExamIds: Set<Long> = emptySet()) {
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
                    forceCancelCodes = obsoleteReminderCodes(exams, recorded.map { it.requestCode }.toSet(), changedExamIds),
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

    /** 仅撤销已删除或明确修改的考试，不能把“本轮不再计划”的所有到点提醒一并取消。 */
    internal fun obsoleteReminderCodes(
        exams: List<ExamEntity>, recordedCodes: Set<Int>, changedExamIds: Set<Long>,
    ): Set<Int> {
        val liveCodes = exams.flatMap { reminderCodes(it.id) }.toSet()
        return (recordedCodes - liveCodes) + changedExamIds.flatMap { reminderCodes(it) }
    }

    private fun reminderCodes(id: Long): List<Int> =
        listOf(REQUEST_CODE_BASE + (id * 2).toInt(), REQUEST_CODE_BASE + (id * 2).toInt() + 1)

    // 8_000_000 段：examId*2(+1)，与上课提醒的 requestCode 空间隔离
    private fun requestCodeOf(exam: ExamEntity, dayBefore: Boolean): Int =
        REQUEST_CODE_BASE + (exam.id * 2).toInt() + if (dayBefore) 0 else 1

    private fun examTimeText(exam: ExamEntity): String =
        listOf(exam.ksrq, exam.timeLabel()).filter { it.isNotBlank() }.joinToString(" ")

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
