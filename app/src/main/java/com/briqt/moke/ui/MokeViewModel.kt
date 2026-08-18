package com.briqt.moke.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.data.Host
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
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.data.UserFont
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

    /** 取本地化字符串（随应用语言）。 */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /** 多会话管理器：Application 作用域单例，跨导航/Activity 存活，配合前台服务后台保活。 */
    val sessions = (app as MokeApplication).sessions

    val hosts: StateFlow<List<Host>> = store.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** 应用明暗主题 / 动态取色 / 键盘模式 / 关闭会话二次确认。 */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keyboardMode: StateFlow<KeyboardMode> = settings.keyboardMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, KeyboardMode.SECURE)
    val scrollMode: StateFlow<ScrollMode> = settings.scrollMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScrollMode.SMART)
    val confirmCloseSession: StateFlow<Boolean> = settings.confirmCloseSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
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
                    entryHint = "", approxBytes = 0, userUploaded = true, note = str(R.string.note_local_import),
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
        if (hosts.value.none { it.id != host.id && it.host == host.host && it.port == host.port }) {
            knownHosts.forget(KnownHosts.idOf(host.host, host.port))
        }
    }

    private val knownHosts by lazy { KnownHosts(getApplication()) }

    /** 该主机已记录的指纹（null=还没记录）。 */
    fun savedFingerprint(host: Host): String? =
        knownHosts.stored(KnownHosts.idOf(host.host, host.port))

    /** 显式忘记指纹：服务器换过密钥时的自救路径（编辑页动作）。 */
    fun clearFingerprint(host: Host) {
        knownHosts.forget(KnownHosts.idOf(host.host, host.port))
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

        val deadline = System.currentTimeMillis() + if (retryUntilReady) 20_000L else 0L
        var out: String?
        do {
            out = runCatching { ts.transport.exec(Tmux.DISCOVER_CMD) }.getOrNull()
            if (out != null || !retryUntilReady || !currentCoroutineContext().isActive) break
            delay(1500)
        } while (System.currentTimeMillis() < deadline)

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
        val session = sessions.openTmux(source, target, resolveJump(source.host), detachOthers)
        ensureSessionService()
        rememberTmuxSession(source.host, target.name)
        confirmTmuxAttach(session, target.name)
        return session.id
    }

    /** 记住该主机上最后选择的 tmux 会话名：下次连接可直接按名附加，不再打扰用户选。 */
    private fun rememberTmuxSession(host: Host, name: String) = viewModelScope.launch {
        val current = hosts.value.firstOrNull { it.id == host.id } ?: host
        if (current.tmuxSessionName != name) {
            store.upsert(current.copy(tmuxSessionName = name), hosts.value)
        }
    }

    /**
     * 附加确认：发出 attach 命令 ≠ 附加成功。远端 tmux 缺失或启动失败时包装命令会回落成登录壳，
     * 此时若仍标成「当前 tmux 会话」，面板与顶栏就在撒谎。用源会话的侧通道数一下客户端：
     * 明确为 0 且新终端仍存活 → 判定未附上，清除关联。数不出来（null）视为"无法确认"，不动状态。
     */
    private fun confirmTmuxAttach(session: TermSession, name: String) =
        viewModelScope.launch(Dispatchers.IO) {
            // 用**该会话自己**的侧通道：源会话（临时登录壳）在选定后就被关掉了，拿它去问必然失败。
            // 传输要等 View 测量后才 start，所以给若干次重试；数不出来一律当"无法确认"，不动状态。
            repeat(8) {
                delay(1000)
                if (!session.alive.value) return@launch
                val count = Tmux.parseClientCount(
                    runCatching { session.transport.exec(Tmux.clientsCmd(name)) }.getOrNull()
                ) ?: return@repeat
                if (count > 0) {
                    session.tmuxAttached.value = true
                } else {
                    session.tmuxAttached.value = false
                    session.remoteTmuxId.value = null
                    session.remoteTmuxName.value = null
                }
                return@launch
            }
        }

    /** 重连时保留协议级启动命令；tmux 专用会话不能退回成普通 shell。 */
    fun reconnectSession(source: TermSession): String {
        touchHost(source.host)
        val session = sessions.open(
            host = source.host,
            jumpHost = resolveJump(source.host),
            initialTitle = source.displayTitle.value,
            remoteTmuxId = source.remoteTmuxId.value,
            remoteTmuxName = source.remoteTmuxName.value,
            startupCommand = source.startupCommand,
        )
        ensureSessionService()
        return session.id
    }

    /** 记录最近连接时间（用于"最近连接"排序）。 */
    fun touchHost(host: Host) = viewModelScope.launch {
        store.upsert(host.copy(lastConnectedAt = System.currentTimeMillis()), hosts.value)
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
        val ts = sessions.open(
            host = host,
            jumpHost = resolveJump(host),
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
        val newId = sessions.open(src.host, resolveJump(src.host), carryFrom = src).id
        ensureSessionService()
        return newId
    }

    /** 解析主机的跳板机（避免自引用；空/无效返回 null）。 */
    private fun resolveJump(host: Host): Host? = host.jumpHostId
        .takeIf { it.isNotBlank() && it != host.id }
        ?.let { id -> hosts.value.firstOrNull { it.id == id } }

    /** 拉起前台服务：退后台/关屏时保活会话（服务在会话归零时自行停止）。 */
    private fun ensureSessionService() {
        val ctx = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MokeSessionService::class.java)) }
    }

    fun closeSession(id: String) = sessions.close(id)

    // ---------- 文件（SFTP） ----------

    /** 传输队列：Application 作用域，退后台/关屏由 [MokeTransferService] 保活。 */
    val transfers = (app as MokeApplication).transfers.also { mgr ->
        // 记住的下载目录失效（被删/撤授权）时忘掉它，之后回到默认落点。
        mgr.onTreeUnusable = { viewModelScope.launch { settings.setDownloadTreeUri("") } }
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
        filesController.open(host, resolveJump(host), from)
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

    /**
     * 是否还得先问用户要一个下载目录。
     *
     * Android 10+ 有免权限写系统「下载」的通道，默认落 `下载/Moke`，一次都不问；更低版本没有
     * 这条路，只能让用户选一个目录（选完记住）。
     */
    val needsDownloadDir: StateFlow<Boolean> = downloadTreeUri
        .map { it.isBlank() && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }

    fun setKeyboardMode(m: KeyboardMode) = viewModelScope.launch { settings.setKeyboardMode(m) }
    fun setScrollMode(m: ScrollMode) = viewModelScope.launch { settings.setScrollMode(m) }

    fun setConfirmCloseSession(on: Boolean) = viewModelScope.launch { settings.setConfirmCloseSession(on) }

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
        checkUpdateSilently()
    }
}
