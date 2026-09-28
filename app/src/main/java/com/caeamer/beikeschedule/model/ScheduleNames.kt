package com.caeamer.beikeschedule.model

object ScheduleNames {
    fun validate(name: String, existing: Collection<String>): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入课表名称" }
        require(trimmed !in existing) { "已有同名课表，请重新命名" }
        return trimmed
    }

    fun available(base: String, existing: Collection<String>): String {
        val stem = base.trim().ifBlank { "新课表" }
        if (stem !in existing) return stem
        var suffix = 2
        while ("$stem $suffix" in existing) suffix++
        return "$stem $suffix"
    }
}
