package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calendar_adjustment_cache")
data class CalendarAdjustmentCache(
    @PrimaryKey val id: Int = 1,
    val body: String = "",
    val lastCheckedAt: Long = 0,
    val updatedAt: Long = 0,
    val status: String = "尚未获取调休配置",
)

@Dao
interface CalendarAdjustmentDao {
    @Query("SELECT * FROM calendar_adjustment_cache WHERE id = 1")
    suspend fun get(): CalendarAdjustmentCache?

    @Query("SELECT * FROM calendar_adjustment_cache WHERE id = 1")
    fun observe(): Flow<CalendarAdjustmentCache?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: CalendarAdjustmentCache)
}
