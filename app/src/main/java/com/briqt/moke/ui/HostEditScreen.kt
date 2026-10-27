package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.briqt.moke.R
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.ListState
import com.briqt.moke.data.MAX_TAILNET_ENTRIES
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.data.StoreResult
import com.briqt.moke.terminal.KnownHosts
import com.briqt.moke.terminal.PortForwards
import com.briqt.moke.net.TailnetDevice
import com.briqt.moke.net.selectTailnetDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    initial: Host?,
    allHosts: List<Host>,
    onSave: suspend (Host) -> StoreResult<Host>,
    onCancel: () -> Unit,
    savedFingerprint: (Host) -> String? = { null },
    onClearFingerprint: (Host) -> Unit = {},
    networks: List<NetworkEntry> = emptyList(),
    networkLive: MokeViewModel.NetworkLive = MokeViewModel.NetworkLive(),
    networkEditor: @Composable (kind: NetworkKind, initial: NetworkEntry?, onClose: (String?) -> Unit) -> Unit = { _, _, _ -> },
    loadDevices: suspend (NetworkEntry) -> List<TailnetDevice> = { emptyList() },
    onSaved: () -> Unit = {},
    networkState: ListState<NetworkEntry> = ListState.Ready(networks),
    hostStoreState: ListState<Host> = ListState.Ready(allHosts),
    onRetryNetworks: () -> Unit = {},
) {
    // In-memory only: stable IDs survive recomposition, not process recreation or saved-state serialization.
    val base = remember(initial?.id) { initial ?: Host() }
    var label by remember(base.id) { mutableStateOf(base.label) }
    var host by remember(base.id) { mutableStateOf(base.host) }
    var port by remember(base.id) { mutableStateOf(base.port.toString()) }
    var username by remember(base.id) { mutableStateOf(base.username) }
    var authType by remember(base.id) { mutableStateOf(base.authType) }
    var password by remember(base.id) { mutableStateOf(base.password) }
    var privateKey by remember(base.id) { mutableStateOf(base.privateKeyPem) }
    var passphrase by remember(base.id) { mutableStateOf(base.passphrase) }
    var showPassword by remember(base.id) { mutableStateOf(false) }
    var showPassphrase by remember(base.id) { mutableStateOf(false) }
    var showPrivateKey by remember(base.id) { mutableStateOf(base.privateKeyPem.isBlank()) }
    var useMosh by remember(base.id) { mutableStateOf(base.useMosh) }
    var jumpHostId by remember(base.id) { mutableStateOf(base.jumpHostId) }
    var networkId by remember(base.id) { mutableStateOf(base.networkId) }
    var creatingNetwork by remember(base.id) { mutableStateOf<NetworkKind?>(null) }
    var editingNetwork by remember(base.id) { mutableStateOf<NetworkEntry?>(null) }
    var choosingDevice by remember(base.id) { mutableStateOf(false) }
    var automaticLabel by remember(base.id) { mutableStateOf<String?>(null) }
    var authenticationChosen by remember(base.id) { mutableStateOf(false) }
    var startupCommand by remember(base.id) { mutableStateOf(base.startupCommand) }
    var loginCommand by remember(base.id) { mutableStateOf(base.loginCommand) }
    var forwardPorts by remember(base.id) { mutableStateOf(base.forwardPorts) }
    var group by remember(base.id) { mutableStateOf(base.group) }
    var persistence by remember(base.id) { mutableStateOf(base.persistence) }
    var submitted by remember(base.id) { mutableStateOf(false) }
    var submitting by remember(base.id) { mutableStateOf(false) }
    var failure by remember(base.id) { mutableStateOf<StoreFailure?>(null) }
    val scope = rememberCoroutineScope()
    val targets = remember(base.id) { List(8) { EditorTarget() } }
    val loadedHosts = (hostStoreState as? ListState.Ready)?.items ?: allHosts
    val loadedNetworks = (networkState as? ListState.Ready)?.items ?: networks
    val jumpHost = loadedHosts.firstOrNull { it.id == jumpHostId && it.id != base.id }
    val selectedNetwork = loadedNetworks.firstOrNull { it.id == networkId }
    val jumpMissing = hostStoreState is ListState.Ready && jumpHostId.isNotEmpty() && jumpHost == null
    val networkMissing = networkState is ListState.Ready && networkId.isNotEmpty() && selectedNetwork == null && jumpHostId.isEmpty()
    val moshNoUdp = useMosh && jumpHostId.isEmpty() && selectedNetwork?.kind == NetworkKind.PROXY && !selectedNetwork.proxyType.carriesUdp
    val validPort = FormValidation.port(port)
    val forwardInvalid = PortForwards.parsePorts(forwardPorts).invalid
    val hostError = if (!FormValidation.host(host)) stringResource(R.string.form_host_invalid) else null
    val portError = if (validPort == null) stringResource(R.string.form_port_invalid) else null
    val usernameError = if (username.isBlank() || username.any { it.isISOControl() }) stringResource(R.string.form_username_invalid) else null
    val keyError = when {
        authType == AuthType.KEY && privateKey.isBlank() -> stringResource(R.string.form_private_key_required)
        authType == AuthType.TAILSCALE_SSH && (jumpHostId.isNotEmpty() || selectedNetwork?.kind != NetworkKind.TAILSCALE || validPort != 22) ->
            stringResource(R.string.tailnet_ssh_route_hint)
        else -> null
    }
    val jumpError = when {
        jumpHostId.isNotEmpty() && hostStoreState == ListState.Loading -> stringResource(R.string.route_loading)
        jumpHostId.isNotEmpty() && hostStoreState == ListState.Unreadable -> stringResource(R.string.hosts_unreadable_title)
        jumpMissing -> stringResource(R.string.route_missing_jump)
        jumpHost?.jumpHostId?.isNotEmpty() == true -> stringResource(R.string.route_jump_chain)
        else -> null
    }
    val networkError = when {
        jumpHostId.isNotEmpty() -> null
        networkId.isNotEmpty() && networkState == ListState.Loading -> stringResource(R.string.networks_loading)
        networkId.isNotEmpty() && networkState == ListState.Unreadable -> stringResource(R.string.networks_unreadable)
        networkMissing -> stringResource(R.string.network_missing_error)
        moshNoUdp -> stringResource(R.string.mosh_needs_udp_network, selectedNetwork.name)
        else -> null
    }
    val errors = listOf(hostError, portError, usernameError, keyError, jumpError, networkError,
        if (forwardInvalid.isNotEmpty()) stringResource(R.string.field_forward_ports_invalid, forwardInvalid.joinToString(", ")) else null)
    fun rawDraft() = listOf(label, host, port, username, authType, password, privateKey, passphrase, useMosh,
        jumpHostId, networkId, startupCommand, loginCommand, forwardPorts, group, persistence)
    val baseline = remember(base.id) { rawDraft() }
    fun current(): Host = base.copy(
        label = label.trim(), host = host.trim(), port = requireNotNull(validPort), username = username.trim(),
        authType = authType, password = password, privateKeyPem = privateKey, passphrase = passphrase,
        useMosh = useMosh, jumpHostId = jumpHostId, networkId = networkId, startupCommand = startupCommand.trim(),
        loginCommand = loginCommand.trim(), forwardPorts = PortForwards.parsePorts(forwardPorts).ports.joinToString(", "),
        group = group.trim(), persistence = persistence,
        tmuxSessionName = if (persistence == SessionPersistence.TMUX) base.tmuxSessionName else "",
    )
    fun save() {
        if (submitting) return
        submitted = true
        val first = errors.indexOfFirst { it != null }
        if (first >= 0) {
            if (first == 3) showPrivateKey = true
            scope.launch { targets[first].reveal(first !in 4..5) }
            return
        }
        val draft = current()
        submitting = true
        failure = null
        scope.launch {
            try {
                val result = onSave(draft)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is StoreResult.Saved -> onSaved()
                    is StoreResult.Failed -> { failure = result.reason; targets[7].reveal(false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = StoreFailure.IO
                targets[7].reveal(false)
            } finally { submitting = false }
        }
    }
    val fingerprintTarget = if (validPort != null && FormValidation.host(host)) current() else null
    val fingerprint = fingerprintTarget?.let(savedFingerprint)
    var confirmFingerprint by remember(base.id) { mutableStateOf<Host?>(null) }
    var fingerprintCleared by remember(fingerprintTarget?.host, fingerprintTarget?.port, fingerprintTarget?.fingerprintNamespace) { mutableStateOf(false) }
    val notLoggedIn = stringResource(R.string.net_state_not_logged_in)
    val errorColor = MaterialTheme.colorScheme.error
    val jumpOptions = listOf(DropdownOption("", stringResource(R.string.jump_none))) + loadedHosts.filter { it.id != base.id }.map {
        DropdownOption(it.id, it.displayName, "${it.username}@${it.host}:${it.port}")
    }
    val networkOptions = listOf(DropdownOption("", stringResource(R.string.network_direct), stringResource(R.string.network_direct_desc))) + loadedNetworks.sortedBy { it.kind }.map {
        DropdownOption(it.id, it.name, networkSubtitle(it), tags = if (it.kind == NetworkKind.TAILSCALE && networkLive.loggedIn[it.id] == false) listOf(BadgeSpec(notLoggedIn, errorColor)) else emptyList())
    }
    EditorScaffold(
        title = stringResource(if (initial == null) R.string.add_host else R.string.host_edit_title),
        dirty = rawDraft() != baseline, submitting = submitting, onLeave = onCancel, onSave = ::save,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.editorTarget(targets[7])) {
                failure?.let { InfoNote(stringResource(R.string.store_save_failed) + "\n" + stringResource(storeFailureMessage(it)), error = true) }
                if (hostStoreState == ListState.Unreadable) InfoNote(stringResource(R.string.hosts_unreadable_title), error = true)
            }
            FormTextField(label, { label = it }, stringResource(R.string.field_name), enabled = !submitting)
            EditableDropdownField(
                label = stringResource(R.string.field_group), value = group, onValueChange = { if (!submitting) group = it },
                options = loadedHosts.mapNotNull { it.group.trim().ifEmpty { null } }.distinct().sorted(), modifier = Modifier.fillMaxWidth(),
            )
            if (jumpOptions.size > 1 || jumpHostId.isNotEmpty()) {
                Column(modifier = Modifier.editorTarget(targets[4])) {
                    RichDropdown(label = stringResource(R.string.field_jump_host), options = jumpOptions, selectedId = jumpHostId,
                        onSelect = { jumpHostId = it; choosingDevice = false }, enabled = !submitting && hostStoreState is ListState.Ready,
                        isError = jumpError != null, missingTitle = if (hostStoreState is ListState.Ready) stringResource(R.string.badge_jump_deleted) else stringResource(R.string.field_jump_host), supportingText = jumpError)
                    if (useMosh && jumpHostId.isNotEmpty()) InfoNote(stringResource(R.string.mosh_jump_hint))
                }
            }
            Column(modifier = Modifier.editorTarget(targets[5])) {
                RichDropdown(
                    label = stringResource(R.string.field_network), options = networkOptions, selectedId = networkId,
                    onSelect = { networkId = it; choosingDevice = false }, enabled = !submitting && jumpHostId.isEmpty() && networkState is ListState.Ready,
                    isError = networkError != null, missingTitle = if (networkState is ListState.Ready) stringResource(R.string.network_missing_option) else stringResource(R.string.field_network), supportingText = networkError,
                    footer = { dismiss ->
                        val limit = loadedNetworks.count { it.kind == NetworkKind.TAILSCALE } >= MAX_TAILNET_ENTRIES
                        DropdownMenuItem(text = { Text(stringResource(R.string.network_new_tailnet_item)) }, enabled = !limit,
                            onClick = { dismiss(); editingNetwork = null; creatingNetwork = NetworkKind.TAILSCALE })
                        if (limit) Text(stringResource(R.string.tailnet_limit, MAX_TAILNET_ENTRIES), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        DropdownMenuItem(text = { Text(stringResource(R.string.network_new_proxy_item)) }, onClick = { dismiss(); editingNetwork = null; creatingNetwork = NetworkKind.PROXY })
                    },
                )
                if (networkState == ListState.Loading) InfoNote(stringResource(R.string.networks_loading))
                if (networkState == ListState.Unreadable) {
                    InfoNote(stringResource(R.string.networks_unreadable), error = true)
                    TextButton(onClick = onRetryNetworks) { Text(stringResource(R.string.action_retry)) }
                }
                if (selectedNetwork?.kind == NetworkKind.TAILSCALE && jumpHostId.isEmpty()) {
                    TextButton(onClick = { choosingDevice = true }, enabled = !submitting && networkState is ListState.Ready, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.tailnet_choose_device))
                    }
                }
            }
            if (jumpHost != null) {
                val via = when {
                    jumpHost.networkId.isEmpty() -> stringResource(R.string.network_direct)
                    networkState == ListState.Loading -> stringResource(R.string.networks_loading)
                    networkState == ListState.Unreadable -> stringResource(R.string.networks_unreadable)
                    else -> loadedNetworks.firstOrNull { it.id == jumpHost.networkId }?.name ?: stringResource(R.string.network_missing_option)
                }
                InfoNote(stringResource(R.string.network_via_jump, jumpHost.displayName, via))
            }
            FormTextField(host, { host = it }, stringResource(R.string.field_host), error = hostError, submitted = submitted,
                enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[0]))
            FormTextField(port, { port = it }, stringResource(R.string.field_port), error = portError, submitted = submitted,
                enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[1]))
            FormTextField(username, { username = it }, stringResource(R.string.field_username), error = usernameError, submitted = submitted,
                enabled = !submitting, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[2]))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = authType == AuthType.PASSWORD, onClick = { authType = AuthType.PASSWORD; authenticationChosen = true }, enabled = !submitting, label = { Text(stringResource(R.string.field_password)) })
                FilterChip(selected = authType == AuthType.KEY, onClick = { authType = AuthType.KEY; authenticationChosen = true }, enabled = !submitting, label = { Text(stringResource(R.string.auth_key)) })
                FilterChip(selected = authType == AuthType.TAILSCALE_SSH, onClick = { authType = AuthType.TAILSCALE_SSH; authenticationChosen = true }, enabled = !submitting, label = { Text(stringResource(R.string.auth_tailscale_ssh)) })
            }
            if (authType == AuthType.PASSWORD) {
                FormTextField(password, { password = it; authenticationChosen = true }, stringResource(R.string.field_password), enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { RevealToggle(showPassword) { showPassword = !showPassword } })
            } else if (authType == AuthType.KEY) {
                if (!showPrivateKey && privateKey.isNotBlank()) {
                    androidx.compose.material3.OutlinedTextField(
                        value = keySummary(privateKey), onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text(stringResource(R.string.field_private_key)) }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { RevealToggle(false) { showPrivateKey = true } },
                    )
                } else {
                    FormTextField(privateKey, { privateKey = it; authenticationChosen = true }, stringResource(R.string.field_private_key), enabled = !submitting,
                        error = keyError, submitted = submitted, singleLine = false, minLines = 3, maxLines = 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.editorTarget(targets[3]),
                        trailingIcon = if (privateKey.isNotBlank()) { { RevealToggle(true) { showPrivateKey = false } } } else null)
                }
                FormTextField(passphrase, { passphrase = it }, stringResource(R.string.field_passphrase), enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { RevealToggle(showPassphrase) { showPassphrase = !showPassphrase } })
            } else {
                Column(Modifier.editorTarget(targets[3])) {
                    InfoNote(keyError ?: stringResource(R.string.tailnet_ssh_hint), error = keyError != null && submitted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.label_protocol), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !useMosh, onClick = { useMosh = false }, enabled = !submitting, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("SSH") }
                    SegmentedButton(selected = useMosh, onClick = { useMosh = true }, enabled = !submitting, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("mosh") }
                }
            }
            RichDropdown(label = stringResource(R.string.host_persistence), options = listOf(
                DropdownOption(SessionPersistence.NONE.name, stringResource(R.string.persistence_none)),
                DropdownOption(SessionPersistence.TMUX.name, stringResource(R.string.persistence_tmux))),
                selectedId = persistence.name, onSelect = { persistence = SessionPersistence.valueOf(it) }, enabled = !submitting)
            FormTextField(startupCommand, { startupCommand = it }, stringResource(R.string.field_startup_command),
                enabled = !submitting && persistence != SessionPersistence.TMUX,
                hint = stringResource(if (persistence == SessionPersistence.TMUX) R.string.startup_command_tmux_note else R.string.startup_command_hint))
            FormTextField(loginCommand, { loginCommand = it }, stringResource(R.string.field_login_command), enabled = !submitting,
                hint = stringResource(R.string.login_command_hint), singleLine = false, maxLines = 6)
            FormTextField(forwardPorts, { forwardPorts = it }, stringResource(R.string.field_forward_ports), enabled = !submitting,
                error = errors[6], submitted = submitted, hint = stringResource(R.string.field_forward_ports_hint), modifier = Modifier.editorTarget(targets[6]))
            if (host.isNotBlank()) {
                TextButton(onClick = { confirmFingerprint = fingerprintTarget }, enabled = fingerprintTarget != null && fingerprint != null && !fingerprintCleared && !submitting, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.hostkey_clear))
                }
                Text(when {
                    validPort == null -> stringResource(R.string.form_port_invalid)
                    fingerprintCleared -> stringResource(R.string.hostkey_cleared)
                    fingerprint != null -> stringResource(R.string.hostkey_clear_hint, fingerprint)
                    else -> stringResource(R.string.hostkey_none)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    confirmFingerprint?.let { target ->
        ConfirmDialog(title = stringResource(R.string.hostkey_clear), message = KnownHosts.idOf(target.host, target.port, target.fingerprintNamespace),
            confirmLabel = stringResource(R.string.action_clear_fingerprint), destructive = true,
            onConfirm = { onClearFingerprint(target); confirmFingerprint = null; fingerprintCleared = true }, onDismiss = { confirmFingerprint = null })
    }
    if (choosingDevice && networkState is ListState.Ready && selectedNetwork?.kind == NetworkKind.TAILSCALE && jumpHostId.isEmpty()) {
        val chosenNetwork = selectedNetwork
        TailnetDevicePicker(
            network = chosenNetwork,
            load = loadDevices,
            onSelect = select@{ device ->
                if (!choosingDevice || networkId != chosenNetwork.id || jumpHostId.isNotEmpty()) return@select
                val draft = base.copy(label = label, host = host, port = validPort ?: base.port, username = username,
                    authType = authType, password = password, privateKeyPem = privateKey, networkId = networkId)
                val selected = selectTailnetDevice(draft, chosenNetwork.id, device, initial == null, automaticLabel, authenticationChosen)
                val generated = label.isBlank() || label == automaticLabel
                host = selected.host
                label = selected.label
                if (generated) automaticLabel = selected.label
                if (initial == null && !authenticationChosen && draft.password.isEmpty() && draft.privateKeyPem.isBlank() && device.tailscaleSsh) port = selected.port.toString()
                authType = selected.authType
                choosingDevice = false
            },
            onOpenNetwork = { choosingDevice = false; editingNetwork = chosenNetwork; creatingNetwork = chosenNetwork.kind },
            onDismiss = { choosingDevice = false },
        )
    }
    creatingNetwork?.let { kind ->
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                networkEditor(kind, editingNetwork) { savedId ->
                    creatingNetwork = null; editingNetwork = null
                    if (savedId != null) networkId = savedId
                }
            }
        }
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(if (revealed) R.string.action_hide else R.string.action_reveal))
    }
}

@Composable
private fun keySummary(pem: String): String {
    val kind = when {
        pem.contains("OPENSSH PRIVATE KEY") -> "OpenSSH"
        pem.contains("RSA PRIVATE KEY") -> "RSA"
        pem.contains("EC PRIVATE KEY") -> "EC"
        pem.contains("DSA PRIVATE KEY") -> "DSA"
        pem.contains("ENCRYPTED PRIVATE KEY") -> "PKCS#8"
        pem.contains("PRIVATE KEY") -> "PKCS#8"
        else -> "?"
    }
    val length = pem.trim().length
    return pluralStringResource(R.plurals.private_key_summary, length, kind, length)
}
