package com.caeamer.beikeschedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * 课程块。字段与 docs/TECH_DESIGN.md 第 5 节一致：
 * 周次采用教务 32 位位图（weekBitmap），无固定时间课程 dayOfWeek=0。
 */
@Entity(tableName = "course", indices = [Index("scheduleId")])
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,        // 教务任务号 RWH（导入源唯一键，手动添加为空串）
    val name: String,          // 课程名
    val teacher: String,       // 教师
    val location: String,      // 上课地点（含【校区】前缀原样保留）
    val dayOfWeek: Int,        // 1..7（周一..周日），0=无固定时间课程
    val startSection: Int,     // 起始小节（对应教务 KSJC）
    val endSection: Int,       // 结束小节（对应教务 JSJC）
    val weekBitmap: String,    // 教务 ZC 位图：ZC[i] 对应第 i 周（index 0 恒为 '0' 占位），'1'=该周有课
    val colorIndex: Int,       // 色板下标（教务 XB；99999 视为无固定时间课程）
    val source: Int,           // 0=教务导入 1=手动添加 2=示例
    val hidden: Boolean = false, // 隐藏后不显示也不安排提醒
    @ColumnInfo(defaultValue = "1") val scheduleId: Long = 1,
) {
    fun hasClassOnWeek(week: Int): Boolean =
        week in 1 until weekBitmap.length && weekBitmap[week] == '1'

    val isUnscheduled: Boolean get() = dayOfWeek == 0

    companion object {
        const val SOURCE_IMPORT = 0
        const val SOURCE_MANUAL = 1
        const val SOURCE_SAMPLE = 2   // 示例数据，可一键清除
        const val COLOR_UNSCHEDULED = 99999
    }
}
