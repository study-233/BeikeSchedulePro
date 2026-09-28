package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Query("SELECT * FROM exam ORDER BY ksrq, kssj")
    fun observeAll(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam ORDER BY ksrq, kssj")
    suspend fun getAll(): List<ExamEntity>

    @Insert
    suspend fun insertAll(exams: List<ExamEntity>)

    @Query("SELECT * FROM exam WHERE id = :id")
    suspend fun get(id: Long): ExamEntity?

    @Insert
    suspend fun insert(exam: ExamEntity): Long

    @Update
    suspend fun update(exam: ExamEntity): Int

    @Query("DELETE FROM exam WHERE id = :id AND source = 1")
    suspend fun deleteManual(id: Long): Int

    @Query("DELETE FROM exam WHERE source = 0")
    suspend fun clearImported()
}
