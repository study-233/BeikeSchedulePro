package com.caeamer.beikeschedule.ui.freeroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 无课教室页面（贝壳教学平台）。
 *
 * 数据来自校外平台，与教务系统独立、无需登录。打开即查一次、支持下拉刷新，
 * 不做自动轮询 —— 空座率虽会变，但用户看一眼就走的场景不需要实时推送。
 *
 * 页面要回答的问题是"**现在（或下一节）我能去哪间教室**"，所以：
 * 1. 顶部交代时间基准（今天几号周几、数据何时更新、来自哪里）；
 * 2. 当前时段自动展开并标"进行中"，已结束的时段淡化并标"已结束"；
 * 3. 全天都结束时明说"今天的时段已全部结束"，而不是把一个上午的时段摆在最显眼处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRoomScreen(viewModel: FreeRoomViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // "现在"每 30 秒复算一次：用于 进行中/已结束 判定与时段自动展开。
    // 只在 RESUMED 时走时钟——退到后台还继续跑 ticker 是这个项目已经踩过的坑。
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = LocalDateTime.now()
                delay(TICK_MS)
            }
        }
    }

    // 换楼栋后回到列表顶部：LazyColumn 的 key 是大节 ID（与楼栋无关），
    // 不重置的话用户会被留在上一个楼栋的滚动位置
    var displayedBuilding by rememberSaveable { mutableStateOf(state.selectedBuildingId) }
    LaunchedEffect(state.selectedBuildingId) {
        if (state.selectedBuildingId.isNotBlank()) {
            if (displayedBuilding.isNotBlank() && displayedBuilding != state.selectedBuildingId) listState.scrollToItem(0)
            displayedBuilding = state.selectedBuildingId
        }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            MetaLine(state = state, now = now, onRefresh = viewModel::refresh)

            if (state.buildings.isNotEmpty()) {
                BuildingTabs(
                    buildings = state.buildings,
                    selectedId = state.selectedBuildingId,
                    onSelect = viewModel::selectBuilding,
                )
            }

            when {
                state.blockingLoading -> CenterBox { CircularProgressIndicator() }

                // 有旧数据时不整屏报错：顶部给一行提示，下面继续显示上次的结果，
                // 比"刷新失败就白屏"更符合用户预期（尤其地铁里网络抖动）
                state.error != null && !state.hasData -> StateMessage(state.error!!, "重试", viewModel::retry)

                // 空态必须给出口：这里的内容不可滚动，下拉刷新在这个分支上不生效
                !state.hasData -> StateMessage("今天没有查询到无课教室", "重新查询", viewModel::refresh)

                else -> {
                    state.error?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    val localNow = now.toLocalTime()
                    if (state.allSlotsPast(localNow)) {
                        Text(
                            "今天的时段已全部结束，以下为今天的记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    SlotList(state = state, now = localNow, listState = listState, onToggle = viewModel::toggleSlot)
                }
            }
        }
    }
}

/** 时间基准：今天几号周几 · 数据何时更新 · 来自哪个平台。 */
@Composable
private fun MetaLine(state: FreeRoomUiState, now: LocalDateTime, onRefresh: () -> Unit) {
    val date = now.toLocalDate()
    val text = buildString {
        append("今天 ")
        append(date.format(MM_DD_FORMAT))
        append(' ')
        append(WEEK_LABELS[date.dayOfWeek.value - 1])
        if (state.loadedAt > 0L) {
            append(" · ")
            append(hmLabel(state.loadedAt))
            append(" 更新")
        }

    }
    val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val stale = state.loadedAt > 0L && nowMillis - state.loadedAt > STALE_MS
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (stale) {
            TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text("刷新", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 楼栋横向切换（可滚动，5 栋楼在窄屏上放不下）。
 *
 * 用 `selectable` + `Role.Tab` 而不是 `clickable`：读屏要能播报"已选中"，
 * 且点击区域至少要 48dp 高（此前 8dp 上下内边距只有约 36dp）。
 */
@Composable
private fun BuildingTabs(
    buildings: List<SmartClassParser.Building>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buildings.forEach { b ->
            val selected = b.id == selectedId
            Text(
                b.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(b.id) })
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/** 6 个大节，每个可展开/收起；当前时段默认展开。 */
@Composable
private fun SlotList(
    state: FreeRoomUiState,
    now: LocalTime,
    listState: LazyListState,
    onToggle: (Int) -> Unit,
) {
    val expanded = state.effectiveExpandedIndex(now)
    var showPast by rememberSaveable(state.selectedBuildingId) { mutableStateOf(false) }
    val allPast = state.allSlotsPast(now)
    val ordered = state.orderedSlotIndices(now)
    val active = ordered.filter { state.slotPhase(it, now) != SlotPhase.PAST }
    val past = ordered.filter { state.slotPhase(it, now) == SlotPhase.PAST }
    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(if (allPast) past else active, key = { "slot_${state.slots[it].nodeId}_$it" }) { index ->
            SlotCard(state.slots[index], state.slotPhase(index, now), index == expanded, { onToggle(index) })
        }
        if (!allPast && past.isNotEmpty()) {
            item(key = "past_toggle") {
                TextButton(onClick = { showPast = !showPast }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (showPast) "收起已结束时段" else "已结束时段（${past.size}）")
                }
            }
            if (showPast) items(past, key = { "slot_${state.slots[it].nodeId}_$it" }) { index ->
                SlotCard(state.slots[index], SlotPhase.PAST, index == expanded, { onToggle(index) })
            }
        }
    }
}

@Composable
private fun SlotCard(
    slot: SmartClassParser.RoomSlot,
    phase: SlotPhase,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val past = phase == SlotPhase.PAST
    Column(
        Modifier
            .fillMaxWidth()
            .clip(com.caeamer.beikeschedule.ui.common.AppLayout.GroupShape)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = if (past) 0.7f else 1f),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 展开态要能被读屏感知：Role.Button + stateDescription（原先只有图标方向）
                .clickable(
                    onClickLabel = if (expanded) "收起" else "展开",
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        slot.nodeName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (past) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (phase != SlotPhase.UPCOMING) {
                        Spacer(Modifier.width(6.dp))
                        PhaseBadge(phase)
                    }
                }
                val span = if (slot.startHm.isNotEmpty() && slot.endHm.isNotEmpty()) {
                    "${slot.startHm}-${slot.endHm}"
                } else ""
                Text(
                    listOf(span, "${slot.rooms.size} 间空教室").filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                // 语义由整行的 stateDescription 承担，图标不重复播报
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                slot.rooms.forEach { RoomRow(it) }
            }
        }
    }
}

/** 「进行中」/「已结束」标记：文字表达，不依赖颜色。 */
@Composable
private fun PhaseBadge(phase: SlotPhase) {
    val label: String
    val container: Color
    val content: Color
    when (phase) {
        SlotPhase.CURRENT -> {
            label = "进行中"
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        SlotPhase.PAST -> {
            label = "已结束"
            container = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        SlotPhase.UPCOMING -> return
    }
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 单间教室：教室名 + 座位数 + 空座率。
 *
 * 尾部两列是**定宽右对齐**的：座位数位数不同（83 座 / 1002 座）时，
 * 若按内容宽度排布，"空座率"这一列会左右浮动，长列表就没法竖着扫读了。
 */
@Composable
private fun RoomRow(room: SmartClassParser.FreeRoom) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(room.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (room.seatCount > 0) "${room.seatCount} 座" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SEAT_COLUMN_WIDTH),
        )
        Spacer(Modifier.width(8.dp))
        // noSeatRate 为 null 表示"暂无数据"，必须与"空座率真的是 0%"区分开
        val rateText = room.noSeatRate?.let { "${(it * 100).roundToInt()}% 空座" } ?: "暂无数据"
        Text(
            rateText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (room.noSeatRate != null && room.noSeatRate >= 0.5) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(RATE_COLUMN_WIDTH),
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * 空态/错误态。
 *
 * 顶部对齐 + 可滚动，而不是居中的 `Box`：`PullToRefreshBox` 只从**可滚动子内容**
 * 拿到下拉手势，居中不动的内容会让"下拉刷新"在这两个状态下彻底失效。
 * 同时显式给一个按钮，保证即使不熟悉下拉手势的用户也有出口。
 */
@Composable
private fun StateMessage(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onAction) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(actionLabel)
        }
    }
}

private const val TICK_MS = 30_000L
private const val STALE_MS = 5 * 60 * 1000L
private val SEAT_COLUMN_WIDTH = 56.dp
private val RATE_COLUMN_WIDTH = 72.dp
private val MM_DD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val HH_MM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** DayOfWeek.value 1..7 → 周一..周日（避免依赖 Locale 的文本格式）。 */
private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun hmLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(HH_MM_FORMAT)
