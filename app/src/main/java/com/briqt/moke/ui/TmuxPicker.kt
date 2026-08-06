package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxSession

/**
 * 连接时的 tmux 会话选择器（主机「会话持久化=tmux」且还没记住会话名时弹出）。
 *
 * 选择结果会被记住，下次连接直接按名附加、不再打扰。存在与新建走同一条路——
 * `tmux new-session -A` 由 tmux 原子处理"存在则附加、否则创建"，所以这里不需要区分两种操作。
 */
@Composable
fun TmuxPickerDialog(
    sessions: List<TmuxSession>,
    defaultName: String,
    onPick: (String) -> Unit,
    onPlainShell: () -> Unit,
) {
    var newName by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onPlainShell,
        title = { Text(stringResource(R.string.tmux_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.tmux_picker_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // 列表可滚，但下面的会话名输入框必须始终可见——否则会话多时「新建会话」旁边
                // 看不到要输入的名字。
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                if (sessions.isEmpty()) {
                    Text(
                        stringResource(R.string.tmux_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                sessions.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(s.name) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.tmux_windows, s.windows, s.windows) +
                                " · " +
                                pluralStringResource(R.plurals.tmux_clients, s.clients, s.clients),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.tmux_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { newName.trim().takeIf { it.isNotEmpty() }?.let(onPick) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { newName.trim().takeIf { it.isNotEmpty() }?.let(onPick) },
                enabled = newName.isNotBlank(),
            ) { Text(stringResource(R.string.tmux_new)) }
        },
        dismissButton = {
            TextButton(onClick = onPlainShell) { Text(stringResource(R.string.tmux_plain_shell)) }
        },
    )
}
