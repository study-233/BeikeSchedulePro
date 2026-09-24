package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.BackgroundScale
import com.caeamer.beikeschedule.model.ScheduleAppearance
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAppearanceTest {
    @Test
    fun `新配置保持原字号和默认渐变`() {
        val value = ScheduleAppearance().normalized()
        assertEquals(100, value.fontPercent)
        assertEquals("", value.backgroundFile)
        assertEquals(BackgroundScale.CROP, value.imageScale)
        assertEquals(30, value.overlayPercent)
        assertEquals(0, value.blurDp)
    }

    @Test
    fun `越界设置限制到合法范围且字号对齐百分之五`() {
        assertEquals(80, ScheduleAppearance(fontPercent = Int.MIN_VALUE).normalized().fontPercent)
        assertEquals(160, ScheduleAppearance(fontPercent = Int.MAX_VALUE).normalized().fontPercent)
        assertEquals(105, ScheduleAppearance(fontPercent = 103).normalized().fontPercent)
        val low = ScheduleAppearance(overlayPercent = -1, blurDp = -1).normalized()
        val high = ScheduleAppearance(overlayPercent = 999, blurDp = 999).normalized()
        assertEquals(0, low.overlayPercent)
        assertEquals(0, low.blurDp)
        assertEquals(80, high.overlayPercent)
        assertEquals(24, high.blurDp)
    }

    @Test
    fun `恢复背景保留字号且清除图片样式`() {
        val restored = ScheduleAppearance(
            fontPercent = 145, backgroundFile = FILE_NAME,
            imageScale = BackgroundScale.FIT, overlayPercent = 70, blurDp = 20,
        ).withoutBackground()
        assertEquals(ScheduleAppearance(fontPercent = 145), restored)
    }

    @Test
    fun `图片标识拒绝外部路径而保留本地文件名`() {
        assertEquals(FILE_NAME, ScheduleAppearance(backgroundFile = FILE_NAME).normalized().backgroundFile)
        listOf("../$FILE_NAME", "/tmp/$FILE_NAME", "content://gallery/photo", "invalid.jpg").forEach {
            assertEquals("", ScheduleAppearance(backgroundFile = it).normalized().backgroundFile)
        }
    }

    companion object {
        private const val FILE_NAME = "f40cdb80-98a0-4c72-b681-7393c09e0800.png"
    }
}
