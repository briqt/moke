package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.briqt.moke.R

/** Shared title editor. Automatic updates never enter the draft or change the input selection. */
@Composable
fun SessionTitleDialog(
    initialPrefix: String,
    initialTitle: String,
    automaticPrefix: String,
    automaticTitle: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
    dialogTitle: String = stringResource(R.string.session_set_title),
) {
    var draft by remember { mutableStateOf(SessionTitleDraft(initialPrefix, initialTitle)) }
    val focusManager = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionTitleField(
                    value = draft.prefix,
                    onValueChange = { draft = draft.copy(prefix = it) },
                    label = stringResource(R.string.session_title_prefix),
                    automaticValue = automaticPrefix.ifBlank { stringResource(R.string.session_title_no_prefix) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                SessionTitleField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = stringResource(R.string.session_title_hint),
                    automaticValue = automaticTitle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                Text(
                    stringResource(R.string.session_title_preview, draft.preview(automaticPrefix, automaticTitle)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.session_title_fields_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.prefix, draft.title) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SessionTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    automaticValue: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyMedium,
            supportingText = {
                Text(stringResource(
                    if (value.isBlank()) R.string.session_title_automatic else R.string.session_title_custom_automatic,
                    automaticValue,
                ))
            },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        )
        if (value.isNotBlank()) {
            TextButton(onClick = { onValueChange("") }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(stringResource(R.string.session_title_use_automatic))
            }
        }
    }
}
