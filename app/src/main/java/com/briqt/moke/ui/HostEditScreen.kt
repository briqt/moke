package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host

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
    val jumpOptions = listOf(DropdownOption(id = "", title = "无（直连）")) +
        allHosts.filter { it.id != base.id }.map { h ->
            DropdownOption(id = h.id, title = h.displayName, subtitle = "${h.username}@${h.host}:${h.port}")
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "添加主机" else "编辑主机") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 键盘弹起时收缩滚动视口（而非被遮挡），底部取消/保存始终可滚到、可点。
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("名称（可选）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = group, onValueChange = { group = it },
                label = { Text("分组（可选）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("主机地址") }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("端口") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == AuthType.PASSWORD,
                    onClick = { authType = AuthType.PASSWORD },
                    label = { Text("密码") },
                )
                FilterChip(
                    selected = authType == AuthType.KEY,
                    onClick = { authType = AuthType.KEY },
                    label = { Text("私钥") },
                )
            }

            if (authType == AuthType.PASSWORD) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = privateKey, onValueChange = { privateKey = it },
                    label = { Text("私钥（PEM，粘贴内容）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6,
                )
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("私钥口令（可选）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("协议", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    label = "跳板机（可选）",
                    options = jumpOptions,
                    selectedId = jumpHostId,
                    onSelect = { jumpHostId = it },
                )
            }

            OutlinedTextField(
                value = loginCommand, onValueChange = { loginCommand = it },
                label = { Text("登录后自动执行（可选）") },
                placeholder = { Text("如 cd /var/www && ls") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
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
                ) { Text("保存") }
            }
        }
    }
}
