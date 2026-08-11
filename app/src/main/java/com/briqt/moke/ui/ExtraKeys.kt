package com.briqt.moke.ui

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch

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

/**
 * 常驻双排附加键（参考 termux 两排布局 + 倒 T 方向键）。均匀铺满宽度、不横向滚动。
 * 行 1 第 2 个键是「更多」——展开全键盘面板的入口；原先那个 `/` 已并入面板的符号页。
 */
val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Key("ESC", KeyId.Esc),
        ExtraKey.Action("更多", ACTION_PANEL),
        ExtraKey.Key("HOME", KeyId.Home),
        ExtraKey.Key("↑", KeyId.Up),
        ExtraKey.Key("END", KeyId.End),
        ExtraKey.Key("PgUp", KeyId.PageUp),
        ExtraKey.Action("文本", ACTION_COMPOSER),
    ),
    listOf(
        ExtraKey.Key("TAB", KeyId.Tab),
        ExtraKey.Mod("CTRL", ModKind.Ctrl),
        ExtraKey.Key("←", KeyId.Left),
        ExtraKey.Key("↓", KeyId.Down),
        ExtraKey.Key("→", KeyId.Right),
        ExtraKey.Key("PgDn", KeyId.PageDown),
        // 回车用文字 "Enter"：↵ 字形在等宽字体里偏小且视觉不居中，文字标签与 TAB/HOME 等一致、清晰居中。
        ExtraKey.Key("Enter", KeyId.Enter),
    ),
)

/** 全键盘面板的一个分段。 */
data class KeySection(val titleRes: Int, val rows: List<List<ExtraKey>>)

private fun ch(c: String) = ExtraKey.Key(c, KeyId.Chars(c))
private fun macro(label: String, bytes: String) = ExtraKey.Key(label, KeyId.Macro(bytes))

/** Ctrl+字母的字节（宏用；标签沿用终端惯例的 `^X` 写法）。 */
private fun ctrlOf(c: Char) = ((c.uppercaseChar().code - 64)).toChar().toString()

/**
 * 展开面板的四个分段：导航编辑 / 功能键 / 符号 / 快捷。
 * 符号页只收手机输入法上难打的那批（`| \ ~ ^ {} [] <>` 等），常见标点不占位。
 */
val KEY_SECTIONS: List<KeySection> = listOf(
    KeySection(
        R.string.keys_section_nav,
        listOf(
            listOf(
                ExtraKey.Mod("SHIFT", ModKind.Shift),
                ExtraKey.Mod("CTRL", ModKind.Ctrl),
                ExtraKey.Mod("ALT", ModKind.Alt),
                ExtraKey.Key("ESC", KeyId.Esc),
                ExtraKey.Key("TAB", KeyId.Tab),
                ExtraKey.Key("Enter", KeyId.Enter),
                ExtraKey.Key("⌫", KeyId.Backspace),
            ),
            listOf(
                ExtraKey.Key("INS", KeyId.Insert),
                ExtraKey.Key("DEL", KeyId.Delete),
                ExtraKey.Key("HOME", KeyId.Home),
                ExtraKey.Key("END", KeyId.End),
                ExtraKey.Key("PgUp", KeyId.PageUp),
                ExtraKey.Key("PgDn", KeyId.PageDown),
                ExtraKey.Key("↑", KeyId.Up),
            ),
            listOf(
                ExtraKey.Key("⇧TAB", KeyId.Macro("\u001b[Z")),
                // 标签不用 ⌥：等宽字体里没有该字形，真机上会渲染成豆腐块。
                ExtraKey.Key("ALT↵", KeyId.Macro("\u001b\r")),
                ch("/"),
                ch("-"),
                ExtraKey.Key("←", KeyId.Left),
                ExtraKey.Key("↓", KeyId.Down),
                ExtraKey.Key("→", KeyId.Right),
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
        R.string.keys_section_symbols,
        listOf(
            listOf(ch("|"), ch("\\"), ch("/"), ch("~"), ch("`"), ch("^"), ch("=")),
            listOf(ch("("), ch(")"), ch("["), ch("]"), ch("{"), ch("}"), ch("<")),
            listOf(ch(">"), ch("_"), ch("+"), ch("*"), ch("&"), ch("$"), ch("#")),
        ),
    ),
    KeySection(
        R.string.keys_section_macros,
        listOf(
            listOf(
                macro("^C", ctrlOf('c')),
                macro("^D", ctrlOf('d')),
                macro("^Z", ctrlOf('z')),
                macro("^L", ctrlOf('l')),
                macro("^R", ctrlOf('r')),
                macro("^A", ctrlOf('a')),
                macro("^E", ctrlOf('e')),
            ),
            listOf(
                macro("^U", ctrlOf('u')),
                macro("^K", ctrlOf('k')),
                macro("^W", ctrlOf('w')),
                macro("^Y", ctrlOf('y')),
                macro("^P", ctrlOf('p')),
                macro("^N", ctrlOf('n')),
                // tmux 默认前缀：面板里直接给一个，省得先按 CTRL 再切输入法打 b。
                macro("tmux ^B", ctrlOf('b')),
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
 * 展开的全键盘面板：**浮在终端之上**（调用方把它放在终端 Box 里），不挤压终端——
 * 挤压会改行数触发远端 SIGWINCH，全屏 TUI 会跟着重绘抖动。
 * 分段可点标签切换，也可左右滑动；常驻两排仍在下方原位不动。
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
    val pager = rememberPagerState(pageCount = { sections.size })
    val scope = rememberCoroutineScope()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                sections.forEachIndexed { index, section ->
                    val selected = pager.currentPage == index
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(index) } }) {
                        Text(
                            stringResource(section.titleRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.keys_panel_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalPager(state = pager) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    sections[page].rows.forEach { row ->
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
