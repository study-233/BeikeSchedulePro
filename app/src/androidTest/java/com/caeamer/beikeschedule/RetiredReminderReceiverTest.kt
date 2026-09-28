package com.caeamer.beikeschedule

import android.content.ContextWrapper
import android.content.Intent
import com.caeamer.beikeschedule.reminder.ReminderReceiver
import com.caeamer.beikeschedule.reminder.TodoReminderScheduler
import org.junit.Test

class RetiredReminderReceiverTest {
    @Test fun delayedLegacyBroadcastDoesNotReadDataScheduleOrNotify() {
        // 旧广播必须在访问权限、存储或通知服务之前返回；任何 Context 操作都会使测试失败。
        val noServices = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = error("旧日程广播不应访问系统服务")
        }
        ReminderReceiver().onReceive(noServices, Intent(TodoReminderScheduler.ACTION_TODO_REMIND)
            .putExtra("title", "旧日程").putExtra("requestCode", 7_000_001))
    }
}
