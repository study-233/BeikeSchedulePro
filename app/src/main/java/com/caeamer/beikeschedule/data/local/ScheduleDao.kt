package com.caeamer.beikeschedule.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule ORDER BY createdAt, id")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun get(id: Long): ScheduleEntity?

    @Insert suspend fun insert(schedule: ScheduleEntity): Long
    @Update suspend fun update(schedule: ScheduleEntity)
    @Query("DELETE FROM schedule WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM schedule_state WHERE id = 1") suspend fun state(): ScheduleStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveState(state: ScheduleStateEntity)

    @Transaction
    @Query("SELECT * FROM schedule_state WHERE id = 1")
    fun observeCurrent(): Flow<ActiveScheduleRecord?>

    @Transaction
    @Query("SELECT * FROM schedule_state WHERE id = 1")
    suspend fun current(): ActiveScheduleRecord?
}
