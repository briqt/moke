package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.net.TailnetDevice
import com.briqt.moke.net.describe
import com.briqt.moke.net.filterTailnetDevices
import com.briqt.moke.net.recovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TailnetDevicePicker(
    network: NetworkEntry,
    load: suspend (NetworkEntry) -> List<TailnetDevice>,
    onSelect: (TailnetDevice) -> Unit,
    onOpenNetwork: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(network) { mutableStateOf("") }
    var revision by remember(network) { mutableIntStateOf(0) }
    var devices by remember(network) { mutableStateOf<List<TailnetDevice>?>(null) }
    var loading by remember(network) { mutableStateOf(true) }
    var failure by remember(network) { mutableStateOf<Throwable?>(null) }
    val context = LocalContext.current
    LaunchedEffect(network, revision) {
        loading = true
        failure = null
        try {
            val result = load(network)
            currentCoroutineContext().ensureActive()
            devices = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure = error
        } finally {
            loading = false
        }
    }
    val filtered = remember(devices, query) { filterTailnetDevices(devices.orEmpty(), query) }
    val recovery = (failure as? NetworkException)?.recovery
    val canOpenNetwork = recovery?.kind in setOf(RecoveryKind.LOGIN_NETWORK, RecoveryKind.VIEW_NETWORK, RecoveryKind.EDIT_NETWORK)
    val available = !loading && failure == null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().heightIn(max = (LocalConfiguration.current.screenHeightDp * .82f).dp)
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tailnet_choose_device), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(network.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { revision++ }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.tailnet_search_devices)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) { {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tailnet_devices_clear_search)) }
                } } else null,
            )
            when {
                failure != null -> Column {
                    Text((failure as? NetworkException)?.describe(context) ?: stringResource(R.string.tailnet_devices_error),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (!devices.isNullOrEmpty()) Text(stringResource(R.string.tailnet_devices_stale), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (canOpenNetwork) onOpenNetwork() else revision++ }) {
                        Text(stringResource(if (canOpenNetwork) recoveryActionLabel(recovery!!.kind) else R.string.action_retry))
                    }
                }
                loading -> Text(stringResource(R.string.tailnet_devices_loading), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(stringResource(R.string.tailnet_devices_scope), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!loading && failure == null && filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                    Text(stringResource(if (devices.isNullOrEmpty()) R.string.tailnet_devices_empty else R.string.tailnet_devices_no_matches),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(filtered, key = { it.id }) { device ->
                    ListItem(
                        headlineContent = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(device.address, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val details = listOf(device.os, if (device.tailscaleSsh) "Tailscale SSH" else "").filter { it.isNotBlank() }
                                if (details.isNotEmpty()) Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        trailingContent = { Text(stringResource(if (device.online) R.string.tailnet_device_online else R.string.tailnet_device_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable(enabled = available) { onSelect(device) },
                    )
                }
            }
        }
    }
}
