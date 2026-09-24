package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore

/** 后台展示使用的只读快照；Room 与 DataStore 仍各自保存原有数据。 */
data class ScheduleSnapshot(
    val courses: List<CourseEntity>,
    val sectionTimes: List<SectionTimeEntity>,
    val semester: SettingsStore.SemesterConfig,
)
