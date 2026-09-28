package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop

internal data class SchedulePager(val state: PagerState, val ready: Boolean)

/** 周次定位不依赖 Pager 已挂载：加载中和空课表均可能尚未创建 HorizontalPager。 */
@Composable
internal fun rememberSchedulePager(
    loaded: Boolean,
    selectedWeek: Int,
    totalWeeks: Int,
    onWeekSelected: (Int) -> Unit,
): SchedulePager {
    val pageCount = totalWeeks.coerceAtLeast(1)
    val target = (selectedWeek - 1).coerceIn(0, pageCount - 1)
    val state = rememberPagerState(initialPage = target, pageCount = { pageCount })
    val selectWeek by rememberUpdatedState(onWeekSelected)
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(loaded, target, pageCount) {
        if (loaded) {
            // scrollToPage 会等待首次布局，而界面又等待 ready 才创建 Pager，造成循环等待。
            // 请求下一次测量直接定位，随后即可显示内容，不经过从第 1 周滚动的动画。
            if (!ready || state.currentPage != target) state.requestScrollToPage(target)
            ready = true
        } else {
            ready = false
        }
    }
    LaunchedEffect(state, ready) {
        if (ready) snapshotFlow { state.settledPage }
            .drop(1).collect { selectWeek(it + 1) }
    }
    return SchedulePager(state, loaded && ready)
}
