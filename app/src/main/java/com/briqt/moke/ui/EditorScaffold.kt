package com.briqt.moke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.ui.theme.MokeDimens

/** Only draft pages use this shell; immediate settings and read-only panels do not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScaffold(
    title: String,
    dirty: Boolean,
    submitting: Boolean,
    onLeave: () -> Unit,
    onSave: () -> Unit,
    submittingLabel: Int = R.string.action_saving,
    content: @Composable (PaddingValues) -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (!submitting) { if (dirty) confirmLeave = true else onLeave() } }
    BackHandler(onBack = leave)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !submitting) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = !submitting, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Text(stringResource(if (submitting) submittingLabel else R.string.action_save))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        content = content,
    )
    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_title),
            message = stringResource(R.string.editor_discard_message),
            confirmLabel = stringResource(R.string.action_discard_changes),
            dismissLabel = stringResource(R.string.action_keep_editing),
            destructive = true,
            onConfirm = { confirmLeave = false; onLeave() },
            onDismiss = { confirmLeave = false },
        )
    }
}

internal class EditorTarget {
    val focus = FocusRequester()
    val bring = BringIntoViewRequester()
    suspend fun reveal(focusInput: Boolean = true) {
        androidx.compose.runtime.withFrameNanos { }
        bring.bringIntoView()
        if (focusInput) focus.requestFocus()
    }
}

internal fun Modifier.editorTarget(target: EditorTarget): Modifier =
    bringIntoViewRequester(target.bring).focusRequester(target.focus)

fun storeFailureMessage(reason: StoreFailure): Int = when (reason) {
    StoreFailure.UNREADABLE -> R.string.store_failure_unreadable
    StoreFailure.ENCRYPTION -> R.string.store_failure_encryption
    StoreFailure.IO -> R.string.store_failure_io
    StoreFailure.MISSING -> R.string.store_failure_missing
    StoreFailure.CONFLICT -> R.string.store_failure_conflict
    StoreFailure.DUPLICATE_NAME -> R.string.store_failure_duplicate_name
    StoreFailure.TAILNET_LIMIT -> R.string.store_failure_tailnet_limit
    StoreFailure.INVALID -> R.string.store_failure_invalid
}
