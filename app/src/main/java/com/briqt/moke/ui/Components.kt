package com.briqt.moke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import com.briqt.moke.data.ThemeMode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeShapes

/** 徽标 / 能力标签规格：文案 + 强调色（背景取该色的淡版）。 */
data class BadgeSpec(val text: String, val color: Color)

/**
 * 统一小徽标（能力标签 / 状态标签）：强调色文字 + 同色淡背景 + 统一胶囊圆角。
 * 收敛此前分散的 TagChip(secondaryContainer,5dp) 与 FontsScreen.Badge(0.16f,6dp)，
 * 使同一批标签在下拉与卡片里长得一致。协议徽标（等宽、紧贴文字的内联变体）见 ProtocolBadge。
 */
@Composable
fun MokeBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = MokeDimens.badgeAlpha),
        shape = MokeShapes.pill,
        modifier = modifier,
    ) {
        Text(
            text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 字体能力标签（内置 / 本地 / 中文 / 连字）及其配色，供字体管理卡片与外观页下拉共用，
 * 保证同一字体的标签在两处颜色/文案一致。为可在非 @Composable 的 map 中调用，
 * 颜色与文案由调用方在 @Composable 作用域预取后传入。
 */
fun fontCapabilityBadges(
    spec: FontSpec,
    bundled: String,
    local: String,
    cjk: String,
    ligature: String,
    tertiary: Color,
    primary: Color,
    secondary: Color,
): List<BadgeSpec> = buildList {
    if (spec.bundled) add(BadgeSpec(bundled, tertiary))
    if (spec.userUploaded) add(BadgeSpec(local, tertiary))
    if (spec.cjk) add(BadgeSpec(cjk, primary))
    if (spec.ligature) add(BadgeSpec(ligature, secondary))
}

/**
 * 统一「设置入口行」：图标 + 标题 + 副标题 + 右向箭头，整行可点。
 * 收敛此前 MenuCard(Card) 与 FontManageEntry(Surface) 两套实现，统一为浮起卡片；
 * 箭头用 auto-mirror 图标（取代字面 "›"，可随 RTL 镜像、粗细可控）。
 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 右侧箭头前点一个主题色小圆点（用于"远端有更新"这类无打扰提示）。 */
    showDot: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showDot) Dot(modifier = Modifier.padding(end = 6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 键盘模式 → 文案资源（列表与终端菜单共用，避免两处文案漂移）。 */
fun keyboardModeLabel(m: KeyboardMode): Int = when (m) {
    KeyboardMode.SECURE -> R.string.kbmode_secure
    KeyboardMode.STANDARD -> R.string.kbmode_standard
    KeyboardMode.IME -> R.string.kbmode_ime
}

private fun keyboardModeDesc(m: KeyboardMode): Int = when (m) {
    KeyboardMode.SECURE -> R.string.kbmode_secure_desc
    KeyboardMode.STANDARD -> R.string.kbmode_standard_desc
    KeyboardMode.IME -> R.string.kbmode_ime_desc
}

/**
 * 键盘模式选择弹窗：三档带说明的单选。终端页 ⋮ 与设置页共用同一个，
 * 保证"在哪儿改都是同一个开关"。
 */
@Composable
fun KeyboardModeDialog(
    current: KeyboardMode,
    onPick: (KeyboardMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keyboard_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                KeyboardMode.entries.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MokeShapes.control).clickable { onPick(m) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = current == m, onClick = { onPick(m) })
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp)) {
                            Text(stringResource(keyboardModeLabel(m)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(keyboardModeDesc(m)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 通用二次确认弹窗（[destructive] 时确认按钮用错误色）。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 明暗主题 → 文案资源。 */
fun themeModeLabel(m: ThemeMode): Int = when (m) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** 明暗主题选择弹窗：跟随系统 / 浅色 / 深色。 */
@Composable
fun ThemeModeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                ThemeMode.entries.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MokeShapes.control).clickable { onPick(m) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == m, onClick = { onPick(m) })
                        Text(stringResource(themeModeLabel(m)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 开关行：与 [NavRow] 同款卡片，右侧是 Switch（整行可点即切换）。 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 主题色小圆点：静默提示"有新内容"（当前用于新版本），不占位、不打扰。 */
@Composable
fun Dot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}
