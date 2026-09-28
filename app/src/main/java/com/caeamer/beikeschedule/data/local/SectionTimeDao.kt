package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionTimeDao {

    @Query("SELECT * FROM section_time WHERE scheduleId = :scheduleId ORDER BY section")
    fun observeAll(scheduleId: Long): Flow<List<SectionTimeEntity>>

    @Query("SELECT * FROM section_time WHERE scheduleId = :scheduleId ORDER BY section")
    suspend fun getAll(scheduleId: Long): List<SectionTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<SectionTimeEntity>)

    @Query("DELETE FROM section_time WHERE scheduleId = :scheduleId")
    suspend fun clear(scheduleId: Long)
}
