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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.ui.theme.MokeMono
import kotlinx.coroutines.delay

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
        ExtraKey.Seq("↵", "\r"),
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
                        // 「文本」段入口键按语言本地化；其余键位（ESC/TAB/箭头等）为通用符号，保持原样。
                        val label = if (key is ExtraKey.Action && key.id == "composer") stringResource(R.string.key_text) else key.label
                        KeyCap(
                            label = label,
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
private fun KeyCap(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 近乎平直的键帽（3dp 微圆角），更贴合终端页面；高度 36dp（较原 40 压平约 1/10）。
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(3.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = MokeMono, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * 文本段输入：底部 sheet 里编辑整段文本后一次性发送——适合长命令 / 多行粘贴，免受屏幕键盘折磨。
 * sheet 浮在键盘之上、终端仍可见。文本状态由上层持有（[value]），关闭保留草稿、发送后由上层清空。
 * 打开即自动聚焦输入框并唤起软键盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextBlockComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: (text: String, appendEnter: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.composer_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 限定输入框高度：默认 2 行起（不撑大空白），内容多则在框内滚动到上限，不顶出发送按钮。
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 200.dp).focusRequester(focusRequester),
                minLines = 2,
                label = { Text(stringResource(R.string.composer_field)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MokeMono),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onValueChange("") }) { Text(stringResource(R.string.composer_clear)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onSend(value, false) }) { Text(stringResource(R.string.composer_send)) }
                Button(onClick = { onSend(value, true) }) { Text(stringResource(R.string.composer_send_enter)) }
            }
        }
    }

    // sheet 展开后聚焦输入框并唤起键盘（略等其入场动画，避免焦点被吞）。
    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }
}
