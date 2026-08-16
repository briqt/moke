package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.ModKind
import com.briqt.moke.terminal.ModState
import com.briqt.moke.terminal.Modifiers
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes

/** 附加键：普通按键（字节由 `KeySeq` 按当前修饰统一编码）/ 修饰键 / 动作键。 */
sealed interface ExtraKey {
    val label: String

    /** 普通按键：只描述"按了哪个键"，序列交给编码器，故能与 Ctrl/Alt/Shift 组合。 */
    data class Key(override val label: String, val key: KeyId) : ExtraKey
    /** 修饰键（三态：一次性 / 锁定 / 关）。 */
    data class Mod(override val label: String, val kind: ModKind) : ExtraKey
    /** 动作键（[id] 交给上层处理）。 */
    data class Action(override val label: String, val id: String) : ExtraKey
}

/** 动作键 id：文本段入口、展开全键盘面板。 */
const val ACTION_COMPOSER = "composer"
const val ACTION_PANEL = "panel"

/*
 * 收录标准（rc.3 重定）：**只放软键盘给不了的键**。
 *
 * 字母、数字、标点（含 `| ~ \ {} <>`）输入法都打得出来，摆在这里等于用最贵的屏幕位置重复一遍
 * 已有的功能。只有实体全键盘才有的是：修饰键、Esc/Tab、方向与翻页、行首行尾、F1–F12、
 * Ctrl 组合、Shift+Tab。按这条线砍掉了 rc.2 的整页符号，以及散在导航行里的 `/` `-`。
 *
 * 分工：**常驻两排 = 给不了 ∩ 高频**；**面板 = 给不了的其余全部**（外加 Enter/⌫ 两个
 * "软键盘被隐藏时"的兜底键）。同一个键不在两处重复出现——面板就浮在常驻两排上方，
 * 重复只会让人分不清该按哪个，也是 rc.2 显得乱的主要来源。
 */

/**
 * 常驻双排附加键：均匀铺满宽度、不横向滚动，中间三列保持倒 T 方向键。
 *
 * 取舍：Enter/⌫ 让位给 ⇧TAB 与 ^C——前者输入法上永远都在，后者输入法永远给不了；
 * 而 ⇧TAB（切 agent 模式）与 ^C（打断）正是这类会话里按得最多的两个。
 */
val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Key("ESC", KeyId.Esc),
        ExtraKey.Mod("CTRL", ModKind.Ctrl),
        ExtraKey.Mod("ALT", ModKind.Alt),
        ExtraKey.Key("↑", KeyId.Up),
        ExtraKey.Key("HOME", KeyId.Home),
        ExtraKey.Key("END", KeyId.End),
        ExtraKey.Action("更多", ACTION_PANEL),
    ),
    listOf(
        ExtraKey.Key("TAB", KeyId.Tab),
        ExtraKey.Key("⇧TAB", KeyId.Macro("\u001b[Z")),
        ExtraKey.Key("←", KeyId.Left),
        ExtraKey.Key("↓", KeyId.Down),
        ExtraKey.Key("→", KeyId.Right),
        ExtraKey.Key("^C", KeyId.Macro(ctrlOf('c'))),
        ExtraKey.Action("文本", ACTION_COMPOSER),
    ),
)

/** 全键盘面板的一个分组（面板是单页竖排，分组只作视觉分区，不再有分段切换）。 */
data class KeySection(val titleRes: Int, val rows: List<List<ExtraKey>>)

private fun macro(label: String, bytes: String) = ExtraKey.Key(label, KeyId.Macro(bytes))

/** Ctrl+字母的字节（宏用；标签沿用终端惯例的 `^X` 写法）。 */
private fun ctrlOf(c: Char) = ((c.uppercaseChar().code - 64)).toChar().toString()

/**
 * 面板的三个分组：编辑 / 功能键 / 控制键。**一屏全在，不用切**。
 *
 * rc.2 是四分段 pager，每段行数还不一样：翻页时 Surface 的高度与页内内容各按各的节奏变，
 * 看上去就是"卡"和"边框与内容不同步"。键收敛到 33 个之后一页放得下，分段机制连同它那套
 * 滑动/高度动画一起去掉——不做的动画不会卡。
 */
val KEY_SECTIONS: List<KeySection> = listOf(
    KeySection(
        R.string.keys_section_edit,
        listOf(
            listOf(
                ExtraKey.Mod("SHIFT", ModKind.Shift),
                ExtraKey.Key("INS", KeyId.Insert),
                ExtraKey.Key("DEL", KeyId.Delete),
                ExtraKey.Key("PgUp", KeyId.PageUp),
                ExtraKey.Key("PgDn", KeyId.PageDown),
                // Enter/⌫ 输入法上有，这里留一份是给"隐藏软键盘只看输出"的场景兜底。
                ExtraKey.Key("⌫", KeyId.Backspace),
                ExtraKey.Key("Enter", KeyId.Enter),
            ),
        ),
    ),
    KeySection(
        R.string.keys_section_fn,
        listOf(
            (1..6).map { ExtraKey.Key("F$it", KeyId.Fn(it)) },
            (7..12).map { ExtraKey.Key("F$it", KeyId.Fn(it)) },
        ),
    ),
    KeySection(
        R.string.keys_section_ctrl,
        listOf(
            // 行编辑：行首 / 行尾 / 删到行首 / 删到行尾 / 删词 / 粘回 / 清屏。
            listOf(
                macro("^A", ctrlOf('a')),
                macro("^E", ctrlOf('e')),
                macro("^U", ctrlOf('u')),
                macro("^K", ctrlOf('k')),
                macro("^W", ctrlOf('w')),
                macro("^Y", ctrlOf('y')),
                macro("^L", ctrlOf('l')),
            ),
            // 作业控制与历史；^B 是 tmux 默认前缀；ALT↵ = ESC+CR（多行输入换行不提交）。
            listOf(
                macro("^D", ctrlOf('d')),
                macro("^Z", ctrlOf('z')),
                macro("^R", ctrlOf('r')),
                macro("^P", ctrlOf('p')),
                macro("^N", ctrlOf('n')),
                macro("^B", ctrlOf('b')),
                // 标签不用 ⌥：等宽字体里没有该字形，真机上会渲染成豆腐块。
                macro("ALT↵", "\u001b\r"),
            ),
        ),
    ),
)

@Composable
fun ExtraKeys(
    rows: List<List<ExtraKey>>,
    mods: Modifiers,
    panelOpen: Boolean = false,
    onKey: (KeyId) -> Unit,
    onToggleMod: (ModKind) -> Unit,
    onAction: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { row ->
                KeyRow(row, mods, panelOpen, onKey, onToggleMod, onAction)
            }
        }
    }
}

@Composable
private fun KeyRow(
    row: List<ExtraKey>,
    mods: Modifiers,
    panelOpen: Boolean,
    onKey: (KeyId) -> Unit,
    onToggleMod: (ModKind) -> Unit,
    onAction: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { key ->
            val state = if (key is ExtraKey.Mod) mods.state(key.kind) else ModState.Off
            // 「文本段」与「更多」用图标（与符号键风格一致、无需 i18n）；本地化文案作无障碍描述。
            val isComposer = key is ExtraKey.Action && key.id == ACTION_COMPOSER
            val isPanel = key is ExtraKey.Action && key.id == ACTION_PANEL
            val label = when {
                isComposer -> stringResource(R.string.key_text)
                isPanel -> stringResource(R.string.key_more)
                else -> key.label
            }
            KeyCap(
                label = label,
                icon = when {
                    isComposer -> Icons.Filled.EditNote
                    isPanel -> if (panelOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp
                    else -> null
                },
                // 一次性修饰用主色实心，锁定态用更强的色块区分——否则分不清"这次有效"和"一直有效"。
                active = state.active || (isPanel && panelOpen),
                locked = state == ModState.Locked,
                modifier = Modifier.weight(1f),
                onClick = {
                    when (key) {
                        is ExtraKey.Key -> onKey(key.key)
                        is ExtraKey.Mod -> onToggleMod(key.kind)
                        is ExtraKey.Action -> onAction(key.id)
                    }
                },
            )
        }
    }
}

/**
 * 展开的全键盘面板：**浮在终端之上**（调用方把它放进终端 Box），不挤压终端——
 * 挤压会改行数触发远端 SIGWINCH，全屏 TUI 会跟着重绘抖动。
 *
 * 单页竖排、分组标题分区，**没有分段切换、没有 pager**：内容与外框同一次布局产出，
 * 不存在"边框先动内容后动"。内容比可用高度长（横屏）时整块滚动，面板本身不改高。
 * 顶部那根横条是收起把手（点一下收起），与常驻行上的「更多」键（展开时高亮成 ⌄）互为两条出路。
 */
@Composable
fun KeyboardPanel(
    sections: List<KeySection>,
    mods: Modifiers,
    onKey: (KeyId) -> Unit,
    onToggleMod: (ModKind) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    // 最高只吃屏幕的 6 成：再高就把终端整块盖没了。父 Box 更矮时由父约束接管，内容滚动。
    val maxHeight = (config.screenHeightDp * 0.6f).dp
    // 横屏矮而宽：竖屏的 5 排在这里放不下（只能滚），但一排塞得下十几个键——
    // 把每组压成尽量少的排，三组一次全见，不用滚。
    val perRow = if (config.screenWidthDp >= 600) 14 else 0
    val collapseLabel = stringResource(R.string.keys_panel_collapse)
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight),
        color = MaterialTheme.colorScheme.surface,
        shape = MokeShapes.keyPanel,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 5.dp)
                .padding(bottom = 6.dp),
        ) {
            // 收起把手：整条可点，命中区域比一个图标按钮大，也不占一整行高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = collapseLabel },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.width(34.dp).height(4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                ) {}
            }
            sections.forEachIndexed { index, section ->
                Text(
                    stringResource(section.titleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, top = if (index == 0) 0.dp else 9.dp, bottom = 4.dp),
                )
                val rows = if (perRow > 0) section.rows.flatten().chunked(perRow) else section.rows
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    rows.forEach { row ->
                        KeyRow(row, mods, panelOpen = false, onKey = onKey, onToggleMod = onToggleMod, onAction = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    active: Boolean,
    locked: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 近乎平直的键帽（微圆角），更贴合终端页面；高度 34dp（在 36 基础上再压扁约 5%）。
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = MokeShapes.keycap,
        color = when {
            locked -> MaterialTheme.colorScheme.tertiary
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            locked -> MaterialTheme.colorScheme.onTertiary
            active -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                // 图标键（文本段 / 更多）：label 作无障碍描述，视觉用图标。
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            } else {
                // 方向键单字符符号（↑ ↓ ← →）本身偏小、看不清，放大到 17sp；其余文字标签（含 Enter）保持 13sp。
                val glyph = label.length == 1 && label[0] in "↑↓←→"
                Text(label, fontFamily = MokeMono, fontSize = if (glyph) 17.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * 文本段输入（底部**内联**输入条）：在附加键行的位置就地展开，编辑整段文本后一次性发送——适合长命令 / 多行粘贴。
 * 与终端同处一个窗口，从终端切到本输入框只是窗口内焦点转移，软键盘**不收起再弹起**（避免弹独立 sheet 的三段跳）。
 * 文本状态由上层持有（[value]），关闭保留草稿、发送后由上层清空。展开即自动聚焦。
 */
@Composable
fun TextBlockComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: (text: String, appendEnter: Boolean) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 头行：标题 + 清空 + 关闭（关闭回到附加键行）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.composer_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onValueChange("") }) { Text(stringResource(R.string.composer_clear)) }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 高度受限：2 行起，内容多则框内滚动到上限，不顶出发送按钮。
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 160.dp).focusRequester(focusRequester),
                minLines = 2,
                placeholder = { Text(stringResource(R.string.composer_field)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MokeMono),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onSend(value, false) }) { Text(stringResource(R.string.composer_send)) }
                Button(onClick = { onSend(value, true) }) { Text(stringResource(R.string.composer_send_enter)) }
            }
        }
    }
    // 内联展开：直接聚焦输入框（同窗口焦点转移，IME 顺滑续上），无需等窗口入场动画。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}
