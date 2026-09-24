package com.caeamer.beikeschedule.model

import kotlin.math.roundToInt

/** 课表外观；只保存本地图片文件名，不保存外部 URI 或绝对路径。 */
data class ScheduleAppearance(
    val fontPercent: Int = 100,
    val backgroundFile: String = "",
    val imageScale: BackgroundScale = BackgroundScale.CROP,
    val overlayPercent: Int = 30,
    val blurDp: Int = 0,
) {
    val fontScale: Float get() = fontPercent / 100f

    fun normalized(): ScheduleAppearance = copy(
        fontPercent = ((fontPercent.coerceIn(80, 160) / 5f).roundToInt() * 5),
        backgroundFile = backgroundFile.takeIf { BACKGROUND_NAME.matches(it) }.orEmpty(),
        overlayPercent = overlayPercent.coerceIn(0, 80),
        blurDp = blurDp.coerceIn(0, 24),
    )

    fun withoutBackground(): ScheduleAppearance = ScheduleAppearance(fontPercent = fontPercent)

    companion object {
        private val BACKGROUND_NAME = Regex("[a-f0-9-]{36}\\.png")
    }
}

enum class BackgroundScale { CROP, FIT }
