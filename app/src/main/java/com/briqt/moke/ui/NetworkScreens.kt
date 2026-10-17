package com.briqt.moke.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.net.NetworkManager
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import java.util.UUID

/** 同时接入的 tailnet 上限（每个节点约十几 MB 内存，见 D10 设计 §3.2）。 */
const val MAX_TAILNETS = 8

/** tailnet 在界面上的状态：本地可知的（登录过没有）叠加运行中才知道的（过期、等待批准）。 */
enum class TailnetPhase { NOT_LOGGED_IN, CONNECTING, WAITING_BROWSER, WAITING_APPROVAL, EXPIRED, LOGGED_IN, ACTIVE }

fun tailnetPhase(e: NetworkEntry, live: MokeViewModel.NetworkLive, flow: MokeViewModel.LoginFlow?): TailnetPhase {
    val st = live.status[e.id]
    return when {
        st?.state == "Running" && !st.expired -> TailnetPhase.ACTIVE
        st?.state == "NeedsMachineAuth" -> TailnetPhase.WAITING_APPROVAL
        flow != null && flow.url.isNotEmpty() -> TailnetPhase.WAITING_BROWSER
        flow != null && flow.busy -> TailnetPhase.CONNECTING
        st != null && (st.expired || (st.state == "NeedsLogin" && st.everLoggedIn)) -> TailnetPhase.EXPIRED
        st == null && e.id in live.expired -> TailnetPhase.EXPIRED
        live.loggedIn[e.id] == true -> TailnetPhase.LOGGED_IN
        else -> TailnetPhase.NOT_LOGGED_IN
    }
}

@Composable
fun tailnetPhaseLabel(p: TailnetPhase): String = stringResource(
    when (p) {
        TailnetPhase.NOT_LOGGED_IN -> R.string.net_state_not_logged_in
        TailnetPhase.CONNECTING -> R.string.net_state_connecting
        TailnetPhase.WAITING_BROWSER -> R.string.net_state_waiting_browser
        TailnetPhase.WAITING_APPROVAL -> R.string.net_state_waiting_approval
        TailnetPhase.EXPIRED -> R.string.net_state_expired
        TailnetPhase.LOGGED_IN -> R.string.net_state_logged_in
        TailnetPhase.ACTIVE -> R.string.net_state_active
    },
)

/** 需要用户去处理的状态用错误色，进行中的用第三色，正常的中性或主色。 */
@Composable
fun tailnetPhaseColor(p: TailnetPhase): Color = when (p) {
    TailnetPhase.NOT_LOGGED_IN, TailnetPhase.EXPIRED -> MaterialTheme.colorScheme.error
    TailnetPhase.CONNECTING, TailnetPhase.WAITING_BROWSER, TailnetPhase.WAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    TailnetPhase.LOGGED_IN -> MaterialTheme.colorScheme.onSurfaceVariant
    TailnetPhase.ACTIVE -> MaterialTheme.colorScheme.primary
}

/** 网络的一行副标题：「Tailscale · 官方」「自建 · hs.example.com」「SOCKS5 · 代理:1080」。 */
@Composable
fun networkSubtitle(e: NetworkEntry): String = when (e.kind) {
    NetworkKind.TAILSCALE -> if (e.isOfficialControl) {
        "Tailscale · " + stringResource(R.string.control_official)
    } else {
        stringResource(R.string.control_self_hosted) + " · " + (e.controlUrl.toUri().host ?: e.controlUrl)
    }
    NetworkKind.PROXY -> "${proxyTypeLabel(e.proxyType)} · ${e.proxyHost}:${e.proxyPort}"
}

fun proxyTypeLabel(t: ProxyType): String = when (t) {
    ProxyType.SOCKS5 -> "SOCKS5"
    ProxyType.SOCKS4 -> "SOCKS4"
    ProxyType.HTTP -> "HTTP"
    ProxyType.HTTPS -> "HTTPS"
}

private fun defaultPort(t: ProxyType): Int = when (t) {
    ProxyType.SOCKS5, ProxyType.SOCKS4 -> 1080
    ProxyType.HTTP -> 8080
    ProxyType.HTTPS -> 443
}

/**
 * 在 Custom Tabs 里打开授权页（同官方 Android 客户端）；没有支持的浏览器时退回系统浏览器。
 * 授权地址只交给浏览器，不进日志、不进剪贴板。
 */
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
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        expandedHeight = MokeDimens.topBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

/**
 * 设置 → 网络：Tailscale 与代理两组。每组为空时一句话说明用途；有条目时每张卡片给出名称、
 * 一行副标题与状态。点卡片进编辑（登录、退出、删除都在编辑页里）。
 */
@Composable
fun NetworksScreen(
    networks: List<NetworkEntry>,
    live: MokeViewModel.NetworkLive,
    loginFlows: Map<String, MokeViewModel.LoginFlow>,
    onWatch: (Boolean) -> Unit,
    onAdd: (NetworkKind) -> Unit,
    onOpen: (NetworkEntry) -> Unit,
    onBack: () -> Unit,
) {
    DisposableEffect(Unit) {
        onWatch(true)
        onDispose { onWatch(false) }
    }
    val tailnets = networks.filter { it.kind == NetworkKind.TAILSCALE }
    val proxies = networks.filter { it.kind == NetworkKind.PROXY }
    Scaffold(topBar = { SubPageTopBar(stringResource(R.string.menu_network), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.section_tailscale))
            if (tailnets.isEmpty()) EmptyHint(stringResource(R.string.tailscale_empty))
            tailnets.forEach { e ->
                val phase = tailnetPhase(e, live, loginFlows[e.id])
                val label = if (phase == TailnetPhase.ACTIVE) {
                    activeLabel(live.leases[e.id] ?: 0)
                } else {
                    tailnetPhaseLabel(phase)
                }
                NetworkCard(e, label, tailnetPhaseColor(phase)) { onOpen(e) }
            }
            AddButton(
                text = stringResource(R.string.add_tailnet),
                enabled = tailnets.size < MAX_TAILNETS,
                onClick = { onAdd(NetworkKind.TAILSCALE) },
            )
            if (tailnets.size >= MAX_TAILNETS) EmptyHint(stringResource(R.string.tailnet_limit, MAX_TAILNETS))

            SectionTitle(stringResource(R.string.section_proxy))
            if (proxies.isEmpty()) EmptyHint(stringResource(R.string.proxy_empty))
            proxies.forEach { e ->
                val leases = live.leases[e.id] ?: 0
                NetworkCard(e, if (leases > 0) activeLabel(leases) else null, MaterialTheme.colorScheme.primary) { onOpen(e) }
            }
            AddButton(text = stringResource(R.string.add_proxy), onClick = { onAdd(NetworkKind.PROXY) })
        }
    }
}

@Composable
private fun activeLabel(leases: Int): String =
    stringResource(R.string.net_state_active) +
        (if (leases > 0) " · " + pluralStringResource(R.plurals.net_connections, leases, leases) else "")

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun NetworkCard(e: NetworkEntry, status: String?, statusColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    networkSubtitle(e),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (e.kind == NetworkKind.PROXY) MokeMono else null,
                    maxLines = 1,
                )
            }
            if (status != null) MokeBadge(status, statusColor, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * 网络表单（新建 / 编辑，Tailscale 与代理共用一个页面）。从「网络」页进来是普通子页；从主机编辑的
 * 「新建…」进来时以全屏层盖在主机表单上，主机表单的草稿因此不丢。
 *
 * Tailscale：点「登录」即保存条目（中途离开保留为「未登录」）；登录状态就地显示在表单里。
 * [onClose] 的参数是已保存的网络 id（没保存过为 null），主机编辑据此自动选中新网络。
 */
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
    onSave: (NetworkEntry) -> NetworkEntry,
    onLogin: (NetworkEntry, String) -> Unit,
    onReopenLogin: (String) -> Unit,
    onCancelLogin: (NetworkEntry) -> Unit,
    onLogout: (NetworkEntry) -> Unit,
    onDelete: (NetworkEntry) -> Unit,
    onClose: (savedId: String?) -> Unit,
) {
    DisposableEffect(Unit) {
        onWatch(true)
        onDispose { onWatch(false) }
    }
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    val stored = networks.firstOrNull { it.id == id }
    val base = stored ?: initial ?: NetworkEntry(id = id, kind = kind)
    var name by remember { mutableStateOf(base.name) }
    // Tailscale
    var official by remember { mutableStateOf(base.kind != NetworkKind.TAILSCALE || base.isOfficialControl) }
    var controlUrl by remember { mutableStateOf(if (base.isOfficialControl) "" else base.controlUrl) }
    // 新建时直接填上默认设备名（可改），比只在获得焦点时才出现的占位提示更明白。
    var deviceName by remember { mutableStateOf(base.deviceName.ifBlank { if (initial == null) NetworkManager.defaultDeviceName() else "" }) }
    var showAuthKey by remember { mutableStateOf(false) }
    var authKey by remember { mutableStateOf("") }
    // 代理
    var proxyType by remember { mutableStateOf(base.proxyType) }
    var proxyHost by remember { mutableStateOf(base.proxyHost) }
    // 端口直接填上该类型的常用端口；改类型时若用户没改过端口，跟着换成新类型的。
    var proxyPort by remember { mutableStateOf(if (initial == null) defaultPort(base.proxyType).toString() else base.proxyPort.toString()) }
    var username by remember { mutableStateOf(base.username) }
    var password by remember { mutableStateOf(base.password) }
    var showPassword by remember { mutableStateOf(false) }
    var extraCa by remember { mutableStateOf(base.extraCaPem) }

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val nameTaken = trimmedName.isNotEmpty() && networks.any { it.id != id && it.kind == kind && it.name.trim().equals(trimmedName, ignoreCase = true) }
    val urlText = controlUrl.trim()
    val urlValid = official || (urlText.startsWith("https://") && urlText.toUri().host?.isNotBlank() == true)
    val portValue = proxyPort.toIntOrNull() ?: defaultPort(proxyType)
    val proxyValid = proxyHost.isNotBlank() && portValue in 1..65535
    val formValid = trimmedName.isNotEmpty() && !nameTaken && when (kind) {
        NetworkKind.TAILSCALE -> urlValid
        NetworkKind.PROXY -> proxyValid
    }

    fun current(): NetworkEntry = when (kind) {
        NetworkKind.TAILSCALE -> base.copy(
            name = trimmedName,
            kind = kind,
            controlUrl = if (official) NetworkEntry.OFFICIAL_CONTROL_URL else urlText.trimEnd('/'),
            deviceName = deviceName.trim(),
        )
        NetworkKind.PROXY -> base.copy(
            name = trimmedName,
            kind = kind,
            proxyType = proxyType,
            proxyHost = proxyHost.trim(),
            proxyPort = portValue,
            username = username.trim(),
            password = if (proxyType == ProxyType.SOCKS4) "" else password,
            extraCaPem = if (proxyType == ProxyType.HTTPS) extraCa.trim() else "",
        )
    }

    val flow = loginFlows[id]
    val phase = if (kind == NetworkKind.TAILSCALE) tailnetPhase(base, live, flow) else null
    // 登录过（或正在登录）就不能再换控制服务器：换服务器 = 新建网络。
    val controlLocked = phase != null && phase != TailnetPhase.NOT_LOGGED_IN
    val title = when {
        initial != null -> stringResource(R.string.network_edit_title)
        kind == NetworkKind.TAILSCALE -> stringResource(R.string.network_new_tailnet)
        else -> stringResource(R.string.network_new_proxy)
    }
    val close = { onClose(if (stored != null) id else null) }
    // 系统返回 = 左上角返回（作为全屏层盖在主机表单上时也一样）。
    BackHandler(onBack = close)

    Scaffold(topBar = { SubPageTopBar(title, close) }) { padding ->
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name_required)) }, singleLine = true,
                    isError = nameTaken,
                    supportingText = if (nameTaken) {
                        { Text(stringResource(R.string.network_name_duplicate)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (kind) {
                    NetworkKind.TAILSCALE -> {
                        LabeledSegments(
                            label = stringResource(R.string.field_control),
                            options = listOf(stringResource(R.string.control_official), stringResource(R.string.control_self_hosted)),
                            selected = if (official) 0 else 1,
                            enabled = !controlLocked,
                            onSelect = { official = it == 0 },
                        )
                        if (!official) {
                            OutlinedTextField(
                                value = controlUrl, onValueChange = { controlUrl = it },
                                label = { Text(stringResource(R.string.field_control_url)) },
                                placeholder = { Text("https://") },
                                singleLine = true,
                                enabled = !controlLocked,
                                isError = urlText.isNotEmpty() && !urlValid,
                                supportingText = if (urlText.isNotEmpty() && !urlValid) {
                                    { Text(stringResource(R.string.control_url_invalid)) }
                                } else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (controlLocked) InfoNote(stringResource(R.string.control_locked_note))
                        OutlinedTextField(
                            value = deviceName, onValueChange = { deviceName = it },
                            label = { Text(stringResource(R.string.field_device_name)) },
                            placeholder = { Text(NetworkManager.defaultDeviceName()) },
                            supportingText = { Text(stringResource(R.string.device_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TailnetLoginPanel(
                            phase = phase!!,
                            status = live.status[id],
                            leases = live.leases[id] ?: 0,
                            flow = flow,
                            official = official,
                            formValid = formValid,
                            showAuthKey = showAuthKey,
                            authKey = authKey,
                            onAuthKey = { authKey = it },
                            onShowAuthKey = { showAuthKey = it },
                            onLogin = { key -> onLogin(current(), key); authKey = ""; showAuthKey = false },
                            onReopen = { onReopenLogin(id) },
                            onCancel = { onCancelLogin(base) },
                            onLogout = { confirmLogout = true },
                        )
                    }
                    NetworkKind.PROXY -> {
                        LabeledSegments(
                            label = stringResource(R.string.field_proxy_type),
                            options = ProxyType.entries.map { proxyTypeLabel(it) },
                            selected = proxyType.ordinal,
                            onSelect = {
                                val next = ProxyType.entries[it]
                                if (proxyPort.isEmpty() || proxyPort == defaultPort(proxyType).toString()) proxyPort = defaultPort(next).toString()
                                proxyType = next
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = proxyHost, onValueChange = { proxyHost = it },
                                label = { Text(stringResource(R.string.field_proxy_host)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = proxyPort, onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                                label = { Text(stringResource(R.string.field_port)) }, singleLine = true,
                                placeholder = { Text(defaultPort(proxyType).toString()) },
                                isError = proxyPort.isNotEmpty() && portValue !in 1..65535,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = username, onValueChange = { username = it },
                            label = {
                                Text(stringResource(if (proxyType == ProxyType.SOCKS4) R.string.field_user_id_optional else R.string.field_proxy_username))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (proxyType != ProxyType.SOCKS4) {
                            OutlinedTextField(
                                value = password, onValueChange = { password = it },
                                label = { Text(stringResource(R.string.field_proxy_password)) }, singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(if (showPassword) R.string.action_hide else R.string.action_reveal),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (proxyType == ProxyType.HTTP && (username.isNotBlank() || password.isNotEmpty())) {
                            InfoNote(stringResource(R.string.proxy_http_plaintext_note))
                        }
                        if (proxyType == ProxyType.HTTPS) {
                            OutlinedTextField(
                                value = extraCa, onValueChange = { extraCa = it },
                                label = { Text(stringResource(R.string.field_extra_ca)) },
                                supportingText = { Text(stringResource(R.string.extra_ca_hint)) },
                                minLines = 2, maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (!proxyType.carriesUdp) {
                            val mosh = moshHostsUsing(id)
                            if (mosh > 0) {
                                InfoNote(pluralStringResource(R.plurals.proxy_no_udp_mosh_hosts, mosh, mosh), error = true)
                            } else {
                                InfoNote(stringResource(R.string.proxy_no_udp_note))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = close, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = { onSave(current()); onClose(id) },
                        enabled = formValid,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_save)) }
                }
                if (stored != null) {
                    TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.network_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete && stored != null) {
        val hosts = hostsUsing(id)
        val sessions = liveSessionsUsing(id)
        val parts = buildList {
            if (hosts > 0) add(pluralStringResource(R.plurals.network_delete_hosts, hosts, hosts))
            if (sessions > 0) add(pluralStringResource(R.plurals.network_close_sessions, sessions, sessions))
            if (kind == NetworkKind.TAILSCALE && phase != TailnetPhase.NOT_LOGGED_IN) add(stringResource(R.string.network_delete_tailnet))
            add(stringResource(R.string.network_delete_plain))
        }
        ConfirmDialog(
            title = stringResource(R.string.network_delete_title, stored.name),
            message = parts.joinToString("\n"),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { confirmDelete = false; onDelete(stored); onClose(null) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmLogout && stored != null) {
        val sessions = liveSessionsUsing(id)
        ConfirmDialog(
            title = stringResource(R.string.logout_title, stored.name),
            message = listOfNotNull(
                stringResource(R.string.logout_message),
                if (sessions > 0) pluralStringResource(R.plurals.network_close_sessions, sessions, sessions) else null,
            ).joinToString("\n"),
            confirmLabel = stringResource(R.string.action_logout),
            destructive = true,
            onConfirm = { confirmLogout = false; onLogout(stored) },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun LabeledSegments(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, text ->
                SegmentedButton(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(text, maxLines = 1) }
            }
        }
    }
}

/** 表单里的登录区：当前状态一行，下面是此刻能做的事。 */
@Composable
private fun TailnetLoginPanel(
    phase: TailnetPhase,
    status: NetworkManager.NodeStatus?,
    leases: Int,
    flow: MokeViewModel.LoginFlow?,
    official: Boolean,
    formValid: Boolean,
    showAuthKey: Boolean,
    authKey: String,
    onAuthKey: (String) -> Unit,
    onShowAuthKey: (Boolean) -> Unit,
    onLogin: (String) -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MokeShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase == TailnetPhase.CONNECTING) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    if (phase == TailnetPhase.ACTIVE) activeLabel(leases) else tailnetPhaseLabel(phase),
                    // 表单里「未登录」不标红：下面就是「登录」按钮，这是待办而不是故障。
                    color = if (phase == TailnetPhase.NOT_LOGGED_IN) MaterialTheme.colorScheme.onSurfaceVariant else tailnetPhaseColor(phase),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 已登录：账号 / tailnet 与本机地址（运行中才知道），供核对接入的是哪个网络。
            if (status != null && (phase == TailnetPhase.ACTIVE || phase == TailnetPhase.LOGGED_IN)) {
                val detail = listOf(status.loginName, status.tailnet).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.ips.isNotEmpty()) {
                    Text(status.ips.joinToString("  "), style = MaterialTheme.typography.bodySmall, fontFamily = MokeMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            flow?.error?.let { InfoNote(it, error = true) }
            when (phase) {
                TailnetPhase.NOT_LOGGED_IN, TailnetPhase.EXPIRED -> {
                    if (official) {
                        Button(onClick = { onLogin("") }, enabled = formValid) { Text(stringResource(R.string.action_login)) }
                    } else {
                        // 自建控制面：没有 OIDC 的 headscale 授权页只给管理员命令，认证密钥与浏览器登录并列可见。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onLogin("") }, enabled = formValid) { Text(stringResource(R.string.action_login_browser)) }
                            OutlinedButton(onClick = { onShowAuthKey(!showAuthKey) }, enabled = formValid) {
                                Text(stringResource(R.string.action_use_auth_key))
                            }
                        }
                        if (showAuthKey) {
                            OutlinedTextField(
                                value = authKey, onValueChange = onAuthKey,
                                label = { Text(stringResource(R.string.field_auth_key)) },
                                supportingText = { Text(stringResource(R.string.auth_key_hint)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = { onLogin(authKey) }, enabled = formValid && authKey.isNotBlank()) {
                                Text(stringResource(R.string.action_login))
                            }
                        }
                    }
                }
                TailnetPhase.CONNECTING, TailnetPhase.WAITING_APPROVAL ->
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(stringResource(R.string.action_cancel_login)) }
                TailnetPhase.WAITING_BROWSER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onReopen) { Text(stringResource(R.string.action_reopen_login)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel_login)) }
                }
                TailnetPhase.LOGGED_IN, TailnetPhase.ACTIVE ->
                    TextButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.action_logout), color = MaterialTheme.colorScheme.error)
                    }
            }
        }
    }
}
