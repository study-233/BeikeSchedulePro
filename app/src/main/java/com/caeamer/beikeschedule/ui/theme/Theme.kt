package com.caeamer.beikeschedule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF246BCE), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FD), onPrimaryContainer = Color(0xFF173D70),
    secondary = Color(0xFF52647C), secondaryContainer = Color(0xFFEDF1F7),
    onSecondaryContainer = Color(0xFF293C55),
    tertiary = Color(0xFF526B76), tertiaryContainer = Color(0xFFE8F1F4),
    onTertiaryContainer = Color(0xFF294650),
    background = Color(0xFFF4F5F7), onBackground = Color(0xFF20242B),
    surface = Color.White, onSurface = Color(0xFF20242B),
    surfaceVariant = Color(0xFFEEF0F4), onSurfaceVariant = Color(0xFF59616E),
    outline = Color(0xFF858D98), outlineVariant = Color(0xFFE1E5EB),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF91BAFF), onPrimary = Color(0xFF123665),
    primaryContainer = Color(0xFF203B60), onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFFB4C5DC), secondaryContainer = Color(0xFF2B3543),
    onSecondaryContainer = Color(0xFFD9E3F0),
    tertiary = Color(0xFFA9CCD9), tertiaryContainer = Color(0xFF263F49),
    onTertiaryContainer = Color(0xFFD6EDF5),
    background = Color(0xFF111317), onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF1D2026), onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF292D35), onSurfaceVariant = Color(0xFFB2BAC7),
    outline = Color(0xFF838D9D), outlineVariant = Color(0xFF343A45),
)

/** 固定品牌色；仅明暗模式跟随系统，避免壁纸改变页面信息层级。 */
@Composable
fun BeikeScheduleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(18.dp)),
        content = content,
    )
}
