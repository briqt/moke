package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.ui.theme.MokeShapes

@Composable
internal fun TerminalInputDialog(
    keyboardMode: KeyboardMode,
    swipeMode: ScrollMode,
    onKeyboard: () -> Unit,
    onKeyboardMode: () -> Unit,
    onSwipeMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_input)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = onKeyboard, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.show_keyboard))
                }
                TextButton(onClick = onKeyboardMode, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keyboard_mode))
                        Text(stringResource(keyboardModeLabel(keyboardMode)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onSwipeMode, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scroll_mode))
                        Text(stringResource(scrollModeLabel(swipeMode)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
internal fun TemporarySwipeHint(mode: ScrollMode, onAutomatic: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MokeShapes.control, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(if (mode == ScrollMode.WHEEL) R.string.scroll_temporary_wheel else R.string.scroll_temporary_arrows),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = onAutomatic) { Text(stringResource(R.string.scroll_reset_automatic)) }
        }
    }
}
