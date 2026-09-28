package com.caeamer.beikeschedule.ui.freeroom

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.SmartClassApi
import com.caeamer.beikeschedule.data.remote.SmartClassException
import com.caeamer.beikeschedule.data.remote.SmartClassKeyProvider
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import com.caeamer.beikeschedule.data.repo.FreeRoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/** 某个大节相对于"现在"的位置。 */
enum class SlotPhase { PAST, CURRENT, UPCOMING }

/** 无课教室页面状态。 */
data class FreeRoomUiState(
    val buildings: List<SmartClassParser.Building> = emptyList(),
    val selectedBuildingId: String = "",
    val slots: List<SmartClassParser.RoomSlot> = emptyList(),
    /** 首次加载（尚无任何数据可显示）。 */
    val loading: Boolean = false,
    /** 下拉刷新中（已有旧数据，仍在刷新）。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    /**
     * 当前展开的时段下标。
     *
     * `-1` = 全部收起；`null` = 跟随当前时间（用户没手动操作过）。
     * 每次刷新会重置为 null，因为"现在能去哪"才是打开这个页面的意图。
     */
    val expandedIndex: Int? = null,
    /** 最近一次成功拿到数据的本机时刻（0 = 还没有数据）。用于在界面上交代数据新鲜度。 */
    val loadedAt: Long = 0L,
) {
    /** 是否已有可展示的数据（用于区分"首屏加载"与"刷新失败"）。 */
    val hasData: Boolean get() = slots.isNotEmpty()

    /** 是否处于"看不到列表的加载中"（切换楼栋时 loading 为真、首屏刷新时 refreshing 为真）。 */
    val blockingLoading: Boolean get() = loading || (refreshing && !hasData)

    /**
     * 实际展开的下标：用户手动选过就用他的，否则用当前时间所在的大节
     * （不在任何时段内则展开第一个）。
     *
     * @param now 注入"现在"，便于单测与界面 ticker 复算
     */
    fun effectiveExpandedIndex(now: LocalTime = LocalTime.now()): Int =
        expandedIndex ?: currentSlotIndex(now).takeIf { it >= 0 }
            ?: orderedSlotIndices(now).firstOrNull { slotPhase(it, now) != SlotPhase.PAST } ?: 0

    /** 正在进行/未来时段在前；已结束时段单独折叠。非法时间稳定地排在末尾。 */
    fun orderedSlotIndices(now: LocalTime): List<Int> = slots.indices.sortedWith(
        compareBy<Int> { slotPhase(it, now) == SlotPhase.PAST }
            .thenBy { slots[it].startHm.toLocalTimeOrNull() ?: LocalTime.MAX }.thenBy { it },
    )

    /**
     * 当前时间落在第几个大节（0 起）；不在任何时段内返回 -1。
     *
     * 时段是 `HH:mm` 文本。解析失败时**跳过该段**而不是让整个判断失效——
     * 一个时段的时间串异常不该导致"默认展开"功能整体失灵。
     */
    fun currentSlotIndex(now: LocalTime = LocalTime.now()): Int {
        slots.forEachIndexed { i, slot ->
            val start = slot.startHm.toLocalTimeOrNull() ?: return@forEachIndexed
            val end = slot.endHm.toLocalTimeOrNull() ?: return@forEachIndexed
            if (!now.isBefore(start) && !now.isAfter(end)) return i
        }
        return -1
    }

    /**
     * 某个大节相对于"现在"的状态：已结束 / 进行中 / 未开始。
     *
     * 时间串缺失或非法时返回 [SlotPhase.UPCOMING]（不做任何断言），
     * 与 [currentSlotIndex] 的"跳过"保持一致。
     */
    fun slotPhase(index: Int, now: LocalTime = LocalTime.now()): SlotPhase {
        val slot = slots.getOrNull(index) ?: return SlotPhase.UPCOMING
        val start = slot.startHm.toLocalTimeOrNull() ?: return SlotPhase.UPCOMING
        val end = slot.endHm.toLocalTimeOrNull() ?: return SlotPhase.UPCOMING
        return when {
            now.isAfter(end) -> SlotPhase.PAST
            now.isBefore(start) -> SlotPhase.UPCOMING
            else -> SlotPhase.CURRENT
        }
    }

    /**
     * 全天的时段是否都已结束。
     *
     * 用于回答"现在打开这个页面还能看到什么"：21:48 打开时今天所有大节都已过去，
     * 页面若照常展示"第一大节 07:58 起"，用户会以为现在还能去 503 自习。
     */
    fun allSlotsPast(now: LocalTime = LocalTime.now()): Boolean =
        slots.isNotEmpty() && slots.indices.all { slotPhase(it, now) == SlotPhase.PAST }
}

private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(this) }.getOrNull()

/**
 * 无课教室页面的 ViewModel。
 *
 * 数据来源是校外平台（`ustb.smartclass.cn`），与教务系统相互独立、也无需登录。
 * 打开即查一次 + 下拉刷新；空教室是实时数据，不做跨会话缓存。
 */
class FreeRoomViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val repo = FreeRoomRepository(SmartClassKeyProvider(app, SmartClassApi()))

    private val _state = MutableStateFlow(FreeRoomUiState(expandedIndex = savedState.get<Int>("expanded")))
    val state: StateFlow<FreeRoomUiState> = _state.asStateFlow()

    /**
     * 请求序号。切楼栋/刷新会自增，在途请求回来时序号已变则丢弃结果。
     *
     * 必须用序号而不是取消 Job：`HttpURLConnection` 的阻塞读不响应协程取消，
     * 取消只能让协程不再继续，无法阻止已经发出的请求返回并写入状态。
     */
    private var loadSeq = 0

    init {
        load(initial = true)
    }

    /**
     * 展开/收起某个时段。
     *
     * 用显式的 `-1` 表示"全部收起"（而非把 expandedIndex 置回 null）：
     * null 的语义是"跟随当前时间"，若复用 null 会导致收起后又自动展开。
     */
    fun toggleSlot(index: Int) {
        val cur = _state.value
        val next = if (cur.effectiveExpandedIndex() == index && cur.expandedIndex != -1) -1 else index
        savedState["expanded"] = next
        _state.update { it.copy(expandedIndex = next) }
    }

    fun selectBuilding(buildingId: String) {
        if (buildingId == _state.value.selectedBuildingId) return
        savedState["building"] = buildingId
        savedState.set<Int?>("expanded", null)
        loadSeq++   // 作废在途请求，避免旧楼栋的响应盖在新楼栋上
        _state.update {
            it.copy(
                selectedBuildingId = buildingId,
                slots = emptyList(),
                // 必须进加载态：只清 slots 会让界面在新数据回来前显示"没有查询到无课教室"
                loading = true,
                refreshing = false,
                error = null,
                // 展开态要跟着重置：新楼栋的大节数可能更少，沿用旧下标会一张卡片都不展开
                expandedIndex = null,
                loadedAt = 0L,
            )
        }
        viewModelScope.launch {
            settings.setFreeRoomBuilding(buildingId)
            loadRooms(buildingId)
        }
    }

    fun refresh() {
        if (!_state.value.loading && !_state.value.refreshing) load(initial = false)
    }

    /** 错误态下的重试。 */
    fun retry() = load(initial = _state.value.buildings.isEmpty())

    private fun load(initial: Boolean) {
        val seq = ++loadSeq
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = initial,
                    refreshing = !initial,
                    error = null,
                    // 首次恢复保留用户展开项；主动刷新回到当前时段。
                    expandedIndex = if (initial) it.expandedIndex else null,
                )
            }
            try {
                val meta = repo.loadMeta()
                // 元数据在途时用户切了楼栋（selectBuilding 也会自增序号）：
                // 放弃这次刷新，否则会把用户刚选的楼栋改回去、或用它的教室覆盖新选择
                if (seq != loadSeq) return@launch
                if (meta.buildings.isEmpty()) {
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = "没有获取到教学楼列表，请稍后重试")
                    }
                    return@launch
                }
                // 优先用上次选的楼；若它已不存在（服务端调整过楼栋）则退回第一栋
                val remembered = settings.freeRoomBuilding.first()
                val selected = meta.buildings.firstOrNull { it.id == remembered }?.id
                    ?: meta.buildings.first().id
                // 注意不要在这里把 loading 置 false：教室还没回来，置 false 会闪一屏空态
                if (selected != _state.value.selectedBuildingId) {
                    // 选择变了（持久化的楼栋已从服务端列表消失）：旧楼的教室必须一起清掉，
                    // 否则会出现"选中 B 楼 + 下面列的是 A 楼的教室"
                    _state.update {
                        it.copy(
                            buildings = meta.buildings,
                            selectedBuildingId = selected,
                            slots = emptyList(),
                            loading = true,
                            expandedIndex = if (initial && savedState.get<String>("building") == selected) savedState.get<Int>("expanded") else null,
                            loadedAt = 0L,
                        )
                    }
                    settings.setFreeRoomBuilding(selected)
                } else {
                    _state.update { it.copy(buildings = meta.buildings) }
                }
                savedState["building"] = selected
                loadRooms(selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != loadSeq) return@launch
                _state.update { it.copy(loading = false, refreshing = false, error = friendly(e)) }
            }
        }
    }

    private fun loadRooms(buildingId: String) {
        val seq = ++loadSeq
        viewModelScope.launch {
            try {
                val slots = repo.loadFreeRooms(buildingId)
                if (seq != loadSeq) return@launch
                _state.update {
                    it.copy(
                        slots = slots,
                        loading = false,
                        refreshing = false,
                        loadedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != loadSeq) return@launch
                _state.update { it.copy(loading = false, refreshing = false, error = friendly(e)) }
            }
        }
    }

    /**
     * 把异常翻成人话。
     *
     * "签名被拒"在 Repository 层已自动重试过一次，走到这里说明重试也没成功——
     * 最可能是 App 内置密钥已过期（服务端轮换了 key，且当前网络取不到新配置）。
     * 但对用户来说这仍只是"暂时查不到"，把内部机制（签名/密钥）暴露出来
     * 既帮不上忙也让人困惑，所以统一成一句可行动的话，细节进日志。
     */
    private fun friendly(e: Exception): String = when {
        e is SmartClassException && e.tokenRejected -> "暂时查不到空教室，请稍后重试"
        e is SmartClassException && !e.message.isNullOrBlank() -> e.message!!
        // 非预期异常不要把英文原文铺到中文界面上
        else -> DEFAULT_ERROR
    }

    private companion object {
        const val DEFAULT_ERROR = "加载失败，请检查网络后重试"
    }
}
