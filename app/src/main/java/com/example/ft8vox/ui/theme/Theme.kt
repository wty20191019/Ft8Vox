package com.example.ft8vox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * new_ui.md §0 的固定深色配色。
 *
 * 有意**不使用** Material You 动态取色：设计指定了确定的色板与对比关系。
 */
private val VoxDarkColorScheme = darkColorScheme(
    primary = VoxAccent,
    onPrimary = VoxBackground,
    primaryContainer = VoxSurfaceVariant,
    onPrimaryContainer = VoxText,
    secondary = VoxAccent,
    onSecondary = VoxBackground,
    tertiary = VoxAccent,
    background = VoxBackground,
    onBackground = VoxText,
    surface = VoxCard,
    onSurface = VoxText,
    surfaceVariant = VoxSurfaceVariant,
    onSurfaceVariant = VoxOnSurfaceVariant,
    surfaceContainer = VoxCard,
    surfaceContainerHigh = VoxSurfaceVariant,
    outline = VoxOutline,
    outlineVariant = VoxOutline,
    error = VoxError,
    onError = VoxBackground,
)

/** new_ui.md §6.5「外观 · 亮」：深色板的对偶，用更深的强调蓝保证白底对比度。 */
private val VoxLightColorScheme = lightColorScheme(
    primary = VoxLightAccent,
    onPrimary = Color.White,
    primaryContainer = VoxLightSurfaceVariant,
    onPrimaryContainer = VoxLightText,
    secondary = VoxLightAccent,
    onSecondary = Color.White,
    tertiary = VoxLightAccent,
    background = VoxLightBackground,
    onBackground = VoxLightText,
    surface = VoxLightCard,
    onSurface = VoxLightText,
    surfaceVariant = VoxLightSurfaceVariant,
    onSurfaceVariant = VoxLightOnSurfaceVariant,
    surfaceContainer = VoxLightCard,
    surfaceContainerHigh = VoxLightSurfaceVariant,
    outline = VoxLightOutline,
    outlineVariant = VoxLightOutline,
    error = Color(0xFFD20F39),
    onError = Color.White,
)

/**
 * 应用主题。
 *
 * - [darkTheme]：由「外观 · 暗/亮」设置驱动（U6 接入）。
 * - [fontScale]：由「外观 · 字体小/中/大」设置驱动；通过覆盖 `LocalDensity.fontScale`
 *   统一缩放所有 sp，dp 尺寸（触摸目标、间距）不受影响。
 */
@Composable
fun Ft8VoxTheme(
    darkTheme: Boolean = true,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale.coerceIn(0.8f, 1.5f)),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) VoxDarkColorScheme else VoxLightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
