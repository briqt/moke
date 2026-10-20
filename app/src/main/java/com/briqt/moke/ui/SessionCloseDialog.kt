package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxUiState
import kotlinx.coroutines.launch

/** This choice layer is the confirmation. Its frozen end target never follows a later client switch. */
@Composable
internal fun SessionCloseDialog(
    target: SessionCloseTarget,
    state: TmuxUiState,
    onDisconnect: () -> Unit,
    onEnd: suspend (TmuxSelection) -> Boolean,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    var submitting by remember(target) { mutableStateOf(false) }
    var failed by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.close_connection), style = MaterialTheme.typography.titleMedium) },
        text = {
            // Choices scroll with the full object name on a small window; Cancel stays reachable.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tmux_target, target.targetLabel), style = MaterialTheme.typography.bodySmall)
                val selection = target.selection
                if (selection != null) {
                    Text(selection.session.name, style = MaterialTheme.typography.bodyLarge)
                    if (target.otherClients > 0) Text(
                        pluralStringResource(R.plurals.tmux_close_other_clients, target.otherClients, target.otherClients),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (target.unknown) {
                    InfoNote(stringResource(R.string.tmux_close_unknown))
                } else {
                    Text(stringResource(R.string.close_connection_confirm, target.title))
                }
                HorizontalDivider()
                TextButton(
                    onClick = onDisconnect,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(if (selection != null || target.unknown) R.string.session_disconnect else R.string.action_close))
                        if (selection != null) Text(stringResource(R.string.tmux_disconnect_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selection != null) {
                    TextButton(
                        enabled = !submitting && state.canManage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = {
                            if (!submitting) {
                                submitting = true
                                failed = false
                                scope.launch {
                                    try {
                                        completeTmuxUiAction({ onEnd(selection) }, onDisconnect, { failed = true })
                                    } finally { submitting = false }
                                }
                            }
                        },
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(stringResource(if (submitting) R.string.action_working else R.string.tmux_kill), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.tmux_end_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (target.unknown && onRefresh != null) {
                    TextButton(onClick = onRefresh, enabled = !submitting && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tmux_refresh))
                    }
                }
                if (failed || state.message != null) {
                    InfoNote(state.message ?: stringResource(R.string.tmux_action_failed), error = true, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
