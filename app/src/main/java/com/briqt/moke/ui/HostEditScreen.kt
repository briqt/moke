package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.ui.theme.MokeDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    initial: Host?,
    allHosts: List<Host>,
    onSave: (Host) -> Unit,
    onCancel: () -> Unit,
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
    var useMosh by remember { mutableStateOf(base.useMosh) }
    var jumpHostId by remember { mutableStateOf(base.jumpHostId) }
    var loginCommand by remember { mutableStateOf(base.loginCommand) }
    var group by remember { mutableStateOf(base.group) }

    // 跳板机候选：其它主机（排除自身，避免自引用）。
    val jumpOptions = listOf(DropdownOption(id = "", title = stringResource(R.string.jump_none))) +
        allHosts.filter { it.id != base.id }.map { h ->
            DropdownOption(id = h.id, title = h.displayName, subtitle = "${h.username}@${h.host}:${h.port}")
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
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = privateKey, onValueChange = { privateKey = it },
                    label = { Text(stringResource(R.string.field_private_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6,
                )
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.field_passphrase)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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

            // 跳板机（可选）：仅在已有其它主机时展示。
            if (jumpOptions.size > 1) {
                RichDropdown(
                    label = stringResource(R.string.field_jump_host),
                    options = jumpOptions,
                    selectedId = jumpHostId,
                    onSelect = { jumpHostId = it },
                )
                // mosh + 跳板机：跳板机只接引导那段 SSH，数据面(UDP)仍需目标从本机直达。
                // 友好说明（info 图标 + 中性色），不硬禁、不吞——那个"UDP 直达、仅 SSH 受限"的窄场景仍成立。
                if (useMosh && jumpHostId.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.mosh_jump_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                                loginCommand = loginCommand.trim(),
                                group = group.trim(),
                            )
                        )
                    },
                    enabled = host.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
        }
    }
}
