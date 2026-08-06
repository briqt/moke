package com.briqt.moke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.briqt.moke.data.Host
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.terminal.Tmux

/** 底部导航分区。 */
enum class HomeTab { Connections, Sessions, Settings }

/**
 * 极简导航（v0.1 不引入 navigation-compose）：Home（底部三分区）为根，
 * 终端/编辑/外观/字体/关于为其上的全屏页，用状态切换。
 */
sealed interface Screen {
    data object Home : Screen
    data class Edit(val host: Host?) : Screen
    data class Terminal(val sessionId: String) : Screen
    data object Appearance : Screen
    data object TerminalSettings : Screen
    data object Fonts : Screen
    data object About : Screen

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

    val hosts by vm.hosts.collectAsState()
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
    val themeMode by vm.themeMode.collectAsState()
    val dynamicColor by vm.dynamicColor.collectAsState()
    val keyboardMode by vm.keyboardMode.collectAsState()
    val confirmClose by vm.confirmCloseSession.collectAsState()
    val keepScreenOn by vm.keepScreenOn.collectAsState()
    val tmuxPickerFor by vm.tmuxPicker.collectAsState()
    val scrollMode by vm.scrollMode.collectAsState()
    val includePrerelease by vm.includePrerelease.collectAsState()
    val updateTag by vm.updateTag.collectAsState()

    // 系统返回键：二级页回其父；Home 非「连接」分区回「连接」；Home「连接」分区不拦截（退出 app）。
    val backEnabled = screen !is Screen.Home || homeTab != HomeTab.Connections
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
            is Screen.Edit -> screen = Screen.Home
            is Screen.Terminal -> screen = Screen.Home
            // 从终端进来的回终端，从连接列表进来的回列表；离开即断开那条 SFTP 连接。
            is Screen.Files -> {
                vm.closeFiles()
                screen = (screen as Screen.Files).sessionId?.let { Screen.Terminal(it) } ?: Screen.Home
            }
            is Screen.Home -> homeTab = HomeTab.Connections
        }
    }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            tab = homeTab,
            onTab = { homeTab = it },
            hosts = hosts,
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
            onConnectHost = { host -> screen = Screen.Terminal(vm.openSession(host)) },
            onReorderHosts = { vm.reorderHosts(it) },
            onOpenSession = { id -> screen = Screen.Terminal(id) },
            onCloseSession = { id -> vm.closeSession(id) },
            onDuplicateSession = { id -> vm.duplicateSession(id) },
            onReorderSessions = { vm.reorderSessions(it) },
            keyboardMode = keyboardMode,
            confirmClose = confirmClose,
            updateTag = updateTag,
            onOpenAppearance = { screen = Screen.Appearance },
            onOpenTerminalSettings = { screen = Screen.TerminalSettings },
            onOpenAbout = { screen = Screen.About },
        )

        is Screen.Edit -> HostEditScreen(
            initial = s.host,
            allHosts = hosts,
            onSave = {
                vm.save(it)
                screen = Screen.Home
            },
            onCancel = { screen = Screen.Home },
            savedFingerprint = s.host?.let { vm.savedFingerprint(it) },
            onClearFingerprint = { h, p -> vm.clearFingerprint(Host(host = h, port = p)) },
        )

        is Screen.Terminal -> {
            val ts = vm.sessions.get(s.sessionId)
            if (ts == null) {
                // 会话已被关闭 → 回主界面（会话分区）。
                LaunchedEffect(s.sessionId) { screen = Screen.Home; homeTab = HomeTab.Sessions }
            } else {
                // key(sessionId)：Terminal→Terminal 直接切会话时强制重建子树，
                // 否则 AndroidView 只创建一次会残留旧 View（重连表现为「坏了」）。
                key(s.sessionId) {
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
                        keyboardMode = keyboardMode,
                        scrollMode = scrollMode,
                        confirmClose = confirmClose,
                        keepScreenOn = keepScreenOn,
                        resolveTypeface = vm.fonts::resolveTypeface,
                        onBack = { screen = Screen.Home },
                        onReconnect = {
                            val newId = vm.reconnectSession(ts)
                            vm.closeSession(ts.id)
                            screen = Screen.Terminal(newId)
                        },
                        onClose = {
                            vm.closeSession(ts.id)
                            screen = Screen.Home; homeTab = HomeTab.Sessions
                        },
                        onFontSize = { vm.setFontSize(it) },
                        onKeyboardMode = { vm.setKeyboardMode(it) },
                        onToggleExtraKeys = { vm.setExtraKeysVisible(!extraKeysVisible) },
                        onTmuxRefresh = { vm.refreshTmux(ts) },
                        onTmuxNew = { vm.tmuxNew(ts, it) },
                        onTmuxRename = { id, name -> vm.tmuxRename(ts, id, name) },
                        onTmuxDetach = { vm.tmuxDetach(ts, it) },
                        onTmuxKill = { vm.tmuxKill(ts, it) },
                        onTmuxAttach = { target ->
                            screen = Screen.Terminal(vm.openTmuxSession(ts, target))
                        },
                        onTmuxTakeOver = { target ->
                            screen = Screen.Terminal(vm.openTmuxSession(ts, target, detachOthers = true))
                        },
                        onOpenFiles = { openFiles(ts.host, ts.id) },
                    )
                    // 连接即选会话：主机「会话持久化=tmux」且还没记住选择时，连上后弹一次。
                    if (tmuxPickerFor == ts.id) {
                        val tmuxState by ts.tmuxState.collectAsState()
                        TmuxPickerDialog(
                            sessions = tmuxState.sessions,
                            defaultName = Tmux.defaultSessionName(ts.host.displayName),
                            onPick = { name ->
                                vm.pickTmuxSession(ts.id, name)?.let { screen = Screen.Terminal(it) }
                            },
                            onPlainShell = { vm.dismissTmuxPicker() },
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
            scrollMode = scrollMode,
            keepScreenOn = keepScreenOn,
            confirmClose = confirmClose,
            onKeyboardMode = { vm.setKeyboardMode(it) },
            onScrollMode = { vm.setScrollMode(it) },
            onKeepScreenOn = { vm.setKeepScreenOn(it) },
            onConfirmClose = { vm.setConfirmCloseSession(it) },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
        )

        is Screen.Files -> {
            val filesState by vm.filesState.collectAsState()
            val tasks by vm.transfers.tasks.collectAsState()
            val sort by vm.filesSort.collectAsState()
            val showHidden by vm.filesShowHidden.collectAsState()
            val treeUri by vm.downloadTreeUri.collectAsState()
            // 下载目录只问一次：拿到后持久化读写授权，之后静默落盘。
            var pendingDownload by remember { mutableStateOf<com.briqt.moke.terminal.sftp.RemoteEntry?>(null) }
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
                    pendingDownload?.let { vm.download(it, uri) }
                }
                pendingDownload = null
            }
            FilesScreen(
                state = filesState,
                tasks = tasks,
                sort = sort,
                showHidden = showHidden,
                hasDownloadDir = treeUri.isNotBlank(),
                onNavigate = { vm.filesNavigate(it) },
                onUp = { vm.filesUp() },
                onRefresh = { vm.filesRefresh() },
                onGoto = { vm.filesGoto(it) },
                onMkdir = { vm.filesMkdir(it) },
                onSort = { vm.setFilesSort(it) },
                onShowHidden = { vm.setFilesShowHidden(it) },
                onUpload = { vm.uploadHere(it) },
                onDownload = { entry ->
                    val saved = treeUri.takeIf { it.isNotBlank() }
                    if (saved != null) {
                        vm.download(entry, android.net.Uri.parse(saved))
                    } else {
                        pendingDownload = entry
                        treePicker.launch(null)
                    }
                },
                onPickDownloadDir = { pendingDownload = null; treePicker.launch(null) },
                onSendToTerminal = s.sessionId?.let { id ->
                    { path: String ->
                        vm.sendToTerminal(id, path)
                        vm.closeFiles()
                        screen = Screen.Terminal(id)
                    }
                },
                onClearError = { vm.filesClearError() },
                onTaskResume = { vm.resumeTransfer(it) },
                onTaskCancel = { vm.cancelTransfer(it) },
                onTaskRemove = { vm.removeTransfer(it) },
                onClearFinished = { vm.clearFinishedTransfers() },
                onBack = {
                    vm.closeFiles()
                    screen = s.sessionId?.let { Screen.Terminal(it) } ?: Screen.Home
                },
            )
        }

        is Screen.About -> AboutScreen(
            updateTag = updateTag,
            includePrerelease = includePrerelease,
            onIncludePrerelease = { vm.setIncludePrerelease(it) },
            onBack = { screen = Screen.Home; homeTab = HomeTab.Settings },
        )
    }
}
