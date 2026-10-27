package com.briqt.moke.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.TailscaleSshAuth

@Composable
internal fun TailscaleSshDialog(request: TailscaleSshAuth.Request) {
    val context = LocalContext.current
    var browserFailed by remember(request.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { TailscaleSshAuth.cancel(request.id) },
        title = { Text(stringResource(R.string.tailscale_ssh_auth_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.target, style = MaterialTheme.typography.bodyMedium)
                if (request.message.isNotBlank()) Text(request.message, style = MaterialTheme.typography.bodyMedium)
                if (request.url != null) {
                    Text(Uri.parse(request.url).host.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.tailscale_ssh_auth_waiting), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (browserFailed) Text(stringResource(R.string.tailnet_ssh_browser_unavailable), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            request.url?.let { url ->
                TextButton(onClick = {
                    // A finished or replaced request must never open a late authentication link.
                    if (TailscaleSshAuth.pending.value.any { it.id == request.id && it.url == url }) {
                        browserFailed = !openLoginPage(context, url)
                    }
                }) { Text(stringResource(R.string.tailscale_ssh_auth_open_browser)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { TailscaleSshAuth.cancel(request.id) }) { Text(stringResource(R.string.tailnet_ssh_cancel_connection)) }
        },
    )
}
