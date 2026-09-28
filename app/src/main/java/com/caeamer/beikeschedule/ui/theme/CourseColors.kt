package com.caeamer.beikeschedule.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 课程块色板（参考 WakeUp：浅色底 + 同色深字）。按 colorIndex 取模循环。 */
object CourseColors {
    data class CardColors(val background: Color, val title: Color, val location: Color, val detail: Color)
    // (底色, 文字色) 对
    private val basePalette = listOf(
        Color(0xFFFCDFD6) to Color(0xFF8C3B2E), // 0 珊瑚粉
        Color(0xFFD6E4FC) to Color(0xFF2E4E8C), // 1 淡蓝
        Color(0xFFD9F0DC) to Color(0xFF2F6B3C), // 2 淡绿
        Color(0xFFFCEFD6) to Color(0xFF8C6A2E), // 3 杏黄
        Color(0xFFE8DFFC) to Color(0xFF5B3E8C), // 4 淡紫
        Color(0xFFD6F0F5) to Color(0xFF2E7B8C), // 5 青
        Color(0xFFFCDDE8) to Color(0xFF8C2E56), // 6 玫粉
        Color(0xFFE3E8EF) to Color(0xFF45536B), // 7 灰蓝
        Color(0xFFF0F5D6) to Color(0xFF6B7A2E), // 8 草绿
        Color(0xFFDFE8FC) to Color(0xFF3E5B8C), // 9 靛蓝
    )

    private val userPalette = basePalette

    private val cardPalettes by lazy {
        listOf(false, true).flatMap { dark ->
            listOf(false, true).map { active ->
                (dark to active) to basePalette.indices.map { index ->
                    val (base, foreground) = of(index, dark)
                    val neutral = if (dark) Color(0xFF292D35) else Color(0xFFEEF0F4)
                    val background = if (active) base else lerp(base, neutral, 0.55f)
                    val ink = if (dark) Color.White else Color(0xFF20242B)
                    val title = readable(lerp(foreground, ink, 0.22f), background, if (active) 7f else 5.5f)
                    CardColors(background, title,
                        readable(lerp(title, background, 0.12f), background, 5f),
                        readable(lerp(title, background, 0.22f), background, 4.6f))
                }
            }
        }.toMap()
    }

    /** 仅供应用课表使用：文字均不透明，小组件继续使用 of 的原有色板。 */
    fun card(colorIndex: Int, dark: Boolean = false, active: Boolean = true): CardColors =
        cardPalettes.getValue(dark to active)[Math.floorMod(colorIndex, basePalette.size)]

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    /** 保留色相，必要时向对比度更高的黑/白端点校正；预计算后不在绘制期间迭代。 */
    private fun readable(color: Color, background: Color, minimum: Float): Color {
        if (contrast(color, background) >= minimum) return color
        val end = if (contrast(Color.Black, background) > contrast(Color.White, background)) Color.Black else Color.White
        var low = 0f
        var high = 1f
        repeat(16) {
            val mid = (low + high) / 2
            if (contrast(lerp(color, end, mid), background) >= minimum) high = mid else low = mid
        }
        return lerp(color, end, high)
    }

    val defaultColorIndex: Int get() = 0

    /**
     * 教务课程颜色：教务 XB 色值如果在本色板范围内直接用（保证导入课与原版一致），
     * 超出范围或与已有冲突时退化为取模。floorMod 保证任意 Int（含 MIN_VALUE）结果非负。
     */
    fun importedOf(colorIndex: Int): Int =
        if (colorIndex in basePalette.indices) colorIndex else Math.floorMod(colorIndex, basePalette.size)

    /** 深色主题保留色相，降低卡片亮度；小组件沿用既有浅色色板。 */
    fun of(colorIndex: Int, dark: Boolean = false): Pair<Color, Color> {
        val (background, foreground) = userPalette[Math.floorMod(colorIndex, userPalette.size)]
        return if (dark) androidx.compose.ui.graphics.lerp(Color(0xFF1D2026), background, 0.20f) to
            androidx.compose.ui.graphics.lerp(background, Color.White, 0.18f)
        else background to foreground
    }
}
