package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxPresence
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.terminal.selectionFor
import kotlinx.coroutines.launch

@Composable
fun TmuxPanel(
    state: TmuxUiState,
    targetLabel: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAttach: suspend (TmuxSelection) -> Boolean,
    onTakeOver: suspend (TmuxSelection) -> Boolean,
    onRename: suspend (TmuxSelection, String) -> Boolean,
    onDetach: suspend (TmuxSelection) -> Boolean,
    onKill: suspend (TmuxSelection) -> Boolean,
    onNew: suspend (String) -> Boolean,
) {
    TmuxSessionSheet(
        state = state,
        targetLabel = targetLabel,
        defaultName = "",
        onDismiss = onDismiss,
        onRefresh = onRefresh,
        onOpen = onAttach,
        onOpened = onDismiss,
        onReturnCurrent = onDismiss,
        onNew = onNew,
        management = TmuxManagement(onTakeOver, onRename, onDetach, onKill),
    )
}

internal data class TmuxManagement(
    val takeOver: suspend (TmuxSelection) -> Boolean,
    val rename: suspend (TmuxSelection, String) -> Boolean,
    val detach: suspend (TmuxSelection) -> Boolean,
    val kill: suspend (TmuxSelection) -> Boolean,
)

private enum class TmuxConfirmAction { TAKE_OVER, DETACH, END }
private data class TmuxConfirmation(val action: TmuxConfirmAction, val target: TmuxRowTarget)

/** Both entry points share rows, safety gates and the same create-and-open editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TmuxSessionSheet(
    state: TmuxUiState,
    targetLabel: String,
    defaultName: String,
    onDismiss: () -> Unit,
    onOpen: suspend (TmuxSelection) -> Boolean,
    onOpened: () -> Unit,
    onNew: suspend (String) -> Boolean,
    onRefresh: (() -> Unit)? = null,
    onReturnCurrent: (() -> Unit)? = null,
    onPlainShell: (() -> Unit)? = null,
    management: TmuxManagement? = null,
) {
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TmuxRowTarget?>(null) }
    var confirmation by remember { mutableStateOf<TmuxConfirmation?>(null) }
    var opening by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy by androidx.compose.runtime.rememberUpdatedState(state.busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || (!opening && !busy) },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!opening && !state.busy) onDismiss() },
        sheetState = sheetState,
    ) {
        // The sheet supplies bounded window/IME constraints. Only the list takes remaining space;
        // it wraps short lists instead of reserving a fixed-height empty region.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tmux_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh, enabled = !state.busy && !opening, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tmux_refresh))
                    }
                }
            }
            val targetDescription = stringResource(R.string.tmux_target, targetLabel)
            Text(
                targetDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clearAndSetSemantics { contentDescription = targetDescription },
            )
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (openFailed || state.message != null) {
                    item("message") {
                        InfoNote(state.message ?: stringResource(R.string.tmux_action_failed), error = true,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                if (opening || state.busy) {
                    item("working") { TmuxStatusText(stringResource(R.string.action_working), loading = true) }
                }
                when (state.phase) {
                    TmuxPhase.IDLE, TmuxPhase.CHECKING -> item("checking") {
                        TmuxStatusText(stringResource(R.string.tmux_checking), loading = true)
                    }
                    TmuxPhase.NOT_INSTALLED -> item("not-installed") {
                        TmuxStatusText(stringResource(R.string.tmux_not_installed))
                    }
                    TmuxPhase.ERROR -> item("error") {
                        if (state.message == null) TmuxStatusText(stringResource(R.string.tmux_control_unavailable))
                        if (onRefresh != null) {
                            TextButton(onClick = onRefresh, enabled = !state.busy && !opening, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.tmux_retry))
                            }
                        }
                    }
                    TmuxPhase.READY -> {
                        if (state.presence is TmuxPresence.Unknown && state.message == null) item("unknown") {
                            TmuxStatusText(stringResource(R.string.tmux_current_unknown))
                        }
                        if (state.sessions.isEmpty()) item("empty") {
                            TmuxStatusText(stringResource(R.string.tmux_empty))
                        }
                        items(state.sessions, key = { "${it.id}:${it.created}" }) { row ->
                            val selected = state.selectionFor(row)
                            val current = isCurrentTmuxRow(state, row)
                            val canReturn = current && onReturnCurrent != null && !state.busy && !opening
                            val enabled = !opening && state.canManage && selected != null
                            TmuxSessionRow(
                                session = row,
                                current = current,
                                enabled = enabled || canReturn,
                                showManagement = management != null,
                                managementEnabled = enabled,
                                onOpen = {
                                    if (canReturn) onReturnCurrent.invoke()
                                    else if (selected != null && !opening) {
                                        opening = true
                                        openFailed = false
                                        scope.launch {
                                            try {
                                                completeTmuxUiAction({ onOpen(selected) }, onOpened, { openFailed = true })
                                            } finally { opening = false }
                                        }
                                    }
                                },
                                // Freeze both the identity and the impact shown in each confirmation.
                                target = selected?.let { TmuxRowTarget(it, row.clients, targetLabel) },
                                onRename = { renameTarget = it },
                                onConfirm = { action, target -> confirmation = TmuxConfirmation(action, target) },
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                TextButton(onClick = { creating = true }, enabled = canCreateTmuxFromEndNotice(state) && !opening,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.tmux_new), modifier = Modifier.padding(start = 8.dp))
                }
                if (onPlainShell != null) {
                    TextButton(onClick = onPlainShell, enabled = !opening && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tmux_plain_shell))
                    }
                }
            }
        }
    }
    if (creating) {
        TmuxNameDialog(
            initial = defaultName,
            state = state,
            onDismiss = { creating = false },
            onSubmit = onNew,
            onSuccess = { creating = false; onOpened() },
        )
    }
    renameTarget?.let { target ->
        TmuxNameDialog(
            initial = target.selection.session.name,
            originalName = target.selection.session.name,
            state = state,
            onDismiss = { renameTarget = null },
            onSubmit = { management?.rename?.invoke(target.selection, it) == true },
            onSuccess = { renameTarget = null },
        )
    }
    confirmation?.let { pending ->
        val target = pending.target
        val name = target.selection.session.name
        val action = pending.action
        AsyncTmuxConfirmDialog(
            title = stringResource(when (action) {
                TmuxConfirmAction.TAKE_OVER -> R.string.tmux_take_over_confirm_title
                TmuxConfirmAction.DETACH -> R.string.tmux_detach_confirm_title
                TmuxConfirmAction.END -> R.string.tmux_kill_confirm_title
            }),
            message = stringResource(R.string.tmux_target, target.targetLabel) + "\n\n" + when (action) {
                TmuxConfirmAction.TAKE_OVER -> pluralStringResource(R.plurals.tmux_take_over_confirm_message, target.clients, target.clients, name)
                TmuxConfirmAction.DETACH -> pluralStringResource(R.plurals.tmux_detach_confirm_message, target.clients, target.clients, name)
                TmuxConfirmAction.END -> stringResource(R.string.tmux_kill_confirm_message, name) +
                    if (target.clients > 0) "\n" + pluralStringResource(R.plurals.tmux_end_clients, target.clients, target.clients) else ""
            },
            confirmLabel = stringResource(when (action) {
                TmuxConfirmAction.TAKE_OVER -> R.string.tmux_take_over
                TmuxConfirmAction.DETACH -> R.string.tmux_detach
                TmuxConfirmAction.END -> R.string.tmux_kill
            }),
            state = state,
            destructive = action == TmuxConfirmAction.END,
            onDismiss = { confirmation = null },
            onConfirm = {
                when (action) {
                    TmuxConfirmAction.TAKE_OVER -> management?.takeOver?.invoke(target.selection)
                    TmuxConfirmAction.DETACH -> management?.detach?.invoke(target.selection)
                    TmuxConfirmAction.END -> management?.kill?.invoke(target.selection)
                } == true
            },
            onSuccess = { confirmation = null; if (action == TmuxConfirmAction.TAKE_OVER) onOpened() },
        )
    }
}

@Composable
private fun TmuxStatusText(text: String, loading: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TmuxSessionRow(
    session: TmuxSession,
    current: Boolean,
    enabled: Boolean,
    showManagement: Boolean,
    managementEnabled: Boolean,
    onOpen: () -> Unit,
    target: TmuxRowTarget?,
    onRename: (TmuxRowTarget) -> Unit,
    onConfirm: (TmuxConfirmAction, TmuxRowTarget) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<TmuxRowTarget?>(null) }
    val openLabel = stringResource(if (current) R.string.tmux_return else R.string.tmux_attach)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).heightIn(min = 72.dp)
                .clickable(enabled = enabled, onClickLabel = openLabel, onClick = onOpen)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                session.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = session.name },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current) Text(stringResource(R.string.tmux_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(pluralStringResource(R.plurals.tmux_windows, session.windows, session.windows),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pluralStringResource(R.plurals.tmux_clients, session.clients, session.clients),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showManagement) Box {
            IconButton(onClick = {
                if (target != null) {
                    menuTarget = target
                    menuExpanded = true
                }
            }, enabled = managementEnabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tmux_more_for, session.name))
            }
            // Keep the captured content through the row menu's fade-only exit transition.
            TmuxRowDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                menuTarget?.let { frozen ->
                    val menu = tmuxRowMenuState(frozen, target, managementEnabled, current)
                    fun choose(action: (TmuxRowTarget) -> Unit) {
                        menu.target?.let {
                            menuExpanded = false
                            action(it)
                        }
                    }
                    Text(frozen.selection.session.name, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tmux_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = menu.canManage,
                        onClick = { choose(onRename) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tmux_take_over)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                        enabled = menu.canTakeOver,
                        onClick = { choose { onConfirm(TmuxConfirmAction.TAKE_OVER, it) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tmux_detach)) },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                        enabled = menu.canDetach,
                        onClick = { choose { onConfirm(TmuxConfirmAction.DETACH, it) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tmux_kill), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        enabled = menu.canManage,
                        onClick = { choose { onConfirm(TmuxConfirmAction.END, it) } },
                    )
                }
            }
        }
    }
}

/** A small, IME-resized editor; a failed request never discards the user's draft. */
@Composable
internal fun TmuxNameDialog(
    initial: String,
    state: TmuxUiState,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    originalName: String? = null,
    fromEndNotice: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val target = remember { EditorTarget() }
    androidx.compose.runtime.LaunchedEffect(Unit) { target.reveal() }
    val validName = FormValidation.tmuxName(text)
    val allowed = when {
        originalName != null -> state.canManage
        fromEndNotice -> canCreateTmuxFromEndNotice(state)
        else -> canCreateTmuxFromEndNotice(state)
    }
    fun submit() {
        if (submitting || !allowed) return
        submitted = true
        if (!validName) { scope.launch { target.reveal() }; return }
        submitting = true
        failed = false
        scope.launch {
            try {
                completeTmuxUiAction({ onSubmit(text.trim()) }, onSuccess, { failed = true })
            } finally { submitting = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(if (originalName == null) R.string.tmux_new else R.string.tmux_rename_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (originalName != null) Text(originalName, style = MaterialTheme.typography.bodyMedium)
                FormTextField(
                    value = text, onValueChange = { text = it }, label = stringResource(R.string.tmux_name_hint),
                    error = if (!validName) stringResource(R.string.tmux_name_invalid) else null, submitted = submitted,
                    enabled = !submitting && !state.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.editorTarget(target),
                )
                if (failed || state.message != null) InfoNote(state.message ?: stringResource(R.string.form_action_failed), error = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !submitting && allowed, onClick = { submit() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (submitting) R.string.action_working else if (originalName == null) R.string.tmux_create_open else R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AsyncTmuxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    state: TmuxUiState,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Boolean,
    onSuccess: () -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (failed || state.message != null) InfoNote(state.message ?: stringResource(R.string.tmux_action_failed), error = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !submitting && state.canManage, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                if (!submitting) {
                    submitting = true
                    failed = false
                    scope.launch {
                        try {
                            completeTmuxUiAction(onConfirm, onSuccess, { failed = true })
                        } finally { submitting = false }
                    }
                }
            }) {
                Text(if (submitting) stringResource(R.string.action_working) else confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
