package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.TmuxTargetStamp
import com.briqt.moke.localized
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.net.needsLogin
import com.briqt.moke.net.recovery
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** The connection and scrollback outlive their currently attached Android view. */
class TermSession(
    val id: String,
    val host: Host,
    val controller: TerminalController,
    val session: TerminalSession,
    val transport: TerminalTransport,
    val jumpHost: Host?,
    val network: NetworkEntry?,
    val scopeKey: TmuxScope,
    val tmuxIntent: TmuxIntent,
    val title: StateFlow<String>,
    private val baseTitleState: MutableStateFlow<String>,
    val customTitle: MutableStateFlow<String?>,
    private val displayTitleState: MutableStateFlow<String>,
    val alive: StateFlow<Boolean>,
    val latency: StateFlow<Int?>,
    val startedAt: Long,
    val scripts: TmuxScripts,
    problemText: (TmuxProblem) -> String,
    val copyMark: String? = null,
    @Volatile var rememberEpoch: Long = 0L,
    val customPrefix: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    private val metadata = SessionMetadata(scopeKey, SessionDirectoryTarget(scopeKey, host.host, host.displayName))
    val directory: StateFlow<SessionDirectory?> = metadata.directory
    private val automaticPrefixState = MutableStateFlow("")
    private val automaticTitleState = MutableStateFlow(host.displayName)
    val automaticPrefix: StateFlow<String> = automaticPrefixState.asStateFlow()
    val automaticTitle: StateFlow<String> = automaticTitleState.asStateFlow()
    val displayTitle: StateFlow<String> = displayTitleState.asStateFlow()
    val baseTitle: String get() = baseTitleState.value
    @Volatile var lastActivityAt: Long = startedAt
    val tmuxState = MutableStateFlow(TmuxUiState())
    val dataReady = MutableStateFlow(false)
    val negotiatedTerm = MutableStateFlow<String?>(null)
    val connectFailed = MutableStateFlow(false)
    val recovery = MutableStateFlow<NetworkRecovery?>(null)
    val loginNetwork = MutableStateFlow<NetworkEntry?>(null)
    val forwards: PortForwards? = (transport as? ForwardCapable)?.let { PortForwards(it) }
    val tmuxCoordinator = TmuxCoordinator(
        scope = scopeKey,
        intent = tmuxIntent,
        scripts = scripts,
        bridge = object : TmuxCoordinator.Bridge {
            override suspend fun observe(command: String): String? = withContext(Dispatchers.IO) {
                transport.exec(command)
            }
            override suspend fun operation(command: String): String? = withContext(Dispatchers.IO) {
                (transport as? TmuxConnection)?.execTmuxOperation(command)
            }
        },
        ownerNonce = { (transport as? TmuxConnection)?.tmuxNonce },
        requireOwner = (transport as? TmuxConnection)?.tmuxOwnerRequired == true,
        dataReady = { dataReady.value },
        dataAlive = { alive.value },
        state = tmuxState,
        message = problemText,
    )

    fun setCustomTitle(title: String?) { customTitle.value = SessionTitlePolicy.userValue(title) }
    fun setCustomPrefix(prefix: String?) { customPrefix.value = SessionTitlePolicy.userValue(prefix) }
    fun setTitleOverrides(prefix: String?, title: String?) {
        setCustomPrefix(prefix)
        setCustomTitle(title)
    }

    internal fun onDirectory(rawUri: String) {
        metadata.update(tmuxState.value, alive.value)
        metadata.onDirectory(rawUri)
    }
    internal fun endMetadata() { metadata.end() }
    internal fun refreshAutomaticTitle() {
        val tmux = tmuxState.value
        metadata.update(tmux, alive.value)
        automaticPrefixState.value = SessionTitlePolicy.automaticPrefix(scopeKey, tmux, alive.value)
        val pane = tmux.currentPane?.takeIf { alive.value && it.binding.scope == scopeKey }
        val fallback = SessionTitlePolicy.fallback(host.displayName, directory.value)
        baseTitleState.value = fallback
        automaticTitleState.value = SessionTitlePolicy.automaticTitle(host.useMosh, pane?.pane?.title, title.value, fallback)
    }
    internal fun updateDisplayTitle(title: String) { displayTitleState.value = title }

    companion object {
        fun composeTitle(useMosh: Boolean, raw: String, custom: String?, base: String): String =
            SessionTitlePolicy.userValue(custom) ?: SessionTitlePolicy.oscTitle(useMosh, raw).ifEmpty { base }

        fun disambiguateTitle(base: String, custom: String?, mark: String?, hasCollision: Boolean): String =
            SessionTitlePolicy.mainTitle(base, custom, mark, hasCollision)
    }
}

/** Application-owned terminal connections, with no name/ID based guesses about their tmux client. */
class SessionManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scripts by lazy { TmuxScripts.load(appContext) }
    private val titleJobs = ConcurrentHashMap<String, Job>()
    private val networkEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val choiceTickets = TmuxChoiceTickets()
    val tmuxMemoryMutex = Mutex()
    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    fun open(
        host: Host,
        jumpHost: Host? = null,
        network: NetworkEntry? = null,
        refusal: String? = null,
        carryFrom: TermSession? = null,
        startupCommand: String? = null,
        tmuxIntent: TmuxIntent = TmuxIntent.None,
        rememberEpoch: Long = 0L,
        observeTmux: Boolean = false,
    ): TermSession {
        val id = UUID.randomUUID().toString()
        val nonce = id.replace("-", "")
        val actualIntent = if (refusal == null) tmuxIntent else TmuxIntent.None
        val command = if (refusal != null) null else startupCommand ?: when (actualIntent) {
            is TmuxIntent.Open -> TmuxRemote.open(actualIntent.target, actualIntent.takeOver)
            is TmuxIntent.Restore -> TmuxRemote.restore(actualIntent.name, socketPath = actualIntent.socketPath)
            TmuxIntent.None -> null
        }
        val scopeKey = TmuxScope(TmuxTargetStamp.of(host, jumpHost, network), id, networkEpoch(network?.id))
        val sameTarget = SessionTitlePolicy.sameTarget(carryFrom?.scopeKey, scopeKey,
            sourceTargetChanged = carryFrom?.tmuxState?.value?.targetChanged == true)
        val overrides = SessionTitlePolicy.inherit(carryFrom?.let {
            SessionTitleOverrides(it.customPrefix.value, it.customTitle.value)
        }, sameTarget)
        val base = host.displayName
        // New connections inherit user values only, never another terminal's OSC, directory or pane.
        val title = MutableStateFlow("")
        val custom = MutableStateFlow(overrides.title)
        val prefix = MutableStateFlow(overrides.prefix)
        val display = MutableStateFlow(SessionTitlePolicy.display(prefix.value, custom.value, "", base))
        val alive = MutableStateFlow(true)
        val latency = MutableStateFlow<Int?>(null)
        var created: TermSession? = null
        val controller = TerminalController(
            context = appContext,
            onFinished = {
                alive.value = false
                latency.value = null
                created?.endMetadata()
                created?.tmuxCoordinator?.dataEnded()
            },
            // An empty OSC clears the dynamic title; it must not freeze an obsolete fallback name.
            onTitle = { if (alive.value) title.value = it.orEmpty() },
            onDirectory = { created?.onDirectory(it) },
        )
        val onNetworkError: (NetworkException) -> Unit = { error ->
            created?.let {
                it.recovery.value = error.recovery
                it.loginNetwork.value = error.network.takeIf { error.needsLogin }
            }
        }
        val observer = nonce.takeIf { host.useMosh || actualIntent != TmuxIntent.None || observeTmux }
        val transport = when {
            refusal != null -> RefusedTransport(refusal)
            host.useMosh -> MoshTransport(
                host, appContext, jumpHost, command, network, onNetworkError,
                observerNonce = observer, tmuxScripts = scripts,
            )
            else -> SshTransport(
                host = host, context = appContext, jumpHost = jumpHost, network = network,
                onNetworkError = onNetworkError, onLatency = { latency.value = it }, startupCommand = command,
                observerNonce = observer, tmuxScripts = scripts, observeDefaultShell = observeTmux,
            )
        }
        val session = TerminalSession(transport, 2000, controller)
        val ts = TermSession(
            id = id, host = host, controller = controller, session = session, transport = transport,
            jumpHost = jumpHost, network = network,
            scopeKey = scopeKey,
            tmuxIntent = actualIntent, title = title.asStateFlow(), baseTitleState = MutableStateFlow(base),
            customTitle = custom, displayTitleState = display, alive = alive.asStateFlow(),
            latency = latency.asStateFlow(), startedAt = System.currentTimeMillis(), scripts = scripts,
            problemText = { appContext.tmuxProblemText(it) },
            copyMark = carryFrom?.takeIf { sameTarget }?.let { nextCopyMark(host.id) }, rememberEpoch = rememberEpoch,
            customPrefix = prefix,
        )
        created = ts
        session.sessionStatusText = object : TerminalSession.StatusText {
            override fun connectFailed(reason: String): String {
                ts.connectFailed.value = true
                if (ts.recovery.value == null) ts.recovery.value = NetworkRecovery(RecoveryKind.EDIT_HOST)
                return TerminalSession.statusText.connectFailed(reason)
            }
            override fun sessionEnded(exitCode: Int): String = TerminalSession.statusText.sessionEnded(exitCode)
        }
        val autoPorts = PortForwards.parsePorts(host.forwardPorts).ports
        val onUp: () -> Unit = {
            ts.dataReady.value = true
            if (observer != null) scope.launch {
                ts.tmuxCoordinator.refresh()
                ts.negotiatedTerm.value = ts.tmuxState.value.snapshot?.observation?.term
            }
            if (autoPorts.isNotEmpty()) scope.launch(Dispatchers.IO) {
                autoPorts.forEach { ts.forwards?.start(it) }
            }
        }
        when (transport) {
            is SshTransport -> transport.onEstablished = onUp
            is MoshTransport -> transport.onEstablished = onUp
        }
        controller.onAlert = { text, body -> TerminalAlerts.post(appContext, ts.id, ts.displayTitle.value, text, body) }
        controller.onActivity = { ts.lastActivityAt = System.currentTimeMillis() }
        _sessions.update { it + ts }
        titleJobs[id] = merge(title.map { Unit }, custom.map { Unit }, prefix.map { Unit },
            ts.tmuxState.map { Unit }, alive.map { Unit }, ts.directory.map { Unit })
            .onEach { ts.refreshAutomaticTitle(); refreshDisplayTitles() }.launchIn(scope)
        refreshDisplayTitles()
        return ts
    }

    /** A candidate is not returned to the UI until its coordinator freshly validates its client. */
    fun findReusable(hostId: String, targetStamp: String, target: TmuxSessionRef): TermSession? = _sessions.value.firstOrNull { candidate ->
        TmuxLaunchPolicy.canReuse(hostId, targetStamp, target, candidate.host.id,
            candidate.alive.value, candidate.tmuxState.value)
    }

    fun openTmux(
        source: TermSession,
        target: TmuxSessionRef,
        jumpHost: Host? = null,
        network: NetworkEntry? = null,
        refusal: String? = null,
        detachOthers: Boolean = false,
        host: Host = source.host,
        rememberEpoch: Long = 0L,
    ): TermSession = open(
        host = host, jumpHost = jumpHost, network = network, refusal = refusal,
        tmuxIntent = TmuxIntent.Open(target, detachOthers), rememberEpoch = rememberEpoch,
    ).also { it.negotiatedTerm.value = source.negotiatedTerm.value }

    /** Only metadata for the exact witnessed incarnation may be shared between local clients. */
    fun renamed(targetStamp: String, target: TmuxSessionRef, newName: String) {
        _sessions.value.filter { it.scopeKey.targetStamp == targetStamp }.forEach { local ->
            local.tmuxCoordinator.renamed(target, newName)
        }
    }

    fun nextTmuxChoice(hostId: String): Long = choiceTickets.issue(hostId)

    fun isCurrentTmuxChoice(session: TermSession): Boolean = choiceTickets.isCurrent(session.host.id, session.rememberEpoch)

    fun claimTmuxChoice(session: TermSession, epoch: Long): Boolean = choiceTickets.claim(session.host.id, epoch) {
        session.rememberEpoch = epoch
    }

    fun networkEpoch(id: String?): Long = id?.let { networkEpochs[it]?.get() } ?: 0L

    fun invalidateTmuxNetwork(id: String) {
        networkEpochs.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
        val affected = _sessions.value.filter { it.network?.id == id }
        affected.forEach { session ->
            choiceTickets.invalidate(session.host.id, session.rememberEpoch)
            session.tmuxState.update { it.copy(targetChanged = true, canStartNewConnection = false) }
            // A not-yet-established transport owns no old live terminal to preserve. Stop it now:
            // otherwise a delayed View measurement could bootstrap its old intent on a new identity.
            if (!session.dataReady.value) close(session.id)
        }
    }

    private fun refreshDisplayTitles() {
        val list = _sessions.value
        val counts = list.groupingBy {
            it.scopeKey.targetStamp to (SessionTitlePolicy.userValue(it.customTitle.value) ?: it.automaticTitle.value)
        }.eachCount()
        list.forEach { item ->
            item.updateDisplayTitle(SessionTitlePolicy.display(item.customPrefix.value, item.customTitle.value,
                item.automaticPrefix.value, item.automaticTitle.value, item.copyMark,
                (counts[item.scopeKey.targetStamp to item.automaticTitle.value] ?: 0) > 1))
        }
    }

    private fun nextCopyMark(hostId: String): String {
        val used = _sessions.value.filter { it.host.id == hostId }
            .mapNotNull { it.copyMark?.removeSurrounding("(", ")")?.toIntOrNull() }.toSet()
        var number = 2
        while (number in used) number++
        return "($number)"
    }

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    fun reorder(orderedIds: List<String>) {
        _sessions.update { list ->
            val byId = list.associateBy { it.id }
            (orderedIds.mapNotNull(byId::get) + list.filterNot { it.id in orderedIds })
                .takeIf { it.size == list.size } ?: list
        }
    }

    fun close(id: String) {
        val session = get(id) ?: return
        session.endMetadata()
        session.tmuxCoordinator.close()
        titleJobs.remove(id)?.cancel()
        session.forwards?.let { scope.launch(Dispatchers.IO) { it.stopAll() } }
        session.controller.onAlert = null
        TerminalAlerts.cancel(appContext, id)
        runCatching { session.session.finishIfRunning() }
        _sessions.update { list -> list.filterNot { it.id == id } }
        refreshDisplayTitles()
    }
}

private fun Context.tmuxProblemText(problem: TmuxProblem): String = localized(when (problem) {
    TmuxProblem.CONNECTION_ENDED -> R.string.tmux_disconnected
    TmuxProblem.PROBE_UNAVAILABLE -> R.string.tmux_control_unavailable
    TmuxProblem.INVALID_RESPONSE -> R.string.tmux_invalid_response
    TmuxProblem.IDENTITY_UNKNOWN -> R.string.tmux_current_unknown
    TmuxProblem.TARGET_CHANGED -> R.string.tmux_target_changed
    TmuxProblem.OBJECT_CHANGED -> R.string.tmux_session_changed
    TmuxProblem.ACTION_FAILED -> R.string.tmux_action_failed
    TmuxProblem.RESULT_UNKNOWN -> R.string.tmux_result_unknown
    TmuxProblem.NAME_EXISTS -> R.string.tmux_name_exists
    TmuxProblem.NOT_INSTALLED -> R.string.tmux_not_installed
    TmuxProblem.REMOTE_ENDED -> R.string.tmux_remote_ended
})
