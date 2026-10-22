package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxUiState
import kotlinx.coroutines.launch

/** This choice layer is the confirmation. Its frozen end target never follows a later client switch. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    val cancelFocus = remember(target) { FocusRequester() }
    val scope = rememberCoroutineScope()
    val selection = target.selection
    BasicAlertDialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            contentColor = AlertDialogDefaults.textContentColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // The dialog supplies the bounded viewport. Scroll the whole surface content so a
            // wrapped footer cannot consume the body's height or leave its own actions clipped.
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.close_connection),
                    style = MaterialTheme.typography.titleMedium,
                    color = AlertDialogDefaults.titleContentColor,
                )
                Column(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.tmux_target, target.targetLabel), style = MaterialTheme.typography.bodySmall)
                    Text(selection?.session?.name ?: target.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(when {
                            selection != null -> R.string.tmux_close_message
                            target.unknown -> R.string.tmux_close_unknown
                            else -> R.string.session_close_plain_message
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (selection != null && target.otherClients > 0) {
                        Text(
                            pluralStringResource(R.plurals.tmux_close_other_clients, target.otherClients, target.otherClients),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (failed || state.message != null) {
                        InfoNote(state.message ?: stringResource(R.string.tmux_action_failed), error = true, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                        modifier = Modifier.focusRequester(cancelFocus).sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    // Request inside the dialog composition, after the Cancel focus node is attached.
                    LaunchedEffect(target) { cancelFocus.requestFocus() }
                    if (selection == null && target.unknown) {
                        TextButton(
                            onClick = { onRefresh?.invoke() },
                            enabled = onRefresh != null && !submitting && !state.busy,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.action_refresh))
                        }
                    }
                    TextButton(
                        onClick = onDisconnect,
                        enabled = !submitting,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.session_disconnect))
                    }
                    if (selection != null) {
                        TextButton(
                            enabled = !submitting && state.canManage,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
                            Text(stringResource(if (submitting) R.string.action_working else R.string.tmux_kill))
                        }
                    }
                }
            }
        }
    }
}
