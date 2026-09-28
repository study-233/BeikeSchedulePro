package com.caeamer.beikeschedule.ui.freeroom

import com.caeamer.beikeschedule.data.remote.SmartClassParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 无课教室页面状态机单测（纯函数，`now` 可注入）。
 *
 * 这一层此前完全没有测试，而它承担了"打开页面时默认展开哪一节"与
 * "已结束的时段要不要淡化/提示"两个用户可见的判断。
 */
class FreeRoomUiStateTest {

    private fun slot(name: String, start: String, end: String, rooms: Int = 1) = SmartClassParser.RoomSlot(
        nodeId = name,
        nodeName = name,
        startTime = "2000-01-01 $start:00",
        endTime = "2000-01-01 $end:00",
        rooms = (1..rooms).map { SmartClassParser.FreeRoom(it.toLong(), "教学楼50$it", 1.0, 40) },
    )

    private val state = FreeRoomUiState(
        slots = listOf(
            slot("第一大节", "07:58", "09:35"),
            slot("第二大节", "09:53", "11:35"),
            slot("第六大节", "19:28", "21:05"),
        ),
    )

    // ——— 当前时段 ———

    @Test
    fun `时段内返回对应下标`() {
        assertEquals(0, state.currentSlotIndex(LocalTime.of(8, 0)))
        assertEquals(1, state.currentSlotIndex(LocalTime.of(10, 30)))
        assertEquals(2, state.currentSlotIndex(LocalTime.of(20, 0)))
    }

    @Test
    fun `边界时刻算进行中`() {
        assertEquals(0, state.currentSlotIndex(LocalTime.of(7, 58)))
        assertEquals(0, state.currentSlotIndex(LocalTime.of(9, 35)))
    }

    @Test
    fun `时段之间与全天之外返回 -1`() {
        assertEquals(-1, state.currentSlotIndex(LocalTime.of(9, 45)))
        assertEquals(-1, state.currentSlotIndex(LocalTime.of(6, 0)))
        // 21:48（真机截图时刻）：当天所有大节都已结束
        assertEquals(-1, state.currentSlotIndex(LocalTime.of(21, 48)))
    }

    @Test
    fun `时间串缺失或非法时跳过该段 - 不影响其它段`() {
        val broken = FreeRoomUiState(
            slots = listOf(slot("坏段", "", ""), slot("好段", "09:53", "11:35")),
        )
        assertEquals(1, broken.currentSlotIndex(LocalTime.of(10, 0)))
    }

    // ——— 默认展开 ———

    @Test
    fun `未手动操作时跟随当前时间`() {
        assertEquals(1, state.effectiveExpandedIndex(LocalTime.of(10, 0)))
    }

    @Test
    fun `全天之外回退到第一个大节`() {
        assertEquals(0, state.effectiveExpandedIndex(LocalTime.of(21, 48)))
    }

    @Test
    fun `手动收起（-1）不会被自动展开覆盖`() {
        val collapsed = state.copy(expandedIndex = -1)
        assertEquals(-1, collapsed.effectiveExpandedIndex(LocalTime.of(10, 0)))
    }

    @Test
    fun `手动展开优先于当前时间`() {
        val manual = state.copy(expandedIndex = 2)
        assertEquals(2, manual.effectiveExpandedIndex(LocalTime.of(10, 0)))
    }

    @Test
    fun `时段间隙优先下一时段且过去时段在后`() {
        val now = LocalTime.of(9, 45)
        assertEquals(1, state.effectiveExpandedIndex(now))
        assertEquals(listOf(1, 2, 0), state.orderedSlotIndices(now))
    }

    @Test
    fun `全部结束后当天记录按时间排列`() {
        assertEquals(listOf(0, 1, 2), state.orderedSlotIndices(LocalTime.of(23, 0)))
    }

    // ——— 进行中 / 已结束 ———

    @Test
    fun `时段状态判定`() {
        val now = LocalTime.of(10, 0)
        assertEquals(SlotPhase.PAST, state.slotPhase(0, now))
        assertEquals(SlotPhase.CURRENT, state.slotPhase(1, now))
        assertEquals(SlotPhase.UPCOMING, state.slotPhase(2, now))
    }

    @Test
    fun `时间串非法时不做断言 - 归为未开始`() {
        val broken = FreeRoomUiState(slots = listOf(slot("坏段", "bad", "")))
        assertEquals(SlotPhase.UPCOMING, broken.slotPhase(0, LocalTime.of(10, 0)))
    }

    @Test
    fun `全天结束后 allSlotsPast 为真`() {
        assertTrue(state.allSlotsPast(LocalTime.of(21, 48)))
        assertFalse(state.allSlotsPast(LocalTime.of(10, 0)))
        assertFalse(state.allSlotsPast(LocalTime.of(6, 0)))
    }

    @Test
    fun `没有数据时 allSlotsPast 为假 - 空列表不等于已结束`() {
        assertFalse(FreeRoomUiState().allSlotsPast(LocalTime.of(21, 48)))
    }

    // ——— 加载态 ———

    @Test
    fun `首屏加载与切楼栋都算阻塞加载`() {
        assertTrue(FreeRoomUiState(loading = true).blockingLoading)
        // 切楼栋：清空 slots + refreshing（没有数据），界面必须显示进度而不是"没有空教室"
        assertTrue(FreeRoomUiState(slots = emptyList(), refreshing = true).blockingLoading)
    }

    @Test
    fun `有旧数据的下拉刷新不算阻塞加载 - 列表要继续可见`() {
        val refreshing = state.copy(refreshing = true)
        assertTrue(refreshing.hasData)
        assertFalse(refreshing.blockingLoading)
    }
}
