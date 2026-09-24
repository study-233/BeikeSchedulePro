package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM course ORDER BY dayOfWeek, startSection")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course ORDER BY dayOfWeek, startSection, endSection, id")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM course WHERE source = :source")
    suspend fun getBySource(source: Int): List<CourseEntity>

    /** 全部同类课程（含隐藏），用于多时段课程分组编辑。 */
    @Query("SELECT * FROM course WHERE source IN (:sources) AND name LIKE :name ORDER BY dayOfWeek, startSection")
    fun observeByNames(sources: List<Int>, name: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CourseEntity>

    @Query("UPDATE course SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** 整组隐藏/恢复：一张卡可能对应多行（教务拆行、手动多时段），必须一次事务写完。 */
    @Query("UPDATE course SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHiddenForIds(ids: List<Long>, hidden: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity): Long

    @androidx.room.Update
    suspend fun update(course: CourseEntity)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM course WHERE source = :source")
    suspend fun deleteBySource(source: Int)

    @Query("SELECT COUNT(*) FROM course")
    suspend fun count(): Int
}
