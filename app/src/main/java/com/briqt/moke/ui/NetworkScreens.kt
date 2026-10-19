package com.briqt.moke.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.ListState
import com.briqt.moke.data.MAX_TAILNET_ENTRIES
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.data.StoreResult
import com.briqt.moke.net.NetworkManager
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Compatibility alias; the data transaction owns the single limit.
const val MAX_TAILNETS = MAX_TAILNET_ENTRIES

enum class TailnetPhase { CHECKING, NOT_LOGGED_IN, CONNECTING, WAITING_BROWSER, WAITING_APPROVAL, EXPIRED, LOGGED_IN, ACTIVE }

fun tailnetPhase(
    e: NetworkEntry,
    live: MokeViewModel.NetworkLive,
    flow: MokeViewModel.LoginFlow?,
    knownPersisted: Boolean = true,
): TailnetPhase {
    val st = live.status[e.id]
    return when {
        st?.state == "Running" && !st.expired -> TailnetPhase.ACTIVE
        st?.state == "NeedsMachineAuth" -> TailnetPhase.WAITING_APPROVAL
        flow != null && flow.url.isNotEmpty() -> TailnetPhase.WAITING_BROWSER
        flow != null && flow.busy -> TailnetPhase.CONNECTING
        st != null && (st.expired || (st.state == "NeedsLogin" && st.everLoggedIn)) -> TailnetPhase.EXPIRED
        st == null && e.id in live.expired -> TailnetPhase.EXPIRED
        live.loggedIn[e.id] == true -> TailnetPhase.LOGGED_IN
        live.loggedIn[e.id] == false || st?.state == "NeedsLogin" -> TailnetPhase.NOT_LOGGED_IN
        knownPersisted -> TailnetPhase.CHECKING
        else -> TailnetPhase.NOT_LOGGED_IN
    }
}

@Composable
fun tailnetPhaseLabel(p: TailnetPhase, leases: Int = 0): String {
    val label = stringResource(tailnetPhaseLabelRes(p, leases))
    return label + if (p == TailnetPhase.ACTIVE && leases > 0) {
        " · " + pluralStringResource(R.plurals.net_connections, leases, leases)
    } else ""
}

@Composable
fun tailnetPhaseColor(p: TailnetPhase): Color = when (p) {
    TailnetPhase.EXPIRED -> MaterialTheme.colorScheme.error
    TailnetPhase.CONNECTING, TailnetPhase.WAITING_BROWSER, TailnetPhase.WAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    TailnetPhase.NOT_LOGGED_IN, TailnetPhase.CHECKING, TailnetPhase.LOGGED_IN -> MaterialTheme.colorScheme.onSurfaceVariant
    TailnetPhase.ACTIVE -> MaterialTheme.colorScheme.primary
}

@Composable
fun networkSubtitle(e: NetworkEntry): String = when (e.kind) {
    NetworkKind.TAILSCALE -> if (e.isOfficialControl) {
        "Tailscale · " + stringResource(R.string.control_official)
    } else {
        stringResource(R.string.control_self_hosted) + " · " + (e.controlUrl.toUri().host ?: e.controlUrl)
    }
    NetworkKind.PROXY -> "${proxyTypeLabel(e.proxyType)} · ${proxyAddressLabel(e.proxyHost, e.proxyPort)}"
}

fun proxyTypeLabel(t: ProxyType): String = when (t) {
    ProxyType.SOCKS5 -> "SOCKS5"
    ProxyType.SOCKS4 -> "SOCKS4"
    ProxyType.HTTP -> "HTTP"
    ProxyType.HTTPS -> "HTTPS"
}

/** Authorization URLs go only to the browser, never to logs or the clipboard. */
fun openLoginPage(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        expandedHeight = MokeDimens.topBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
fun NetworksScreen(
    networks: List<NetworkEntry>,
    live: MokeViewModel.NetworkLive,
    loginFlows: Map<String, MokeViewModel.LoginFlow>,
    onWatch: (Boolean) -> Unit,
    onAdd: (NetworkKind) -> Unit,
    onOpen: (NetworkEntry) -> Unit,
    onBack: () -> Unit,
    networkState: ListState<NetworkEntry> = ListState.Ready(networks),
    onRetry: () -> Unit = {},
) {
    DisposableEffect(Unit) { onWatch(true); onDispose { onWatch(false) } }
    val entries = (networkState as? ListState.Ready)?.items.orEmpty()
    val tailnets = entries.filter { it.kind == NetworkKind.TAILSCALE }
    val proxies = entries.filter { it.kind == NetworkKind.PROXY }
    Scaffold(topBar = { SubPageTopBar(stringResource(R.string.menu_network), onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (networkState) {
                ListState.Loading -> InfoNote(stringResource(R.string.networks_loading))
                ListState.Unreadable -> {
                    InfoNote(stringResource(R.string.networks_unreadable), error = true)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                is ListState.Ready -> {
                    InfoNote(stringResource(R.string.network_usage_hint))
                    NetworkSectionHeader(stringResource(R.string.section_tailscale), stringResource(R.string.add_tailnet),
                        { onAdd(NetworkKind.TAILSCALE) }, tailnets.size < MAX_TAILNET_ENTRIES)
                    if (tailnets.size >= MAX_TAILNET_ENTRIES) EmptyHint(stringResource(R.string.tailnet_limit, MAX_TAILNET_ENTRIES))
                    if (tailnets.isEmpty()) EmptyHint(stringResource(R.string.tailscale_empty))
                    tailnets.forEach { e ->
                        val phase = tailnetPhase(e, live, loginFlows[e.id])
                        NetworkCard(e, tailnetPhaseLabel(phase, live.leases[e.id] ?: 0), tailnetPhaseColor(phase)) { onOpen(e) }
                    }
                    NetworkSectionHeader(stringResource(R.string.section_proxy), stringResource(R.string.add_proxy), { onAdd(NetworkKind.PROXY) })
                    if (proxies.isEmpty()) EmptyHint(stringResource(R.string.proxy_empty))
                    proxies.forEach { e ->
                        val leases = live.leases[e.id] ?: 0
                        NetworkCard(e, if (leases > 0) activeLabel(leases) else null, MaterialTheme.colorScheme.primary) { onOpen(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkSectionHeader(title: String, addText: String, onAdd: () -> Unit, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 4.dp))
        TextButton(onClick = onAdd, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.weight(1f, fill = false).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(addText, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun activeLabel(leases: Int): String = stringResource(R.string.net_state_active) +
    if (leases > 0) " · " + pluralStringResource(R.plurals.net_connections, leases, leases) else ""

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun NetworkCard(e: NetworkEntry, status: String?, statusColor: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.name, fontWeight = FontWeight.SemiBold)
                Text(networkSubtitle(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (e.kind == NetworkKind.PROXY) MokeMono else null)
                if (status != null) MokeBadge(status, statusColor)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Save and login both await durable storage; only login stays on this page after success. */
@Composable
fun NetworkEditScreen(
    kind: NetworkKind,
    initial: NetworkEntry?,
    networks: List<NetworkEntry>,
    live: MokeViewModel.NetworkLive,
    loginFlows: Map<String, MokeViewModel.LoginFlow>,
    hostsUsing: (String) -> Int,
    moshHostsUsing: (String) -> Int,
    liveSessionsUsing: (String) -> Int,
    onWatch: (Boolean) -> Unit,
    onSave: suspend (NetworkEntry, Boolean) -> StoreResult<NetworkEntry>,
    onLogin: suspend (NetworkEntry, String, Boolean) -> StoreResult<NetworkEntry>,
    onReopenLogin: (String) -> Unit,
    onCancelLogin: (NetworkEntry) -> Unit,
    onLogout: suspend (NetworkEntry) -> StoreResult<Unit>,
    onDelete: suspend (NetworkEntry) -> StoreResult<Unit>,
    onClose: (savedId: String?) -> Unit,
    networkState: ListState<NetworkEntry> = ListState.Ready(networks),
    onRetryNetworks: () -> Unit = {},
) {
    DisposableEffect(Unit) { onWatch(true); onDispose { onWatch(false) } }
    val base = remember { initial ?: NetworkEntry(kind = kind) }
    val id = base.id
    var savedEntry by remember(id) { mutableStateOf(initial) }
    var name by remember(id) { mutableStateOf(base.name) }
    var official by remember(id) { mutableStateOf(base.kind != NetworkKind.TAILSCALE || base.isOfficialControl) }
    var controlUrl by remember(id) { mutableStateOf(if (base.isOfficialControl) "" else base.controlUrl) }
    val defaultDeviceName = remember(id) { NetworkManager.defaultDeviceName() }
    var deviceName by remember(id) { mutableStateOf(base.deviceName.ifBlank { if (initial == null) defaultDeviceName else "" }) }
    var useAuthKey by remember(id) { mutableStateOf(false) }
    var authKey by remember(id) { mutableStateOf("") }
    var proxyType by remember(id) { mutableStateOf(base.proxyType) }
    var proxyHost by remember(id) { mutableStateOf(base.proxyHost) }
    var proxyPort by remember(id) { mutableStateOf(if (initial == null) defaultPort(base.proxyType).toString() else base.proxyPort.toString()) }
    var username by remember(id) { mutableStateOf(base.username) }
    var password by remember(id) { mutableStateOf(base.password) }
    var showPassword by remember(id) { mutableStateOf(false) }
    var extraCa by remember(id) { mutableStateOf(base.extraCaPem) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    var confirmLogout by remember(id) { mutableStateOf(false) }
    var submitted by remember(id) { mutableStateOf(false) }
    var authSubmitted by remember(id) { mutableStateOf(false) }
    var submitting by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf<StoreFailure?>(null) }
    var actionFailure by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val targets = remember(id) { List(10) { EditorTarget() } }
    val entries = (networkState as? ListState.Ready)?.items.orEmpty()
    val atLimit = savedEntry == null && kind == NetworkKind.TAILSCALE && entries.count { it.kind == NetworkKind.TAILSCALE } >= MAX_TAILNET_ENTRIES
    val nameError = when {
        name.isBlank() -> stringResource(R.string.form_name_required)
        FormValidation.duplicateName(name, id, kind, entries) -> stringResource(R.string.network_name_duplicate)
        else -> null
    }
    val urlError = if (kind == NetworkKind.TAILSCALE && !official && !FormValidation.controlUrl(controlUrl)) stringResource(R.string.control_url_invalid) else null
    val deviceError = if (kind == NetworkKind.TAILSCALE && !FormValidation.deviceName(deviceName, defaultDeviceName)) stringResource(R.string.device_name_invalid) else null
    val hostError = if (kind == NetworkKind.PROXY && !FormValidation.proxyHost(proxyHost)) stringResource(R.string.form_host_invalid) else null
    val portValue = FormValidation.port(proxyPort)
    val portError = if (kind == NetworkKind.PROXY && portValue == null) stringResource(R.string.form_port_invalid) else null
    val userError = if (kind == NetworkKind.PROXY && !FormValidation.proxyCredential(username, proxyType, true)) stringResource(R.string.proxy_username_invalid) else null
    val passwordError = if (kind == NetworkKind.PROXY && proxyType != ProxyType.SOCKS4 && !FormValidation.proxyCredential(password, proxyType, false)) stringResource(R.string.proxy_password_invalid) else null
    val caValid = remember(extraCa, proxyType, kind) { kind != NetworkKind.PROXY || proxyType != ProxyType.HTTPS || FormValidation.extraCa(extraCa) }
    val caError = if (!caValid) stringResource(R.string.extra_ca_invalid) else null
    val authError = if (authKey.isBlank() || !FormValidation.authKey(authKey)) stringResource(R.string.auth_key_invalid) else null
    val errors = listOf(nameError, urlError, deviceError, hostError, portError, userError, passwordError, caError)
    fun rawDraft() = listOf(name, official, controlUrl, deviceName, proxyType, proxyHost, proxyPort, username, password, extraCa, authKey)
    var baseline by remember(id) { mutableStateOf(rawDraft()) }
    fun current(): NetworkEntry = when (kind) {
        NetworkKind.TAILSCALE -> (savedEntry ?: base).copy(name = name.trim(), kind = kind,
            controlUrl = if (official) NetworkEntry.OFFICIAL_CONTROL_URL else controlUrl.trim(), deviceName = deviceName.trim())
        NetworkKind.PROXY -> (savedEntry ?: base).copy(name = name.trim(), kind = kind, proxyType = proxyType,
            proxyHost = proxyHost.trim(), proxyPort = requireNotNull(portValue), username = username,
            password = if (proxyType == ProxyType.SOCKS4) "" else password,
            extraCaPem = if (proxyType == ProxyType.HTTPS) extraCa.trim() else "")
    }
    fun submit(login: Boolean = false, useKey: Boolean = false) {
        if (submitting) return
        submitted = true
        authSubmitted = useKey
        if (networkState !is ListState.Ready || atLimit) {
            scope.launch { targets[9].reveal(false) }
            return
        }
        val first = errors.indexOfFirst { it != null }.let { if (it < 0 && useKey && authError != null) 8 else it }
        if (first >= 0) { scope.launch { targets[first].reveal() }; return }
        val draft = current()
        actionFailure = false
        val editing = savedEntry != null
        val key = if (useKey) authKey.trim() else ""
        submitting = true
        failure = null
        scope.launch {
            try {
                val result = if (login) onLogin(draft, key, editing) else onSave(draft, editing)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is StoreResult.Saved -> {
                        savedEntry = result.value
                        if (login) { authKey = ""; authSubmitted = false; baseline = rawDraft() }
                        else onClose(result.value.id)
                    }
                    is StoreResult.Failed -> { failure = result.reason; targets[9].reveal(false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failure = StoreFailure.IO; targets[9].reveal(false)
            } finally { submitting = false }
        }
    }
    fun storedAction(delete: Boolean) {
        val entry = savedEntry ?: return
        if (submitting) return
        actionFailure = true
        submitting = true
        failure = null
        scope.launch {
            try {
                val result = if (delete) onDelete(entry) else onLogout(entry)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is StoreResult.Saved -> if (delete) onClose(null)
                    is StoreResult.Failed -> { failure = result.reason; targets[9].reveal(false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failure = StoreFailure.IO; targets[9].reveal(false)
            } finally { submitting = false }
        }
    }
    val flow = loginFlows[id]
    val phase = if (kind == NetworkKind.TAILSCALE) tailnetPhase(savedEntry ?: base, live, flow, knownPersisted = savedEntry != null) else null
    val controlLocked = phase != null && phase != TailnetPhase.NOT_LOGGED_IN
    val title = stringResource(when {
        savedEntry != null && kind == NetworkKind.TAILSCALE -> R.string.network_edit_tailnet
        savedEntry != null -> R.string.network_edit_proxy
        kind == NetworkKind.TAILSCALE -> R.string.network_new_tailnet
        else -> R.string.network_new_proxy
    })
    EditorScaffold(title, rawDraft() != baseline, submitting, onLeave = { onClose(savedEntry?.id) }, onSave = { submit() },
        submittingLabel = if (actionFailure) R.string.action_working else R.string.action_saving) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.editorTarget(targets[9])) {
                failure?.let { InfoNote(stringResource(if (actionFailure) R.string.form_action_failed else R.string.store_save_failed) + "\n" + stringResource(storeFailureMessage(it)), error = true) }
                when (networkState) {
                    ListState.Loading -> InfoNote(stringResource(R.string.networks_loading))
                    ListState.Unreadable -> {
                        InfoNote(stringResource(R.string.networks_unreadable), error = true)
                        TextButton(onClick = onRetryNetworks, enabled = !submitting) { Text(stringResource(R.string.action_retry)) }
                    }
                    is ListState.Ready -> if (atLimit) InfoNote(stringResource(R.string.tailnet_limit, MAX_TAILNET_ENTRIES), error = true)
                }
            }
            FormTextField(name, { name = it }, stringResource(R.string.field_name_required), error = nameError, submitted = submitted,
                enabled = !submitting, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[0]))
            when (kind) {
                NetworkKind.TAILSCALE -> {
                    LabeledChoices(stringResource(R.string.field_control), listOf(stringResource(R.string.control_official), stringResource(R.string.control_self_hosted)),
                        if (official) 0 else 1, { official = it == 0 }, enabled = !controlLocked && !submitting)
                    if (!official) FormTextField(controlUrl, { controlUrl = it }, stringResource(R.string.field_control_url), error = urlError,
                        submitted = submitted, enabled = !controlLocked && !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[1]))
                    if (controlLocked) InfoNote(stringResource(R.string.control_locked_note))
                    FormTextField(deviceName, { deviceName = it }, stringResource(R.string.field_device_name), error = deviceError, submitted = submitted,
                        hint = stringResource(R.string.device_name_hint), enabled = !submitting, modifier = Modifier.editorTarget(targets[2]))
                    TailnetLoginPanel(phase!!, live.status[id], live.leases[id] ?: 0, flow, official, !submitting,
                        useAuthKey, authKey, { authKey = it }, { useAuthKey = it },
                        saveBeforeLogin = tailnetLoginRequiresSave(savedEntry, current()),
                        authError = authError, authSubmitted = authSubmitted, authTarget = targets[8],
                        onLogin = { useKey -> submit(login = true, useKey = useKey) }, onReopen = { onReopenLogin(id) },
                        onCancel = { onCancelLogin(savedEntry ?: base) }, onLogout = { confirmLogout = true })
                }
                NetworkKind.PROXY -> {
                    LabeledChoices(stringResource(R.string.field_proxy_type), ProxyType.entries.map(::proxyTypeLabel), proxyType.ordinal, {
                        val next = ProxyType.entries[it]
                        proxyPort = changedProxyPort(proxyPort, proxyType, next)
                        proxyType = next
                    }, enabled = !submitting)
                    FormTextField(proxyHost, { proxyHost = it }, stringResource(R.string.field_proxy_host), error = hostError, submitted = submitted,
                        enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[3]))
                    FormTextField(proxyPort, { proxyPort = it }, stringResource(R.string.field_port), error = portError, submitted = submitted,
                        enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.editorTarget(targets[4]))
                    FormTextField(username, { username = it }, stringResource(if (proxyType == ProxyType.SOCKS4) R.string.field_user_id_optional else R.string.field_proxy_username),
                        error = userError, submitted = submitted, enabled = !submitting, modifier = Modifier.editorTarget(targets[5]))
                    if (proxyType != ProxyType.SOCKS4) FormTextField(password, { password = it }, stringResource(R.string.field_proxy_password),
                        error = passwordError, submitted = submitted, enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.editorTarget(targets[6]),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = stringResource(if (showPassword) R.string.action_hide else R.string.action_reveal))
                        } })
                    if (proxyType == ProxyType.HTTP && (username.isNotEmpty() || password.isNotEmpty())) InfoNote(stringResource(R.string.proxy_http_plaintext_note))
                    if (proxyType == ProxyType.HTTPS) FormTextField(extraCa, { extraCa = it }, stringResource(R.string.field_extra_ca),
                        error = caError, submitted = submitted, hint = stringResource(R.string.extra_ca_hint), singleLine = false, minLines = 2, maxLines = 6,
                        enabled = !submitting, modifier = Modifier.editorTarget(targets[7]))
                    if (!proxyType.carriesUdp) {
                        val mosh = moshHostsUsing(id)
                        InfoNote(if (mosh > 0) pluralStringResource(R.plurals.proxy_no_udp_mosh_hosts, mosh, mosh) else stringResource(R.string.proxy_no_udp_note), error = mosh > 0)
                    } else InfoNote(stringResource(R.string.proxy_socks5_udp_note))
                }
            }
            if (savedEntry != null) TextButton(onClick = { confirmDelete = true }, enabled = !submitting, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(stringResource(R.string.network_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    savedEntry?.let { stored ->
        if (confirmDelete) {
            val hosts = hostsUsing(id)
            val sessions = liveSessionsUsing(id)
            val parts = buildList {
                if (hosts > 0) add(pluralStringResource(R.plurals.network_delete_hosts, hosts, hosts))
                if (sessions > 0) add(pluralStringResource(R.plurals.network_close_sessions, sessions, sessions))
                if (kind == NetworkKind.TAILSCALE && phase != TailnetPhase.NOT_LOGGED_IN) add(stringResource(R.string.network_delete_tailnet))
                add(stringResource(R.string.network_delete_plain))
            }
            ConfirmDialog(stringResource(R.string.network_delete_title, stored.name), parts.joinToString("\n"), stringResource(R.string.action_delete),
                destructive = true, onConfirm = { confirmDelete = false; storedAction(true) }, onDismiss = { confirmDelete = false })
        }
        if (confirmLogout) {
            val sessions = liveSessionsUsing(id)
            ConfirmDialog(stringResource(R.string.logout_title, stored.name), listOfNotNull(stringResource(R.string.logout_message),
                if (sessions > 0) pluralStringResource(R.plurals.network_close_sessions, sessions, sessions) else null).joinToString("\n"), stringResource(R.string.action_logout),
                destructive = true, onConfirm = { confirmLogout = false; storedAction(false) }, onDismiss = { confirmLogout = false })
        }
    }
}

/** Wrapping choices keep long translations and large fonts usable on narrow screens. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LabeledChoices(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, text ->
                androidx.compose.material3.FilterChip(selected = selected == index, onClick = { onSelect(index) }, enabled = enabled, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun TailnetLoginPanel(
    phase: TailnetPhase,
    status: NetworkManager.NodeStatus?,
    leases: Int,
    flow: MokeViewModel.LoginFlow?,
    official: Boolean,
    enabled: Boolean,
    useAuthKey: Boolean,
    authKey: String,
    onAuthKey: (String) -> Unit,
    onUseAuthKey: (Boolean) -> Unit,
    saveBeforeLogin: Boolean,
    authError: String?,
    authSubmitted: Boolean,
    authTarget: EditorTarget,
    onLogin: (useKey: Boolean) -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MokeShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase == TailnetPhase.CONNECTING || phase == TailnetPhase.CHECKING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = if (phase == TailnetPhase.CHECKING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
                Text(tailnetPhaseLabel(phase, leases), fontWeight = FontWeight.SemiBold, color = tailnetPhaseColor(phase))
            }
            if (status != null && phase in listOf(TailnetPhase.ACTIVE, TailnetPhase.LOGGED_IN)) {
                val detail = listOf(status.loginName, status.tailnet).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.ips.isNotEmpty()) Text(status.ips.joinToString("  "), style = MaterialTheme.typography.bodySmall, fontFamily = MokeMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            flow?.error?.let { InfoNote(it, error = true) }
            when (phase) {
                TailnetPhase.CHECKING -> Unit
                TailnetPhase.NOT_LOGGED_IN, TailnetPhase.EXPIRED -> {
                    if (!official) {
                        LabeledChoices(stringResource(R.string.field_login_method),
                            listOf(stringResource(R.string.login_method_browser), stringResource(R.string.field_auth_key)),
                            if (useAuthKey) 1 else 0, { onUseAuthKey(it == 1) }, enabled = enabled)
                    }
                    val loginWithKey = !official && useAuthKey
                    if (loginWithKey) {
                        FormTextField(authKey, onAuthKey, stringResource(R.string.field_auth_key), error = authError, submitted = authSubmitted,
                            hint = stringResource(R.string.auth_key_hint), enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.editorTarget(authTarget))
                    }
                    Button(onClick = { onLogin(loginWithKey) }, enabled = enabled, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text(stringResource(if (saveBeforeLogin) R.string.action_save_and_login else R.string.action_login))
                    }
                }
                TailnetPhase.CONNECTING, TailnetPhase.WAITING_APPROVAL -> {
                    if (phase == TailnetPhase.WAITING_APPROVAL) InfoNote(stringResource(R.string.net_waiting_approval_hint))
                    TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.action_cancel_login)) }
                }
                TailnetPhase.WAITING_BROWSER -> {
                    InfoNote(stringResource(R.string.net_return_after_login))
                    OutlinedButton(onClick = onReopen, enabled = enabled) { Text(stringResource(R.string.action_reopen_login)) }
                    TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.action_cancel_login)) }
                }
                TailnetPhase.LOGGED_IN, TailnetPhase.ACTIVE -> {
                    InfoNote(stringResource(R.string.net_logged_in_hint))
                    TextButton(onClick = onLogout, enabled = enabled) {
                        Text(stringResource(R.string.action_logout), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
