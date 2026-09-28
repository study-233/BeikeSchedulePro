package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.ExamEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 列表和提醒共享文案，开始时间已知时不要退回“时间待定”。 */
fun ExamEntity.timeLabel(): String = when {
    kssj.isNotBlank() && jssj.isNotBlank() -> "$kssj–$jssj"
    kssj.isNotBlank() -> "$kssj 开始 · 结束待定"
    kssjms.isNotBlank() -> kssjms
    else -> "时间待定"
}

/** 不把当天已考完的考试继续显示为待考；时间未知时保守保留到当天结束。 */
fun ExamEntity.hasEnded(now: LocalDateTime): Boolean {
    val date = runCatching { LocalDate.parse(ksrq) }.getOrNull() ?: return false
    val end = runCatching { LocalTime.parse(jssj) }.getOrNull() ?: LocalTime.MAX
    return now.isAfter(date.atTime(end))
}
