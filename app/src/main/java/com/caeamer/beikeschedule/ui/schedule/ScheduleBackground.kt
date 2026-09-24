package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.ScheduleBackgroundStore
import com.caeamer.beikeschedule.model.BackgroundScale
import com.caeamer.beikeschedule.model.ScheduleAppearance
import com.caeamer.beikeschedule.ui.theme.CourseColors

internal data class ScheduleBackgroundImage(
    val name: String,
    val bitmap: ImageBitmap? = null,
    val loading: Boolean = true,
)

@Composable
internal fun rememberScheduleBackground(name: String): ScheduleBackgroundImage {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ScheduleBackgroundStore(context) }
    val result by produceState(ScheduleBackgroundImage(name), name, store) {
        value = ScheduleBackgroundImage(name, store.load(name)?.asImageBitmap(), loading = false)
    }
    // 切换文件名时不展示上一张图；位图由 Compose/GC 管理，避免绘制过程中 recycle。
    return if (result.name == name) result else ScheduleBackgroundImage(name)
}

@Composable
internal fun ScheduleBackground(
    appearance: ScheduleAppearance,
    modifier: Modifier = Modifier,
    image: ScheduleBackgroundImage = rememberScheduleBackground(appearance.backgroundFile),
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier.clipToBounds().background(
            if (dark) CourseColors.scheduleGradientDark else CourseColors.scheduleGradient,
        ),
    ) {
        image.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = if (appearance.imageScale == BackgroundScale.CROP) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().blur(appearance.blurDp.dp),
            )
            Box(
                Modifier.fillMaxSize().background(
                    MaterialTheme.colorScheme.background.copy(alpha = appearance.overlayPercent / 100f),
                ),
            )
        }
    }
}
