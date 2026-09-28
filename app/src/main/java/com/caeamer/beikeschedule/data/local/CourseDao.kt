package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM course WHERE scheduleId = :scheduleId ORDER BY dayOfWeek, startSection")
    fun observeAll(scheduleId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE scheduleId = :scheduleId ORDER BY dayOfWeek, startSection, endSection, id")
    suspend fun getAll(scheduleId: Long): List<CourseEntity>

    @Query("SELECT * FROM course WHERE scheduleId = :scheduleId AND source = :source")
    suspend fun getBySource(scheduleId: Long, source: Int): List<CourseEntity>

    /** 全部同类课程（含隐藏），用于多时段课程分组编辑。 */
    @Query("SELECT * FROM course WHERE scheduleId = :scheduleId AND source IN (:sources) AND name = :name ORDER BY dayOfWeek, startSection")
    fun observeByNames(scheduleId: Long, sources: List<Int>, name: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE scheduleId = :scheduleId AND id IN (:ids)")
    suspend fun getByIds(scheduleId: Long, ids: List<Long>): List<CourseEntity>

    @Query("UPDATE course SET hidden = :hidden WHERE scheduleId = :scheduleId AND id = :id")
    suspend fun setHidden(scheduleId: Long, id: Long, hidden: Boolean)

    /** 整组隐藏/恢复：一张卡可能对应多行（教务拆行、手动多时段），必须一次事务写完。 */
    @Query("UPDATE course SET hidden = :hidden WHERE scheduleId = :scheduleId AND id IN (:ids)")
    suspend fun setHiddenForIds(scheduleId: Long, ids: List<Long>, hidden: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity): Long

    @androidx.room.Update
    suspend fun update(course: CourseEntity)

    @Query("DELETE FROM course WHERE scheduleId = :scheduleId AND id = :id")
    suspend fun deleteById(scheduleId: Long, id: Long)

    @Query("DELETE FROM course WHERE scheduleId = :scheduleId AND source = :source")
    suspend fun deleteBySource(scheduleId: Long, source: Int)

    @Query("DELETE FROM course WHERE scheduleId = :scheduleId")
    suspend fun clear(scheduleId: Long)

    @Query("SELECT COUNT(*) FROM course WHERE scheduleId = :scheduleId")
    suspend fun count(scheduleId: Long): Int
}
