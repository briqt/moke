package com.briqt.moke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.briqt.moke.terminal.TerminalAlerts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.briqt.moke.R
import com.briqt.moke.terminal.HostKeyChangePrompt
import com.briqt.moke.terminal.HostKeyPrompt
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.data.Host
import com.briqt.moke.data.ListState
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.terminal.Tmux
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind

/** 底部导航分区。 */
enum class HomeTab { Connections, Sessions, Settings }

/**
 * 极简导航（v0.1 不引入 navigation-compose）：Home（底部三分区）为根，
 * 终端/编辑/外观/字体/关于为其上的全屏页，用状态切换。
 */
sealed interface Screen {
    data object Home : Screen
    data class Edit(val host: Host?, val back: Screen = Home) : Screen
    data class Terminal(val sessionId: String) : Screen
    data object Appearance : Screen
    data object TerminalSettings : Screen
    data object Fonts : Screen
    data object About : Screen

    /** 设置 → 网络（tailnet 与代理）。 */
    data object Networks : Screen

    /** Keep the opening snapshot: a read failure must not turn an existing editor into a new entry. */
    data class NetworkEdit(val kind: NetworkKind, val entry: NetworkEntry?, val back: Screen) : Screen

    /**
     * 远端文件页。[sessionId] 非空表示从终端 ⋮ 进来的——只有那种情况才拿得到"终端当前目录"，
     * 也只有那种情况才谈得上把路径发回终端。
     */
    data class Files(val hostId: String, val sessionId: String?) : Screen
}

@Composable
fun MokeApp(vm: MokeViewModel = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var homeTab by remember { mutableStateOf(HomeTab.Connections) }
    val navigationScope = rememberCoroutineScope()
    var pendingFilesReturn by remember { mutableStateOf<Pair<Screen, Job>?>(null) }
    LaunchedEffect(screen) {
        pendingFilesReturn?.takeIf { it.first !== screen }?.let { pending ->
            pendingFilesReturn = null
            pending.second.cancel()
        }
    }

    val hosts by vm.hosts.collectAsState()
    val hostsState by vm.hostsState.collectAsState()
    val networksState by vm.networksState.collectAsState()
    val credentialsUnreadable by vm.credentialsUnreadable.collectAsState()
    val sessions by vm.sessions.sessions.collectAsState()
    val hostGroupOrder by vm.hostGroupOrder.collectAsState()
    val hostCollapsedGroups by vm.hostCollapsedGroups.collectAsState()
    val sessionGroupBy by vm.sessionGroupBy.collectAsState()
    val sessionSortBy by vm.sessionSortBy.collectAsState()
    val sessionGroupOrder by vm.sessionGroupOrder.collectAsState()
    val sessionCollapsedGroups by vm.sessionCollapsedGroups.collectAsState()
    val schemeId by vm.colorSchemeId.collectAsState()
    val lightSchemeId by vm.lightColorSchemeId.collectAsState()
    val schemeFollowsTheme by vm.schemeFollowsTheme.collectAsState()
    val effectiveSchemeId by vm.effectiveSchemeId.collectAsState()
    val primaryFontId by vm.primaryFontId.collectAsState()
    val fallbackFontId by vm.fallbackFontId.collectAsState()
    val fontCatalog by vm.fontCatalog.collectAsState()
    val fontStates by vm.fontStates.collectAsState()
    val importError by vm.importError.collectAsState()
    val importing by vm.importing.collectAsState()
    val importSuccess by vm.importSuccess.collectAsState()
    val fontSizeSp by vm.fontSizeSp.collectAsState()
    val lineSpacing by vm.lineSpacing.collectAsState()
    val letterSpacing by vm.letterSpacing.collectAsState()
    val cursorStyle by vm.cursorStyle.collectAsState()
    val cursorBlink by vm.cursorBlink.collectAsState()
    val extraKeysVisible by vm.extraKeysVisible.collectAsState()
    val extraKeysLayout by vm.extraKeysLayout.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val dynamicColor by vm.dynamicColor.collectAsState()
    val keyboardMode by vm.keyboardMode.collectAsState()
    val confirmClose by vm.confirmCloseSession.collectAsState()
    val keepScreenOn by vm.keepScreenOn.collectAsState()
    val autoTrustNewHostKey by vm.autoTrustNewHostKey.collectAsState()
    val terminalAlerts by vm.terminalAlerts.collectAsState()
    val hostKeyRequest by vm.hostKeyRequest.collectAsState()
    val hostKeyChange by vm.hostKeyChange.collectAsState()
    val tmuxScrollSetup by vm.tmuxScrollSetup.collectAsState()
    val tmuxPickerFor by vm.tmuxPicker.collectAsState()
    val includePrerelease by vm.includePrerelease.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val openSessionsRequest by vm.openSessionsRequest.collectAsState()
    val networks by vm.networks.collectAsState()
    val networkLive by vm.networkLive.collectAsState()
    val loginFlows by vm.loginFlows.collectAsState()
    val loginUrl by vm.loginUrl.collectAsState()
    val networkNotice by vm.networkNotice.collectAsState()
    val context = LocalContext.current
    val sessionConnectFailed by rememberUpdatedState(stringResource(R.string.session_connect_failed))
    val navigationVisit = remember(screen, homeTab) { Any() }
    val activeNavigationVisit by rememberUpdatedState(navigationVisit)
    var pendingConnections by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun requestSession(key: String, source: Screen, open: suspend () -> String?): Boolean {
        if (key in pendingConnections || screen !== source) return false
        val sourceTab = homeTab
        val sourceVisit = activeNavigationVisit
        pendingConnections = pendingConnections + key
        return try {
            val id = open()?.takeIf { it.isNotBlank() } ?: return false
            currentCoroutineContext().ensureActive()
            // A completed connection may remain in the background; it must not steal navigation.
            if (screen === source && activeNavigationVisit === sourceVisit && (source !is Screen.Home || homeTab == sourceTab)) {
                screen = Screen.Terminal(id)
            }
            true
        } finally {
            pendingConnections = pendingConnections - key
        }
    }

    fun launchSessionRequest(key: String, source: Screen, open: suspend () -> String?) {
        if (key in pendingConnections || screen !== source) return
        val sourceTab = homeTab
        val sourceVisit = activeNavigationVisit
        navigationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val opened = requestSession(key, source, open)
                if (!opened && screen === source && activeNavigationVisit === sourceVisit && (source !is Screen.Home || homeTab == sourceTab)) {
                    android.widget.Toast.makeText(context, sessionConnectFailed, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (screen === source && activeNavigationVisit === sourceVisit && (source !is Screen.Home || homeTab == sourceTab)) {
                    android.widget.Toast.makeText(context, sessionConnectFailed, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun returnTo(target: Screen) {
        if (target is Screen.Files) {
            if (pendingFilesReturn?.second?.isActive == true) return
            val source = screen
            lateinit var job: Job
            job = navigationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Reattach only: returning must not list or replay the failed operation.
                    vm.filesResumeForRecovery(target.hostId)
                    currentCoroutineContext().ensureActive()
                    if (screen === source) screen = target
                } finally {
                    if (pendingFilesReturn?.second === job) pendingFilesReturn = null
                }
            }
            pendingFilesReturn = source to job
            job.start()
        } else if (target is Screen.Terminal && vm.sessions.get(target.sessionId) == null) {
            screen = Screen.Home
            homeTab = HomeTab.Sessions
        } else screen = target
    }
    fun editHost(host: Host, back: Screen = Screen.Home) {
        val latest = (hostsState as? ListState.Ready)?.items?.firstOrNull { it.id == host.id } ?: host
        screen = Screen.Edit(latest, back)
    }
    fun openNetworkRecovery(recovery: NetworkRecovery, back: Screen) {
        recovery.network?.let { entry -> screen = Screen.NetworkEdit(entry.kind, entry, back) }
    }

    // Browser results are tied to the current request; returning to the app remains a user action.
    LaunchedEffect(loginUrl) {
        val request = loginUrl ?: return@LaunchedEffect
        vm.consumeLoginUrl(request)?.let { openLoginPage(context, it) }
    }
    LaunchedEffect(networkNotice) {
        val text = networkNotice ?: return@LaunchedEffect
        vm.consumeNetworkNotice()
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    }
    // 回到连接列表时刷新一次本地可知的网络状态（卡片上的「未登录」、设置行的提示）。
    LaunchedEffect(screen is Screen.Home, networks) {
        if (screen is Screen.Home) vm.refreshNetworksOnce()
    }

    /** 绑定好 ViewModel 的网络表单：网络页路由与主机编辑的全屏层共用。 */
    val networkEditor: @Composable (NetworkKind, NetworkEntry?, (String?) -> Unit) -> Unit = { kind, entry, onClose ->
        NetworkEditScreen(
            kind = kind,
            initial = entry,
            networks = networks,
            live = networkLive,
            loginFlows = loginFlows,
            hostsUsing = vm::hostsUsing,
            moshHostsUsing = vm::moshHostsUsing,
            liveSessionsUsing = vm::liveSessionsUsing,
            onWatch = vm::watchNetworks,
            onSave = vm::saveNetwork,
            onLogin = vm::loginTailnet,
            onReopenLogin = vm::reopenLoginUrl,
            onCancelLogin = vm::cancelLogin,
            onLogout = vm::logoutTailnet,
            onDelete = vm::deleteNetwork,
            onClose = onClose,
            networkState = networksState,
            onRetryNetworks = vm::retryNetworkReads,
        )
    }

    // 点后台保活通知：只有一个会话就直接进它，多个就进会话列表；停在文件页的先断开 SFTP。
    LaunchedEffect(openSessionsRequest, screen) {
        val req = openSessionsRequest ?: return@LaunchedEffect
        // Queue notification navigation until editing finishes instead of discarding a draft.
        if (screen is Screen.Edit || screen is Screen.NetworkEdit) return@LaunchedEffect
        if (screen is Screen.Files) vm.closeFiles()
        val list = vm.sessions.sessions.value
        val target = req.sessionId?.takeIf { id -> list.any { it.id == id } }
        if (target != null) {
            screen = Screen.Terminal(target)
        } else if (list.size == 1) {
            screen = Screen.Terminal(list.first().id)
        } else {
            screen = Screen.Home
            homeTab = HomeTab.Sessions
        }
        vm.consumeOpenSessions()
    }

    // 系统返回键：二级页回其父；Home 非「连接」分区回「连接」；Home「连接」分区不拦截（退出 app）。
    val backEnabled = screen !is Screen.Edit && screen !is Screen.NetworkEdit &&
        (screen !is Screen.Home || homeTab != HomeTab.Connections)
    // 打开文件页：断开旧的（若有）并按新主机建连；从终端进时带上会话以取当前目录。
    val openFiles: (Host, String?) -> Unit = { host, sessionId ->
        vm.openFiles(host, sessionId?.let { vm.sessions.get(it) })
        screen = Screen.Files(host.id, sessionId)
    }
    BackHandler(enabled = backEnabled) {
        when (screen) {
            is Screen.Fonts -> screen = Screen.Appearance
            is Screen.Appearance -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.TerminalSettings -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.About -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.Networks -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.NetworkEdit, is Screen.Edit -> Unit // Their editor shell owns every leave request.
            is Screen.Terminal -> screen = Screen.Home
            // 从终端进来的回终端，从连接列表进来的回列表；离开即断开那条 SFTP 连接。
            is Screen.Files -> {
                vm.closeFiles()
                returnTo((screen as Screen.Files).sessionId?.let { Screen.Terminal(it) } ?: Screen.Home)
            }
            is Screen.Home -> homeTab = HomeTab.Connections
        }
    }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            tab = homeTab,
            onTab = { homeTab = it },
            hosts = hosts,
            credentialsUnreadable = credentialsUnreadable,
            sessions = sessions,
            hostGroupOrder = hostGroupOrder,
            hostCollapsedGroups = hostCollapsedGroups,
            onReorderHostGroups = { vm.setHostGroupOrder(it) },
            onToggleHostGroupCollapse = { vm.toggleHostGroupCollapsed(it) },
            sessionGroupBy = sessionGroupBy,
            sessionSortBy = sessionSortBy,
            onSessionGroupBy = { vm.setSessionGroupBy(it) },
            onSessionSortBy = { vm.setSessionSortBy(it) },
            sessionGroupOrder = sessionGroupOrder,
            sessionCollapsedGroups = sessionCollapsedGroups,
            onReorderSessionGroups = { vm.setSessionGroupOrder(it) },
            onToggleSessionGroupCollapse = { vm.toggleSessionGroupCollapsed(it) },
            onAddHost = { screen = Screen.Edit(null) },
            onEditHost = { screen = Screen.Edit(it) },
            onOpenHostFiles = { openFiles(it, null) },
            onDuplicateHost = { vm.duplicate(it) },
            onDeleteHost = { vm.delete(it) },
            onConnectHost = { host -> launchSessionRequest("host:${host.id}", s) { vm.openSession(host) } },
            onReorderHosts = { vm.reorderHosts(it) },
            onOpenSession = { id -> screen = Screen.Terminal(id) },
            onCloseSession = { id -> vm.closeSession(id) },
            onCloseEndedSessions = { vm.closeEndedSessions() },
            onDuplicateSession = { id -> launchSessionRequest("duplicate:$id", s) { vm.duplicateSession(id) } },
            onReorderSessions = { vm.reorderSessions(it) },
            keyboardMode = keyboardMode,
            confirmClose = confirmClose,
            updateInfo = updateInfo,
            onOpenAppearance = { screen = Screen.Appearance },
            onOpenTerminalSettings = { screen = Screen.TerminalSettings },
            onOpenAbout = { screen = Screen.About },
            networks = networks,
            networkLive = networkLive,
            onOpenNetworks = { screen = Screen.Networks },
            preflight = vm::preflight,
            onLoginNetwork = { screen = Screen.NetworkEdit(NetworkKind.TAILSCALE, it, Screen.Home) },
            hostsState = hostsState,
            networkState = networksState,
            onRetryReads = vm::retryNetworkReads,
            onEndTmux = { id, selection -> vm.sessions.get(id)?.let { vm.tmuxEndCurrent(it, selection) } ?: false },
            onTmuxRefresh = { id -> vm.sessions.get(id)?.let { vm.refreshTmux(it) } },
            onRecover = { host, recovery ->
                when (recovery.kind) {
                    RecoveryKind.LOGIN_NETWORK, RecoveryKind.VIEW_NETWORK, RecoveryKind.EDIT_NETWORK -> openNetworkRecovery(recovery, Screen.Home)
                    RecoveryKind.EDIT_HOST -> editHost(host)
                    RecoveryKind.RETRY -> vm.retryNetworkReads()
                }
            },
        )

        is Screen.Edit -> key(s.host?.id ?: "new-host") {
            HostEditScreen(
                initial = s.host,
                allHosts = hosts,
                onSave = { vm.saveHost(it, editing = s.host != null) },
                onSaved = { if (screen == s) returnTo(s.back) },
                onCancel = { if (screen == s) returnTo(s.back) },
                savedFingerprint = vm::savedFingerprint,
                onClearFingerprint = vm::clearFingerprint,
                networks = networks,
                networkLive = networkLive,
                networkState = networksState,
                hostStoreState = hostsState,
                onRetryNetworks = vm::retryNetworkReads,
                networkEditor = { kind, onClose -> networkEditor(kind, null, onClose) },
            )
        }

        is Screen.Networks -> NetworksScreen(
            networks = networks,
            live = networkLive,
            loginFlows = loginFlows,
            onWatch = vm::watchNetworks,
            onAdd = { kind -> screen = Screen.NetworkEdit(kind, null, Screen.Networks) },
            onOpen = { e -> screen = Screen.NetworkEdit(e.kind, e, Screen.Networks) },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
            networkState = networksState,
            onRetry = vm::retryNetworkReads,
        )

        is Screen.NetworkEdit -> key(s.entry?.id ?: s.kind.name) {
            networkEditor(s.kind, s.entry) { if (screen == s) returnTo(s.back) }
        }

        is Screen.Terminal -> {
            val ts = vm.sessions.get(s.sessionId)
            if (ts == null) {
                // 会话已被关闭 → 回主界面（会话分区）。
                LaunchedEffect(s.sessionId, pendingConnections) {
                    if (screen === s && "session:${s.sessionId}" !in pendingConnections) {
                        screen = Screen.Home; homeTab = HomeTab.Sessions
                    }
                }
            } else {
                // key(sessionId)：Terminal→Terminal 直接切会话时强制重建子树，
                // 否则 AndroidView 只创建一次会残留旧 View（重连表现为「坏了」）。
                key(s.sessionId) {
                    // 正看着的会话不发提醒；进来即收起它已有的提醒。
                    val appContext = LocalContext.current.applicationContext
                    DisposableEffect(s.sessionId) {
                        TerminalAlerts.visibleSessionId = s.sessionId
                        TerminalAlerts.cancel(appContext, s.sessionId)
                        onDispose { if (TerminalAlerts.visibleSessionId == s.sessionId) TerminalAlerts.visibleSessionId = null }
                    }
                    val reconnect = {
                        launchSessionRequest("session:${ts.id}", s) {
                            vm.retryNetworkReads()
                            val newId = vm.reconnectSession(ts)
                            if (newId.isNotBlank() && newId != ts.id) vm.closeSession(ts.id)
                            newId
                        }
                    }
                    val editFailedHost = {
                        if (screen === s) {
                            vm.closeSession(ts.id)
                            editHost(ts.host)
                        }
                    }
                    TerminalScreen(
                        ts = ts,
                        primaryFontId = primaryFontId,
                        fallbackFontId = fallbackFontId,
                        fontSizeSp = fontSizeSp,
                        lineSpacing = lineSpacing,
                        letterSpacing = letterSpacing,
                        cursorStyle = cursorStyle,
                        cursorBlink = cursorBlink,
                        schemeId = effectiveSchemeId,
                        extraKeysVisible = extraKeysVisible,
                        extraKeysLayout = extraKeysLayout,
                        keyboardMode = keyboardMode,
                        confirmClose = confirmClose,
                        keepScreenOn = keepScreenOn,
                        resolveTypeface = vm.fonts::resolveTypeface,
                        onBack = { screen = Screen.Home },
                        onReconnect = reconnect,
                        onClose = {
                            vm.closeSession(ts.id)
                            if (screen === s) { screen = Screen.Home; homeTab = HomeTab.Sessions }
                        },
                        // 连接失败 → 改配置：失败的会话只有一行报错，关掉它，编辑的是主机的**最新**配置
                        // （ts.host 是打开那一刻的快照）。
                        onEditHost = editFailedHost,
                        onLoginNetwork = { n -> screen = Screen.NetworkEdit(n.kind, n, Screen.Terminal(ts.id)) },
                        onRecover = { recovery ->
                            when (recovery.kind) {
                                RecoveryKind.LOGIN_NETWORK, RecoveryKind.VIEW_NETWORK, RecoveryKind.EDIT_NETWORK -> openNetworkRecovery(recovery, Screen.Terminal(ts.id))
                                RecoveryKind.EDIT_HOST -> editFailedHost()
                                RecoveryKind.RETRY -> reconnect()
                            }
                        },
                        onFontSize = { vm.setFontSize(it) },
                        onKeyboardMode = { vm.setKeyboardMode(it) },
                        onToggleExtraKeys = { vm.setExtraKeysVisible(!extraKeysVisible) },
                        onTmuxRefresh = { vm.refreshTmux(ts) },
                        onTmuxNew = { name -> requestSession("session:${ts.id}", s) { vm.tmuxNew(ts, name) } },
                        onTmuxRename = { selection, name -> vm.tmuxRename(ts, selection, name) },
                        onTmuxDetach = { vm.tmuxDetach(ts, it) },
                        onTmuxKill = { vm.tmuxKill(ts, it) },
                        onEndTmux = { vm.tmuxEndCurrent(ts, it) },
                        onTmuxAttach = { selection ->
                            requestSession("session:${ts.id}", s) { vm.openTmuxSession(ts, selection) }
                        },
                        onTmuxTakeOver = { selection ->
                            requestSession("session:${ts.id}", s) { vm.openTmuxSession(ts, selection, detachOthers = true) }
                        },
                        onOpenFiles = { openFiles(ts.host, ts.id) },
                    )
                    // 主机入口每次明确选择；原连接返回与重连仍遵循各自的恢复边界。
                    if (ts.id in tmuxPickerFor) {
                        val tmuxState by ts.tmuxState.collectAsState()
                        val viaJump = ts.jumpHost?.let { stringResource(R.string.tmux_via_jump, it.displayName) }.orEmpty()
                        TmuxPickerDialog(
                            state = tmuxState,
                            targetLabel = tmuxTargetLabel(ts.host.protocol, ts.host.username, ts.host.host, ts.host.port, viaJump),
                            defaultName = Tmux.defaultSessionName(ts.host.displayName),
                            onPick = { selection ->
                                requestSession("session:${ts.id}", s) {
                                    vm.pickTmuxSession(ts.id, selection)?.takeIf { it.isNotBlank() }
                                        ?.also { vm.dismissTmuxPicker(ts.id) }
                                }
                            },
                            onNew = { name ->
                                requestSession("session:${ts.id}", s) {
                                    vm.createPickedTmuxSession(ts.id, name)?.takeIf { it.isNotBlank() }
                                        ?.also { vm.dismissTmuxPicker(ts.id) }
                                }
                            },
                            onPlainShell = { vm.dismissTmuxPicker(ts.id) },
                        )
                    }
                }
            }
        }

        is Screen.Appearance -> AppearanceScreen(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            onThemeMode = { vm.setThemeMode(it) },
            onDynamicColor = { vm.setDynamicColor(it) },
            schemeId = schemeId,
            lightSchemeId = lightSchemeId,
            schemeFollowsTheme = schemeFollowsTheme,
            effectiveSchemeId = effectiveSchemeId,
            primaryFontId = primaryFontId,
            fallbackFontId = fallbackFontId,
            fonts = fontCatalog,
            fontStates = fontStates,
            fontSizeSp = fontSizeSp,
            lineSpacing = lineSpacing,
            letterSpacing = letterSpacing,
            cursorStyle = cursorStyle,
            cursorBlink = cursorBlink,
            resolveTypeface = vm.fonts::resolveTypeface,
            onSelectScheme = { vm.setColorScheme(it) },
            onSelectLightScheme = { vm.setLightColorScheme(it) },
            onSchemeFollowsTheme = { vm.setSchemeFollowsTheme(it) },
            onSelectPrimary = { vm.setPrimaryFont(it) },
            onSelectFallback = { vm.setFallbackFont(it) },
            onFontSize = { vm.setFontSize(it) },
            onLineSpacing = { vm.setLineSpacing(it) },
            onLetterSpacing = { vm.setLetterSpacing(it) },
            onCursorStyle = { vm.setCursorStyle(it) },
            onCursorBlink = { vm.setCursorBlink(it) },
            onResetDefaults = { vm.resetAppearanceDefaults() },
            onOpenFonts = { screen = Screen.Fonts },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
        )

        is Screen.Fonts -> FontsScreen(
            primaryId = primaryFontId,
            fallbackId = fallbackFontId,
            fonts = fontCatalog,
            states = fontStates,
            onDownload = { vm.downloadFont(it) },
            onDelete = { vm.deleteFont(it) },
            onSetPrimary = { vm.setPrimaryFont(it) },
            onSetFallback = { vm.setFallbackFont(it) },
            onImport = { vm.importFont(it) },
            importError = importError,
            onClearImportError = { vm.clearImportError() },
            importing = importing,
            importSuccess = importSuccess,
            onClearImportSuccess = { vm.clearImportSuccess() },
            onBack = { screen = Screen.Appearance },
        )

        is Screen.TerminalSettings -> TerminalSettingsScreen(
            keyboardMode = keyboardMode,
            tmuxScrollSetup = tmuxScrollSetup,
            keepScreenOn = keepScreenOn,
            confirmClose = confirmClose,
            autoTrustNewHostKey = autoTrustNewHostKey,
            terminalAlerts = terminalAlerts,
            onTerminalAlerts = { vm.setTerminalAlerts(it) },
            onKeyboardMode = { vm.setKeyboardMode(it) },
            onTmuxScrollSetup = { vm.setTmuxScrollSetup(it) },
            onKeepScreenOn = { vm.setKeepScreenOn(it) },
            onConfirmClose = { vm.setConfirmCloseSession(it) },
            onAutoTrustNewHostKey = { vm.setAutoTrustNewHostKey(it) },
            extraKeysLayout = extraKeysLayout,
            onExtraKeysLayout = { vm.setExtraKeysLayout(it) },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
        )

        is Screen.Files -> {
            val filesState by vm.filesState.collectAsState()
            val filesScope = rememberCoroutineScope()
            var recovering by remember(s) { mutableStateOf(false) }
            val recoverFiles: (NetworkRecovery) -> Unit = { recovery ->
                if (!recovering) {
                    recovering = true
                    filesScope.launch {
                        try {
                            // Release the old SFTP lease before login can start a replacement identity.
                            vm.filesPauseForRecovery()
                            if (screen != s) return@launch
                            when (recovery.kind) {
                                RecoveryKind.LOGIN_NETWORK, RecoveryKind.VIEW_NETWORK, RecoveryKind.EDIT_NETWORK -> openNetworkRecovery(recovery, s)
                                RecoveryKind.EDIT_HOST -> filesState.host?.let { editHost(it, s) }
                                RecoveryKind.RETRY -> { vm.retryNetworkReads(); vm.filesRefresh() }
                            }
                        } finally { recovering = false }
                    }
                }
            }
            val tasks by vm.transfers.tasks.collectAsState()
            val sort by vm.filesSort.collectAsState()
            val showHidden by vm.filesShowHidden.collectAsState()
            val treeUri by vm.downloadTreeUri.collectAsState()
            val uploadConflict by vm.uploadConflict.collectAsState()
            // 用户在文件页主动选的下载目录：持久化读写授权，之后静默落盘。
            val context = androidx.compose.ui.platform.LocalContext.current
            val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    vm.setDownloadTree(uri.toString())
                }
            }
            FilesScreen(
                state = filesState,
                tasks = tasks,
                sort = sort,
                showHidden = showHidden,
                onNavigate = { vm.filesNavigate(it) },
                onUp = { vm.filesUp() },
                onRefresh = { vm.filesRefresh() },
                onGoto = { vm.filesGoto(it) },
                onMkdir = { vm.filesMkdir(it) },
                onSort = { vm.setFilesSort(it) },
                onShowHidden = { vm.setFilesShowHidden(it) },
                onUpload = { vm.uploadHere(it) },
                onBeforePickUpload = vm::prepareUpload,
                overwrite = uploadConflict,
                onOverwriteConfirm = { vm.confirmUploadOverwrite() },
                onOverwriteCancel = { vm.dismissUploadConflict() },
                onDownload = { entry ->
                    val saved = treeUri.takeIf { it.isNotBlank() }
                    // 用户自己指定过目录 → 用它；否则默认落「下载/Moke」，不打断
                    vm.download(entry, saved?.let { android.net.Uri.parse(it) })
                },
                onPickDownloadDir = { treePicker.launch(null) },
                onSendToTerminal = s.sessionId?.takeIf { vm.sessions.get(it) != null }?.let { id ->
                    { path: String ->
                        vm.sendToTerminal(id, path)
                        vm.closeFiles()
                        returnTo(Screen.Terminal(id))
                    }
                },
                onClearError = { vm.filesClearError() },
                onLoginNetwork = { n -> recoverFiles(NetworkRecovery(RecoveryKind.LOGIN_NETWORK, n)) },
                onRecover = recoverFiles,
                onTaskResume = { vm.resumeTransfer(it) },
                onTaskCancel = { vm.cancelTransfer(it) },
                onTaskRemove = { vm.removeTransfer(it) },
                onClearFinished = { vm.clearFinishedTransfers() },
                onBack = {
                    vm.closeFiles()
                    returnTo(s.sessionId?.let { Screen.Terminal(it) } ?: Screen.Home)
                },
            )
        }

        is Screen.About -> AboutScreen(
            updateInfo = updateInfo,
            includePrerelease = includePrerelease,
            onIncludePrerelease = { vm.setIncludePrerelease(it) },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
        )
    }

    // 首连指纹确认：连接线程正卡在这上面等答案，所以放在导航之外的最外层——
    // 不管当前在哪个页面（终端、文件页、设置）发起的连接，都要能弹出来。
    hostKeyRequest?.let { req ->
        HostKeyConfirmDialog(
            request = req,
            onTrust = { vm.resolveHostKey(req.id, true) },
            onCancel = { vm.resolveHostKey(req.id, false) },
        )
    }
    hostKeyChange?.let { req ->
        HostKeyChangedDialog(
            request = req,
            onClear = { vm.clearChangedFingerprint(req.target) },
            onCancel = { vm.dismissHostKeyChange() },
        )
    }
}

/** 首次连接新主机时的指纹确认弹窗（默认行为；可在设置里改为自动信任）。 */
@Composable
private fun HostKeyConfirmDialog(
    request: HostKeyPrompt.Request,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.hostkey_confirm_title)) },
        text = {
            Column {
                Text(
                    request.target,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 指纹用等宽字体单独一段：核对逐字符进行，混在正文里更容易看错。
                Text(
                    text = listOf(request.keyType, request.fingerprint)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(R.string.hostkey_confirm_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 主机密钥变更：说明发生了什么，并询问是否清除已保存的指纹。 */
@Composable
private fun HostKeyChangedDialog(
    request: HostKeyChangePrompt.Request,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.hostkey_changed_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.hostkey_changed_rejected),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.hostkey_changed_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(request.saved, style = MaterialTheme.typography.bodySmall, fontFamily = MokeMono)
                Text(
                    stringResource(R.string.hostkey_changed_current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(request.current, style = MaterialTheme.typography.bodySmall, fontFamily = MokeMono)
                Text(
                    stringResource(R.string.hostkey_changed_ask),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.action_clear_fingerprint))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
