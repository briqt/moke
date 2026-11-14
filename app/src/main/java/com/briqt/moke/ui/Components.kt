package com.briqt.moke.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.text.style.TextOverflow
import com.briqt.moke.ui.theme.MokeMono
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.net.RecoveryKind
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeShapes
import com.briqt.moke.ui.theme.extra
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalMinimumInteractiveComponentSize

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
 * 设置分组：把相邻的 [NavRow] / [SwitchRow] 收进一张卡片，行与行之间是 1dp 细线。
 * 实现上分组底色取细线色、每行铺卡片色、行间留 1dp 缝，缝就是分隔线——行不必知道自己是不是第一行。
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.outlineVariant),
        border = cardBorder(),
    ) {
        CompositionLocalProvider(LocalInSettingsGroup provides true) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), content = content)
        }
    }
}

private val LocalInSettingsGroup = staticCompositionLocalOf { false }

/** 设置行外壳：在 [SettingsGroup] 里是一条平铺的行，单独使用时自带卡片。两种情况行高、内边距一致。 */
@Composable
private fun SettingsRowShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .clickable(onClick = onClick)
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    if (LocalInSettingsGroup.current) {
        Row(
            modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).then(rowModifier),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MokeShapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = cardBorder(),
        ) {
            Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
private fun RowScope.SettingsRowText(title: String, subtitle: String) {
    Column(modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 统一「设置入口行」：图标 + 标题 + 副标题 + 右向箭头，整行可点。
 * 箭头用 auto-mirror 图标（可随 RTL 镜像）。放进 [SettingsGroup] 时与相邻行共用一张卡片。
 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 右侧箭头前点一方朱砂小印（用于"远端有更新"这类无打扰提示）。 */
    showDot: Boolean = false,
) {
    SettingsRowShell(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsRowText(title, subtitle)
        if (showDot) Dot(modifier = Modifier.padding(end = 6.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
/** 当前连接的临时滑动输入方式，选择后立即生效。 */
@Composable
fun ScrollModeDialog(
    current: ScrollMode,
    onPick: (ScrollMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scroll_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.scroll_temporary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ScrollMode.entries.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MokeShapes.control).clickable { onPick(m) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == m, onClick = { onPick(m) })
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(stringResource(scrollModeLabel(m)), style = MaterialTheme.typography.bodyLarge)
                            if (m != ScrollMode.SMART) {
                                Text(
                                    stringResource(if (m == ScrollMode.WHEEL) R.string.scroll_wheel_description else R.string.scroll_arrows_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

fun scrollModeLabel(m: ScrollMode): Int = when (m) {
    ScrollMode.SMART -> R.string.scroll_mode_smart
    ScrollMode.WHEEL -> R.string.scroll_mode_wheel
    ScrollMode.ARROWS -> R.string.scroll_mode_arrows
}

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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
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
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Action labels follow structured recovery data, never translated error text. */
fun recoveryActionLabel(kind: RecoveryKind): Int = when (kind) {
    RecoveryKind.LOGIN_NETWORK -> R.string.action_login
    RecoveryKind.VIEW_NETWORK -> R.string.action_view_network
    RecoveryKind.EDIT_NETWORK -> R.string.action_edit_network
    RecoveryKind.EDIT_HOST -> R.string.action_edit_host
    RecoveryKind.RETRY -> R.string.action_retry
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** 开关行：与 [NavRow] 同款外壳，右侧是 Switch（整行可点即切换）。 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRowShell(onClick = { onCheckedChange(!checked) }) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsRowText(title, subtitle)
        // 整行就是触控区，Switch 不再另撑 48dp 最小触控高度——否则开关行比箭头行高一截。
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * 表单里的说明行：小图标 + 一段 bodySmall 文字。中性说明用 info 图标；[error] 时换错误色与警告图标，
 * 用于「必须先改正才能保存」的原因（如 mosh 选了不能转 UDP 的代理）。
 */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier.semantics { if (error) liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (error) Icons.Filled.Error else Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
            tint = color,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 朱砂小印：静默提示"有新内容"（当前用于新版本），不占位、不打扰。与启动图标的印同形：实心方块、小圆角。 */
@Composable
fun Dot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.extra.sealFill, MokeShapes.seal)
    )
}

/** 卡片细线：页面底与卡片只差一档墨色，不用阴影，靠 1px 细线分层。 */
@Composable
fun cardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * 计数角标（会话数、转发数、tmux 会话数）：与印同形的小方块，但用中性的浮起控件底——
 * 计数只是「这里有几个」，不是要你注意的事；朱砂只留给 [Dot] 那类提示。
 */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 16.dp)
            .widthIn(min = 16.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MokeShapes.seal)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MokeMono,
        )
    }
}

/** 各页顶栏统一配色：与页面同底，滚动后浮起到栏色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun mokeTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** 上沿 1px 细线（底栏、底部操作区与内容分界，代替阴影）。 */
@Composable
fun Modifier.topHairline(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
}

/** 下沿 1px 细线（顶栏与内容分界）。 */
@Composable
fun Modifier.bottomHairline(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

/**
 * 分组列表行（主机、会话）：和设置页同一套样式——同组的行合成一个圆角容器（表面底 + 1px 细线），
 * 组内用同色 1px 线分隔，没有一行一张的卡片，也没有行间空隙。行在组内的位置由 [LocalRowPosition] 给出。
 * 标题 15sp、副标题 12sp：列表标题是主机名、终端标题这类长数据，比设置项文字小一档才不显突兀。
 * 长按只挂在文字列上：拖动把手有自己的长按手势，两者不能叠在同一块区域。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    subtitle: androidx.compose.ui.text.AnnotatedString?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val pos = LocalRowPosition.current
    val r = 12.dp
    val shape = when {
        dragging || (pos.first && pos.last) -> androidx.compose.foundation.shape.RoundedCornerShape(r)
        pos.first -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = r, topEnd = r)
        pos.last -> androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val closesBottom = dragging || pos.last
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .drawWithContent {
                drawContent()
                // 沿容器外形描 1px（描边宽 2px、外半被裁掉）；不是组内最后一行就不画下沿，由下一行的上沿充当分隔线。
                val w = 1.dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                clipRect(bottom = if (closesBottom) size.height else size.height - w) {
                    drawOutline(outline, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2 * w))
                }
            }
            .heightIn(min = 56.dp)
            .padding(start = if (dragHandle != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            Box(modifier = dragHandle.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
                .padding(start = if (dragHandle != null) 4.dp else 0.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** 右沿 1px 细线（侧边导航栏与内容分界）。 */
@Composable
fun Modifier.endHairline(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val x = size.width - 0.5.dp.toPx()
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
}

/**
 * 设置行式下拉：与 [NavRow] 同高同排版，当前值靠右显示，点整行在行下展开选项（选项排版同 [RichDropdown]）。
 * 设置页里用它代替带浮动标签的方框下拉——后者高一截、又是另一种样式，夹在分组行之间显得乱。
 */
@Composable
fun DropdownSettingRow(
    icon: ImageVector,
    title: String,
    options: List<DropdownOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    missingTitle: String = "—",
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    Box {
        SettingsRowShell(onClick = { open = true }) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsRowText(title, "")
            Text(
                selected?.title ?: missingTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 168.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { OptionRow(opt) },
                    onClick = { onSelect(opt.id); open = false },
                )
            }
        }
    }
}

/**
 * 设置行式数值滑块：名称、−、滑块、+、当前值在一行（与其它设置行同高）。滑块快调（离散步进），± 单档精调。
 */
@Composable
fun SliderSettingRow(
    icon: ImageVector,
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    SettingsRowShell(onClick = {}) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp).widthIn(min = 52.dp),
        )
        androidx.compose.material3.IconButton(onClick = onMinus, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease), modifier = Modifier.size(18.dp))
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            androidx.compose.material3.Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValue,
                valueRange = valueRange,
                steps = steps,
                // 墨色滑块：滑钮是正文墨，已走过的轨道退一档，刻度点不画（字号有 30 多档，点会连成一串）。
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f).height(32.dp),
            )
        }
        androidx.compose.material3.IconButton(onClick = onPlus, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase), modifier = Modifier.size(18.dp))
        }
        Text(
            valueText,
            fontFamily = com.briqt.moke.ui.theme.MokeMono,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(min = 36.dp),
        )
    }
}
