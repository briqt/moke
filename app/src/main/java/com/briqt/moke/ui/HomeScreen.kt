package com.briqt.moke.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.runtime.CompositionLocalProvider

import android.app.Activity
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.briqt.moke.LocaleManager
import com.briqt.moke.R
import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.Host
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.ListState
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.data.SortBy
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import com.briqt.moke.update.UpdateInfo
import kotlin.math.abs

/**
 * 主界面：底部导航「连接 · 会话 · 设置」三分区，内容区可左右滑动切换（顺序同底栏）。
 * 终端本体是独立全屏页（不带底栏），由 MokeApp 在此之上导航打开。会话对象常驻 ViewModel，切分区不销毁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    hosts: List<Host>,
    /** 存量凭据解不开（Keystore 密钥失效）：连接页要说明原因，而不是显示成空列表。 */
    credentialsUnreadable: Boolean,
    sessions: List<TermSession>,
    hostGroupOrder: List<String>,
    hostCollapsedGroups: Set<String>,
    onReorderHostGroups: (List<String>) -> Unit,
    onToggleHostGroupCollapse: (String) -> Unit,
    sessionGroupBy: GroupBy,
    sessionSortBy: SortBy,
    onSessionGroupBy: (GroupBy) -> Unit,
    onSessionSortBy: (SortBy) -> Unit,
    sessionGroupOrder: List<String>,
    sessionCollapsedGroups: Set<String>,
    onReorderSessionGroups: (List<String>) -> Unit,
    onToggleSessionGroupCollapse: (String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Host) -> Unit,
    onOpenHostFiles: (Host) -> Unit,
    onDuplicateHost: (Host) -> Unit,
    onDeleteHost: (Host) -> Unit,
    onConnectHost: (Host) -> Unit,
    onReorderHosts: (List<Host>) -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onCloseEndedSessions: () -> Unit,
    onDuplicateSession: (String) -> Unit,
    onReorderSessions: (List<String>) -> Unit,
    keyboardMode: KeyboardMode,
    confirmClose: Boolean,
    updateInfo: UpdateInfo?,
    onOpenAppearance: () -> Unit,
    onOpenTerminalSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    networks: List<NetworkEntry> = emptyList(),
    networkLive: MokeViewModel.NetworkLive = MokeViewModel.NetworkLive(),
    onOpenNetworks: () -> Unit = {},
    /** 点主机连接前的本地检查（已删除的网络/跳板机、从没登录的 tailnet），见 [MokeViewModel.preflight]。 */
    preflight: (Host) -> MokeViewModel.Preflight = { MokeViewModel.Preflight.Ok },
    onLoginNetwork: (NetworkEntry) -> Unit = {},
    hostsState: ListState<Host> = if (credentialsUnreadable) ListState.Unreadable else ListState.Ready(hosts),
    networkState: ListState<NetworkEntry> = ListState.Ready(networks),
    onRetryReads: () -> Unit = {},
    onEndTmux: suspend (String, TmuxSelection) -> Boolean = { _, _ -> false },
    onTmuxRefresh: (String) -> Unit = {},
    onRecover: (Host, NetworkRecovery) -> Unit = { host, recovery ->
        when (recovery.kind) {
            RecoveryKind.LOGIN_NETWORK, RecoveryKind.VIEW_NETWORK, RecoveryKind.EDIT_NETWORK -> recovery.network?.let(onLoginNetwork)
            RecoveryKind.EDIT_HOST -> onEditHost(host)
            RecoveryKind.RETRY -> onRetryReads()
        }
    },
) {
    var pendingPreflight by remember { mutableStateOf<Pair<Host, MokeViewModel.Preflight>?>(null) }
    val connect: (Host) -> Unit = { host ->
        when (val p = preflight(host)) {
            MokeViewModel.Preflight.Ok -> onConnectHost(host)
            else -> pendingPreflight = host to p
        }
    }
    // Capture the actual current client/session at the request, not a later title or selection.
    val closeResources = LocalResources.current
    var pendingClose by remember { mutableStateOf<Pair<String, SessionCloseTarget>?>(null) }
    val closeRequest: (String) -> Unit = { id ->
        sessions.firstOrNull { it.id == id }?.let { session ->
            val state = session.tmuxState.value
            val observeCurrent = observesCurrentTmux(session.host.useMosh,
                session.host.persistence == SessionPersistence.TMUX, session.tmuxIntent)
            if (requiresSessionCloseChoice(state, confirmClose, observeCurrent)) {
                val viaJump = session.jumpHost?.let { closeResources.getString(R.string.tmux_via_jump, it.displayName) }.orEmpty()
                val label = tmuxTargetLabel(session.host.protocol, session.host.username, session.host.host, session.host.port, viaJump)
                pendingClose = id to freezeSessionCloseTarget(state, session.displayTitle.value, label, observeCurrent)
            } else onCloseSession(id)
        }
    }
    // 删除主机会连同密码 / 私钥 / 指纹一起删掉且无法恢复：一律二次确认（危险操作不走"撤销"）。
    var pendingDeleteHost by remember { mutableStateOf<Host?>(null) }
    val showAddHostFab = tab == HomeTab.Connections && hostsState is ListState.Ready
    var addHostFabTop by remember { mutableStateOf<Float?>(null) }

    // 左右滑动切分区：页序即底栏顺序，pager 与外部 [tab] 双向同步。
    val tabs = remember { HomeTab.entries.toList() }
    val pagerState = rememberPagerState(initialPage = tab.ordinal) { tabs.size }
    val lifecycleOwner = LocalLifecycleOwner.current
    val visibleSessions by rememberUpdatedState(sessions)
    val refreshSession by rememberUpdatedState(onTmuxRefresh)
    LaunchedEffect(tab, lifecycleOwner) {
        if (tab != HomeTab.Sessions) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                visibleSessions.filter {
                    it.alive.value && observesCurrentTmux(it.host.useMosh,
                        it.host.persistence == SessionPersistence.TMUX, it.tmuxIntent)
                }.forEach { refreshSession(it.id) }
                delay(5_000)
            }
        }
    }
    // 外部改分区（底栏点击 / 返回键 / 从二级页带着分区回来）→ 翻页跟上。
    LaunchedEffect(tab) {
        val target = tab.ordinal
        if (pagerState.currentPage == target) return@LaunchedEffect
        // 相邻分区带动画滑过去；跨一页（连接↔设置）直接落位——动画会途经中间页，
        // 那一瞬的 currentPage 会被下面的回写当成"用户选了中间页"，把切换半路截停。
        if (abs(pagerState.currentPage - target) == 1) pagerState.animateScrollToPage(target)
        else pagerState.scrollToPage(target)
    }
    // 滑过半即认页（currentPage 而非 settledPage）→ 顶栏标题 / 底栏高亮 / FAB 当场跟手，不等回弹结束。
    // 回写同值不会触发重组，所以这里不必再判 `!= tab`（那还会读到启动时的旧值）。
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            tabs.getOrNull(page)?.let(onTab)
        }
    }

    // 宽屏（横屏手机、平板）用侧边导航栏，把纵向空间全留给内容；竖屏用矮版底栏（64dp）。Material 的标准自适应做法。
    val useRail = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val navTabs = listOf(
        NavSpec(HomeTab.Connections, Icons.Filled.Dns, stringResource(R.string.nav_connections), null, false),
        NavSpec(HomeTab.Sessions, Icons.Filled.Terminal, stringResource(R.string.nav_sessions), sessions.size.takeIf { it > 0 }, false),
        // 有新版时给「设置」tab 点一方朱砂小印（顺着分组一路指到「关于」）。
        NavSpec(HomeTab.Settings, Icons.Filled.Settings, stringResource(R.string.nav_settings), null, updateInfo != null),
    )
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (useRail) {
        androidx.compose.material3.NavigationRail(
            modifier = Modifier.endHairline(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Spacer(Modifier.weight(1f))
            navTabs.forEach { spec ->
                androidx.compose.material3.NavigationRailItem(
                    selected = tab == spec.tab,
                    onClick = { onTab(spec.tab) },
                    icon = { NavIcon(spec) },
                    label = { Text(spec.label, maxLines = 1) },
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
    Scaffold(
        modifier = Modifier.weight(1f).then(
            if (useRail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            HomeTab.Connections -> stringResource(R.string.app_title)
                            HomeTab.Sessions -> stringResource(R.string.nav_sessions)
                            HomeTab.Settings -> stringResource(R.string.nav_settings)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                // 会话页把分组/排序收进标题栏右侧（两个紧凑胶囊）；连接页固定按项目分组、无排序，故不放按钮。
                actions = {
                    when (tab) {
                        HomeTab.Connections -> {}
                        HomeTab.Sessions -> if (sessions.size > 1) GroupSortActions(
                            groupBy = sessionGroupBy,
                            groupOptions = listOf(GroupBy.PROJECT, GroupBy.HOST, GroupBy.CONNECTION, GroupBy.NONE),
                            onGroupBy = onSessionGroupBy,
                            sortBy = sessionSortBy,
                            sortOptions = listOf(SortBy.CREATED, SortBy.UPDATED, SortBy.MANUAL),
                            onSortBy = onSessionSortBy,
                        )
                        HomeTab.Settings -> {}
                    }
                },
                // 44dp（与 iOS 导航栏同高）：一行标题加 48dp 触控按钮已够；标题在栏内垂直居中。
                expandedHeight = MokeDimens.topBarHeight,
                colors = mokeTopBarColors(),
            )
        },
        bottomBar = {
            // Material 3 的矮版底栏（64dp，标准 80dp 的上下留白对三个入口是浪费）；仍是官方组件与选中药丸。
            if (!useRail) androidx.compose.material3.ShortNavigationBar(
                modifier = Modifier.topHairline(),
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                navTabs.forEach { spec ->
                    androidx.compose.material3.ShortNavigationBarItem(
                        selected = tab == spec.tab,
                        onClick = { onTab(spec.tab) },
                        icon = { NavIcon(spec) },
                        label = { Text(spec.label, maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (showAddHostFab) {
                // 一屏唯一的实心按钮：墨色反白，不用阴影。
                FloatingActionButton(
                    onClick = onAddHost,
                    modifier = Modifier.onGloballyPositioned { addHostFabTop = it.boundsInRoot().top },
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_host))
                }
            }
        },
    ) { padding ->
        // 每页内容自己吃 Scaffold 的 padding（原来就是这么写的），所以 pager 本身不加内边距。
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (tabs[page]) {
                HomeTab.Connections -> ConnectionsContent(padding, (hostsState as? ListState.Ready)?.items ?: hosts, hostGroupOrder, hostCollapsedGroups, onToggleHostGroupCollapse, onReorderHostGroups, onReorderHosts, onEditHost, onOpenHostFiles, onDuplicateHost, { pendingDeleteHost = it }, connect, networks, networkLive, hostsState, networkState, onRetryReads, addHostFabTop.takeIf { showAddHostFab })
                HomeTab.Sessions -> SessionsContent(padding, sessions, sessionGroupBy, sessionSortBy, onSessionGroupBy, onSessionSortBy, sessionGroupOrder, sessionCollapsedGroups, onToggleSessionGroupCollapse, onReorderSessionGroups, onOpenSession, closeRequest, onDuplicateSession, onReorderSessions, onCloseEndedSessions)
                HomeTab.Settings -> SettingsMenuContent(
                    padding, keyboardMode, updateInfo, onOpenAppearance, onOpenTerminalSettings, onOpenAbout,
                    networks, networkLive, onOpenNetworks, networkState,
                )
            }
        }
    }
    }

    pendingDeleteHost?.let { host ->
        val jumpUsers = hosts.count { it.id != host.id && it.jumpHostId == host.id }
        ConfirmDialog(
            title = stringResource(R.string.host_delete_title),
            message = stringResource(R.string.host_delete_confirm, host.displayName) +
                (if (jumpUsers > 0) "\n" + pluralStringResource(R.plurals.host_delete_jump_users, jumpUsers, jumpUsers) else ""),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { pendingDeleteHost = null; onDeleteHost(host) },
            onDismiss = { pendingDeleteHost = null },
        )
    }

    pendingPreflight?.let { (host, p) ->
        when (p) {
            is MokeViewModel.Preflight.NeedsLogin -> ConfirmDialog(
                title = stringResource(if (p.expired) R.string.preflight_expired_title else R.string.preflight_login_title, p.network.name),
                message = stringResource(if (p.expired) R.string.preflight_expired_message else R.string.preflight_login_message, p.network.name),
                confirmLabel = stringResource(R.string.action_login),
                onConfirm = { pendingPreflight = null; onRecover(host, NetworkRecovery(RecoveryKind.LOGIN_NETWORK, p.network)) },
                onDismiss = { pendingPreflight = null },
            )
            is MokeViewModel.Preflight.Refused -> ConfirmDialog(
                title = stringResource(R.string.preflight_refused_title),
                message = p.reason,
                confirmLabel = stringResource(p.recovery?.let { recoveryActionLabel(it.kind) } ?: R.string.action_ok),
                onConfirm = { pendingPreflight = null; p.recovery?.let { onRecover(host, it) } },
                onDismiss = { pendingPreflight = null },
            )
            MokeViewModel.Preflight.Ok -> pendingPreflight = null
        }
    }

    pendingClose?.let { (id, target) ->
        sessions.firstOrNull { it.id == id }?.let { session ->
            val state by session.tmuxState.collectAsState()
            val observeCurrent = observesCurrentTmux(session.host.useMosh,
                session.host.persistence == SessionPersistence.TMUX, session.tmuxIntent)
            SessionCloseDialog(
                target = target,
                state = sessionClosePresentationState(state, target, observeCurrent),
                onDisconnect = { pendingClose = null; onCloseSession(id) },
                onEnd = { selection -> onEndTmux(id, selection) },
                onDismiss = { pendingClose = null },
                onRefresh = { onTmuxRefresh(id) },
            )
        }
    }
}

private data class NavSpec(val tab: HomeTab, val icon: ImageVector, val label: String, val count: Int?, val dot: Boolean)

@Composable
private fun NavIcon(spec: NavSpec) {
    when {
        // 计数用中性小方块；「有新版」用朱砂小印（M3 Badge 默认是 error 色，那语义是"出错/警告"）。
        spec.count != null -> BadgedBox(badge = { CountBadge(spec.count.toString()) }) {
            Icon(spec.icon, contentDescription = spec.label, modifier = Modifier.size(24.dp))
        }
        spec.dot -> BadgedBox(badge = { Dot(size = 7.dp) }) {
            Icon(spec.icon, contentDescription = spec.label, modifier = Modifier.size(24.dp))
        }
        else -> Icon(spec.icon, contentDescription = spec.label, modifier = Modifier.size(24.dp))
    }
}

// ---------- 连接 ----------

@Composable
private fun ConnectionsContent(
    padding: PaddingValues,
    hosts: List<Host>,
    groupOrder: List<String>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onReorderGroups: (List<String>) -> Unit,
    onReorderHosts: (List<Host>) -> Unit,
    onEdit: (Host) -> Unit,
    onFiles: (Host) -> Unit,
    onDuplicate: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onConnect: (Host) -> Unit,
    networks: List<NetworkEntry>,
    networkLive: MokeViewModel.NetworkLive,
    hostsState: ListState<Host>,
    networkState: ListState<NetworkEntry>,
    onRetryReads: () -> Unit,
    fabTop: Float?,
) {
    // 主机卡片上的路由徽标：选了网络显示网络名；本地就能知道的问题（引用已删除、tailnet 从没登录）用错误色。
    val availableNetworks = (networkState as? ListState.Ready)?.items ?: networks
    val loadingNetworks = stringResource(R.string.networks_loading)
    val unreadableNetworks = stringResource(R.string.networks_unreadable)
    val loadingHosts = stringResource(R.string.hosts_loading)
    val unreadableHosts = stringResource(R.string.hosts_unreadable_title)
    val deletedNetwork = stringResource(R.string.badge_network_deleted)
    val deletedJump = stringResource(R.string.badge_jump_deleted)
    val notLoggedIn = stringResource(R.string.net_state_not_logged_in)
    val expiredLabel = stringResource(R.string.net_state_expired)
    val errorColor = MaterialTheme.colorScheme.error
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val routeBadge: (Host) -> BadgeSpec? = { h ->
        // 有跳板机时第一跳实际走的是跳板机的网络（F2），卡片要标真正在用的那个。
        val jump = hosts.firstOrNull { it.id == h.jumpHostId }
        val effective = if (h.jumpHostId.isNotBlank()) jump else h
        val net = availableNetworks.firstOrNull { it.id == effective?.networkId }
        when {
            h.jumpHostId.isNotBlank() && hostsState == ListState.Loading -> BadgeSpec(loadingHosts, neutral)
            h.jumpHostId.isNotBlank() && hostsState == ListState.Unreadable -> BadgeSpec(unreadableHosts, errorColor)
            h.jumpHostId.isNotBlank() && jump == null -> BadgeSpec(deletedJump, errorColor)
            effective == null || effective.networkId.isBlank() -> null
            networkState == ListState.Loading -> BadgeSpec(loadingNetworks, neutral)
            networkState == ListState.Unreadable -> BadgeSpec(unreadableNetworks, errorColor)
            net == null -> BadgeSpec(deletedNetwork, errorColor)
            net.kind == NetworkKind.TAILSCALE && networkLive.loggedIn[net.id] == false -> BadgeSpec("${net.name} · $notLoggedIn", errorColor)
            net.kind == NetworkKind.TAILSCALE && net.id in networkLive.expired -> BadgeSpec("${net.name} · $expiredLabel", errorColor)
            else -> BadgeSpec(net.name, neutral)
        }
    }
    // 「读不出来」不能伪装成「一台都没有」——那会让用户以为数据没了，
    // 转头新建一条连接就把还在磁盘上的密文覆盖掉（写入已在 HostStore 侧挡住，这里负责说清楚）。
    if (hostsState == ListState.Loading) {
        EmptyState(padding, Icons.Filled.Dns, stringResource(R.string.hosts_loading))
        return
    }
    if (hostsState == ListState.Unreadable) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Shield,
            title = stringResource(R.string.hosts_unreadable_title),
            error = true,
            onRetry = onRetryReads,
        )
        return
    }
    if (hosts.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Dns,
            title = stringResource(R.string.empty_hosts_title),
            hint = stringResource(R.string.empty_hosts_hint),
        )
        return
    }
    fun keyOf(h: Host) = h.group.ifBlank { UNGROUPED_KEY }
    // 固定按项目分组。分组显示顺序=持久化顺序（过滤到当前存在的组）+ 首次出现的新组补末尾。
    val present = hosts.map { keyOf(it) }.distinct()
    val orderedKeys = groupOrder.filter { it in present } + present.filter { it !in groupOrder }
    val hasNamedGroup = present.any { it != UNGROUPED_KEY }
    var listBottom by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    // Root coordinates already account for the bottom bar and consumed Scaffold insets.
    val fabOverlap = fabTop?.let { top ->
        with(density) { (listBottom - top).coerceAtLeast(0f).toDp() }
    } ?: 0.dp
    val listPadding = PaddingValues(top = 12.dp, bottom = 12.dp + fabOverlap)
    val listModifier = Modifier.fillMaxSize().onGloballyPositioned { listBottom = it.boundsInRoot().bottom }

    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        if (!hasNamedGroup) {
            // 一台主机都没设分组 → 退化成无分组头的平铺列表，仍可长按拖动重排。
            ReorderableColumn(
                items = hosts,
                key = { it.id },
                onReorder = onReorderHosts,
                modifier = listModifier,
                contentPadding = listPadding,
                spacing = 0.dp,
            ) { host, dragging, handle ->
                HostCard(host, { onConnect(host) }, { onEdit(host) }, { onFiles(host) }, { onDuplicate(host) }, { onDelete(host) }, dragHandle = handle, dragging = dragging, routeBadge = routeBadge(host))
            }
        } else {
            val groups = orderedKeys.map { k -> ReorderGroup(k, hosts.filter { keyOf(it) == k }) }
            GroupedReorderableList(
                groups = groups,
                itemKey = { it.id },
                collapsed = collapsed,
                onReorderGroups = onReorderGroups,
                onReorderItems = { groupKey, newItems ->
                    val members = hosts.filter { keyOf(it) == groupKey }.map { it.id }.toSet()
                    val byId = hosts.associateBy { it.id }
                    val order = mergeSessionOrder(hosts.map { it.id }, newItems.map { it.id }.filter { it in members })
                    onReorderHosts(order.map { byId.getValue(it) })
                },
                modifier = listModifier,
                contentPadding = listPadding,
                spacing = 0.dp,
                header = { group, dragging, handle ->
                    val key = group.key
                    GroupHeaderRow(
                        name = if (key == UNGROUPED_KEY) stringResource(R.string.ungrouped) else group.label,
                        count = group.items.size,
                        collapsed = key in collapsed,
                        onToggle = { onToggleCollapse(key) },
                        dragHandle = handle,
                        dragging = dragging,
                    )
                },
            ) { host, dragging, handle ->
                // 已按项目分组，分组头展示组名 → 卡片副标题尾部不再重复。
                HostCard(host, { onConnect(host) }, { onEdit(host) }, { onFiles(host) }, { onDuplicate(host) }, { onDelete(host) }, showGroup = false, dragHandle = handle, dragging = dragging, routeBadge = routeBadge(host))
            }
        }
    }
}

// 未分组分桶的哨兵键（不直接展示，展示时本地化为 R.string.ungrouped）。
private const val UNGROUPED_KEY = "\u0000__ungrouped__"

/**
 * 分组 / 排序控制（标题栏右侧两个紧凑胶囊：[▤ 值 ▾] [↕ 值 ▾]），连接页与会话页共用。
 * 两者正交；每页只传入适用维度子集。图标区分分组/排序，胶囊上显示当前值。
 */
@Composable
private fun GroupSortActions(
    groupBy: GroupBy,
    groupOptions: List<GroupBy>,
    onGroupBy: (GroupBy) -> Unit,
    sortBy: SortBy,
    sortOptions: List<SortBy>,
    onSortBy: (SortBy) -> Unit,
) {
    Row(
        modifier = Modifier.padding(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerChip(
            label = stringResource(R.string.sort_group),
            leadingIcon = Icons.Filled.Folder,
            valueText = stringResource(groupBy.labelRes),
            options = groupOptions.map { it to stringResource(it.labelRes) },
            selected = groupBy,
            onSelect = onGroupBy,
            menuHeader = stringResource(R.string.group_menu_hint),
        )
        PickerChip(
            label = stringResource(R.string.sort_label),
            leadingIcon = Icons.AutoMirrored.Filled.Sort,
            valueText = stringResource(sortBy.labelRes),
            options = sortOptions.map { it to stringResource(it.labelRes) },
            selected = sortBy,
            onSelect = onSortBy,
            menuHeader = stringResource(R.string.sort_menu_hint),
        )
    }
}

/** 通用下拉胶囊：显示「图标/标签 + 当前值 + ▾」，点开列出候选、当前项打勾。[leadingIcon] 非空则用图标取代文字标签（标签作无障碍描述）。 */
@Composable
private fun <T> PickerChip(
    label: String,
    valueText: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    menuHeader: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // 标题栏里的轻量下拉：透明底、紧凑，读作「图标 值 ▾」的内联控件，不做成突兀的实心块。
        Surface(
            onClick = { open = true },
            shape = MokeShapes.control,
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                } else {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(valueText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // 顶部小字提示当前维度（如「选择分组」/「选择排序」），点明这个下拉是干嘛的（无分隔线，避免小菜单里太重）。
            if (menuHeader != null) {
                Text(
                    menuHeader,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); open = false },
                    trailingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

/** 连接页 / 会话页共用的可折叠 + 可拖动的分组头：[chevron] 组名 计数 …… [拖动手柄]。点行体折叠/展开，长按手柄调分组顺序。 */
@Composable
internal fun GroupHeaderRow(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    dragHandle: Modifier,
    dragging: Boolean,
) {
    // 与设置页分区小标题同一层级：说明字色的小标题，下面紧跟本组的圆角容器。
    Surface(
        onClick = onToggle,
        shape = MokeShapes.control,
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (collapsed) R.string.group_expand else R.string.group_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            Box(
                modifier = dragHandle.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.drag_to_reorder),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HostCard(
    host: Host,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onFiles: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    // 是否在副标题尾部展示分组名：按项目分组时分组头已展示、置 false 免重复；平铺/不分组时置 true。
    showGroup: Boolean = true,
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
    /** 经网络时的网络名，或本地可知的路由问题（错误色）。 */
    routeBadge: BadgeSpec? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var menuOpen by remember { mutableStateOf(false) }
    // 副标题一行说清「怎么连」：地址 · 分组 · mosh（SSH 是默认，不标）· 网络（本地可知的问题用错误色）。
    val subtitle = androidx.compose.ui.text.buildAnnotatedString {
        append("${host.username}@${host.host}:${host.port}")
        if (host.group.isNotBlank() && showGroup) append(" · ${host.group}")
        if (host.useMosh) append(" · mosh")
        routeBadge?.let { route ->
            append(" · ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = route.color)); append(route.text); pop()
        }
    }
    ListRow(
        title = host.displayName.ifBlank { stringResource(R.string.unnamed) },
        subtitle = subtitle,
        onClick = onConnect,
        dragHandle = dragHandle,
        dragging = dragging,
    ) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    // 文件：不开会话也能进（主入口仍是终端 ⋮，那里还能带上终端当前目录）。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_open)) },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        onClick = { menuOpen = false; onFiles() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_duplicate)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_copy_command)) },
                        leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("moke", host.connectCommand))
                            Toast.makeText(context, resources.getString(R.string.host_copied, host.connectCommand), Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
    }
}

/** 协议徽标：mosh 用正文墨色、SSH 用说明字色（不上彩色，彩色只留给状态）。背景块紧贴文字（去字体额外行距，收紧内边距），供连接列表 / 会话列表 / 终端顶栏共用。 */
@Composable
fun ProtocolBadge(mosh: Boolean) {
    val color = if (mosh) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = color.copy(alpha = MokeDimens.badgeAlpha), shape = MokeShapes.pill) {
        Text(
            if (mosh) "mosh" else "SSH",
            color = color,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MokeMono,
            maxLines = 1,
            // 去掉字体自带上下额外行距，让背景高度≈字形本身（约原 3/5）；水平内边距收窄（约原 4/5）。
            style = LocalTextStyle.current.copy(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

// ---------- 会话 ----------

@Composable
private fun SessionsContent(
    padding: PaddingValues,
    sessions: List<TermSession>,
    groupBy: GroupBy,
    sortBy: SortBy,
    onGroupBy: (GroupBy) -> Unit,
    onSortBy: (SortBy) -> Unit,
    groupOrder: List<String>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onReorderGroups: (List<String>) -> Unit,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onCloseEnded: () -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.empty_sessions_title),
            hint = stringResource(R.string.empty_sessions_hint),
        )
        return
    }
    val groupLabels = sessions.associate { ts ->
        val directory = key(ts.id) { ts.directory.collectAsState().value }
        ts.id to sessionGroupLabel(groupBy, ts.host, ts.scopeKey.targetStamp,
            directory?.path, directory?.targetKey, directory?.targetLabel)
    }
    fun keyOf(ts: TermSession): String = groupLabels.getValue(ts.id).key
    var editingTitleId by remember { mutableStateOf<String?>(null) }
    val manual = sortBy == SortBy.MANUAL
    val cmp = sessionComparator(sortBy)

    // 已结束的会话会一直留在列表里（保留是为了「重新连接」），但一条条 × 太笨：
    // 攒到四五条时清理成本比逐条关闭还高，所以有一条就给一个一次清空的入口。
    val endedCount = sessions.count { !it.alive.collectAsState().value }
    var confirmClearEnded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        if (endedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { confirmClearEnded = true }) {
                    Text(stringResource(R.string.sessions_clear_ended, endedCount))
                }
            }
        }
        if (confirmClearEnded && endedCount > 0) {
            ConfirmDialog(
                title = stringResource(R.string.sessions_clear_ended_title),
                message = stringResource(R.string.sessions_clear_ended_confirm, endedCount),
                confirmLabel = stringResource(R.string.action_clear),
                destructive = true,
                onConfirm = { confirmClearEnded = false; onCloseEnded() },
                onDismiss = { confirmClearEnded = false },
            )
        }
        if (groupBy == GroupBy.NONE) {
            if (manual) {
                // 无分组 + 手动：整列长按拖动重排（仅内存顺序）。
                ReorderableColumn(
                    items = sessions,
                    key = { it.id },
                    onReorder = { list -> onReorder(list.map { it.id }) },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    spacing = 0.dp,
                ) { ts, dragging, handle ->
                    SessionCard(ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) }, onDuplicate = { onDuplicate(ts.id) }, onTitle = { editingTitleId = ts.id }, dragHandle = handle, dragging = dragging)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    val sorted = sessions.sortedWith(cmp)
                    itemsIndexed(sorted, key = { _, it -> it.id }) { index, ts ->
                        CompositionLocalProvider(LocalRowPosition provides RowPosition(index, sorted.size)) {
                            SessionCard(ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) },
                                onDuplicate = { onDuplicate(ts.id) }, onTitle = { editingTitleId = ts.id })
                        }
                    }
                }
            }
        } else {
            // 分组显示顺序：内存顺序（过滤到当前存在的组）+ 首次出现的新组补末尾。
            val present = sessions.map { keyOf(it) }.distinct()
            val orderedKeys = groupOrder.filter { it in present } + present.filter { it !in groupOrder }
            val groups = orderedKeys.map { k ->
                val items = sessions.filter { keyOf(it) == k }
                ReorderGroup(k, if (manual) items else items.sortedWith(cmp),
                    groupLabels.values.first { it.key == k }.label)
            }
            GroupedReorderableList(
                groups = groups,
                itemKey = { it.id },
                collapsed = collapsed,
                onReorderGroups = onReorderGroups,
                onReorderItems = { groupKey, newItems ->
                    val members = sessions.filter { keyOf(it) == groupKey }.map { it.id }.toSet()
                    onReorder(mergeSessionOrder(sessions.map { it.id }, newItems.map { it.id }.filter { it in members }))
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                spacing = 0.dp,
                header = { group, dragging, handle ->
                    val key = group.key
                    GroupHeaderRow(
                        name = group.label.ifBlank { stringResource(R.string.ungrouped) },
                        count = group.items.size,
                        collapsed = key in collapsed,
                        onToggle = { onToggleCollapse(key) },
                        dragHandle = handle,
                        dragging = dragging,
                    )
                },
            ) { ts, dragging, handle ->
                // 手动排序时组内卡片可拖（接 handle）；创建/更新时间排序时按比较器排、卡片不可拖。
                SessionCard(
                    ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) }, onDuplicate = { onDuplicate(ts.id) }, onTitle = { editingTitleId = ts.id },
                    dragHandle = if (manual) handle else null, dragging = dragging,
                )
            }
        }
    }
    sessions.firstOrNull { it.id == editingTitleId }?.let { session ->
        key(session.id) {
            val prefix by session.customPrefix.collectAsState()
            val customTitle by session.customTitle.collectAsState()
            val automaticPrefix by session.automaticPrefix.collectAsState()
            val automaticTitle by session.automaticTitle.collectAsState()
            SessionTitleDialog(
                initialPrefix = prefix.orEmpty(), initialTitle = customTitle.orEmpty(),
                automaticPrefix = automaticPrefix, automaticTitle = automaticTitle,
                onConfirm = { newPrefix, newTitle ->
                    session.setTitleOverrides(newPrefix, newTitle)
                    editingTitleId = null
                },
                onDismiss = { editingTitleId = null },
            )
        }
    }
}

/** 会话组内排序比较器（CREATED=创建时间倒序、UPDATED=最后活动时间倒序；MANUAL 不走此处）。 */
private fun sessionComparator(sortBy: SortBy): Comparator<TermSession> = when (sortBy) {
    SortBy.UPDATED -> compareByDescending { it.lastActivityAt }
    else -> compareByDescending { it.startedAt }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    ts: TermSession,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    onTitle: () -> Unit,
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
) {
    val title by ts.displayTitle.collectAsState()
    val alive by ts.alive.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val ended = stringResource(R.string.session_ended_short)
    // 副标题：连接名 · mosh（SSH 是默认，不标）· 已结束。
    val subtitle = androidx.compose.ui.text.AnnotatedString(buildString {
        append(ts.host.displayName)
        if (ts.host.useMosh) append(" · mosh")
        if (!alive) append(" · ").append(ended)
    })
    // 行上只留最常用的「关闭」；修改标题、复制会话收进长按菜单（单击仍是进入会话）。
    Box {
        ListRow(
            title = title,
            subtitle = subtitle,
            onClick = onOpen,
            onLongClick = { menuOpen = true },
            dragHandle = dragHandle,
            dragging = dragging,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_session), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_set_title)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuOpen = false; onTitle() },
            )
            // 复制：用同一主机再开一个独立会话（新连接，非克隆 live 状态）；沿用标题并加不重复标记。
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate_session)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = { menuOpen = false; onDuplicate() },
            )
        }
    }
}

// ---------- 设置（菜单，二级页承载具体项） ----------

@Composable
private fun SettingsMenuContent(
    padding: PaddingValues,
    keyboardMode: KeyboardMode,
    updateInfo: UpdateInfo?,
    onOpenAppearance: () -> Unit,
    onOpenTerminalSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    networks: List<NetworkEntry>,
    networkLive: MokeViewModel.NetworkLive,
    onOpenNetworks: () -> Unit,
    networkState: ListState<NetworkEntry>,
) {
    val context = LocalContext.current
    var langDialog by remember { mutableStateOf(false) }
    // 设置行副标题：没有网络时说用途；有需要登录的 tailnet 时点名并带小圆点；否则给数量。
    val entries = (networkState as? ListState.Ready)?.items ?: networks
    val tailnets = entries.filter { it.kind == NetworkKind.TAILSCALE }
    val needsLogin = if (networkState is ListState.Ready) tailnets.firstOrNull { networkLive.loggedIn[it.id] == false || it.id in networkLive.expired } else null
    val networkSubtitle = when {
        networkState == ListState.Loading -> stringResource(R.string.networks_loading)
        networkState == ListState.Unreadable -> stringResource(R.string.networks_unreadable)
        entries.isEmpty() -> stringResource(R.string.menu_network_empty)
        needsLogin != null -> stringResource(R.string.menu_network_needs_login, needsLogin.name)
        else -> stringResource(R.string.menu_network_summary, tailnets.size, entries.size - tailnets.size)
    }
    val langTag = LocaleManager.currentTag(context)
    val langLabel = when (langTag) {
        LocaleManager.ZH -> stringResource(R.string.lang_zh)
        LocaleManager.EN -> stringResource(R.string.lang_en)
        else -> stringResource(R.string.lang_system)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 一级设置只放「分组入口」，具体开关都在二级页 —— 功能变多时靠加/扩分组消化，
        // 而不是继续往这一屏平铺（那样很快就乱）。
        SettingsGroup {
            NavRow(Icons.Filled.Palette, stringResource(R.string.menu_appearance), onClick = onOpenAppearance)
            NavRow(Icons.Filled.Terminal, stringResource(R.string.menu_terminal_input), onClick = onOpenTerminalSettings)
            NavRow(Icons.Filled.Hub, stringResource(R.string.menu_network), networkSubtitle, onClick = onOpenNetworks, showDot = needsLogin != null)
            NavRow(Icons.Filled.Language, stringResource(R.string.menu_language), langLabel, onClick = { langDialog = true })
        }
        // 有新版时在「关于」上点一方朱砂小印（静默检查的唯一提示）。
        NavRow(
            Icons.Filled.Info,
            stringResource(R.string.menu_about),
            subtitle = updateInfo?.let { stringResource(R.string.update_found, it.tag) }.orEmpty(),
            onClick = onOpenAbout,
            showDot = updateInfo != null,
        )
    }

    if (langDialog) {
        LanguageDialog(
            current = langTag,
            onDismiss = { langDialog = false },
            onPick = { tag ->
                langDialog = false
                if (tag != langTag) {
                    LocaleManager.setTag(context, tag)
                    (context as? Activity)?.recreate()   // 重建 Activity → attachBaseContext 重新包裹语言
                }
            },
        )
    }
}

/** 语言选择弹窗：跟随系统 / 中文 / English。 */
@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        LocaleManager.SYSTEM to stringResource(R.string.lang_system),
        LocaleManager.ZH to stringResource(R.string.lang_zh),
        LocaleManager.EN to stringResource(R.string.lang_en),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // 标题收小（默认 headlineSmall 偏大、留白多），选项占满宽度、字号提到 bodyLarge，减少空旷感。
        title = { Text(stringResource(R.string.menu_language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MokeShapes.control).clickable { onPick(tag) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == tag, onClick = { onPick(tag) })
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

// ---------- 通用空态 ----------

@Composable
private fun EmptyState(
    padding: PaddingValues,
    icon: ImageVector,
    title: String,
    hint: String = "",
    // 出错态（如凭据解不开）用错误色，把「没有内容」和「读不出来」在视觉上分开。
    error: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
