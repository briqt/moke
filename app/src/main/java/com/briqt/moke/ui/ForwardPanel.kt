package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.PortForwards
import com.briqt.moke.ui.theme.MokeMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 端口转发面板（终端 ⋮「端口转发」、顶栏转发图标都打开它——转发的唯一主入口）。
 * 形态与 tmux 面板一致：标题行 + 主操作「添加」+ 说明 + 列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPanel(
    forwards: PortForwards,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries by forwards.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    // 正在建立的远端端口（建连可能要几秒，期间给个"连接中"而不是毫无反应）。
    var pending by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun start(remote: Int, local: Int?) {
        pending = pending + remote
        scope.launch {
            withContext(Dispatchers.IO) { forwards.start(remote, local) }
            pending = pending - remote
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fwd_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.fwd_add))
                }
            }
            HorizontalDivider()
            if (entries.isEmpty() && pending.isEmpty()) {
                Text(
                    stringResource(R.string.fwd_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            pending.filter { p -> entries.none { it.remotePort == p && it.state == PortForwards.State.ACTIVE } }.forEach { p ->
                Text(
                    stringResource(R.string.fwd_connecting, p),
                    fontFamily = MokeMono,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            entries.forEach { e ->
                ForwardRow(
                    entry = e,
                    onOpen = { onOpen(e.localUrl) },
                    onCopy = { onCopy(e.localUrl) },
                    onRetry = { start(e.remotePort, e.localPort.takeIf { it > 0 }) },
                    onStop = { scope.launch(Dispatchers.IO) { forwards.stop(e.remotePort) } },
                )
            }
        }
    }

    if (adding) {
        AddForwardDialog(
            onConfirm = { remote, local -> adding = false; start(remote, local) },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun ForwardRow(
    entry: PortForwards.Entry,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    val active = entry.state == PortForwards.State.ACTIVE
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.fwd_row_remote, entry.remotePort),
                fontFamily = MokeMono,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (active) "→ 127.0.0.1:${entry.localPort}" else stringResource(R.string.fwd_failed),
                fontFamily = MokeMono,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (active) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.link_open)) }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.fwd_copy_address), modifier = Modifier.size(18.dp))
            }
        } else {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.fwd_stop), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddForwardDialog(onConfirm: (Int, Int?) -> Unit, onDismiss: () -> Unit) {
    var remote by remember { mutableStateOf("") }
    var local by remember { mutableStateOf("") }
    val remotePort = remote.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val localText = local.trim()
    val localPort = localText.toIntOrNull()?.takeIf { it in 1024..65535 }
    val localOk = localText.isEmpty() || localPort != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fwd_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.fwd_remote_port)) },
                    placeholder = { Text("5173") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = local,
                    onValueChange = { local = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.fwd_local_port)) },
                    supportingText = { Text(stringResource(if (localOk) R.string.fwd_local_port_hint else R.string.fwd_local_port_invalid)) },
                    isError = !localOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = remotePort != null && localOk, onClick = { onConfirm(remotePort!!, localPort) }) {
                Text(stringResource(R.string.fwd_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
