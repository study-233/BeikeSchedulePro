package com.caeamer.beikeschedule.model

/** 版本只在切换、清空、删除或重新导入时改变；每日重排不影响延迟投递。 */
object ClassReminderIdentity {
    fun key(scheduleId: Long, version: Long) = "$scheduleId:$version"

    fun matches(scheduleId: Long, version: Long, activeId: Long, activeVersion: Long): Boolean =
        scheduleId > 0 && version > 0 && scheduleId == activeId && version == activeVersion
}
