package com.caeamer.beikeschedule.ui.notices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.NoticeEntity
import com.caeamer.beikeschedule.data.local.NoticeFeedEntity
import com.caeamer.beikeschedule.data.repo.NoticeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

data class NoticesUiState(val rows: List<NoticeEntity> = emptyList(), val feed: NoticeFeedEntity? = null,
                          val loaded: Boolean = false, val error: String? = null)

class NoticesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NoticeRepository(app)
    val state = combine(repo.notices, repo.feed) { rows, feed -> NoticesUiState(rows, feed, true) }
        .catch { if (it is CancellationException) throw it else emit(NoticesUiState(loaded = true, error = "读取公告缓存失败")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NoticesUiState())
}
