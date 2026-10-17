package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.terminal.KnownHosts
import com.briqt.moke.terminal.PortForwards
import com.briqt.moke.ui.theme.MokeDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    initial: Host?,
    allHosts: List<Host>,
    onSave: (Host) -> Unit,
    onCancel: () -> Unit,
    /** 该 host:port 已记录的指纹（null=无记录），用于「清除已保存的主机指纹」。 */
    savedFingerprint: String? = null,
    onClearFingerprint: (String, Int) -> Unit = { _, _ -> },
    networks: List<NetworkEntry> = emptyList(),
    networkLive: MokeViewModel.NetworkLive = MokeViewModel.NetworkLive(),
    /** 「新建…」打开的网络表单（全屏层，主机表单留在下面不丢草稿）；回调参数是保存了的网络 id。 */
    networkEditor: @Composable (kind: NetworkKind, onClose: (String?) -> Unit) -> Unit = { _, _ -> },
) {
    val base = initial ?: Host()
    var label by remember { mutableStateOf(base.label) }
    var host by remember { mutableStateOf(base.host) }
    var port by remember { mutableStateOf(base.port.toString()) }
    var username by remember { mutableStateOf(base.username) }
    var authType by remember { mutableStateOf(base.authType) }
    var password by remember { mutableStateOf(base.password) }
    var privateKey by remember { mutableStateOf(base.privateKeyPem) }
    var passphrase by remember { mutableStateOf(base.passphrase) }
    // 凭据默认遮蔽，由眼睛开关逐个揭示（新建时私钥为空，直接展开编辑框）。
    var showPassword by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }
    var showPrivateKey by remember { mutableStateOf(base.privateKeyPem.isBlank()) }
    var useMosh by remember { mutableStateOf(base.useMosh) }
    var jumpHostId by remember { mutableStateOf(base.jumpHostId) }
    var networkId by remember { mutableStateOf(base.networkId) }
    var creatingNetwork by remember { mutableStateOf<NetworkKind?>(null) }
    var startupCommand by remember { mutableStateOf(base.startupCommand) }
    var loginCommand by remember { mutableStateOf(base.loginCommand) }
    var forwardPorts by remember { mutableStateOf(base.forwardPorts) }
    // 非法项不静默丢弃：列出来并禁止保存，免得用户以为配好了、连上却什么都没转。
    val forwardInvalid = PortForwards.parsePorts(forwardPorts).invalid
    var group by remember { mutableStateOf(base.group) }
    var persistence by remember { mutableStateOf(base.persistence) }
    var fingerprintCleared by remember { mutableStateOf(false) }
    var confirmClearFingerprint by remember { mutableStateOf(false) }

    // 跳板机候选：其它主机（排除自身，避免自引用）。
    val jumpOptions = listOf(DropdownOption(id = "", title = stringResource(R.string.jump_none))) +
        allHosts.filter { it.id != base.id }.map { h ->
            DropdownOption(id = h.id, title = h.displayName, subtitle = "${h.username}@${h.host}:${h.port}")
        }
    val jumpHost = allHosts.firstOrNull { it.id == jumpHostId && it.id != base.id }
    // 失效引用（被引用的跳板机 / 网络已删除）：下拉显示为错误项，改选前不能保存（不悄悄直连）。
    val jumpMissing = jumpHostId.isNotBlank() && jumpHost == null
    val selectedNetwork = networks.firstOrNull { it.id == networkId }
    val networkMissing = networkId.isNotBlank() && selectedNetwork == null && jumpHostId.isBlank()
    // mosh 的 UDP 必须走选定的网络：只能转 TCP 的代理下 mosh 必然不可用，禁止保存并说明原因。
    val moshNoUdp = useMosh && jumpHostId.isBlank() && selectedNetwork?.kind == NetworkKind.PROXY &&
        !selectedNetwork.proxyType.carriesUdp
    val notLoggedIn = stringResource(R.string.net_state_not_logged_in)
    val errorColor = MaterialTheme.colorScheme.error
    val networkOptions = listOf(
        DropdownOption(id = "", title = stringResource(R.string.network_direct), subtitle = stringResource(R.string.network_direct_desc)),
    ) + networks.sortedBy { it.kind }.map { e ->
        DropdownOption(
            id = e.id,
            title = e.name,
            subtitle = networkSubtitle(e),
            tags = if (e.kind == NetworkKind.TAILSCALE && networkLive.loggedIn[e.id] == false) {
                listOf(BadgeSpec(notLoggedIn, errorColor))
            } else {
                emptyList()
            },
        )
    }
    // 已有分组（动态枚举：来自各主机的 group 字段，无独立管理；无主机使用的分组自然不出现）。
    val existingGroups = allHosts.mapNotNull { it.group.trim().ifBlank { null } }.distinct().sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (initial == null) R.string.add_host else R.string.host_edit_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
        },
    ) { padding ->
        // 输入密集页：整体输入文字收一档到 bodyMedium（14sp）更精致；标签/按钮各用其默认排版，不受影响。
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 键盘弹起时收缩滚动视口（而非被遮挡），底部取消/保存始终可滚到、可点。
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text(stringResource(R.string.field_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 分组：可编辑下拉——点箭头列出已有分组、输入即可新建（动态枚举，无独立管理）。
            EditableDropdownField(
                label = stringResource(R.string.field_group),
                value = group,
                onValueChange = { group = it },
                options = existingGroups,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text(stringResource(R.string.field_host)) }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.field_port)) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.field_username)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == AuthType.PASSWORD,
                    onClick = { authType = AuthType.PASSWORD },
                    label = { Text(stringResource(R.string.field_password)) },
                )
                FilterChip(
                    selected = authType == AuthType.KEY,
                    onClick = { authType = AuthType.KEY },
                    label = { Text(stringResource(R.string.auth_key)) },
                )
            }

            if (authType == AuthType.PASSWORD) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { RevealToggle(showPassword) { showPassword = !showPassword } },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 私钥默认不明文显示（旁人一瞥即泄），只给一行摘要 + 眼睛开关；点开才显示可编辑的多行文本框。
                if (!showPrivateKey && privateKey.isNotBlank()) {
                    OutlinedTextField(
                        value = keySummary(privateKey),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.field_private_key)) },
                        singleLine = true,
                        trailingIcon = { RevealToggle(false) { showPrivateKey = true } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey, onValueChange = { privateKey = it },
                        label = { Text(stringResource(R.string.field_private_key)) },
                        trailingIcon = if (privateKey.isNotBlank()) {
                            { RevealToggle(true) { showPrivateKey = false } }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3, maxLines = 6,
                    )
                }
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.field_passphrase)) }, singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { RevealToggle(showPassphrase) { showPassphrase = !showPassphrase } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.label_protocol), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useMosh,
                        onClick = { useMosh = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("SSH") }
                    SegmentedButton(
                        selected = useMosh,
                        onClick = { useMosh = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("mosh") }
                }
            }

            // 跳板机（可选）：仅在已有其它主机、或引用已失效时展示。
            if (jumpOptions.size > 1 || jumpMissing) {
                RichDropdown(
                    label = stringResource(R.string.field_jump_host),
                    options = jumpOptions,
                    selectedId = jumpHostId,
                    onSelect = { jumpHostId = it },
                    isError = jumpMissing,
                    missingTitle = stringResource(R.string.badge_jump_deleted),
                    supportingText = if (jumpMissing) stringResource(R.string.route_missing_jump) else null,
                )
                // mosh + 跳板机：跳板机只接引导那段 SSH，数据面(UDP)仍需目标从本机直达。
                // 友好说明，不硬禁——那个"UDP 直达、仅 SSH 受限"的窄场景仍成立。
                if (useMosh && jumpHostId.isNotBlank()) InfoNote(stringResource(R.string.mosh_jump_hint))
            }

            // 网络：只决定「怎么直接到达这台主机」。有跳板机时第一跳归跳板机自己的网络，这里置灰并说明实际路径。
            RichDropdown(
                label = stringResource(R.string.field_network),
                options = networkOptions,
                selectedId = networkId,
                onSelect = { networkId = it },
                enabled = jumpHostId.isBlank(),
                isError = networkMissing || moshNoUdp,
                missingTitle = stringResource(R.string.network_missing_option),
                supportingText = when {
                    networkMissing -> stringResource(R.string.network_missing_error)
                    moshNoUdp -> stringResource(R.string.mosh_needs_udp_network, selectedNetwork.name)
                    else -> null
                },
                footer = { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.network_new_tailnet_item), color = MaterialTheme.colorScheme.primary) },
                        onClick = { dismiss(); creatingNetwork = NetworkKind.TAILSCALE },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.network_new_proxy_item), color = MaterialTheme.colorScheme.primary) },
                        onClick = { dismiss(); creatingNetwork = NetworkKind.PROXY },
                    )
                },
            )
            if (jumpHost != null) {
                val via = networks.firstOrNull { it.id == jumpHost.networkId }?.name ?: stringResource(R.string.network_direct)
                InfoNote(stringResource(R.string.network_via_jump, jumpHost.displayName, via))
            }

            // 会话持久化：选 tmux 后，连接这台主机即进入 tmux 会话（记住过会话名就直接进）。
            RichDropdown(
                label = stringResource(R.string.host_persistence),
                options = listOf(
                    DropdownOption(
                        id = SessionPersistence.NONE.name,
                        title = stringResource(R.string.persistence_none),
                    ),
                    DropdownOption(
                        id = SessionPersistence.TMUX.name,
                        title = stringResource(R.string.persistence_tmux),
                    ),
                ),
                selectedId = persistence.name,
                onSelect = { persistence = SessionPersistence.valueOf(it) },
            )

            // 启动命令：协议级 exec（SSH command channel / mosh-server --），空=远端默认 login shell。
            // 与「登录后自动执行」是两回事：那条是 shell 起来之后往 PTY 里敲的一行。
            // 会话持久化=tmux 时这个位置归 tmux 附加命令，字段置灰并说明原因。
            val startupEnabled = persistence != SessionPersistence.TMUX
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedTextField(
                    value = startupCommand, onValueChange = { startupCommand = it },
                    label = { Text(stringResource(R.string.field_startup_command)) },
                    placeholder = { Text(stringResource(R.string.startup_command_hint)) },
                    enabled = startupEnabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!startupEnabled) {
                    Text(
                        stringResource(R.string.startup_command_tmux_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 登录后自动执行：支持多行（每行一条命令，按序执行）；传输层按 "命令+\n" 原样下发。
            OutlinedTextField(
                value = loginCommand, onValueChange = { loginCommand = it },
                label = { Text(stringResource(R.string.field_login_command)) },
                placeholder = { Text(stringResource(R.string.login_command_hint)) },
                minLines = 1, maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // 连接后自动转发的端口：固定的开发端口不必每次手动加。
            OutlinedTextField(
                value = forwardPorts, onValueChange = { forwardPorts = it },
                label = { Text(stringResource(R.string.field_forward_ports)) },
                supportingText = {
                    Text(
                        if (forwardInvalid.isEmpty()) stringResource(R.string.field_forward_ports_hint)
                        else stringResource(R.string.field_forward_ports_invalid, forwardInvalid.joinToString(", "))
                    )
                },
                isError = forwardInvalid.isNotEmpty(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 主机指纹：服务器换过密钥时的自救路径。指纹按 host:port 存、与本条目无关，
            // 删除重建连接不会清除它——社区实报有人因此彻底连不上、只能清应用数据。
            if (host.isNotBlank()) {
                if (confirmClearFingerprint) {
                    val target = KnownHosts.idOf(host.trim(), port.toIntOrNull() ?: 22)
                    ConfirmDialog(
                        title = stringResource(R.string.hostkey_clear),
                        message = target,
                        confirmLabel = stringResource(R.string.action_clear_fingerprint),
                        destructive = true,
                        onConfirm = {
                            confirmClearFingerprint = false
                            onClearFingerprint(host.trim(), port.toIntOrNull() ?: 22)
                            fingerprintCleared = true
                        },
                        onDismiss = { confirmClearFingerprint = false },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        // 清除后下次连接要重新确认指纹，属于安全相关的不可逆操作：二次确认。
                        onClick = { confirmClearFingerprint = true },
                        enabled = savedFingerprint != null && !fingerprintCleared,
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(stringResource(R.string.hostkey_clear)) }
                    Text(
                        when {
                            fingerprintCleared -> stringResource(R.string.hostkey_cleared)
                            savedFingerprint != null ->
                                stringResource(R.string.hostkey_clear_hint, savedFingerprint)
                            else -> stringResource(R.string.hostkey_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = {
                        onSave(
                            base.copy(
                                label = label.trim(),
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 22,
                                username = username.trim(),
                                authType = authType,
                                password = password,
                                privateKeyPem = privateKey,
                                passphrase = passphrase,
                                useMosh = useMosh,
                                jumpHostId = jumpHostId,
                                networkId = networkId,
                                startupCommand = startupCommand.trim(),
                                loginCommand = loginCommand.trim(),
                                forwardPorts = PortForwards.parsePorts(forwardPorts).ports.joinToString(", "),
                                group = group.trim(),
                                persistence = persistence,
                                // 关掉持久化时一并忘记记住的会话名，避免下次重新开启后悄悄附加到旧会话。
                                tmuxSessionName = if (persistence == SessionPersistence.TMUX) {
                                    base.tmuxSessionName
                                } else {
                                    ""
                                },
                            )
                        )
                    },
                    enabled = host.isNotBlank() && username.isNotBlank() && forwardInvalid.isEmpty() &&
                        !jumpMissing && !networkMissing && !moshNoUdp,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
        }
    }

    creatingNetwork?.let { kind ->
        NetworkEditorOverlay(kind, networkEditor) { savedId ->
            creatingNetwork = null
            if (savedId != null) networkId = savedId
        }
    }
}

@Composable
private fun NetworkEditorOverlay(kind: NetworkKind, editor: @Composable (NetworkKind, (String?) -> Unit) -> Unit, onClose: (String?) -> Unit) {
    Dialog(
        onDismissRequest = { onClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            editor(kind, onClose)
        }
    }
}

/** 凭据字段尾部的「眼睛」开关：[revealed] 为真表示当前明文可见，点击即切换。 */
@Composable
private fun RevealToggle(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(if (revealed) R.string.action_hide else R.string.action_reveal),
        )
    }
}

/**
 * 遮蔽态下私钥的一行摘要：从 PEM 头识别类型 + 长度，让人确认"存的是哪把钥匙"而不暴露内容。
 * 只做字符串匹配，不解析密钥本体（避免为一行 UI 文案引入解析开销与失败分支）。
 */
private fun keySummary(pem: String): String {
    val kind = when {
        pem.contains("OPENSSH PRIVATE KEY") -> "OpenSSH"
        pem.contains("RSA PRIVATE KEY") -> "RSA"
        pem.contains("EC PRIVATE KEY") -> "EC"
        pem.contains("DSA PRIVATE KEY") -> "DSA"
        pem.contains("ENCRYPTED PRIVATE KEY") -> "PKCS#8 (encrypted)"
        pem.contains("PRIVATE KEY") -> "PKCS#8"
        else -> "?"
    }
    return "•••••••• $kind · ${pem.trim().length} chars"
}
