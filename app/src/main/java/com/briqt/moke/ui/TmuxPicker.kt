package com.briqt.moke.ui

import androidx.compose.runtime.Composable
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxLaunchPolicy
import com.briqt.moke.terminal.TmuxUiState

/** Selecting an existing session and strictly creating a new one are separate operations. */
@Composable
fun TmuxPickerDialog(
    state: TmuxUiState,
    targetLabel: String,
    defaultName: String,
    onPick: suspend (TmuxSelection) -> Boolean,
    onNew: suspend (String) -> Boolean,
    onPlainShell: () -> Unit,
) {
    TmuxSessionSheet(
        state = state,
        targetLabel = targetLabel,
        defaultName = defaultName,
        onDismiss = onPlainShell,
        onOpen = onPick,
        // Success is owned by the caller's picker state; never open a plain shell on success.
        onOpened = {},
        onNew = onNew,
        onReturnCurrent = onPlainShell,
        onPlainShell = onPlainShell.takeIf { TmuxLaunchPolicy.offersPlainShell(state) },
    )
}
