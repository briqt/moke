package com.briqt.moke.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.terminal.AlertToggleAction
import com.briqt.moke.terminal.BackgroundKeepalive
import com.briqt.moke.terminal.MokeSessionService
import com.briqt.moke.ui.theme.MokeDimens
import kotlinx.coroutines.delay

private data class NotificationSystemState(
    val appAllowed: Boolean,
    val sessionChannelEnabled: Boolean,
    val alertChannelEnabled: Boolean,
)

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
    onOpenExtraKeys: () -> Unit,
    onTerminalAlerts: (Boolean) -> Unit,
    onKeyboardMode: (KeyboardMode) -> Unit,
    onTmuxScrollSetup: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onConfirmClose: (Boolean) -> Unit,
    onAutoTrustNewHostKey: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var kbDialog by remember { mutableStateOf(false) }
    val extraKeyConfig = remember(extraKeysLayout) { ExtraKeyLayout.parseConfig(extraKeysLayout) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var pendingEnableAlerts by rememberSaveable { mutableStateOf(false) }
    var wasRationaleBefore by rememberSaveable { mutableStateOf(false) }
    var launchElapsedMs by rememberSaveable { mutableLongStateOf(0L) }

    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()
    val notifState = rememberSystemState(refreshTick) { ctx ->
        NotificationSystemState(
            appAllowed = BackgroundKeepalive.areAppNotificationsAllowed(ctx),
            sessionChannelEnabled = BackgroundKeepalive.isSessionNotificationChannelEnabled(ctx),
            alertChannelEnabled = BackgroundKeepalive.isAlertNotificationChannelEnabled(ctx),
        )
    }

    LaunchedEffect(notifState.sessionChannelEnabled) {
        if (notifState.sessionChannelEnabled) {
            MokeSessionService.syncOngoingNotificationIfRunning()
        }
    }

    LaunchedEffect(pendingEnableAlerts, notifState.appAllowed, notifState.alertChannelEnabled) {
        if (pendingEnableAlerts && notifState.appAllowed && notifState.alertChannelEnabled) {
            pendingEnableAlerts = false
            onTerminalAlerts(true)
        }
    }

    LaunchedEffect(pendingEnableAlerts, lifecycleOwner) {
        if (!pendingEnableAlerts) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(950)
            if (pendingEnableAlerts &&
                !(BackgroundKeepalive.areAppNotificationsAllowed(context) &&
                    BackgroundKeepalive.isAlertNotificationChannelEnabled(context))
            ) {
                pendingEnableAlerts = false
            }
        }
    }

    fun applyAlertAction(action: AlertToggleAction, launchPermission: () -> Unit) {
        when (action) {
            AlertToggleAction.ENABLE -> {
                pendingEnableAlerts = false
                onTerminalAlerts(true)
            }
            AlertToggleAction.DISABLE -> {
                pendingEnableAlerts = false
                onTerminalAlerts(false)
            }
            AlertToggleAction.REQUEST_PERMISSION -> launchPermission()
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS -> {
                pendingEnableAlerts = true
                BackgroundKeepalive.openAppNotificationSettings(context)
            }
            AlertToggleAction.OPEN_ALERT_CHANNEL_SETTINGS -> {
                pendingEnableAlerts = true
                BackgroundKeepalive.openAlertNotificationChannelSettings(context)
            }
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++
        if (granted) {
            MokeSessionService.syncOngoingNotificationIfRunning()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - launchElapsedMs
        val isRationaleAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BackgroundKeepalive.findActivity(context)?.let { activity ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            } == true
        } else {
            false
        }
        val rationaleEverSeen = BackgroundKeepalive.wasNotifRationaleEverSeen(context)
        BackgroundKeepalive.recordNotifRationaleIfSeen(context, wasRationaleBefore || isRationaleAfter)
        val action = BackgroundKeepalive.decideAlertPermissionResult(
            granted = granted,
            systemNotificationsEnabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false),
            alertChannelEnabled = BackgroundKeepalive.isAlertNotificationChannelEnabled(context),
            wasRationaleBefore = wasRationaleBefore,
            isRationaleAfter = isRationaleAfter,
            rationaleEverSeen = rationaleEverSeen,
            elapsedMs = elapsedMs,
        )
        applyAlertAction(action) {}
    }

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
                    stringResource(
                        R.string.extra_keys_summary_format,
                        extraKeyConfig.columns,
                        stringResource(extraKeyHeightLabelRes(extraKeyConfig.height)),
                    ),
                    onClick = onOpenExtraKeys,
                )
                SwitchRow(
                    icon = Icons.Filled.SwipeVertical,
                    title = stringResource(R.string.menu_tmux_scroll_setup),
                    checked = tmuxScrollSetup,
                    onCheckedChange = onTmuxScrollSetup,
                )
            }
            SettingsGroup {
                SwitchRow(
                    icon = Icons.Filled.BatterySaver,
                    title = stringResource(R.string.ignore_battery_optimizations),
                    checked = ignoringBatteryOptimizations,
                    onCheckedChange = {
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
                    checked = BackgroundKeepalive.effectiveTerminalAlertsChecked(
                        prefEnabled = terminalAlerts,
                        appNotificationsAllowed = notifState.appAllowed,
                        alertChannelEnabled = notifState.alertChannelEnabled,
                    ),
                    onCheckedChange = { wantChecked ->
                        val action = BackgroundKeepalive.decideAlertToggle(
                            wantChecked = wantChecked,
                            permissionGranted = BackgroundKeepalive.isPostNotificationsPermissionGranted(context),
                            systemNotificationsEnabled = runCatching {
                                NotificationManagerCompat.from(context).areNotificationsEnabled()
                            }.getOrDefault(false),
                            alertChannelEnabled = BackgroundKeepalive.isAlertNotificationChannelEnabled(context),
                        )
                        applyAlertAction(action) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                wasRationaleBefore = BackgroundKeepalive.findActivity(context)?.let { activity ->
                                    ActivityCompat.shouldShowRequestPermissionRationale(
                                        activity,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    )
                                } == true
                                BackgroundKeepalive.recordNotifRationaleIfSeen(context, wasRationaleBefore)
                                launchElapsedMs = SystemClock.elapsedRealtime()
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                )
                if (notifState.appAllowed) {
                    SwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.menu_ongoing_notification),
                        checked = notifState.sessionChannelEnabled,
                        onCheckedChange = {
                            BackgroundKeepalive.openSessionNotificationChannelSettings(context)
                        },
                    )
                }
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

    if (kbDialog) {
        KeyboardModeDialog(
            current = keyboardMode,
            onPick = { onKeyboardMode(it); kbDialog = false },
            onDismiss = { kbDialog = false },
        )
    }
}
