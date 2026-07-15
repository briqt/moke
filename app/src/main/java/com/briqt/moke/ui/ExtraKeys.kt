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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes

/** 附加键：发送字节序列 / 粘滞修饰键(Ctrl·Alt) / 触发动作。 */
sealed interface ExtraKey {
    val label: String

    /** 发送固定字节序列（转义键、箭头、翻页等）。 */
    data class Seq(override val label: String, val bytes: String) : ExtraKey
    /** 粘滞修饰键；[ctrl]=true 为 Ctrl，false 为 Alt。 */
    data class Mod(override val label: String, val ctrl: Boolean) : ExtraKey
    /** 动作键（[id] 交给上层处理）。 */
    data class Action(override val label: String, val id: String) : ExtraKey
}

/**
 * 默认双排附加键（参考 termux 两排布局 + 倒 T 方向键）。均匀铺满宽度、不横向滚动。
 * 每排 7 键；右端两格放「文本」段入口（行1）与回车（行2），故终端顶栏右上角留空。
 */
val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Seq("ESC", ""),
        ExtraKey.Seq("/", "/"),
        ExtraKey.Seq("HOME","[H"),
        ExtraKey.Seq("↑", "[A"),
        ExtraKey.Seq("END", "[F"),
        ExtraKey.Seq("PgUp", "[5~"),
        ExtraKey.Action("文本", "composer"),
    ),
    listOf(
        ExtraKey.Seq("TAB", "\t"),
        ExtraKey.Mod("CTRL", ctrl = true),
        ExtraKey.Seq("←", "[D"),
        ExtraKey.Seq("↓", "[B"),
        ExtraKey.Seq("→", "[C"),
        ExtraKey.Seq("PgDn", "[6~"),
        // 回车用文字 "Enter"：↵ 字形在等宽字体里偏小且视觉不居中，文字标签与 TAB/HOME 等一致、清晰居中。
        ExtraKey.Seq("Enter", "\r"),
    ),
)

@Composable
fun ExtraKeys(
    rows: List<List<ExtraKey>>,
    ctrlOn: Boolean,
    altOn: Boolean,
    onSeq: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onAction: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key ->
                        val active = key is ExtraKey.Mod && ((key.ctrl && ctrlOn) || (!key.ctrl && altOn))
                        // 「文本段」入口用图标（与符号键风格一致、无需 i18n）；本地化文案作无障碍描述。
                        val isComposer = key is ExtraKey.Action && key.id == "composer"
                        val label = if (isComposer) stringResource(R.string.key_text) else key.label
                        KeyCap(
                            label = label,
                            icon = if (isComposer) Icons.Filled.EditNote else null,
                            active = active,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (key) {
                                    is ExtraKey.Seq -> onSeq(key.bytes)
                                    is ExtraKey.Mod -> if (key.ctrl) onToggleCtrl() else onToggleAlt()
                                    is ExtraKey.Action -> onAction(key.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 近乎平直的键帽（微圆角），更贴合终端页面；高度 34dp（在 36 基础上再压扁约 5%）。
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = MokeShapes.keycap,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                // 图标键（如文本段入口）：label 作无障碍描述，视觉用图标。
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
