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
import com.briqt.moke.terminal.Tmux
import com.briqt.moke.terminal.TmuxDiscovery
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxSession
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
import com.briqt.moke.data.Route
import com.briqt.moke.data.clearDanglingJumps
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.data.UserFont
import com.briqt.moke.data.resolveRoute
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.NetworkManager
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    val hosts: StateFlow<List<Host>> = store.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 主机 / 网络列表的三态（加载中、解不开、已读到）：连接前据此解析路由，不把「没读完」当「已删除」。 */
    private val hostsState: StateFlow<ListState<Host>> = store.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState.Loading)
    private val networkStore = NetworkStore(app)
    val networksState: StateFlow<ListState<NetworkEntry>> = networkStore.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState.Loading)

    /** 最近一次已接受的网络配置：连续保存时 StateFlow 可能还没刷新，不能用它判断「改了什么」。 */
    private val lastSavedNetworks = HashMap<String, NetworkEntry>()

    /**
     * 存量凭据解不开（Keystore 密钥失效，典型是整机备份恢复到新机）。
     * 连接页据此说清原因——否则用户只看到一个空列表，会以为数据自己没了。
     */
    val credentialsUnreadable: StateFlow<Boolean> = store.unreadable
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
    val scrollMode: StateFlow<ScrollMode> = settings.scrollMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScrollMode.SMART)
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
        val current = store.hosts.first()
        val repaired = current.map { host ->
            if (Tmux.isLegacyInjectedLoginCommand(host.loginCommand)) {
                host.copy(loginCommand = "")
            } else {
                host
            }
        }
        if (repaired != current) store.save(repaired)
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

    fun save(host: Host) = viewModelScope.launch { store.upsert(host, hosts.value) }

    fun delete(host: Host) = viewModelScope.launch {
        store.delete(host, hosts.value)
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
    val networks: StateFlow<List<NetworkEntry>> = networkStore.state
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

    /** 进行中的 tailnet 登录：网络 id → 登录流程状态。 */
    data class LoginFlow(val url: String = "", val error: String? = null, val busy: Boolean = true)

    private val _loginFlows = MutableStateFlow<Map<String, LoginFlow>>(emptyMap())
    val loginFlows: StateFlow<Map<String, LoginFlow>> = _loginFlows.asStateFlow()

    /** 要在浏览器（Custom Tabs）里打开的授权页；界面打开后调 [consumeLoginUrl]。 */
    private val _loginUrl = MutableStateFlow<String?>(null)
    val loginUrl: StateFlow<String?> = _loginUrl.asStateFlow()
    fun consumeLoginUrl() { _loginUrl.value = null }

    /** 登录在浏览器里完成：界面据此把应用带回前台（一次性）。 */
    private val _loginCompleted = MutableStateFlow<String?>(null)
    val loginCompleted: StateFlow<String?> = _loginCompleted.asStateFlow()
    fun consumeLoginCompleted() { _loginCompleted.value = null }

    /** 网络操作的一次性提示（如「已退出，但没能通知控制服务器」）。 */
    private val _networkNotice = MutableStateFlow<String?>(null)
    val networkNotice: StateFlow<String?> = _networkNotice.asStateFlow()
    fun consumeNetworkNotice() { _networkNotice.value = null }

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

    private fun refreshNetworkLive() {
        val list = networks.value
        val nm = networkManager
        val status = HashMap<String, NetworkManager.NodeStatus>()
        val leases = HashMap<String, Int>()
        for (e in list) {
            if (!nm.isRunning(e)) continue
            runCatching { nm.status(e) }.getOrNull()?.let { status[e.id] = it }
            leases[e.id] = runCatching { nm.leases(e) }.getOrDefault(0)
        }
        _networkLive.value = NetworkLive(
            loggedIn = list.filter { it.kind == NetworkKind.TAILSCALE }.associate { it.id to nm.hasLoggedIn(it) },
            status = status,
            leases = leases,
            expired = list.filter { nm.isKnownExpired(it) }.map { it.id }.toSet(),
        )
        // 登录流程：节点进入 Running 即完成（浏览器授权、认证密钥、管理员批准都走到这里）。
        val flows = _loginFlows.value
        val done = flows.keys.filter { id -> status[id]?.let { it.state == "Running" && !it.expired } == true }
        if (done.isNotEmpty()) {
            done.forEach { id -> list.firstOrNull { it.id == id }?.let { nm.endLogin(it) } }
            if (done.any { flows[it]?.url?.isNotEmpty() == true }) _loginCompleted.value = done.first()
            _loginFlows.update { it - done.toSet() }
        }
    }

    /**
     * 保存网络。代理的连接参数变了就升版本：之后的新连接用新实例，已连着的留在旧实例上直到结束。
     * @return 保存后的条目（版本可能已加一）。
     */
    fun saveNetwork(entry: NetworkEntry): NetworkEntry {
        val old = synchronized(lastSavedNetworks) {
            lastSavedNetworks[entry.id] ?: networks.value.firstOrNull { it.id == entry.id }
        }
        val bumped = if (old != null && entry.kind == NetworkKind.PROXY && proxyChanged(old, entry)) {
            entry.copy(revision = old.revision + 1)
        } else {
            entry.copy(revision = old?.revision ?: entry.revision)
        }
        synchronized(lastSavedNetworks) { lastSavedNetworks[entry.id] = bumped }
        viewModelScope.launch {
            networkStore.update { list ->
                if (list.any { it.id == bumped.id }) list.map { if (it.id == bumped.id) bumped else it } else list + bumped
            }
            refreshNetworkLive()
        }
        if (old != null && bumped.revision != old.revision && networkManager.leases(old) > 0) {
            _networkNotice.value = str(R.string.network_saved_sessions_unaffected)
        }
        return bumped
    }

    private fun proxyChanged(a: NetworkEntry, b: NetworkEntry) =
        a.proxyType != b.proxyType || a.proxyHost != b.proxyHost || a.proxyPort != b.proxyPort ||
            a.username != b.username || a.password != b.password || a.extraCaPem != b.extraCaPem

    /** 开始 tailnet 登录（先保存条目）。[authKey] 为空 = 浏览器登录。 */
    fun loginTailnet(entry: NetworkEntry, authKey: String = "") {
        val saved = saveNetwork(entry)
        _loginFlows.update { it + (saved.id to LoginFlow()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = networkManager.beginLogin(saved, authKey.trim())
                _loginFlows.update { it + (saved.id to LoginFlow(url = url, busy = url.isEmpty())) }
                if (url.isNotEmpty()) _loginUrl.value = url
            } catch (t: Throwable) {
                networkManager.endLogin(saved)
                val text = (t as? NetworkException)?.describe(getApplication()) ?: t.message ?: t.javaClass.simpleName
                _loginFlows.update { it + (saved.id to LoginFlow(error = text, busy = false)) }
            }
            refreshNetworkLive()
        }
    }

    /** 重新打开授权页（用户把浏览器关了还没授权）。 */
    fun reopenLoginUrl(id: String) {
        _loginFlows.value[id]?.url?.takeIf { it.isNotEmpty() }?.let { _loginUrl.value = it }
    }

    /** 放弃这次登录：不再保持节点运行（空闲后自动停止）。 */
    fun cancelLogin(entry: NetworkEntry) {
        networkManager.endLogin(entry)
        _loginFlows.update { it - entry.id }
    }

    /** 退出登录：尽力通知控制服务器，必定清除本机的节点状态；条目保留为「未登录」。 */
    fun logoutTailnet(entry: NetworkEntry) {
        closeSessionsUsing(entry.id)
        _loginFlows.update { it - entry.id }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = networkManager.forget(entry)
            if (!ok) _networkNotice.value = str(R.string.network_logout_offline, entry.name)
            refreshNetworkLive()
        }
    }

    /** 删除网络：断开在用它的会话、清掉节点状态（tailnet 尽力退出登录），条目移除。 */
    fun deleteNetwork(entry: NetworkEntry) {
        closeSessionsUsing(entry.id)
        _loginFlows.update { it - entry.id }
        viewModelScope.launch {
            networkStore.update { list -> list.filterNot { it.id == entry.id } }
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) { networkManager.forget(entry) }
            if (!ok) _networkNotice.value = str(R.string.network_logout_offline, entry.name)
            refreshNetworkLive()
        }
    }

    private fun closeSessionsUsing(networkId: String) {
        sessions.sessions.value.filter { it.network?.id == networkId }.forEach { sessions.close(it.id) }
    }

    /** 直接选用这个网络的主机数（经跳板机时目标的网络不生效，但引用仍在，删除后同样失效）。 */
    fun hostsUsing(networkId: String): Int = hosts.value.count { it.networkId == networkId }

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
        data class Refused(val reason: String) : Preflight
    }

    fun preflight(host: Host): Preflight {
        val route = routeOf(host.id)
        if (route is Route.Missing && route.what != MissingKind.HOST) {
            return Preflight.Refused(route.refusalText(getApplication()).orEmpty())
        }
        // 列表还在读 / 解不开：等待或明确说「读不出来」，绝不能当成「已删除」或放行去连接。
        if (route is Route.Loading || route is Route.Unreadable) {
            return Preflight.Refused(route.refusalText(getApplication()).orEmpty())
        }
        val net = (route as? Route.Ok)?.network ?: return Preflight.Ok
        // 已知失效的登录（本次运行中连接时得知）不论节点还在不在跑都先去登录；从没登录过的只在没跑时判断
        // （正在登录中的节点会在跑）。
        if (net.kind == NetworkKind.TAILSCALE &&
            (networkManager.isKnownExpired(net) || (!networkManager.isRunning(net) && !networkManager.hasLoggedIn(net)))
        ) {
            return Preflight.NeedsLogin(net, expired = networkManager.isKnownExpired(net))
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
    fun reorderHosts(newOrder: List<Host>) = viewModelScope.launch { store.save(newOrder) }

    /** 拖动重排会话（仅内存，无持久化）。 */
    fun reorderSessions(orderedIds: List<String>) = sessions.reorder(orderedIds)

    // ---------- tmux 侧通道管理（SSH 复用现有连接；mosh 按需建立独立 SSH 控制连接）----------
    /**
     * 探测/刷新远端 tmux。状态明确区分检查中、未安装、零会话与失败；
     * 同一终端会话的刷新/增删改用 Mutex 串行，避免慢请求用旧列表覆盖新操作结果。
     */
    fun refreshTmux(ts: TermSession) = viewModelScope.launch(Dispatchers.IO) {
        ts.tmuxMutex.withLock { refreshTmuxLocked(ts, retryUntilReady = true) }
    }

    private suspend fun refreshTmuxLocked(ts: TermSession, retryUntilReady: Boolean) {
        val previous = ts.tmuxState.value
        ts.tmuxState.value = previous.copy(
            phase = if (previous.phase == TmuxPhase.READY) TmuxPhase.READY else TmuxPhase.CHECKING,
            busy = true,
            message = null,
        )

        // 重试是给「传输还没 start 完」留的窗口，不是给不可达主机用的压测。
        // 固定 1.5s 间隔在 20s 内会打满 13 次；mosh 主机上每一次都是一条完整 SSH 登录，
        // 对带 fail2ban / MaxStartups 的远端等于自我封禁。改成指数退避：0/1/3/7/15s 共 5 次。
        val deadline = System.currentTimeMillis() + if (retryUntilReady) 20_000L else 0L
        var out: String?
        var backoff = 1_000L
        while (true) {
            out = runCatching { ts.transport.exec(Tmux.DISCOVER_CMD) }.getOrNull()
            if (out != null || !retryUntilReady || !currentCoroutineContext().isActive) break
            if (System.currentTimeMillis() + backoff >= deadline) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }

        if (out == null) {
            ts.tmuxState.value = previous.copy(
                phase = if (previous.phase == TmuxPhase.READY) TmuxPhase.READY else TmuxPhase.ERROR,
                busy = false,
                message = str(R.string.tmux_control_unavailable),
            )
            return
        }

        ts.tmuxState.value = when (val discovery = Tmux.parseDiscovery(out)) {
            TmuxDiscovery.NotInstalled -> previous.copy(
                phase = TmuxPhase.NOT_INSTALLED,
                sessions = emptyList(),
                busy = false,
                message = null,
            )
            is TmuxDiscovery.Ready -> previous.copy(
                phase = TmuxPhase.READY,
                sessions = discovery.sessions,
                busy = false,
                message = null,
            ).also {
                // 记下远端真的可用的 TERM，供后续 attach 使用（远端缺 xterm-256color 条目时
                // tmux 会拒绝启动，这是 rc.3 附加全挂的根因）。
                discovery.term?.let { t -> ts.negotiatedTerm.value = t }
                sessions.reconcileTmuxAssociations(
                    ts.host.id,
                    discovery.sessions,
                )
            }
            TmuxDiscovery.Malformed -> previous.copy(
                phase = TmuxPhase.ERROR,
                busy = false,
                message = str(R.string.tmux_invalid_response),
            )
        }
    }

    /** 执行管理命令，显示远端错误；成功后在同一个串行临界区内回刷。 */
    private fun tmuxAction(
        ts: TermSession,
        cmd: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch(Dispatchers.IO) {
        ts.tmuxMutex.withLock {
            val before = ts.tmuxState.value
            ts.tmuxState.value = before.copy(busy = true, message = null)
            val out = runCatching { ts.transport.exec(Tmux.actionCmd(cmd)) }.getOrNull()
            val result = out?.let(Tmux::parseAction)
            when {
                out == null -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = str(R.string.tmux_control_unavailable),
                )
                result == null -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = str(R.string.tmux_invalid_response),
                )
                !result.ok -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = result.output.ifBlank { str(R.string.tmux_action_failed) }.take(500),
                )
                else -> {
                    onSuccess()
                    refreshTmuxLocked(ts, retryUntilReady = false)
                }
            }
        }
    }
    fun tmuxNew(ts: TermSession, name: String) = tmuxAction(ts, Tmux.newCmd(name))
    fun tmuxRename(ts: TermSession, id: String, name: String) = tmuxAction(ts, Tmux.renameCmd(id, name)) {
        sessions.renameTmuxAssociation(ts.host.id, id, name)
    }
    fun tmuxDetach(ts: TermSession, id: String) = tmuxAction(ts, Tmux.detachCmd(id)) {
        sessions.clearTmuxAssociation(ts.host.id, id, tmuxNameOf(ts, id))
    }
    fun tmuxKill(ts: TermSession, id: String) = tmuxAction(ts, Tmux.killCmd(id)) {
        sessions.clearTmuxAssociation(ts.host.id, id, tmuxNameOf(ts, id))
    }

    /** 面板列表里该 ID 对应的会话名：本地关联可能还没拿到 ID，只能按名清（见 clearTmuxAssociation）。 */
    private fun tmuxNameOf(ts: TermSession, id: String): String? =
        ts.tmuxState.value.sessions.firstOrNull { it.id == id }?.name

    /**
     * 打开远端 tmux 会话：创建（或复用）专门的 Moke 终端连接，按名称原子恢复。
     * 不向当前前台终端注入文本，因此当前正在运行的 shell/TUI/半输入命令均不会被破坏。
     */
    fun openTmuxSession(
        source: TermSession,
        target: TmuxSession,
        detachOthers: Boolean = false,
    ): String {
        val route = routeOf(source.host.id)
        val ok = route as? Route.Ok
        val session = sessions.openTmux(
            source, target, ok?.jump, ok?.network, route.refusalText(getApplication()), detachOthers,
            host = ok?.host ?: source.host,
        )
        ensureSessionService()
        rememberTmuxSession(source.host, target.name)
        confirmTmuxAttach(session, target.name)
        return session.id
    }

    /** 记住该主机上最后选择的 tmux 会话名：下次连接可直接按名附加，不再打扰用户选。 */
    private fun rememberTmuxSession(host: Host, name: String) = viewModelScope.launch {
        store.update(host.id) { if (it.tmuxSessionName == name) it else it.copy(tmuxSessionName = name) }
    }

    /**
     * 附加确认：发出 attach 命令 ≠ 附加成功。远端 tmux 缺失或启动失败时包装命令会回落成登录壳，
     * 此时若仍标成「当前 tmux 会话」，面板与顶栏就在撒谎。用源会话的侧通道数一下客户端：
     * **持续**为 0（一直数到窗口最后一轮）且此刻会话仍存活 → 判定未附上，清除关联。数不出来（null）、
     * 会话已结束、以及窗口内的中间态 0 都视为"无法确认"，不动状态。
     */
    private fun confirmTmuxAttach(session: TermSession, name: String) =
        viewModelScope.launch(Dispatchers.IO) {
            // 用**该会话自己**的侧通道：源会话（临时登录壳）在选定后就被关掉了，拿它去问必然失败。
            // 传输要等 View 测量后才 start，所以给若干次重试；数不出来一律当"无法确认"，不动状态。
            repeat(ATTACH_CONFIRM_ROUNDS) { round ->
                delay(1000)
                if (!session.alive.value) return@launch
                val count = Tmux.parseClientCount(
                    runCatching { session.transport.exec(Tmux.clientsCmd(name)) }.getOrNull()
                ) ?: return@repeat
                // 命令是在会话还活着时发出的，结果却可能在它结束之后才回来。这种时候"0 个客户端"
                // 说明不了任何事——detach 本身就会让计数归零——按它清关联会把「已离开 tmux，远端
                // 会话仍在运行」退化成「会话已结束」（实测：detach 恰好撞上确认往返时就会这样）。
                if (!session.alive.value) return@launch
                if (count > 0) {
                    session.tmuxAttached.value = true
                    // 附上了才下发滚动绑定：让 tmux 现场判定翻页器/命令行（客户端侧判不了，见
                    // Tmux.scrollSetupCmd）。失败无所谓——退回原有的客户端启发式。
                    if (tmuxScrollSetup.value) {
                        runCatching { session.transport.exec(Tmux.scrollSetupCmd(name)) }
                    }
                    return@launch
                }
                // 数到 0 **不等于**没附上：mosh 要先引导 mosh-server、再由包装命令里的 tmux 去附加，
                // 这中间有几秒；第一轮就把 0 当结论，会把一个其实附加成功的会话永久标成"没附上"
                // ——面板于此给出「加入」（再开一个客户端）、detach 退化成「会话已结束」、滚动绑定
                // 也不会下发。所以只有窗口最后一轮的 0 才算数。
                if (round == ATTACH_CONFIRM_ROUNDS - 1) {
                    session.tmuxAttached.value = false
                    session.remoteTmuxId.value = null
                    session.remoteTmuxName.value = null
                }
            }
        }

    /** 重连时保留协议级启动命令；tmux 专用会话不能退回成普通 shell。 */
    fun reconnectSession(source: TermSession): String {
        touchHost(source.host)
        // 按 ID 重读：重连用主机**当前**的配置（会话里存的是打开那一刻的快照）。
        val route = routeOf(source.host.id)
        val ok = route as? Route.Ok
        val session = sessions.open(
            host = ok?.host ?: source.host,
            jumpHost = ok?.jump,
            network = ok?.network,
            refusal = route.refusalText(getApplication()),
            initialTitle = source.displayTitle.value,
            remoteTmuxId = source.remoteTmuxId.value,
            remoteTmuxName = source.remoteTmuxName.value,
            startupCommand = source.startupCommand,
        )
        ensureSessionService()
        // 重连同样要核对是否真的附上了。漏掉这一步，新会话的 tmuxAttached 恒为 null：
        // detach 后提示会退化成「会话已结束」，而 tmux 真的没附上时顶栏还继续标着 tmux ——
        // 正是首次附加时特意用侧通道消灭掉的那种「UI 撒谎」。
        source.remoteTmuxName.value?.let { confirmTmuxAttach(session, it) }
        return session.id
    }

    /**
     * 记录最近连接时间（用于"最近连接"排序）。
     *
     * **只能按 id 重读当前记录再改这一个字段**：`upsert` 是整条替换，而调用方手里的 [host] 往往是
     * 会话**打开那一刻**的快照（重连/复制会话都拿 `TermSession.host`）。把快照整条写回去，等于把
     * 此后的一切改动静默回滚——实测「会话开着时编辑主机 → 复制会话」编辑即丢，`tmuxSessionName`
     * 也是这样被吃掉的（于是"选择会被记住"失效、选择器反复弹）。
     */
    fun touchHost(host: Host) = viewModelScope.launch {
        store.update(host.id) { it.copy(lastConnectedAt = System.currentTimeMillis()) }
    }

    /**
     * 新建会话并返回其 id（UI 据此导航到终端页），并记录最近连接。解析跳板机（避免自引用）。
     *
     * 会话持久化=tmux 时自适应两条路：
     * - 已记住会话名 → 连接即按名附加（`new-session -A` 原子"存在则附加、否则创建"），零额外往返。
     * - 还没记住 → 先开普通登录壳，连上后侧通道探测，再弹选择器。**不**在连接前另开一条探测连接：
     *   那会多一次握手，还会让探测连接成为 TOFU 的首次信任（指纹静默入库）。
     */
    fun openSession(host: Host): String {
        touchHost(host)
        val remembered = host.tmuxSessionName
            .takeIf { host.persistence == SessionPersistence.TMUX && it.isNotBlank() }
        val route = routeOf(host.id)
        val ok = route as? Route.Ok
        val ts = sessions.open(
            host = ok?.host ?: host,
            jumpHost = ok?.jump,
            network = ok?.network,
            refusal = route.refusalText(getApplication()),
            remoteTmuxName = remembered,
            startupCommand = remembered?.let { Tmux.attachOrCreateCommand(it) },
        )
        ensureSessionService()
        if (remembered != null) {
            confirmTmuxAttach(ts, remembered)
        } else if (host.persistence == SessionPersistence.TMUX) {
            requestTmuxPickerWhenReady(ts)
        }
        return ts.id
    }

    /** 连接就绪后探测一次；确实装了 tmux 才弹选择器（未安装/失败都不打扰）。 */
    private fun requestTmuxPickerWhenReady(ts: TermSession) = viewModelScope.launch {
        refreshTmux(ts).join()
        if (ts.alive.value && ts.tmuxState.value.phase == TmuxPhase.READY) {
            _tmuxPicker.value = ts.id
        }
    }

    /** 连接时待弹选择器的会话 id（null=不弹）。 */
    private val _tmuxPicker = MutableStateFlow<String?>(null)
    val tmuxPicker: StateFlow<String?> = _tmuxPicker.asStateFlow()

    fun dismissTmuxPicker() { _tmuxPicker.value = null }

    /**
     * 从选择器选定一个会话名（已存在或新建都走同一条路——`new-session -A` 原子处理）。
     * 用独立连接附加，并关掉刚才那条临时登录壳，避免一台主机上挂两条连接。
     */
    fun pickTmuxSession(sourceId: String, name: String): String? {
        val source = sessions.get(sourceId) ?: return null
        _tmuxPicker.value = null
        val target = source.tmuxState.value.sessions.firstOrNull { it.name == name }
            ?: TmuxSession(id = "", name = name, windows = 0, clients = 0, created = 0L)
        val newId = openTmuxSession(source, target)
        if (newId != sourceId) sessions.close(sourceId)
        return newId
    }

    /** 复制会话：用同一主机再开一个独立连接，沿用来源的标题/前缀并加不重复标记。源不存在时返回 null。 */
    fun duplicateSession(id: String): String? {
        val src = sessions.get(id) ?: return null
        touchHost(src.host)
        val route = routeOf(src.host.id)
        val ok = route as? Route.Ok
        val newId = sessions.open(
            host = ok?.host ?: src.host,
            jumpHost = ok?.jump,
            network = ok?.network,
            refusal = route.refusalText(getApplication()),
            carryFrom = src,
        ).id
        ensureSessionService()
        return newId
    }

    /**
     * 这台主机现在怎么连（唯一入口，见 [resolveRoute]）：失效的跳板机或网络一律拒连，
     * 不再像过去那样把被删除的跳板机静默当成直连。
     */
    private fun routeOf(hostId: String): Route = resolveRoute(hostId, hostsState.value, networksState.value)

    /** 拉起前台服务：退后台/关屏时保活会话（服务在会话归零时自行停止）。 */
    private fun ensureSessionService() {
        val ctx = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MokeSessionService::class.java)) }
    }

    fun closeSession(id: String) = sessions.close(id)

    /** 一次清掉列表里所有已结束的会话（保留活着的）。 */
    fun closeEndedSessions() {
        sessions.sessions.value.filterNot { it.alive.value }.forEach { sessions.close(it.id) }
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
                if (st.host?.id == hostId && st.path == dir) filesController.refresh()
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
        val route = routeOf(host.id)
        val ok = route as? Route.Ok
        filesController.open(ok?.host ?: host, ok?.jump, ok?.network, from, route.refusalText(getApplication()))
    }

    fun closeFiles() = filesController.close()
    fun filesNavigate(path: String) = filesController.navigate(path)
    fun filesUp() = filesController.up()
    fun filesRefresh() = filesController.refresh()
    fun filesGoto(path: String) = filesController.goto(path)
    fun filesMkdir(name: String) = filesController.mkdir(name)
    fun filesClearError() = filesController.clearError()
    fun setFilesSort(s: FilesSort) = viewModelScope.launch { settings.setFilesSort(s) }
    fun setFilesShowHidden(on: Boolean) = viewModelScope.launch { settings.setFilesShowHidden(on) }

    /** 记住下载目录（已在 UI 侧取得持久化读写授权）。 */
    fun setDownloadTree(uri: String) = viewModelScope.launch { settings.setDownloadTreeUri(uri) }

    /** 待确认覆盖的上传（非空=显示确认弹窗）。 */
    private val _uploadConflict = MutableStateFlow<UploadConflict?>(null)
    val uploadConflict: StateFlow<UploadConflict?> = _uploadConflict.asStateFlow()

    /** 选好文件后先查远端是否已有同名：有就先问，没有就直接传。 */
    fun uploadHere(uris: List<android.net.Uri>) = viewModelScope.launch {
        if (filesState.value.host == null || filesState.value.path.isBlank()) return@launch
        val names = uris.map { displayNameOfUri(it) }
        val clash = filesController.existingNames(names)
        if (clash.isEmpty()) startUpload(uris) else _uploadConflict.value = UploadConflict(uris, clash)
    }

    fun confirmUploadOverwrite() {
        val pending = _uploadConflict.value ?: return
        _uploadConflict.value = null
        startUpload(pending.uris)
    }

    fun dismissUploadConflict() { _uploadConflict.value = null }

    private fun startUpload(uris: List<android.net.Uri>) {
        val host = filesState.value.host ?: return
        val dir = filesState.value.path.ifBlank { return }
        transfers.enqueueUpload(host, uris, dir)
        ensureTransferService()
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
    fun setScrollMode(m: ScrollMode) = viewModelScope.launch { settings.setScrollMode(m) }

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
        repairLegacyTmuxLoginCommands()
        clearDanglingJumpsOnce()
        checkUpdateSilently()
    }

    private companion object {
        /**
         * 附加确认的轮数（每轮间隔 1s）：要盖住"mosh 引导 + tmux 附加"的那几秒，又不至于让真的
         * 没附上的会话长时间挂着错误标注。
         */
        const val ATTACH_CONFIRM_ROUNDS = 8
    }
}
