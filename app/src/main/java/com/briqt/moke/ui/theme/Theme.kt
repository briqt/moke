package com.briqt.moke.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

// 浅色方案：与暗色一一对应地全量映射 M3 角色（同理由——漏给的角色会回退默认紫）。
private val MokeLightColorScheme = lightColorScheme(
    primary = MokeJadeL,
    onPrimary = MokeOnJadeL,
    primaryContainer = MokeJadeContainerL,
    onPrimaryContainer = MokeOnJadeContainerL,
    inversePrimary = MokeJadeInverseL,

    secondary = MokeTealL,
    onSecondary = MokeOnJadeL,
    secondaryContainer = MokeTealContainerL,
    onSecondaryContainer = MokeOnTealContainerL,

    tertiary = MokeCyanL,
    onTertiary = MokeOnCyanL,
    tertiaryContainer = MokeCyanContainerL,
    onTertiaryContainer = MokeOnCyanContainerL,

    background = MokeBgL,
    onBackground = MokeInkL,
    surface = MokeSurfaceL,
    onSurface = MokeInkL,
    surfaceVariant = MokeSurfaceVariantL,
    onSurfaceVariant = MokeOnSurfaceVariantL,
    surfaceTint = MokeJadeL,

    surfaceContainerLowest = MokeSurfaceLowestL,
    surfaceContainerLow = MokeSurfaceLowL,
    surfaceContainer = MokeSurfaceL,
    surfaceContainerHigh = MokeSurfaceHighL,
    surfaceContainerHighest = MokeSurfaceHighestL,

    inverseSurface = MokeInkL,
    inverseOnSurface = MokeSurfaceL,

    error = MokeDangerL,
    onError = MokeOnDangerL,
    errorContainer = MokeDangerContainerL,
    onErrorContainer = MokeOnDangerContainerL,

    outline = MokeOutlineL,
    outlineVariant = MokeOutlineVariantL,
    scrim = Color(0xFF000000),
)

/**
 * 应用主题。[darkTheme] 由调用方按「跟随系统 / 浅色 / 深色」解析后传入；
 * [dynamicColor] 为真且系统支持（Android 12+）时取壁纸动态色，否则用墨客品牌色。
 */
@Composable
fun MokeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> MokeColorScheme
        else -> MokeLightColorScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content,
    )
}
