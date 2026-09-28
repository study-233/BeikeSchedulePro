package com.caeamer.beikeschedule.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notice")
data class NoticeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val publishedAt: String,
    val external: Boolean,
    val url: String,
    val position: Int,
)

@Entity(tableName = "notice_feed")
data class NoticeFeedEntity(
    @PrimaryKey val id: Int = 1,
    val page: Int,
    val nextPage: Int,
    val hasNext: Boolean,
    val total: Int,
    val fetchedAt: Long,
)

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notice ORDER BY position") fun observeNotices(): Flow<List<NoticeEntity>>
    @Query("SELECT * FROM notice_feed WHERE id = 1") fun observeFeed(): Flow<NoticeFeedEntity?>
    @Query("SELECT * FROM notice_feed WHERE id = 1") suspend fun feed(): NoticeFeedEntity?
    @Query("SELECT * FROM notice ORDER BY position") suspend fun all(): List<NoticeEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rows: List<NoticeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFeed(feed: NoticeFeedEntity)
    @Query("DELETE FROM notice") suspend fun clearRows()
    @Query("DELETE FROM notice_feed") suspend fun clearFeed()
}
