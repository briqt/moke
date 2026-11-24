package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.terminal.BackgroundKeepalive
import com.briqt.moke.ui.theme.MokeDimens

/**
 * 「终端与输入」设置页：按输入、会话与后台、安全三组分段组织。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    keyboardMode: KeyboardMode,
    tmuxScrollSetup: Boolean,
    keepScreenOn: Boolean,
    confirmClose: Boolean,
    autoTrustNewHostKey: Boolean,
    terminalAlerts: Boolean,
    extraKeysLayout: String,
    onExtraKeysLayout: (String) -> Unit,
    onTerminalAlerts: (Boolean) -> Unit,
    onKeyboardMode: (KeyboardMode) -> Unit,
    onTmuxScrollSetup: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onConfirmClose: (Boolean) -> Unit,
    onAutoTrustNewHostKey: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var kbDialog by remember { mutableStateOf(false) }
    var keysDialog by remember { mutableStateOf(false) }
    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.menu_terminal_input),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = mokeTopBarColors(),
            )
        },
    ) { padding ->
        CenteredScrollColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup {
                NavRow(
                    Icons.Filled.KeyboardAlt,
                    stringResource(R.string.keyboard_mode),
                    stringResource(keyboardModeLabel(keyboardMode)),
                    onClick = { kbDialog = true },
                )
                NavRow(
                    Icons.Filled.Keyboard,
                    stringResource(R.string.menu_extra_keys),
                    onClick = { keysDialog = true },
                )
                SwitchRow(
                    icon = Icons.Filled.SwipeVertical,
                    title = stringResource(R.string.menu_tmux_scroll_setup),
                    checked = tmuxScrollSetup,
                    onCheckedChange = onTmuxScrollSetup,
                )
            }
            SettingsGroup {
                NavRow(
                    Icons.Filled.BatterySaver,
                    stringResource(R.string.ignore_battery_optimizations),
                    stringResource(if (ignoringBatteryOptimizations) R.string.status_enabled else R.string.status_disabled),
                    onClick = {
                        if (!ignoringBatteryOptimizations) {
                            BackgroundKeepalive.requestIgnoreBatteryOptimizations(context)
                        } else {
                            BackgroundKeepalive.openAppDetailsSettings(context)
                        }
                    },
                )
                SwitchRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = stringResource(R.string.menu_terminal_alerts),
                    checked = terminalAlerts,
                    onCheckedChange = onTerminalAlerts,
                )
                SwitchRow(
                    icon = Icons.Filled.LightMode,
                    title = stringResource(R.string.menu_keep_screen_on),
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOn,
                )
                SwitchRow(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.menu_confirm_close),
                    checked = confirmClose,
                    onCheckedChange = onConfirmClose,
                )
            }
            SettingsGroup {
                SwitchRow(
                    icon = Icons.Filled.Key,
                    title = stringResource(R.string.menu_auto_trust_hostkey),
                    checked = autoTrustNewHostKey,
                    onCheckedChange = onAutoTrustNewHostKey,
                )
            }
        }
    }

    if (keysDialog) {
        ExtraKeysLayoutDialog(
            stored = extraKeysLayout,
            onChange = onExtraKeysLayout,
            onDismiss = { keysDialog = false },
        )
    }

    if (kbDialog) {
        KeyboardModeDialog(
            current = keyboardMode,
            onPick = { onKeyboardMode(it); kbDialog = false },
            onDismiss = { kbDialog = false },
        )
    }
}
