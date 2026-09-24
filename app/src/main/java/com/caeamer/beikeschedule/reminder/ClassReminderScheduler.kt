package com.caeamer.beikeschedule.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.ReminderCourses
import com.caeamer.beikeschedule.model.WeekResolver
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
 * 上课提醒调度：为未来 8 天内"当周有课"的每个课程块排一个闹钟提醒。
 *
 * 策略：全量重排 —— 课程/设置/学期任何变化时，先只读地算出这一轮该排的全部闹钟，
 * 再交给 [ReminderAlarmScheduler.apply] 做不可中断的替换（只取消仍在未来的旧闹钟）。
 * 另挂一个每日脉冲闹钟兜底自续（应用长期不打开也能续期）。
 */
object ClassReminderScheduler {

    const val CHANNEL_ID = "class_reminder"
    const val ACTION_REMIND = "io.github.study233.beikeschedulepro.action.REMIND"
    const val ACTION_DAILY_PULSE = "io.github.study233.beikeschedulepro.action.DAILY_PULSE"
    const val EXTRA_NAME = "name"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_MINUTES = "minutes"
    private const val REQUEST_DAILY_PULSE = 9_000_000
    private const val SCHEDULE_DAYS = 8

    /**
     * 上课提醒的 requestCode 空间 [0, 7M)，与日程提醒（[7M,8M)）、考试提醒（8M 段）
     * 和每日脉冲（9M）完全隔离。
     * 顺带让"通知 ID = requestCode"在三类提醒之间也不会撞车。
     *
     * 历史注：曾用 [0,8M)，与日程段 [7M,8M) 有 1/8 重叠——闹钟因 action 不同互不干扰，
     * 但通知 ID（=裸 requestCode）跨类同码时会互相覆盖。改小后旧码经下次重排自愈
     * （记录里未来旧码不在新计划内会被取消，再按新码重设）。
     */
    private const val REQUEST_CODE_RANGE = 7_000_000

    /**
     * 重排串行化：reschedule 会被 App 打开、每日脉冲、开机广播等多处并发触发。
     * [ReminderAlarmScheduler.apply] 内部已经是原子的，但并发跑两轮仍会互相覆盖记录，故仍串行化。
     */
    private val rescheduleMutex = Mutex()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "每节课开始前 N 分钟提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 需要排提醒的课程：排除无固定时间与已隐藏课程，并合并教务拆出的同名同段多行
     * （否则同一节课会因多行各排一个闹钟而重复弹两次）。
     */
    internal fun reminderCourses(all: List<CourseEntity>): List<CourseEntity> =
        ReminderCourses.eligible(all)

    /** 一条待排的上课提醒（纯数据，便于单测；PendingIntent 由调用方按需构造）。 */
    internal data class PlannedClassReminder(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val courseName: String,
        val location: String,
        val startTime: String,
        val minutes: Int,
    )

    /** 课程/学期/提醒设置变化时调用：按最新数据全量重排。 */
    suspend fun reschedule(context: Context) = rescheduleMutex.withLock {
        // 切到 IO：下面全是 Room/DataStore 读 + 每条闹钟一次 binder 调用
        // （PendingIntent 构造 + setExact），窗口内几十条时在主线程会明显掉帧
        withContext(Dispatchers.IO) {
            val repo = ScheduleRepository(context)
            val settings = repo.settings

            // —— 先算：所有读取与计算都在取消任何闹钟之前完成 ——
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            val enabled = settings.reminderEnabled.first()
            val planned = if (enabled) {
                val minutes = settings.reminderMinutes.first()
                val semester = settings.semester.first()
                val courses = reminderCourses(repo.courses.first())
                val startTimes = repo.sectionTimes.first().associate { it.section to it.startTime }
                planClassReminders(courses, startTimes, semester, minutes, now, zone)
            } else {
                emptyList()
            }
            val recorded = settings.reminderScheduledAlarms.first()

            // —— 后换：不可中断地取消 + 设置 + 写回 ——
            ReminderAlarmScheduler.apply(
                context = context,
                action = ACTION_REMIND,
                recorded = recorded,
                planned = planned.map { p ->
                    ReminderAlarmScheduler.PlannedAlarm(
                        requestCode = p.requestCode,
                        triggerAtMillis = p.triggerAtMillis,
                        pendingIntent = remindPendingIntent(context, p),
                    )
                },
                // 用户主动关掉提醒时连"已到点还没投递"的也一并取消 —— 那种情况下再弹一次才是 bug
                cancelDueAlarms = !enabled,
                persist = { settings.saveReminderScheduledAlarms(it) },
            )

            scheduleDailyPulse(context)
        }
    }

    /**
     * 纯函数：算出未来 [SCHEDULE_DAYS] 天内要排的上课提醒。
     *
     * - 只在"今天所属教学周"内匹配，开学前/假期跳周/学期后（teachingWeekOf 返回 null）不排；
     * - 触发时刻 = 该节次开始时间 − 提前分钟数；
     * - 只排仍在未来的（过去的不排，避免一开 App 就补一堆过期提醒）；
     * - 节次时间缺失/格式异常的课程直接跳过，不影响其他课。
     */
    internal fun planClassReminders(
        courses: List<CourseEntity>,
        sectionStartTimes: Map<Int, String>,
        semester: SettingsStore.SemesterConfig,
        minutes: Int,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedClassReminder> {
        val today = now.toLocalDate()
        val result = mutableListOf<PlannedClassReminder>()
        for (offset in 0 until SCHEDULE_DAYS) {
            val date = today.plusDays(offset.toLong())
            val week = teachingWeekOf(semester, date) ?: continue
            val dayOfWeek = date.dayOfWeek.value
            courses.forEach { course ->
                if (course.dayOfWeek != dayOfWeek || !course.hasClassOnWeek(week)) return@forEach
                val startTime = sectionStartTimes[course.startSection] ?: return@forEach
                val start = runCatching { LocalTime.parse(startTime) }.getOrNull() ?: return@forEach
                val trigger = LocalDateTime.of(date, start).minusMinutes(minutes.toLong())
                if (!trigger.isAfter(now)) return@forEach
                result += PlannedClassReminder(
                    requestCode = requestCodeOf(course, date),
                    triggerAtMillis = ReminderAlarmScheduler.toEpochMillis(trigger, zone),
                    courseName = course.name,
                    location = course.location,
                    startTime = startTime,
                    minutes = minutes,
                )
            }
        }
        return result
    }

    /**
     * 日期落在第几教学周；开学前/假期跳周/学期外都返回 null（那些天本来就没课）。
     * 统一走 [WeekResolver.teachingWeekOf]：此前这里有一份拷贝，兜底路径会在开学前
     * 6 天误判成"第 1 周"并为那些天排提醒。
     */
    private fun teachingWeekOf(semester: SettingsStore.SemesterConfig, date: LocalDate): Int? =
        WeekResolver.teachingWeekOf(semester, date)

    /**
     * 提醒的 requestCode = hash(课程 id + 日期)，落在 [0, REQUEST_CODE_RANGE)。
     * 内容寻址所以跨轮次稳定；同一 (课程,日期) 永远得到同一个码，删课/改时间也能精确取消。
     *
     * 碰撞概率：n 个码落在 m 个槽的生日近似 p ≈ n(n-1)/2m。本段 m = 7e6、窗口内 n ≈ 100，
     * p ≈ 7e-4（不是 1e-6 量级）。碰撞后果是"后设置的闹钟覆盖前一个"，表现为少弹一条且无日志，
     * 记录里会留下两条同码不同时刻。实测 40 门课 × 8 天的码无碰撞，故维持现状。
     */
    internal fun requestCodeOf(course: CourseEntity, date: LocalDate): Int =
        Math.floorMod("${course.id}@$date".hashCode(), REQUEST_CODE_RANGE)

    private fun remindPendingIntent(
        context: Context,
        plan: PlannedClassReminder,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_NAME, plan.courseName)
            .putExtra(EXTRA_LOCATION, plan.location)
            .putExtra(EXTRA_TIME_TEXT, "${plan.startTime} 上课")
            .putExtra(EXTRA_MINUTES, plan.minutes)
            .putExtra(ReminderAlarmScheduler.EXTRA_REQUEST_CODE, plan.requestCode)
        return PendingIntent.getBroadcast(
            context, plan.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 每日凌晨脉冲：触发一次 reschedule 让 8 天排期窗口永远向前滚动。
     *
     * 用 setInexactRepeating 而非"一次性闹钟 + 触发后重新排自己"：
     * 一次性脉冲一旦某次没送达（设备关机/Doze 深睡/OEM 清理）就永久不再续期，
     * 8 天后所有提醒静默失效；重复闹钟由系统常驻，不依赖 App 每次重新武装。
     * 续期只需在凌晨大致跑一次，非精确即可，也无需精确闹钟权限。
     *
     * 注意：它会被 Doze 推迟到早上的维护窗口，正好落在某节课的提醒窗口里 ——
     * 那个场景以前会吃掉这条提醒，现在由 ReminderAlarmScheduler 的"已到点不取消"兜住。
     */
    private fun scheduleDailyPulse(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_DAILY_PULSE)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_DAILY_PULSE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextRun = LocalDate.now().plusDays(1).atTime(4, 30)
        val millis = ReminderAlarmScheduler.toEpochMillis(nextRun)
        context.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pending)
    }
}
