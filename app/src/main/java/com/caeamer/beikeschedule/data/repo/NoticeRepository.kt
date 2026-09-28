package com.caeamer.beikeschedule.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.NoticeFeedEntity
import com.caeamer.beikeschedule.import.parser.NoticePage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class NoticeRepository internal constructor(private val db: AppDatabase) {
    constructor(context: Context) : this(AppDatabase.get(context))
    private val dao = db.noticeDao()
    val notices = dao.observeNotices()
    val feed = dao.observeFeed()

    suspend fun save(page: NoticePage, ensureCurrent: () -> Unit) = db.withTransaction {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val previous = dao.feed()
        require(page.page == 1 || (previous?.hasNext == true && previous.nextPage == page.page)) {
            "公告列表已变化，请刷新后重试"
        }
        val old = if (page.page == 1) emptyList() else dao.all()
        val positions = old.associate { it.id to it.position }
        var position = (old.maxOfOrNull { it.position } ?: -1) + 1
        if (page.page == 1) dao.clearRows()
        dao.insert(page.rows.map { it.copy(position = positions[it.id] ?: position++) })
        dao.saveFeed(NoticeFeedEntity(page = page.page, nextPage = page.nextPage, hasNext = page.hasNext,
            total = page.total, fetchedAt = if (page.page == 1) System.currentTimeMillis() else requireNotNull(previous).fetchedAt))
        currentCoroutineContext().ensureActive()
        ensureCurrent()
    }

    suspend fun clear() = db.withTransaction { dao.clearRows(); dao.clearFeed() }
}
