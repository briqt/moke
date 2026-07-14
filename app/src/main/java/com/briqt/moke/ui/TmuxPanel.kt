package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.ui.theme.MokeMono

/**
 * tmux 管理面板（BottomSheet）：列出远端 tmux 会话，支持 附加 / 重命名 / 关闭 + 新建。
 * 增删改查走侧通道 exec（由上层回调实现）；附加注入当前前台 PTY 并收起面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxPanel(
    sessions: List<TmuxSession>,
    onDismiss: () -> Unit,
    onAttach: (TmuxSession) -> Unit,
    onRename: (TmuxSession, String) -> Unit,
    onKill: (TmuxSession) -> Unit,
    onNew: (String) -> Unit,
) {
    // 输入弹窗：null=不显示；(初值, 提交回调)。新建时初值空，重命名时预填当前名。
    var dialog by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tmux_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { dialog = "" to { name -> onNew(name) } }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.tmux_new))
                }
            }
            HorizontalDivider()

            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.tmux_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    sessions.forEach { s ->
                        TmuxRow(
                            session = s,
                            onAttach = { onAttach(s); onDismiss() },
                            onRename = { dialog = s.name to { name -> onRename(s, name) } },
                            onKill = { onKill(s) },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { (initial, submit) ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (initial.isBlank()) stringResource(R.string.tmux_new) else stringResource(R.string.tmux_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tmux_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { submit(text.trim()); dialog = null },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TmuxRow(
    session: TmuxSession,
    onAttach: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.name, fontWeight = FontWeight.Medium, maxLines = 1)
            val sub = stringResource(R.string.tmux_windows, session.windows) +
                (if (session.attached) "  · " + stringResource(R.string.tmux_attached) else "")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = MokeMono,
                maxLines = 1,
            )
        }
        FilledTonalButton(onClick = onAttach, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.tmux_attach))
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.tmux_rename), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onKill) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tmux_kill), tint = MaterialTheme.colorScheme.error)
        }
    }
}
