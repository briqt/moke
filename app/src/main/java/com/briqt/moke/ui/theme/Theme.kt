package com.briqt.moke.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 全量映射 M3 角色：凡组件会用到的 container/tertiary/surfaceContainer* 都显式给值，
// 否则未定义角色会回退到 Material3 默认紫。
// 主色 = 正文墨色：按钮、开关、单选、滑块、进度条都是墨，靠字重与填充区分层次，不靠色相。
private val InkScheme = darkColorScheme(
    primary = InkText,
    onPrimary = InkBg,
    primaryContainer = InkLifted,
    onPrimaryContainer = InkText,
    inversePrimary = InkControl,

    secondary = InkText,
    onSecondary = InkBg,
    secondaryContainer = InkLifted,
    onSecondaryContainer = InkText,

    tertiary = InkText,
    onTertiary = InkBg,
    tertiaryContainer = InkControl,
    onTertiaryContainer = InkText,

    background = InkBg,
    onBackground = InkText,
    surface = InkSurface,
    onSurface = InkText,
    surfaceVariant = InkControl,
    onSurfaceVariant = InkMuted,
    // 不取主色：M3 会按层级把 surfaceTint 叠到表面上，墨色叠上去只会把表面染灰。
    surfaceTint = InkSurface,

    surfaceContainerLowest = InkBg,
    surfaceContainerLow = InkSurfaceLow,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkDialog,
    surfaceContainerHighest = InkControl,
    surfaceBright = InkLifted,
    surfaceDim = InkBg,

    inverseSurface = InkText,
    inverseOnSurface = InkBg,

    error = InkDanger,
    onError = InkOnDanger,
    errorContainer = InkDangerContainer,
    onErrorContainer = InkOnDangerContainer,

    outline = InkOutline,
    outlineVariant = InkHairline,
    scrim = Color(0xFF000000),
)

private val PaperScheme = lightColorScheme(
    primary = PaperText,
    onPrimary = PaperSurface,
    primaryContainer = PaperLifted,
    onPrimaryContainer = PaperText,
    inversePrimary = PaperControl,

    secondary = PaperText,
    onSecondary = PaperSurface,
    secondaryContainer = PaperLifted,
    onSecondaryContainer = PaperText,

    tertiary = PaperText,
    onTertiary = PaperSurface,
    tertiaryContainer = PaperControl,
    onTertiaryContainer = PaperText,

    background = PaperBg,
    onBackground = PaperText,
    surface = PaperSurface,
    onSurface = PaperText,
    surfaceVariant = PaperControl,
    onSurfaceVariant = PaperMuted,
    surfaceTint = PaperSurface,

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = PaperSurfaceLow,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperDialog,
    surfaceContainerHighest = PaperControl,
    surfaceBright = PaperSurface,
    surfaceDim = PaperLifted,

    inverseSurface = PaperText,
    inverseOnSurface = PaperSurface,

    error = PaperDanger,
    onError = PaperOnDanger,
    errorContainer = PaperDangerContainer,
    onErrorContainer = PaperOnDangerContainer,

    outline = PaperOutline,
    outlineVariant = PaperHairline,
    scrim = Color(0xFF000000),
)

/**
 * M3 之外的品牌色。三种彩色各管一件事，互不借用：
 * - [seal] 朱砂：只做光标（终端与输入框）和 [sealFill] 的实心小印（角标、未读提示）。不做文字、按钮、选中态。
 * - [ok] 状态绿：只表示状态（延迟良好、在线、成功）。
 * - 危险用 M3 的 error（胭脂），只用于危险操作的文字和报错。
 */
@Immutable
data class MokeExtraColors(
    val seal: Color,
    val sealFill: Color,
    val onSealFill: Color,
    val ok: Color,
)

private val InkExtra = MokeExtraColors(seal = InkSeal, sealFill = SealFill, onSealFill = OnSealFill, ok = InkOk)
private val PaperExtra = MokeExtraColors(seal = PaperSeal, sealFill = SealFill, onSealFill = OnSealFill, ok = PaperOk)

private val LocalMokeColors = staticCompositionLocalOf { InkExtra }

/** 品牌扩展色：`MaterialTheme.extra.ok` 等。 */
val MaterialTheme.extra: MokeExtraColors
    @Composable get() = LocalMokeColors.current

/**
 * 圆角档位交给主题：AlertDialog、底部弹层、菜单、悬浮按钮等框架组件随之统一，
 * 不再与自家的 [MokeShapes] 两套并存。按钮保持框架默认的全圆角。
 */
private val MokeMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * 字阶：标题与按钮用半粗，说明字常规；字距比 M3 默认收紧（默认值按拉丁字母定，中文会显得松散）。
 * 字号沿用 M3 档位，代码里 `MaterialTheme.typography.*` 的现有用法不变。
 */
private val MokeTypography: Typography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.15.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.1.sp),
        bodySmall = t.bodySmall.copy(letterSpacing = 0.2.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelMedium = t.labelMedium.copy(letterSpacing = 0.2.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.2.sp),
    )
}

/** 输入框统一配色：光标与选区手柄是朱砂（与终端光标同一个印），其余沿用主题默认。 */
@Composable
fun mokeTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    cursorColor = MaterialTheme.extra.seal,
    selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.extra.seal,
        backgroundColor = MaterialTheme.extra.seal.copy(alpha = 0.3f),
    ),
)

/**
 * 应用主题。[darkTheme] 由调用方按「跟随系统 / 浅色 / 深色」解析后传入；
 * [dynamicColor] 为真且系统支持（Android 12+）时中性色取自壁纸，朱砂与状态绿不变。
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
        darkTheme -> InkScheme
        else -> PaperScheme
    }
    val extra = if (darkTheme) InkExtra else PaperExtra
    MaterialTheme(
        colorScheme = scheme,
        shapes = MokeMaterialShapes,
        typography = MokeTypography,
    ) {
        CompositionLocalProvider(
            LocalMokeColors provides extra,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = extra.seal,
                backgroundColor = extra.seal.copy(alpha = 0.3f),
            ),
            content = content,
        )
    }
}
