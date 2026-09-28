package com.caeamer.beikeschedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机、升级与时间变化后清理旧日程，独立重排课程与考试提醒。
 *
 * 闹钟不随重启保留，所以要监听 BOOT_COMPLETED；而所有闹钟都按 epoch 毫秒排、
 * 计划却按本地时间算，跨时区或手动改时间后已排的闹钟会整体错点，
 * 所以 TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED 也要走一遍同样的全量重排
 * （reschedule 是幂等的"先算后换"，多跑一次没有副作用）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { TodoReminderScheduler.cleanup(context.applicationContext) }
                runCatching { ClassReminderScheduler.reschedule(context.applicationContext) }
                runCatching { ExamReminderScheduler.reschedule(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
