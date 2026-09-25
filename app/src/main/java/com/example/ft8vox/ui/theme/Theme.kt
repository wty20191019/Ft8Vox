package com.example.ft8vox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * new_ui.md §0 的固定深色配色。
 *
 * 有意**不使用** Material You 动态取色：设计指定了确定的色板与对比关系。
 * 亮色主题（§6.5 外观）待 U6 接入，届时按设置切换 [Ft8VoxTheme.darkTheme]。
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

// darkTheme 参数留给 U6「外观」使用；当前只提供深色板。
@Suppress("UNUSED_PARAMETER")
@Composable
fun Ft8VoxTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = VoxDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
