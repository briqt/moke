package com.briqt.moke.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 全量映射 M3 角色：凡组件会用到的 container/tertiary/surfaceContainer* 都显式给值，
// 否则未定义角色会回退到 Material3 默认紫（FAB/FilterChip/Switch/TonalButton 曾因此发紫）。
private val MokeColorScheme = darkColorScheme(
    primary = MokeJade,
    onPrimary = MokeOnJade,
    primaryContainer = MokeJadeContainer,
    onPrimaryContainer = MokeOnJadeContainer,
    inversePrimary = MokeJadeInverse,

    secondary = MokeTeal,
    onSecondary = MokeOnJade,
    secondaryContainer = MokeTealContainer,
    onSecondaryContainer = MokeOnTealContainer,

    tertiary = MokeCyan,
    onTertiary = MokeOnCyan,
    tertiaryContainer = MokeCyanContainer,
    onTertiaryContainer = MokeOnCyanContainer,

    background = MokeBg,
    onBackground = MokeInk,
    surface = MokeSurface,
    onSurface = MokeInk,
    surfaceVariant = MokeSurfaceVariant,
    onSurfaceVariant = MokeOnSurfaceVariant,
    surfaceTint = MokeJade,

    surfaceContainerLowest = MokeSurfaceLowest,
    surfaceContainerLow = MokeSurfaceLow,
    surfaceContainer = MokeSurface,
    surfaceContainerHigh = MokeSurfaceHigh,
    surfaceContainerHighest = MokeSurfaceHighest,

    inverseSurface = MokeInk,
    inverseOnSurface = MokeSurface,

    error = MokeDanger,
    onError = MokeOnDanger,
    errorContainer = MokeDangerContainer,
    onErrorContainer = MokeOnDangerContainer,

    outline = MokeOutline,
    outlineVariant = MokeOutlineVariant,
    scrim = Color(0xFF000000),
)

@Composable
fun MokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MokeColorScheme,
        typography = Typography(),
        content = content,
    )
}
