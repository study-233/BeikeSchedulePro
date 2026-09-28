package com.caeamer.beikeschedule.data.local

import androidx.room.Entity

/** 每小节起止时间，导入时从教务 /component/queryKbjg 拉取并缓存。 */
@Entity(tableName = "section_time", primaryKeys = ["scheduleId", "section"])
data class SectionTimeEntity(
    val section: Int,  // 小节号 1..13（北科实测每天 13 小节）
    val startTime: String,         // "08:00"
    val endTime: String,           // "08:45"
    val scheduleId: Long = 1,
)
