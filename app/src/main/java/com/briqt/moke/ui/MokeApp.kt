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
    data object Fonts : Screen
    data object About : Screen
}

@Composable
fun MokeApp(vm: MokeViewModel = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var homeTab by remember { mutableStateOf(HomeTab.Connections) }

    val hosts by vm.hosts.collectAsState()
    val sessions by vm.sessions.sessions.collectAsState()
    val hostSort by vm.hostSort.collectAsState()
    val schemeId by vm.colorSchemeId.collectAsState()
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

    // 系统返回键：二级页回其父；Home 非「连接」分区回「连接」；Home「连接」分区不拦截（退出 app）。
    val backEnabled = screen !is Screen.Home || homeTab != HomeTab.Connections
    BackHandler(enabled = backEnabled) {
        when (screen) {
            is Screen.Fonts -> screen = Screen.Appearance
            is Screen.Appearance -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.About -> { screen = Screen.Home; homeTab = HomeTab.Settings }
            is Screen.Edit -> screen = Screen.Home
            is Screen.Terminal -> screen = Screen.Home
            is Screen.Home -> homeTab = HomeTab.Connections
        }
    }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            tab = homeTab,
            onTab = { homeTab = it },
            hosts = hosts,
            sessions = sessions,
            sort = hostSort,
            onSort = { vm.setHostSort(it) },
            onAddHost = { screen = Screen.Edit(null) },
            onEditHost = { screen = Screen.Edit(it) },
            onDuplicateHost = { vm.duplicate(it) },
            onDeleteHost = { vm.delete(it) },
            onConnectHost = { host -> screen = Screen.Terminal(vm.openSession(host)) },
            onReorderHosts = { vm.reorderHosts(it) },
            onOpenSession = { id -> screen = Screen.Terminal(id) },
            onCloseSession = { id -> vm.closeSession(id) },
            onReorderSessions = { vm.reorderSessions(it) },
            onOpenAppearance = { screen = Screen.Appearance },
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
                        schemeId = schemeId,
                        extraKeysVisible = extraKeysVisible,
                        resolveTypeface = vm.fonts::resolveTypeface,
                        onBack = { screen = Screen.Home },
                        onReconnect = {
                            val newId = vm.openSession(ts.host)
                            vm.closeSession(ts.id)
                            screen = Screen.Terminal(newId)
                        },
                        onClose = {
                            vm.closeSession(ts.id)
                            screen = Screen.Home; homeTab = HomeTab.Sessions
                        },
                        onFontSize = { vm.setFontSize(it) },
                        onToggleExtraKeys = { vm.setExtraKeysVisible(!extraKeysVisible) },
                    )
                }
            }
        }

        is Screen.Appearance -> AppearanceScreen(
            schemeId = schemeId,
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
            resolveTypeface = vm.fonts::resolveTypeface,
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

        is Screen.About -> AboutScreen(onBack = { screen = Screen.Home; homeTab = HomeTab.Settings })
    }
}
