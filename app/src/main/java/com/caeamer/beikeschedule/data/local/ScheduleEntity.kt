package com.caeamer.beikeschedule.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.caeamer.beikeschedule.data.pref.SettingsStore

@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val xn: String = "",
    val xq: String = "",
    val semesterName: String = "",
    val firstMonday: String = "",
    val totalWeeks: Int = 20,
    val weekMondays: String = "",
) {
    fun semester() = SettingsStore.SemesterConfig(
        xn, xq, semesterName, firstMonday, totalWeeks,
        weekMondays.split(',').filter { it.isNotBlank() },
    )

    fun withSemester(value: SettingsStore.SemesterConfig) = copy(
        xn = value.xn, xq = value.xq, semesterName = value.name,
        firstMonday = value.firstMonday, totalWeeks = value.totalWeeks,
        weekMondays = value.weekMondays.joinToString(","),
    )
}

/** 当前课表和提醒版本与课程修改一起提交，避免跨存储切换产生混合状态。 */
@Entity(tableName = "schedule_state")
data class ScheduleStateEntity(
    @PrimaryKey val id: Int = 1,
    val activeScheduleId: Long,
    val reminderVersion: Long = 1,
    val initialized: Boolean = false,
)

data class ScheduleWithDetails(
    @Embedded val schedule: ScheduleEntity,
    @Relation(parentColumn = "id", entityColumn = "scheduleId") val courses: List<CourseEntity>,
    @Relation(parentColumn = "id", entityColumn = "scheduleId") val sections: List<SectionTimeEntity>,
)

data class ActiveScheduleRecord(
    @Embedded val state: ScheduleStateEntity,
    @Relation(parentColumn = "activeScheduleId", entityColumn = "id", entity = ScheduleEntity::class)
    val details: ScheduleWithDetails,
)
