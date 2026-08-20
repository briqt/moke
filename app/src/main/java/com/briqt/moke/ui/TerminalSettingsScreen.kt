package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.ui.theme.MokeDimens

/**
 * 「终端与输入」设置页：终端行为 + 输入相关的开关都归到这里。
 * 一级设置只留分组入口，具体项集中在本页——后续加「会话持久化 / 滚屏行数 / 附加键布局」等也落这儿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    keyboardMode: KeyboardMode,
    scrollMode: ScrollMode,
    tmuxScrollSetup: Boolean,
    keepScreenOn: Boolean,
    confirmClose: Boolean,
    onKeyboardMode: (KeyboardMode) -> Unit,
    onScrollMode: (ScrollMode) -> Unit,
    onTmuxScrollSetup: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onConfirmClose: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var kbDialog by remember { mutableStateOf(false) }
    var scrollDialog by remember { mutableStateOf(false) }

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavRow(
                Icons.Filled.KeyboardAlt,
                stringResource(R.string.keyboard_mode),
                stringResource(keyboardModeLabel(keyboardMode)),
                onClick = { kbDialog = true },
            )
            NavRow(
                Icons.Filled.SwipeVertical,
                stringResource(R.string.scroll_mode),
                stringResource(scrollModeLabel(scrollMode)),
                onClick = { scrollDialog = true },
            )
            SwitchRow(
                icon = Icons.Filled.SwipeVertical,
                title = stringResource(R.string.menu_tmux_scroll_setup),
                subtitle = stringResource(R.string.menu_tmux_scroll_setup_sub),
                checked = tmuxScrollSetup,
                onCheckedChange = onTmuxScrollSetup,
            )
            SwitchRow(
                icon = Icons.Filled.LightMode,
                title = stringResource(R.string.menu_keep_screen_on),
                subtitle = stringResource(R.string.menu_keep_screen_on_sub),
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOn,
            )
            SwitchRow(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.menu_confirm_close),
                subtitle = stringResource(R.string.menu_confirm_close_sub),
                checked = confirmClose,
                onCheckedChange = onConfirmClose,
            )
        }
    }

    if (scrollDialog) {
        ScrollModeDialog(
            current = scrollMode,
            onPick = { onScrollMode(it); scrollDialog = false },
            onDismiss = { scrollDialog = false },
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
