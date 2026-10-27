package com.briqt.moke.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.HostKeyChangePrompt
import com.briqt.moke.terminal.HostKeyPrompt
import com.briqt.moke.terminal.KnownHosts
import com.briqt.moke.terminal.MokeSessionService
import com.briqt.moke.terminal.MokeTransferService
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.RefusedTransport
import com.briqt.moke.terminal.Tmux
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxOperationCode
import com.briqt.moke.terminal.TmuxConnection
import com.briqt.moke.terminal.TmuxIntent
import com.briqt.moke.terminal.TmuxLaunchPolicy
import com.briqt.moke.terminal.TmuxPresence
import com.briqt.moke.terminal.TmuxRemote
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxSessionRef
import com.briqt.moke.terminal.sameInstance
import com.briqt.moke.terminal.selectionFor
import com.briqt.moke.data.FilesSort
import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.SortBy
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.ListState
import com.briqt.moke.data.MissingKind
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.NetworkStore
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.data.StoreResult
import com.briqt.moke.data.Route
import com.briqt.moke.data.clearDanglingJumps
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.data.UserFont
import com.briqt.moke.data.resolveRoute
import com.briqt.moke.data.hostsUsingNetwork
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.NetworkManager
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.net.recovery
import com.briqt.moke.net.describe
import com.briqt.moke.net.refusalText
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.FontRepository
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.terminal.TerminalThemes
import com.briqt.moke.update.UpdateChecker
import com.briqt.moke.update.UpdateInfo
import com.briqt.moke.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class MokeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HostStore(app)
    private val settings = SettingsStore(app)
    val fonts = FontRepository(app)

    /** 取本地化字符串（随**应用内**语言，而非系统语言——ViewModel 手里只有 Application context）。 */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().localized(id, *args)

    /** 多会话管理器：Application 作用域单例，跨导航/Activity 存活，配合前台服务后台保活。 */
    val sessions = (app as MokeApplication).sessions

    /**
     * 待处理的"回到会话"请求（点后台保活通知时由 MainActivity 发出）。存成状态而不是一次性事件：
     * 冷启动时请求先于界面组合到达，事件会丢；界面处理完调 [consumeOpenSessions] 清掉，避免重建时重跳。
     */
    private val _openSessionsRequest = MutableStateFlow<OpenSessionsRequest?>(null)
    val openSessionsRequest: StateFlow<OpenSessionsRequest?> = _openSessionsRequest.asStateFlow()
    fun requestOpenSessions(sessionId: String? = null) { _openSessionsRequest.value = OpenSessionsRequest(sessionId) }
    fun consumeOpenSessions() { _openSessionsRequest.value = null }

    /** [sessionId] 非空 = 指明要回到哪个会话（终端提醒）；为空 = 按会话数决定落点（保活通知）。 */
    data class OpenSessionsRequest(val sessionId: String?)

    /** 主机 / 网络列表的三态（加载中、解不开、已读到）。 */
    val hostsState: StateFlow<ListState<Host>> = store.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState.Loading)
    val hosts: StateFlow<List<Host>> = hostsState
        .map { (it as? ListState.Ready)?.items.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val networkStore = NetworkStore(app)
    val networksState: StateFlow<ListState<NetworkEntry>> = networkStore.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState.Loading)

    /**
     * 存量凭据解不开（Keystore 密钥失效，典型是整机备份恢复到新机）。
     * 连接页据此说清原因——否则用户只看到一个空列表，会以为数据自己没了。
     */
    val credentialsUnreadable: StateFlow<Boolean> = hostsState.map { it is ListState.Unreadable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 用户选的配色：关闭联动时即最终生效者；开启联动后是「深色模式用」那一套。 */
    val colorSchemeId: StateFlow<String> = settings.colorSchemeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_ID)

    /** 「浅色模式用」配色（仅联动开启时参与）。 */
    val lightColorSchemeId: StateFlow<String> = settings.lightColorSchemeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_LIGHT_ID)

    /** 配色是否随应用明暗联动。 */
    val schemeFollowsTheme: StateFlow<Boolean> = settings.schemeFollowsTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 应用当前是否深色。由 UI 层（已把「跟随系统/浅色/深色」解析成布尔）回灌——
     * `isSystemInDarkTheme()` 只能在 Compose 里读，故不在 VM 内自行判断。
     */
    private val _appIsDark = MutableStateFlow(true)
    fun setAppIsDark(dark: Boolean) { _appIsDark.value = dark }

    /**
     * 最终生效的配色 id：联动开启且当前浅色 → 用浅色那套，否则用主选择。
     * **全局终端调色板只在这里注入**（含启动首值），避免两处各写一遍打架。
     */
    val effectiveSchemeId: StateFlow<String> =
        combine(settings.colorSchemeId, settings.lightColorSchemeId, settings.schemeFollowsTheme, _appIsDark) { dark, light, follows, isDark ->
            if (follows && !isDark) light else dark
        }
            .onEach { id -> TerminalThemes.byId(id).applyToTerminal() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_ID)

    val primaryFontId: StateFlow<String> = settings.primaryFontId
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontCatalog.DEFAULT_ID)

    val fallbackFontId: StateFlow<String> = settings.fallbackFontId
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FALLBACK_FONT_ID)

    val fontSizeSp: StateFlow<Float> = settings.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FONT_SIZE_SP)

    val cursorStyle: StateFlow<Int> = settings.cursorStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val cursorBlink: StateFlow<Boolean> = settings.cursorBlink
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val extraKeysVisible: StateFlow<Boolean> = settings.extraKeysVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val extraKeysLayout: StateFlow<String> = settings.extraKeysLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 应用明暗主题 / 动态取色 / 键盘模式 / 关闭会话二次确认。 */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keyboardMode: StateFlow<KeyboardMode> = settings.keyboardMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, KeyboardMode.SECURE)
    val tmuxScrollSetup: StateFlow<Boolean> = settings.tmuxScrollSetup
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val confirmCloseSession: StateFlow<Boolean> = settings.confirmCloseSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 首连是否自动信任主机密钥（默认关=先问一次）。
     *
     * 值的镜像（[HostKeyPrompt.autoTrust]）由 `MokeApplication` 在进程作用域维护：校验发生在
     * 连接线程上、必须同步返回，不能在那儿读 DataStore；也不能只依赖 ViewModel 活着。
     */
    val autoTrustNewHostKey: StateFlow<Boolean> = settings.autoTrustNewHostKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 待确认的首连指纹（非空=显示确认弹窗）。 */
    val hostKeyRequest: StateFlow<HostKeyPrompt.Request?> = HostKeyPrompt.pending

    fun resolveHostKey(id: String, trusted: Boolean) = HostKeyPrompt.resolve(id, trusted)

    /** 主机密钥变更（非空=询问是否清除已保存的指纹）。 */
    val hostKeyChange: StateFlow<HostKeyChangePrompt.Request?> = HostKeyChangePrompt.pending

    fun clearChangedFingerprint(target: String) {
        knownHosts.forget(target)
        HostKeyChangePrompt.dismiss()
    }

    fun dismissHostKeyChange() = HostKeyChangePrompt.dismiss()
    /** 检查更新是否包含预发布版（关于页开关）。 */
    val includePrerelease: StateFlow<Boolean> = settings.includePrerelease
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setIncludePrerelease(enabled: Boolean) = viewModelScope.launch {
        settings.setIncludePrerelease(enabled)
        checkUpdateSilently()   // 节流已被清零，这里立刻按新口径重查一次
    }

    /**
     * 静默检查更新的结果：非空 = 远端有更新（值为 tag，如 v0.1.16），UI 据此点一个主题色小圆点。
     * 跨启动保留（存在 DataStore），所以离线打开也还看得见上次发现的新版。
     */
    val updateInfo: StateFlow<UpdateInfo?> = combine(settings.latestSeenTag, settings.latestSeenUrl) { tag, url ->
        tag.takeIf { it.isNotBlank() && UpdateChecker.isNewer(it.removePrefix("v"), appVersion) }
            ?.let { UpdateInfo(it, url) }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 本机版本号（versionName）。 */
    private val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0" }.getOrDefault("0")

    /**
     * 启动时静默查一次更新：不打扰、失败静默丢弃；6 小时内不重复查（避免每次冷启都打网络）。
     * 查到的 tag 落库，UI 只以小圆点提示。
     */
    private fun checkUpdateSilently() = viewModelScope.launch(Dispatchers.IO) {
        val last = settings.lastUpdateCheckAt.first()
        val now = System.currentTimeMillis()
        if (now - last < 6 * 60 * 60 * 1000L) return@launch
        val includeRc = settings.includePrerelease.first()
        when (val r = UpdateChecker.check(appVersion, getApplication(), includeRc)) {
            is UpdateStatus.Available -> settings.recordUpdateCheck(now, r.latest, r.url)
            is UpdateStatus.UpToDate -> settings.recordUpdateCheck(now, "")
            else -> Unit   // 失败/超时：不记时间戳，下次启动再试；绝不打扰用户
        }
    }

    /** 修复 rc.1 可能把运行态 `tmux attach-session -t '$N'` 污染进保存连接的问题。 */
    private fun repairLegacyTmuxLoginCommands() = viewModelScope.launch(Dispatchers.IO) {
        store.updateAll { current ->
            current.map { host ->
                if (Tmux.isLegacyInjectedLoginCommand(host.loginCommand)) host.copy(loginCommand = "") else host
            }.takeIf { it != current }
        }
    }

    /**
     * 升级后只做一次：清掉已经失效的跳板引用（旧版对它们静默直连，清掉后行为不变）。
     * 之后删除跳板机按新规则拒连。主机库解不开时不动，下次启动再试。
     */
    private fun clearDanglingJumpsOnce() = viewModelScope.launch(Dispatchers.IO) {
        if (settings.danglingJumpsCleared.first()) return@launch
        if (store.updateAll { clearDanglingJumps(it) }) settings.setDanglingJumpsCleared()
    }

    // 连接页：固定按项目分组，仅持久化「分组顺序」与「已折叠分组」。
    val hostGroupOrder: StateFlow<List<String>> = settings.hostGroupOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hostCollapsedGroups: StateFlow<Set<String>> = settings.hostCollapsedGroups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    // 会话页：分组 / 排序两个正交维度，均持久化。
    val sessionGroupBy: StateFlow<GroupBy> = settings.sessionGroupBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroupBy.PROJECT)
    val sessionSortBy: StateFlow<SortBy> = settings.sessionSortBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortBy.CREATED)

    // 会话分组的顺序/折叠：会话本身是内存态（重启即清空），故这两项也只放内存、不持久化。
    private val _sessionGroupOrder = MutableStateFlow<List<String>>(emptyList())
    val sessionGroupOrder: StateFlow<List<String>> = _sessionGroupOrder.asStateFlow()
    private val _sessionCollapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val sessionCollapsedGroups: StateFlow<Set<String>> = _sessionCollapsedGroups.asStateFlow()

    val lineSpacing: StateFlow<Float> = settings.lineSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    val letterSpacing: StateFlow<Float> = settings.letterSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    /** 内置目录 + 用户上传字体的合并列表（供外观/字体页展示与选择）。 */
    val fontCatalog: StateFlow<List<FontSpec>> = settings.userFonts
        .map { users ->
            FontCatalog.all + users.map { uf ->
                FontSpec(
                    id = uf.id, name = uf.name, nameZh = uf.name, license = str(R.string.license_local),
                    cjk = false, bundled = false, url = null, archive = false,
                    entryHint = "", approxBytes = 0, userUploaded = true, noteRes = R.string.note_local_import,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontCatalog.all)

    /** 仅承载「下载中 / 失败」这类瞬时状态；已装/未装由字体文件是否存在推导。 */
    private val _downloadStates = MutableStateFlow<Map<String, FontInstallState>>(emptyMap())

    /** 每个字体的安装状态：下载中/失败取瞬时态，否则按文件是否存在给已装/未装。 */
    val fontStates: StateFlow<Map<String, FontInstallState>> =
        combine(fontCatalog, _downloadStates) { catalog, dl ->
            catalog.associate { spec ->
                spec.id to (dl[spec.id] ?: if (fonts.isInstalled(spec)) FontInstallState.Installed else FontInstallState.Absent)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 字体导入的错误提示（一次性，供 UI 展示后清除）。 */
    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()
    fun clearImportError() { _importError.value = null }

    /** 字体导入进行中（UI 显示 loading、禁用按钮）。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 导入成功提示（字体名，一次性）。 */
    private val _importSuccess = MutableStateFlow<String?>(null)
    val importSuccess: StateFlow<String?> = _importSuccess.asStateFlow()
    fun clearImportSuccess() { _importSuccess.value = null }

    suspend fun saveHost(host: Host, editing: Boolean): StoreResult<Host> = sessions.tmuxMemoryMutex.withLock {
        store.commit(host, editing)
    }

    fun save(host: Host) = viewModelScope.launch {
        val result = saveHost(host, hosts.value.any { it.id == host.id })
        if (result is StoreResult.Failed) _networkNotice.value = str(storeFailureMessage(result.reason))
    }

    fun delete(host: Host) = viewModelScope.launch {
        val removed = store.remove(host.id)
        if (removed is StoreResult.Failed) {
            _networkNotice.value = str(storeFailureMessage(removed.reason))
            return@launch
        }
        // 同时忘记指纹：否则服务器换密钥后"删除重建连接"依然连不上（指纹按 host:port 存，与条目无关）。
        if (hosts.value.none {
                it.id != host.id && it.host == host.host && it.port == host.port && it.fingerprintNamespace == host.fingerprintNamespace
            }
        ) {
            knownHosts.forget(KnownHosts.idOf(host.host, host.port, host.fingerprintNamespace))
        }
    }

    private val knownHosts by lazy { KnownHosts(getApplication()) }

    // ---------- 网络（tailnet / 代理） ----------

    private val networkManager get() = getApplication<MokeApplication>().networks

    /** 已保存的网络（读不出来或还在读时为空；那两种情况由 [networksState] 区分）。 */
    val networks: StateFlow<List<NetworkEntry>> = networksState
        .map { (it as? ListState.Ready)?.items.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 网络的运行时快照：本地是否登录过（看节点状态文件，不启动任何东西），运行中的 tailnet 再加
     * 实时状态。只在有网络页面可见时轮询（[watchNetworks]），平时按需刷新一次。
     */
    data class NetworkLive(
        val loggedIn: Map<String, Boolean> = emptyMap(),
        val status: Map<String, NetworkManager.NodeStatus> = emptyMap(),
        val leases: Map<String, Int> = emptyMap(),
        /** 本次运行中已得知登录失效的 tailnet（节点停了也记得）。 */
        val expired: Set<String> = emptySet(),
    )

    private val _networkLive = MutableStateFlow(NetworkLive())
    val networkLive: StateFlow<NetworkLive> = _networkLive.asStateFlow()

    data class LoginFlow(val url: String = "", val error: String? = null, val busy: Boolean = true, val attempt: Long = 0)
    data class LoginUrlRequest(val networkId: String, val attempt: Long, val url: String)

    private val networkLogins by lazy {
        NetworkLoginController(viewModelScope, ::saveNetwork, object : NetworkLoginBackend {
            override suspend fun prepare(entry: NetworkEntry): StoreResult<NetworkEntry> = prepareTmuxNetworkIdentity(entry)
            override fun hold(entry: NetworkEntry, attempt: Long) = networkManager.holdLogin(entry, attempt)
            override fun begin(entry: NetworkEntry, authKey: String, attempt: Long): String = networkManager.beginLogin(entry, authKey, attempt)
            override fun end(entry: NetworkEntry, attempt: Long) = networkManager.endLogin(entry, attempt)
        }) { t -> when (t) {
            is NetworkLoginPreparationException -> str(storeFailureMessage(t.reason))
            is NetworkException -> t.describe(getApplication())
            else -> str(R.string.store_failure_io)
        } }
    }
    val loginFlows: StateFlow<Map<String, LoginFlow>> get() = networkLogins.flows
    val loginUrl: StateFlow<LoginUrlRequest?> get() = networkLogins.url
    fun consumeLoginUrl(request: LoginUrlRequest): String? = networkLogins.consume(request)

    /** 网络操作的一次性提示（如「已退出，但没能通知控制服务器」）。 */
    private val _networkNotice = MutableStateFlow<String?>(null)
    val networkNotice: StateFlow<String?> = _networkNotice.asStateFlow()
    fun consumeNetworkNotice() { _networkNotice.value = null }

    suspend fun tailnetDevices(entry: NetworkEntry): List<com.briqt.moke.net.TailnetDevice> = withContext(Dispatchers.IO) {
        networkManager.devices(entry)
    }

    private var watchers = 0
    private var watchJob: kotlinx.coroutines.Job? = null

    /** 网络页面可见时开（离开时关）：每 2 秒刷新一次快照。 */
    fun watchNetworks(on: Boolean) {
        watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
        if (watchers > 0 && watchJob == null) {
            watchJob = viewModelScope.launch(Dispatchers.IO) {
                while (currentCoroutineContext().isActive) {
                    refreshNetworkLive()
                    delay(2_000)
                }
            }
        } else if (watchers == 0) {
            watchJob?.cancel()
            watchJob = null
        }
    }

    /** 不在网络页面时也要知道「未登录」（设置行、主机卡片、连接前检查）：刷新一次本地状态。 */
    fun refreshNetworksOnce() = viewModelScope.launch(Dispatchers.IO) { refreshNetworkLive() }

    fun retryNetworkReads() {
        networkStore.refresh()
        store.refresh()
        refreshNetworksOnce()
    }

    private suspend fun refreshNetworkLive() = coroutineScope {
        val list = (networksState.value as? ListState.Ready)?.items ?: return@coroutineScope
        val ids = list.map { it.id }.toSet()
        _networkLive.update { live -> live.copy(
            loggedIn = live.loggedIn.filterKeys { it in ids },
            status = live.status.filterKeys { it in ids },
            leases = live.leases.filterKeys { it in ids },
            expired = live.expired.intersect(ids),
        ) }
        // Each entry publishes independently: one slow node must not delay another entry's local marker.
        for (entry in list) launch(Dispatchers.IO) {
            val nm = networkManager
            val loggedIn = if (entry.kind == NetworkKind.TAILSCALE) runCatching { nm.hasLoggedIn(entry) }.getOrNull() else null
            val status = if (nm.isRunning(entry)) runCatching { nm.status(entry) }.getOrNull() else null
            val leases = if (nm.isRunning(entry)) runCatching { nm.refreshLeases(entry) }.getOrDefault(0) else 0
            if ((networksState.value as? ListState.Ready)?.items?.firstOrNull { it.id == entry.id } != entry) return@launch
            val currentMarker = nm.loggedInSnapshot(entry) ?: loggedIn
            val currentStatus = status?.takeUnless { it.state == "Running" && currentMarker == false }
            _networkLive.update { live -> live.copy(
                loggedIn = if (currentMarker == null) live.loggedIn - entry.id else live.loggedIn + (entry.id to currentMarker),
                status = if (currentStatus == null) live.status - entry.id else live.status + (entry.id to currentStatus),
                leases = live.leases + (entry.id to leases),
                expired = if (nm.isKnownExpired(entry)) live.expired + entry.id else live.expired - entry.id,
            ) }
            val flow = loginFlows.value[entry.id]
            if (flow != null && currentStatus?.let { it.state == "Running" && !it.expired } == true) {
                networkLogins.complete(entry, flow.attempt)
            }
        }
    }

    /**
     * 保存网络。代理的连接参数变了就升版本：之后的新连接用新实例，已连着的留在旧实例上直到结束。
     * @return 保存后的条目（版本可能已加一）。
     */
    suspend fun saveNetwork(entry: NetworkEntry, editing: Boolean): StoreResult<NetworkEntry> {
        val old = networks.value.firstOrNull { it.id == entry.id }
        val result = sessions.tmuxMemoryMutex.withLock { networkStore.commit(entry, editing) }
        if (result is StoreResult.Saved) {
            if (old != null && result.value.revision != old.revision && networkManager.leases(old) > 0) {
                _networkNotice.value = str(R.string.network_saved_sessions_unaffected)
            }
            if (old != null && old.kind == NetworkKind.TAILSCALE && old.deviceName != entry.deviceName && networkManager.isRunning(old)) {
                _networkNotice.value = str(R.string.network_saved_device_name_next_start)
            }
            refreshNetworksOnce()
        }
        return result
    }

    suspend fun loginTailnet(entry: NetworkEntry, authKey: String, editing: Boolean): StoreResult<NetworkEntry> =
        networkLogins.login(entry, authKey, editing)

    fun reopenLoginUrl(id: String) = networkLogins.reopen(id)
    fun cancelLogin(entry: NetworkEntry) = networkLogins.cancel(entry)

    /** Durable revision + preference invalidation precede any replacement of a tailnet identity. */
    private suspend fun prepareTmuxNetworkIdentity(entry: NetworkEntry): StoreResult<NetworkEntry> =
        sessions.tmuxMemoryMutex.withLock {
            val state = networkStore.state.first()
            if (state !is ListState.Ready) return@withLock StoreResult.Failed(StoreFailure.UNREADABLE)
            val current = state.items.singleOrNull { it.id == entry.id }
                ?: return@withLock StoreResult.Failed(StoreFailure.MISSING)
            if (current.kind != entry.kind || current.controlUrl != entry.controlUrl) {
                return@withLock StoreResult.Failed(StoreFailure.CONFLICT)
            }
            sessions.invalidateTmuxNetwork(entry.id)
            val advanced = if (current.kind == NetworkKind.TAILSCALE) networkStore.advanceTailnetIdentity(entry.id)
                else StoreResult.Saved(current)
            if (advanced is StoreResult.Failed) return@withLock advanced
            when (val cleared = store.clearTmuxMemoryForNetwork(entry.id)) {
                is StoreResult.Failed -> cleared
                is StoreResult.Saved -> advanced
            }
        }

    suspend fun logoutTailnet(entry: NetworkEntry): StoreResult<Unit> = viewModelScope.async {
        cancelLogin(entry)
        when (val prepared = prepareTmuxNetworkIdentity(entry)) {
            is StoreResult.Failed -> prepared
            is StoreResult.Saved -> {
                closeSessionsUsing(entry.id)
                forgetNetwork(prepared.value)
            }
        }
    }.await()

    suspend fun deleteNetwork(entry: NetworkEntry): StoreResult<Unit> = viewModelScope.async {
        cancelLogin(entry)
        when (val prepared = prepareTmuxNetworkIdentity(entry)) {
            is StoreResult.Failed -> prepared
            is StoreResult.Saved -> {
                when (val removed = sessions.tmuxMemoryMutex.withLock { networkStore.remove(entry.id) }) {
                    is StoreResult.Failed -> removed
                    is StoreResult.Saved -> {
                        closeSessionsUsing(entry.id)
                        forgetNetwork(prepared.value)
                    }
                }
            }
        }
    }.await()

    private suspend fun forgetNetwork(entry: NetworkEntry): StoreResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ok = networkManager.forget(entry)
            if (!ok) _networkNotice.value = str(R.string.network_logout_offline, entry.name)
            refreshNetworkLive()
            StoreResult.Saved(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoreResult.Failed(StoreFailure.IO)
        }
    }

    private fun closeSessionsUsing(networkId: String) {
        sessions.sessions.value.filter { it.network?.id == networkId }.forEach { sessions.close(it.id) }
    }

    /** 当前连接路径依赖此网络的主机数，包含跳板依赖，不把被覆盖的配置引用当作断连。 */
    fun hostsUsing(networkId: String): Int = hostsUsingNetwork(networkId, hostsState.value, networksState.value)

    /** 选用它、且确实会经它跑 mosh 的主机数（不经跳板机）。 */
    fun moshHostsUsing(networkId: String): Int =
        hosts.value.count { it.networkId == networkId && it.useMosh && it.jumpHostId.isBlank() }

    /** 正在经它连接的会话数（删除时会断开）。 */
    fun liveSessionsUsing(networkId: String): Int =
        sessions.sessions.value.count { it.network?.id == networkId && it.alive.value }

    /** 点主机连接之前、本地就能知道的问题（不必开一个注定失败的会话）。 */
    sealed interface Preflight {
        data object Ok : Preflight
        data class NeedsLogin(val network: NetworkEntry, val expired: Boolean) : Preflight
        data class Refused(val reason: String, val recovery: NetworkRecovery? = null) : Preflight
    }

    fun preflight(host: Host): Preflight {
        val route = routeOf(host.id)
        if (route !is Route.Ok) return Preflight.Refused(route.refusalText(getApplication()).orEmpty(), route.recovery)
        val net = route.network ?: return Preflight.Ok
        if (net.kind == NetworkKind.TAILSCALE) {
            val expired = networkManager.isKnownExpired(net)
            if (expired) return Preflight.NeedsLogin(net, expired = true)
            if (!networkManager.isRunning(net)) {
                when (networkManager.loggedInSnapshot(net)) {
                    false -> return Preflight.NeedsLogin(net, expired = false)
                    null -> {
                        refreshNetworksOnce()
                        return Preflight.Refused(str(R.string.net_state_checking), NetworkRecovery(RecoveryKind.RETRY))
                    }
                    true -> Unit
                }
            }
        }
        return Preflight.Ok
    }

    /** 该主机已记录的指纹（null=还没记录）。 */
    fun savedFingerprint(host: Host): String? =
        knownHosts.stored(KnownHosts.idOf(host.host, host.port, host.fingerprintNamespace))

    /** 显式忘记指纹：服务器换过密钥时的自救路径（编辑页动作）。 */
    fun clearFingerprint(host: Host) {
        knownHosts.forget(KnownHosts.idOf(host.host, host.port, host.fingerprintNamespace))
    }

    /** 复制主机为新条目（label 加「副本」，新 id，清空最近连接时间）。 */
    fun duplicate(host: Host) = viewModelScope.launch {
        val copy = host.copy(
            id = java.util.UUID.randomUUID().toString(),
            label = (host.label.ifBlank { host.displayName }) + " " + str(R.string.duplicate_suffix),
            lastConnectedAt = 0L,
        )
        store.upsert(copy, hosts.value)
    }

    fun setSessionGroupBy(g: GroupBy) = viewModelScope.launch { settings.setSessionGroupBy(g) }
    fun setSessionSortBy(s: SortBy) = viewModelScope.launch { settings.setSessionSortBy(s) }

    /** 会话分组之间顺序（长按分组头拖动，内存态）。 */
    fun setSessionGroupOrder(order: List<String>) { _sessionGroupOrder.value = order }

    /** 折叠/展开某个会话分组（内存态）。 */
    fun toggleSessionGroupCollapsed(group: String) =
        _sessionCollapsedGroups.update { if (group in it) it - group else it + group }

    /** 连接页分组之间的顺序（长按分组头拖动后持久化）。 */
    fun setHostGroupOrder(order: List<String>) = viewModelScope.launch { settings.setHostGroupOrder(order) }

    /** 折叠/展开某个连接分组（跨重启记忆）。 */
    fun toggleHostGroupCollapsed(group: String) = viewModelScope.launch {
        val cur = hostCollapsedGroups.value
        settings.setHostCollapsedGroups(if (group in cur) cur - group else cur + group)
    }

    /** 手动拖动重排：持久化新的连接顺序（组内拖动 / 无分组平铺拖动均走此处）。 */
    fun reorderHosts(newOrder: List<Host>) = viewModelScope.launch {
        if (!runCatching { store.reorder(newOrder.map { it.id }) }.getOrDefault(false)) {
            _networkNotice.value = str(R.string.store_failure_io)
        }
    }

    /** 拖动重排会话（仅内存，无持久化）。 */
    fun reorderSessions(orderedIds: List<String>) = sessions.reorder(orderedIds)

    // ---------- tmux：管理动作永远使用来源连接；新连接另行校验最新持久化 Route。 ----------
    private val tmuxTracking = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val tmuxOpening = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
    private val tmuxSheets = TmuxSheetController()
    internal val tmuxSheet: StateFlow<TmuxSheetRequest?> = tmuxSheets.request

    /** A connection finishing in the background consumes, rather than defers, its automatic sheet. */
    internal fun tmuxSessionOpened(id: String, visit: Any?) {
        sessions.get(id)?.let {
            tmuxSheets.opened(it.scopeKey, visit)
            if (!it.alive.value || it.connectFailed.value) tmuxSheets.dataEnded(it.scopeKey)
        }
    }

    internal fun enterTmuxVisit(ts: TermSession, visit: Any) {
        if (sessions.get(ts.id) === ts) tmuxSheets.enter(ts.scopeKey, visit)
    }

    internal fun leaveTmuxVisit(ts: TermSession, visit: Any) = tmuxSheets.leave(ts.scopeKey, visit)
    internal fun backgroundTmuxVisit(ts: TermSession, visit: Any) = tmuxSheets.background(ts.scopeKey, visit)
    internal fun tmuxSheetPresented(request: TmuxSheetRequest) = tmuxSheets.presented(request)

    internal fun openTmuxManagement(ts: TermSession, visit: Any) {
        if (sessions.get(ts.id) !== ts || ts.connectFailed.value) return
        tmuxSheets.manage(ts.scopeKey, visit)
        refreshTmux(ts)
    }

    internal fun dismissTmuxSheet(request: TmuxSheetRequest) = tmuxSheets.dismiss(request)

    fun refreshTmux(ts: TermSession) = viewModelScope.launch {
        if (sessions.get(ts.id) !== ts || !ts.dataReady.value) return@launch
        updateTmuxRoute(ts, storedRouteOf(ts.host.id))
        ts.tmuxCoordinator.refresh()
        ts.negotiatedTerm.value = ts.tmuxState.value.snapshot?.observation?.term
    }

    private fun routeMatches(ts: TermSession, route: Route): Boolean = TmuxLaunchPolicy.matches(
        ts.scopeKey, route, sessions.networkEpoch((route as? Route.Ok)?.network?.id),
    )

    private fun updateTmuxRoute(ts: TermSession, route: Route) {
        val matches = routeMatches(ts, route)
        ts.tmuxState.update { old ->
            val observesTarget = ts.host.useMosh || ts.host.persistence == SessionPersistence.TMUX ||
                ts.tmuxIntent != TmuxIntent.None || old.snapshot != null || old.lastBinding != null
            old.copy(
                targetChanged = old.targetChanged || (!matches && observesTarget && ts.transport !is RefusedTransport),
                canStartNewConnection = matches && !old.targetChanged && sessions.get(ts.id) === ts,
            )
        }
    }

    /** A newly edited target must never inherit an old list, even when its host ID is unchanged. */
    private suspend fun routeForTmuxOpen(ts: TermSession): Route.Ok? {
        val route = storedRouteOf(ts.host.id)
        updateTmuxRoute(ts, route)
        if (route !is Route.Ok || !routeMatches(ts, route) || ts.tmuxState.value.targetChanged) {
            ts.tmuxState.update { it.copy(message = route.refusalText(getApplication()) ?: str(R.string.tmux_target_changed)) }
            return null
        }
        return route
    }

    private fun trackTmux(ts: TermSession) {
        if (tmuxTracking[ts.id]?.isActive == true) return
        tmuxTracking[ts.id] = viewModelScope.launch {
            var scrollTarget: TmuxSessionRef? = null
            var dataEnded = false
            combine(ts.tmuxState, store.state, networkStore.state, ts.alive, ts.connectFailed) { state, hosts, networks, _, _ ->
                state to resolveRoute(ts.host.id, hosts, networks)
            }.collect { (state, route) ->
                if (sessions.get(ts.id) !== ts) return@collect
                if (!dataEnded && (!ts.alive.value || ts.connectFailed.value)) {
                    dataEnded = true
                    tmuxSheets.dataEnded(ts.scopeKey)
                }
                updateTmuxRoute(ts, route)
                val binding = state.currentBinding ?: return@collect
                if (binding.scope != ts.scopeKey || state.snapshot?.verified != true) return@collect
                if (tmuxScrollSetup.value && scrollTarget?.sameInstance(binding.session) != true) {
                    // One attempt per witnessed object, never a replay after a missing ACK.
                    scrollTarget = binding.session
                    val selection = state.snapshot.selection(state.sessions.singleOrNull {
                        it.id == binding.session.id && it.created == binding.session.created
                    } ?: return@collect)?.copy(expectedClient = binding.client)
                    if (selection != null && ts.tmuxCoordinator.validateSelection(selection, requireCurrent = true)) {
                        val result = withContext(Dispatchers.IO) {
                            val connection = ts.transport as? TmuxConnection
                            if (connection == null) null else runCatching {
                                connection.execTmuxOperation(TmuxRemote.guardPeer(
                                    ts.scripts, connection.tmuxNonce, connection.tmuxOwnerRequired, binding.server.bootId,
                                    TmuxRemote.scrollSetup(binding.session),
                                ))?.let(TmuxRemote::parseOperation)
                            }.getOrNull()
                        }
                        if (result?.ok != true && sessions.get(ts.id) === ts) {
                            val message = str(when (result?.code) {
                                TmuxOperationCode.STALE -> R.string.tmux_session_changed
                                TmuxOperationCode.FAILED -> R.string.tmux_action_failed
                                else -> R.string.tmux_result_unknown
                            })
                            ts.tmuxState.update { if (it.currentBinding == binding) it.copy(message = message) else it }
                        }
                    }
                }
                sessions.tmuxMemoryMutex.withLock {
                    val latest = storedRouteOf(ts.host.id)
                    if (!sessions.isCurrentTmuxChoice(ts) || sessions.get(ts.id) !== ts ||
                        ts.tmuxState.value.currentBinding != binding || !routeMatches(ts, latest) ||
                        ts.host.persistence != SessionPersistence.TMUX) return@withLock
                    val saved = (latest as Route.Ok).host
                    if (saved.tmuxSessionName == binding.session.name && saved.tmuxTargetStamp == ts.scopeKey.targetStamp) return@withLock
                    val result = store.rememberTmux(ts.host, binding.session.name, ts.scopeKey.targetStamp)
                    if (result is StoreResult.Failed && result.reason !in setOf(StoreFailure.CONFLICT, StoreFailure.MISSING)) {
                        ts.tmuxState.update { it.copy(message = str(storeFailureMessage(result.reason))) }
                    }
                }
            }
        }
    }

    suspend fun tmuxRename(ts: TermSession, selection: TmuxSelection, name: String): Boolean {
        if (sessions.get(ts.id) !== ts || !ts.tmuxCoordinator.rename(selection, name)) return false
        sessions.renamed(ts.scopeKey.targetStamp, selection.session, name)
        // A non-current row may be the saved preference, but must not replace a newer selection.
        sessions.tmuxMemoryMutex.withLock {
            if (sessions.isCurrentTmuxChoice(ts) && routeMatches(ts, storedRouteOf(ts.host.id))) store.update(ts.host.id) { host ->
                if (host.tmuxTargetStamp == ts.scopeKey.targetStamp && host.tmuxSessionName == selection.session.name)
                    host.copy(tmuxSessionName = name) else host
            }
        }
        return true
    }

    suspend fun tmuxDetach(ts: TermSession, selection: TmuxSelection): Boolean =
        sessions.get(ts.id) === ts && ts.tmuxCoordinator.detach(selection)

    suspend fun tmuxKill(ts: TermSession, selection: TmuxSelection): Boolean =
        sessions.get(ts.id) === ts && ts.tmuxCoordinator.kill(selection)

    suspend fun tmuxEndCurrent(ts: TermSession, selection: TmuxSelection): Boolean {
        if (selection.expectedClient == null || sessions.get(ts.id) !== ts ||
            !ts.tmuxCoordinator.validateSelection(selection, requireCurrent = true)) return false
        return ts.tmuxCoordinator.kill(selection)
    }

    private suspend fun <T> withTmuxOpening(ts: TermSession, action: suspend () -> T?): T? {
        val lock = tmuxOpening.getOrPut(ts.id) { kotlinx.coroutines.sync.Mutex() }
        if (!lock.tryLock()) return null
        try { return action() } finally { lock.unlock() }
    }

    suspend fun openTmuxSession(source: TermSession, target: TmuxSelection, detachOthers: Boolean = false): String? =
        withTmuxOpening(source) {
            val epoch = sessions.nextTmuxChoice(source.host.id)
            val route = routeForTmuxOpen(source) ?: return@withTmuxOpening null
            if (!source.tmuxCoordinator.validateSelection(target)) return@withTmuxOpening null
            if (!detachOthers) {
                val candidate = sessions.findReusable(source.host.id, source.scopeKey.targetStamp, target.session)
                val state = candidate?.tmuxState?.value
                val row = state?.sessions?.singleOrNull { it.id == target.session.id && it.created == target.session.created }
                val selection = row?.let { state.selectionFor(it) }
                if (candidate != null && selection != null &&
                    candidate.tmuxCoordinator.validateSelection(selection, requireCurrent = true)) {
                    val accepted = sessions.tmuxMemoryMutex.withLock {
                        if (sessions.get(source.id) !== source || sessions.get(candidate.id) !== candidate ||
                            routeForTmuxOpen(source) == null) false else {
                            sessions.claimTmuxChoice(candidate, epoch)
                            true
                        }
                    }
                    if (!accepted) return@withTmuxOpening null
                    rememberCurrentTmux(candidate)
                    return@withTmuxOpening candidate.id
                }
            }
            openTmuxTarget(source, route, target.session, epoch, detachOthers)?.id
        }

    private suspend fun openTmuxTarget(source: TermSession, route: Route.Ok, target: TmuxSessionRef,
        epoch: Long, detachOthers: Boolean = false): TermSession? = sessions.tmuxMemoryMutex.withLock {
        if (sessions.get(source.id) !== source || routeForTmuxOpen(source) == null) return@withLock null
        sessions.openTmux(
            source, target, route.jump, route.network, detachOthers = detachOthers,
            host = route.host, rememberEpoch = epoch,
        ).also { trackTmux(it); ensureSessionService() }
    }

    private suspend fun rememberCurrentTmux(ts: TermSession) {
        val binding = ts.tmuxState.value.currentBinding ?: return
        sessions.tmuxMemoryMutex.withLock {
            if (sessions.get(ts.id) === ts && sessions.isCurrentTmuxChoice(ts) && routeMatches(ts, storedRouteOf(ts.host.id)) &&
                ts.tmuxState.value.snapshot?.verified == true && ts.tmuxState.value.currentBinding == binding &&
                ts.host.persistence == SessionPersistence.TMUX) {
                store.rememberTmux(ts.host, binding.session.name, ts.scopeKey.targetStamp)
            }
        }
    }

    /** Strict creation and attachment share one frozen Route; a concurrent edit cannot redirect it. */
    suspend fun tmuxNew(source: TermSession, name: String): String? = withTmuxOpening(source) {
        val epoch = sessions.nextTmuxChoice(source.host.id)
        val route = routeForTmuxOpen(source) ?: return@withTmuxOpening null
        val sourceState = source.tmuxState.value
        val ended = !source.alive.value || sourceState.restoreMissing || sourceState.presence is TmuxPresence.Ended
        var temporary: TermSession? = null
        try {
            val creator = if (!ended) source else sessions.tmuxMemoryMutex.withLock {
                if (sessions.get(source.id) !== source || routeForTmuxOpen(source) == null) return@withTmuxOpening null
                sessions.open(
                    host = route.host.copy(persistence = SessionPersistence.NONE, loginCommand = "", forwardPorts = ""),
                    jumpHost = route.jump, network = route.network, observeTmux = true,
                    startupCommand = "sh -c 'while IFS= read -r moke_unused; do :; done'",
                ).also { temporary = it; it.session.updateSize(80, 24, 0, 0) }
            }
            if (!awaitTmuxReady(creator)) {
                source.tmuxState.update { it.copy(message = creator.tmuxState.value.message ?: str(R.string.tmux_control_unavailable)) }
                return@withTmuxOpening null
            }
            val created = creator.tmuxCoordinator.create(name)
            if (created == null) {
                source.tmuxState.update { it.copy(message = creator.tmuxState.value.message) }
                return@withTmuxOpening null
            }
            openTmuxTarget(source, route, created, epoch)?.id
        } finally {
            temporary?.let { sessions.close(it.id) }
        }
    }

    private suspend fun awaitTmuxReady(ts: TermSession): Boolean {
        val ready = kotlinx.coroutines.withTimeoutOrNull(180_000) {
            combine(ts.dataReady, ts.alive, sessions.sessions) { up, alive, list ->
                up || !alive || list.none { it === ts }
            }.first { it }
            ts.dataReady.value && ts.alive.value && sessions.get(ts.id) === ts
        } == true
        if (!ready) return false
        for (wait in listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L)) {
            if (wait > 0) delay(wait)
            if (sessions.get(ts.id) !== ts || !ts.alive.value) return false
            val state = ts.tmuxCoordinator.refresh()
            if (state.snapshot?.verified == true || state.currentBinding != null) return true
            if (state.phase == TmuxPhase.NOT_INSTALLED || state.restoreMissing || state.targetChanged) return false
        }
        return false
    }

    suspend fun reconnectSession(source: TermSession): String {
        tmuxSheets.invalidate(source.scopeKey)
        val epoch = sessions.nextTmuxChoice(source.host.id)
        return sessions.tmuxMemoryMutex.withLock {
            val route = storedRouteOf(source.host.id)
            val ok = route as? Route.Ok
            if (ok != null && !routeMatches(source, ok)) return@withLock openSessionLocked(ok.host, epoch)
            touchHost(source.host)
            val intent = source.tmuxCoordinator.reconnectIntent()
            val picker = intent == TmuxIntent.None && ok?.host?.persistence == SessionPersistence.TMUX
            val host = ok?.host ?: source.host
            val session = sessions.open(
                host = if (picker) host.copy(loginCommand = "") else host,
                jumpHost = ok?.jump, network = ok?.network,
                refusal = route.refusalText(getApplication()),
                tmuxIntent = intent, rememberEpoch = epoch, observeTmux = picker || intent != TmuxIntent.None,
            ).also {
                it.recovery.value = route.recovery
                it.setTitleOverrides(source.customPrefix.value, source.customTitle.value)
            }
            trackTmux(session)
            ensureSessionService()
            if (picker) tmuxSheets.prepareSelection(session.scopeKey)
            session.id
        }
    }

    /** Only update the timestamp, never overwrite an edited profile with a terminal's old snapshot. */
    fun touchHost(host: Host) = viewModelScope.launch {
        store.update(host.id) { it.copy(lastConnectedAt = System.currentTimeMillis()) }
    }

    suspend fun openSession(host: Host): String {
        val epoch = sessions.nextTmuxChoice(host.id)
        return sessions.tmuxMemoryMutex.withLock { openSessionLocked(host, epoch) }
    }

    private suspend fun openSessionLocked(host: Host, epoch: Long): String {
        val route = storedRouteOf(host.id)
        val ok = route as? Route.Ok
        val current = ok?.host ?: host
        touchHost(current)
        // A host-card action starts a new choice, not an implicit attachment to saved memory.
        val intent = TmuxIntent.None
        val picker = TmuxLaunchPolicy.requestsPicker(route)
        val ts = sessions.open(
            host = if (picker) current.copy(loginCommand = "") else current,
            jumpHost = ok?.jump, network = ok?.network, refusal = route.refusalText(getApplication()),
            tmuxIntent = intent,
            rememberEpoch = epoch, observeTmux = picker,
        ).also { it.recovery.value = route.recovery }
        trackTmux(ts)
        ensureSessionService()
        if (picker) tmuxSheets.prepareSelection(ts.scopeKey)
        return ts.id
    }

    private fun tmuxSheetSource(request: TmuxSheetRequest): TermSession? =
        sessions.get(request.source.connectionId)?.takeIf { tmuxSheets.owns(request) && it.scopeKey == request.source }

    private fun finishTmuxSheet(request: TmuxSheetRequest, newId: String) {
        val disposable = tmuxSheets.finish(request, newId) ?: return
        // A late completion from an abandoned visit cannot dispose a source now used independently.
        sessions.get(disposable.connectionId)?.takeIf { it.scopeKey == disposable }?.let { closeSession(it.id) }
    }

    internal suspend fun pickTmuxSession(request: TmuxSheetRequest, target: TmuxSelection, detachOthers: Boolean = false): String? {
        val source = tmuxSheetSource(request) ?: return null
        val id = openTmuxSession(source, target, detachOthers) ?: return null
        finishTmuxSheet(request, id)
        return id
    }

    internal suspend fun createPickedTmuxSession(request: TmuxSheetRequest, name: String): String? {
        val source = tmuxSheetSource(request) ?: return null
        val id = tmuxNew(source, name) ?: return null
        finishTmuxSheet(request, id)
        return id
    }

    suspend fun duplicateSession(id: String): String? = sessions.tmuxMemoryMutex.withLock {
        val source = sessions.get(id) ?: return@withLock null
        val route = storedRouteOf(source.host.id)
        val ok = route as? Route.Ok
        touchHost(source.host)
        val ts = sessions.open(
            host = ok?.host ?: source.host, jumpHost = ok?.jump, network = ok?.network,
            refusal = route.refusalText(getApplication()), carryFrom = source.takeIf { routeMatches(it, route) },
        ).also { it.recovery.value = route.recovery }
        trackTmux(ts)
        ensureSessionService()
        ts.id
    }

    /**
     * 这台主机现在怎么连（唯一入口，见 [resolveRoute]）：失效的跳板机或网络一律拒连，
     * 不再像过去那样把被删除的跳板机静默当成直连。
     */
    private fun routeOf(hostId: String): Route = resolveRoute(hostId, hostsState.value, networksState.value)

    private suspend fun storedRouteOf(hostId: String): Route = withContext(Dispatchers.IO) {
        resolveRoute(hostId, store.state.first(), networkStore.state.first())
    }

    /** 拉起前台服务：退后台/关屏时保活会话（服务在会话归零时自行停止）。 */
    private fun ensureSessionService() {
        val ctx = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MokeSessionService::class.java)) }
    }

    fun closeSession(id: String) {
        sessions.get(id)?.let { tmuxSheets.invalidate(it.scopeKey) }
        tmuxTracking.remove(id)?.cancel()
        sessions.close(id)
    }

    /** 一次清掉列表里所有已结束的会话（保留活着的）。 */
    fun closeEndedSessions() {
        sessions.sessions.value.filterNot { it.alive.value }.forEach { closeSession(it.id) }
    }

    // ---------- 文件（SFTP） ----------

    /** 传输队列：Application 作用域，退后台/关屏由 [MokeTransferService] 保活。 */
    val transfers = (app as MokeApplication).transfers.also { mgr ->
        // 记住的下载目录失效（被删/撤授权）时忘掉它，之后回到默认落点。
        mgr.onTreeUnusable = { viewModelScope.launch { settings.setDownloadTreeUri("") } }
        // 上传成功后刷新正在看的那个目录：列表是一次性拉取的快照，不刷新就看不到刚传上去的文件，
        // 只能理解成"传失败了"。只在同主机同目录时刷新，避免把用户已经翻走的位置拽回来。
        mgr.onUploadDone = { hostId, dir ->
            viewModelScope.launch {
                val st = filesState.value
                if (st.host?.id == hostId && st.path == dir && filesController.canAutoRefresh && pendingUploadTarget == null && _uploadConflict.value == null) {
                    refreshFiles(auto = true)
                }
            }
        }
    }

    private val filesController = FilesController(app, viewModelScope)
    val filesState: StateFlow<FilesUiState> = filesController.state

    val downloadTreeUri: StateFlow<String> = settings.downloadTreeUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val filesShowHidden: StateFlow<Boolean> = settings.filesShowHidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filesSort: StateFlow<FilesSort> = settings.filesSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, FilesSort.NAME)

    /** 打开文件页；[from] 非空（从终端 ⋮ 进）时尝试以终端当前目录为起点。 */
    fun openFiles(host: Host, from: TermSession? = null) {
        invalidateUploadSelection()
        val route = routeOf(host.id)
        val ok = route as? Route.Ok
        filesController.open(ok?.host ?: host, ok?.jump, ok?.network, from, route.refusalText(getApplication()), route.recovery)
    }

    private fun invalidateUploadSelection() {
        pendingUploadTarget = null
        _uploadConflict.value = null
    }
    fun closeFiles() { invalidateUploadSelection(); filesController.close() }
    suspend fun filesPauseForRecovery() { invalidateUploadSelection(); filesController.pauseForRecovery() }
    fun filesNavigate(path: String) { invalidateUploadSelection(); filesController.navigate(path) }
    fun filesUp() { invalidateUploadSelection(); filesController.up() }
    fun filesRefresh() { invalidateUploadSelection(); refreshFiles(auto = false) }

    private fun refreshFiles(auto: Boolean) = viewModelScope.launch {
        val before = filesState.value
        val host = before.host ?: return@launch
        if (auto && !filesController.canAutoRefresh) return@launch
        val target = if (auto) filesController.captureUploadTarget() else null
        val route = storedRouteOf(host.id)
        if (filesState.value !== before) return@launch
        if (auto && (target == null || route !is Route.Ok || !target.destination.matches(route))) return@launch
        val ok = route as? Route.Ok
        filesController.retry(ok?.host ?: host, ok?.jump, ok?.network, route.refusalText(getApplication()), route.recovery)
    }

    suspend fun filesResumeForRecovery(hostId: String) {
        val before = filesState.value
        val host = before.host?.takeIf { it.id == hostId } ?: return
        val route = storedRouteOf(hostId)
        currentCoroutineContext().ensureActive()
        if (filesState.value !== before) return
        val ok = route as? Route.Ok
        filesController.resume(ok?.host ?: host, ok?.jump, ok?.network, route.refusalText(getApplication()), route.recovery)
    }

    suspend fun filesGoto(path: String): Boolean { invalidateUploadSelection(); return filesController.goto(path) }
    suspend fun filesMkdir(name: String): Boolean { invalidateUploadSelection(); return filesController.mkdir(name) }
    fun filesClearError() = filesController.clearError()
    fun setFilesSort(s: FilesSort) = viewModelScope.launch { settings.setFilesSort(s) }
    fun setFilesShowHidden(on: Boolean) = viewModelScope.launch { settings.setFilesShowHidden(on) }

    /** 记住下载目录（已在 UI 侧取得持久化读写授权）。 */
    fun setDownloadTree(uri: String) = viewModelScope.launch { settings.setDownloadTreeUri(uri) }

    /** 待确认覆盖的上传（非空=显示确认弹窗）。 */
    private val _uploadConflict = MutableStateFlow<UploadConflict?>(null)
    val uploadConflict: StateFlow<UploadConflict?> = _uploadConflict.asStateFlow()

    private var pendingUploadTarget: UploadTarget? = null

    /** Capture before the document picker: its result cannot choose a different page or directory. */
    fun prepareUpload(): Boolean {
        _uploadConflict.value = null
        pendingUploadTarget = filesController.captureUploadTarget()
        return pendingUploadTarget != null
    }

    fun uploadHere(uris: List<android.net.Uri>) {
        val target = pendingUploadTarget
        pendingUploadTarget = null
        if (uris.isEmpty() || target == null) return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) { uris.map { displayNameOfUri(it) } }
            if (!filesController.isUploadTargetCurrent(target)) {
                _networkNotice.value = str(R.string.files_upload_location_changed)
                return@launch
            }
            if (names.distinct().size != names.size) {
                _networkNotice.value = str(R.string.files_upload_duplicate_name)
                return@launch
            }
            val inspection = filesController.inspectUpload(target, names) ?: return@launch
            if (inspection.clashes.isEmpty()) startUpload(uris, inspection, confirmed = false)
            else _uploadConflict.value = UploadConflict(uris, inspection.clashes, inspection)
        }
    }

    fun confirmUploadOverwrite() {
        val pending = _uploadConflict.value ?: return
        _uploadConflict.value = null
        val inspection = pending.inspection ?: return
        viewModelScope.launch { startUpload(pending.uris, inspection, confirmed = true) }
    }

    fun dismissUploadConflict() { _uploadConflict.value = null }

    private suspend fun startUpload(uris: List<android.net.Uri>, inspection: UploadInspection, confirmed: Boolean) {
        val target = inspection.target
        val route = storedRouteOf(target.host.id)
        if (!filesController.isUploadTargetCurrent(target) || route !is Route.Ok || !target.destination.matches(route)) {
            _networkNotice.value = str(R.string.files_upload_location_changed)
            return
        }
        val added = transfers.enqueueUpload(
            target.host, uris, target.directory, names = inspection.names, destination = target.destination,
            overwriteNames = if (confirmed) inspection.clashes.toSet() else emptySet(),
        )
        if (added) ensureTransferService() else _networkNotice.value = str(R.string.files_upload_name_invalid)
    }

    /** SAF URI 的显示名（拿不到就退回最后一段路径，与传输层同一口径）。 */
    private fun displayNameOfUri(uri: android.net.Uri): String {
        val ctx = getApplication<Application>()
        val fromCursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
        return fromCursor ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** [treeUri] 为 null=用默认落点（下载/Moke）。 */
    fun download(entry: com.briqt.moke.terminal.sftp.RemoteEntry, treeUri: android.net.Uri?) {
        val host = filesState.value.host ?: return
        transfers.enqueueDownload(host, entry, treeUri)
        ensureTransferService()
    }

    fun resumeTransfer(id: String) {
        transfers.retry(id)
        ensureTransferService()
    }

    fun cancelTransfer(id: String) = transfers.cancel(id)
    fun removeTransfer(id: String) = transfers.remove(id)
    fun clearFinishedTransfers() = transfers.clearFinished()

    /** 把远端路径写进终端输入行（已 shell 转义），省掉在手机上手打长路径。 */
    fun sendToTerminal(sessionId: String, text: String) {
        sessions.get(sessionId)?.session?.write(text)
    }

    private fun ensureTransferService() {
        val ctx = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MokeTransferService::class.java))
        }
    }

    fun setColorScheme(id: String) = viewModelScope.launch { settings.setColorScheme(id) }

    fun setLightColorScheme(id: String) = viewModelScope.launch { settings.setLightColorScheme(id) }

    fun setSchemeFollowsTheme(on: Boolean) = viewModelScope.launch { settings.setSchemeFollowsTheme(on) }

    fun setPrimaryFont(id: String) = viewModelScope.launch { settings.setPrimaryFont(id) }

    fun setFallbackFont(id: String) = viewModelScope.launch { settings.setFallbackFont(id) }

    fun setFontSize(sp: Float) = viewModelScope.launch { settings.setFontSize(sp) }

    fun setCursorStyle(style: Int) = viewModelScope.launch { settings.setCursorStyle(style) }

    fun setCursorBlink(blink: Boolean) = viewModelScope.launch { settings.setCursorBlink(blink) }

    fun setLineSpacing(v: Float) = viewModelScope.launch { settings.setLineSpacing(v) }

    fun setLetterSpacing(v: Float) = viewModelScope.launch { settings.setLetterSpacing(v) }

    fun setExtraKeysVisible(visible: Boolean) = viewModelScope.launch { settings.setExtraKeysVisible(visible) }

    fun setExtraKeysLayout(layout: String) = viewModelScope.launch { settings.setExtraKeysLayout(layout) }

    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }

    fun setKeyboardMode(m: KeyboardMode) = viewModelScope.launch { settings.setKeyboardMode(m) }

    fun setTmuxScrollSetup(on: Boolean) = viewModelScope.launch { settings.setTmuxScrollSetup(on) }

    fun setConfirmCloseSession(on: Boolean) = viewModelScope.launch { settings.setConfirmCloseSession(on) }

    fun setAutoTrustNewHostKey(on: Boolean) = viewModelScope.launch { settings.setAutoTrustNewHostKey(on) }

    /** 「响铃与通知提醒」（默认关闭）；镜像到 TerminalAlerts.enabled 由 MokeApplication 维护。 */
    val terminalAlerts: StateFlow<Boolean> = settings.terminalAlerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setTerminalAlerts(on: Boolean) = viewModelScope.launch { settings.setTerminalAlerts(on) }

    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(on) }

    /** 恢复外观默认（配色/字体/字号/行距/字距/光标）。 */
    fun resetAppearanceDefaults() = viewModelScope.launch { settings.resetAppearanceDefaults() }

    fun downloadFont(id: String) {
        val spec = FontCatalog.byId(id)
        if (spec.bundled || spec.url == null) return
        if (_downloadStates.value[id] is FontInstallState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _downloadStates.update { it + (id to FontInstallState.Downloading(0f)) }
            val result = fonts.download(spec) { p -> _downloadStates.update { it + (id to FontInstallState.Downloading(p)) } }
            _downloadStates.update { m ->
                result.fold(
                    onSuccess = { m - id },   // 装好后移除瞬时态 → 由文件存在推导为「已装」
                    onFailure = { m + (id to FontInstallState.Failed(it.message ?: str(R.string.download_failed))) },
                )
            }
        }
    }

    /** 从系统文件选择器导入本地 TTF/OTF 字体。 */
    fun importFont(uri: android.net.Uri) = viewModelScope.launch {
        val name = displayNameOf(uri)
        _importing.value = true
        _importError.value = null
        fonts.importFont(uri).fold(
            onSuccess = { id -> settings.addUserFont(UserFont(id, name)); _importSuccess.value = name },
            onFailure = { _importError.value = it.message ?: str(R.string.import_failed) },
        )
        _importing.value = false
    }

    private fun displayNameOf(uri: android.net.Uri): String {
        val ctx = getApplication<Application>()
        val fromCursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val raw = fromCursor ?: uri.lastPathSegment ?: str(R.string.font_default_name)
        return raw.substringAfterLast('/').substringBeforeLast('.').ifBlank { str(R.string.font_default_name) }
    }

    fun deleteFont(id: String) = viewModelScope.launch {
        fonts.delete(id)
        settings.removeUserFont(id)              // 若是用户字体则移除记录（对内置无副作用）
        _downloadStates.update { it - id }
        // 若被删字体正被使用，回退到默认/无
        if (primaryFontId.value == id) settings.setPrimaryFont(FontCatalog.DEFAULT_ID)
        if (fallbackFontId.value == id) settings.setFallbackFont("")
    }

    // 放在类末尾：init 里要用到上面声明的 settings / appVersion，Kotlin 按声明顺序初始化，提前放会 NPE。
    init {
        sessions.sessions.onEach { current ->
            val ids = current.map { it.id }.toSet()
            tmuxTracking.keys.filterNot { it in ids }.toList().forEach { tmuxTracking.remove(it)?.cancel() }
            tmuxSheets.retainSources(current.map { it.scopeKey }.toSet())
            current.forEach(::trackTmux)
        }.launchIn(viewModelScope)
        repairLegacyTmuxLoginCommands()
        clearDanglingJumpsOnce()
        checkUpdateSilently()
    }

}
