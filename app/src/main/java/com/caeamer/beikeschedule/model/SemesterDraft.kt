package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.pref.SettingsStore

/** 只携带用户可编辑字段；保存时合并最新官方校历，绝不回写进入页面时的旧快照。 */
data class SemesterDraft(val name: String, val firstMonday: String, val totalWeeks: Int) {
    fun applyTo(current: SettingsStore.SemesterConfig) = current.copy(
        name = name.trim(), firstMonday = firstMonday,
        totalWeeks = totalWeeks.coerceAtLeast(current.weekMondays.size.coerceAtLeast(1)),
    )
    companion object {
        fun from(config: SettingsStore.SemesterConfig) = SemesterDraft(config.name, config.firstMonday, config.totalWeeks)
    }
}
