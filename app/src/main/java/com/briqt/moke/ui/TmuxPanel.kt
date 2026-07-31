package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.ui.theme.MokeMono

/**
 * tmux 管理面板（BottomSheet）：列出远端 tmux 会话，支持打开 / 重命名 / 关闭 + 新建。
 * 所有状态与失败都显式展示；打开由上层切到专用 Moke 终端连接，不注入当前前台输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxPanel(
    state: TmuxUiState,
    currentTmuxId: String?,
    targetLabel: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAttach: (TmuxSession) -> Unit,
    onTakeOver: (TmuxSession) -> Unit,
    onRename: (TmuxSession, String) -> Unit,
    onDetach: (TmuxSession) -> Unit,
    onKill: (TmuxSession) -> Unit,
    onNew: (String) -> Unit,
) {
    // 输入弹窗：null=不显示；(初值, 提交回调)。新建时初值空，重命名时预填当前名。
    var dialog by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var detachTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var takeOverTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var killTarget by remember { mutableStateOf<TmuxSession?>(null) }

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
                IconButton(onClick = onRefresh, enabled = !state.busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tmux_refresh))
                }
                TextButton(
                    enabled = state.phase == TmuxPhase.READY && !state.busy,
                    onClick = { dialog = "" to { name -> onNew(name) } },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.tmux_new))
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.tmux_target, targetLabel),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp),
            )
            Text(
                stringResource(R.string.tmux_manual_hop_notice),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
            )

            state.message?.takeIf { state.phase == TmuxPhase.READY }?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp),
                )
            }

            when (state.phase) {
                TmuxPhase.IDLE, TmuxPhase.CHECKING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.tmux_checking),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TmuxPhase.NOT_INSTALLED -> EmptyTmuxText(stringResource(R.string.tmux_not_installed))
                TmuxPhase.ERROR -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Text(
                            state.message ?: stringResource(R.string.tmux_control_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onRefresh, enabled = !state.busy) {
                            Text(stringResource(R.string.tmux_retry))
                        }
                    }
                }
                TmuxPhase.READY -> {
                    if (state.sessions.isEmpty()) {
                        EmptyTmuxText(stringResource(R.string.tmux_empty))
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            state.sessions.forEach { s ->
                                TmuxRow(
                                    session = s,
                                    current = currentTmuxId == s.id,
                                    enabled = !state.busy,
                                    onAttach = { onAttach(s); onDismiss() },
                                    onTakeOver = { takeOverTarget = s },
                                    onRename = { dialog = s.name to { name -> onRename(s, name) } },
                                    onDetach = { detachTarget = s },
                                    onKill = { killTarget = s },
                                )
                            }
                        }
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

    detachTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_detach_confirm_title),
            message = pluralStringResource(
                R.plurals.tmux_detach_confirm_message,
                target.clients,
                target.clients,
                target.name,
            ),
            confirmLabel = stringResource(R.string.tmux_detach),
            onConfirm = {
                detachTarget = null
                onDetach(target)
            },
            onDismiss = { detachTarget = null },
        )
    }

    takeOverTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_take_over_confirm_title),
            message = pluralStringResource(
                R.plurals.tmux_take_over_confirm_message,
                target.clients,
                target.clients,
                target.name,
            ),
            confirmLabel = stringResource(R.string.tmux_take_over),
            onConfirm = {
                takeOverTarget = null
                onTakeOver(target)
                onDismiss()
            },
            onDismiss = { takeOverTarget = null },
        )
    }

    killTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_kill_confirm_title),
            message = stringResource(R.string.tmux_kill_confirm_message, target.name),
            confirmLabel = stringResource(R.string.tmux_kill),
            destructive = true,
            onConfirm = {
                killTarget = null
                onKill(target)
            },
            onDismiss = { killTarget = null },
        )
    }
}

@Composable
private fun EmptyTmuxText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun TmuxRow(
    session: TmuxSession,
    current: Boolean,
    enabled: Boolean,
    onAttach: () -> Unit,
    onTakeOver: () -> Unit,
    onRename: () -> Unit,
    onDetach: () -> Unit,
    onKill: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.name + if (current) "  · " + stringResource(R.string.tmux_current) else "",
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val sub = pluralStringResource(R.plurals.tmux_windows, session.windows, session.windows) +
                (if (session.clients > 0) {
                    "  · " + pluralStringResource(R.plurals.tmux_clients, session.clients, session.clients)
                } else "")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = MokeMono,
                maxLines = 1,
            )
        }
        FilledTonalButton(
            onClick = onAttach,
            enabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(
                    when {
                        current -> R.string.tmux_return
                        session.clients > 0 -> R.string.tmux_join
                        else -> R.string.tmux_resume
                    }
                )
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tmux_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tmux_take_over)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                    enabled = session.clients > 0 && !current,
                    onClick = {
                        menuOpen = false
                        onTakeOver()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tmux_detach)) },
                    leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                    enabled = session.clients > 0,
                    onClick = {
                        menuOpen = false
                        onDetach()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tmux_kill), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menuOpen = false
                        onKill()
                    },
                )
            }
        }
    }
}
