package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore

/** 界面与后台共用的事务快照；所有课表内容来自同一份 Room 数据。 */
data class ScheduleSnapshot(
    val courses: List<CourseEntity>,
    val sectionTimes: List<SectionTimeEntity>,
    val semester: SettingsStore.SemesterConfig,
    val scheduleId: Long = 1,
    val scheduleName: String = "默认课表",
    val reminderVersion: Long = 1,
)
