package com.briqt.moke.ui

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.localized
import com.briqt.moke.data.FilesSort
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.TmuxTargetStamp
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.net.connectFailureText
import com.briqt.moke.net.networkRecovery
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.TmuxBinding
import com.briqt.moke.terminal.TmuxConnection
import com.briqt.moke.terminal.TmuxCoordinatorRules
import com.briqt.moke.terminal.TmuxProbeKind
import com.briqt.moke.terminal.TmuxRemote
import com.briqt.moke.terminal.TmuxScope
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.terminal.sameInstance
import com.briqt.moke.terminal.sftp.RemoteEntry
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.SftpSession
import com.briqt.moke.terminal.sftp.UploadDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Captured before the document picker opens. No generated toString may expose connection secrets. */
class UploadTarget internal constructor(
    val host: Host,
    val jumpHost: Host?,
    val network: NetworkEntry?,
    val directory: String,
    val destination: UploadDestination,
    internal val ticket: FilesWork.Ticket<SftpSession>,
)

class UploadInspection internal constructor(val target: UploadTarget, val names: List<String>, val clashes: List<String>)
data class UploadConflict(val uris: List<android.net.Uri>, val names: List<String>, val inspection: UploadInspection? = null)

/** A failure never replaces an already displayed directory with a fictional empty listing. */
data class FilesUiState(
    val host: Host? = null,
    val path: String = "",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val notice: String = "",
    val terminalPath: String = "",
    val loginNetwork: NetworkEntry? = null,
    val recovery: NetworkRecovery? = null,
    /** A lazy browsing connection is attached; this does not imply that SSH has connected yet. */
    val browsingActive: Boolean = false,
) {
    val canUpload: Boolean get() = browsingActive && !loading && path.isNotBlank() && error.isBlank()
}

/** One independent SFTP connection per open page. Transfers own separate connections. */
class FilesController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private val work = FilesWork<SftpSession>()
    private var job: Job? = null
    private data class Configuration(val host: Host, val jump: Host?, val network: NetworkEntry?, val refusal: String?, val recovery: NetworkRecovery?) {
        val targetStamp: String = TmuxTargetStamp.of(host, jump, network)
    }
    private var configuration: Configuration? = null
    val isPaused: Boolean get() = _state.value.host != null && !work.isAttached()
    val canAutoRefresh: Boolean get() = work.isAttached() && _state.value.canUpload

    fun open(
        host: Host, jumpHost: Host?, network: NetworkEntry?, from: TermSession?,
        refusal: String? = null, refusalRecovery: NetworkRecovery? = null,
    ) {
        close()
        connect(Configuration(host, jumpHost, network, refusal, refusalRecovery), from, preserve = false)
    }

    /** Caller resolves the route again before retrying; never silently reuse a deleted network. */
    fun retry(
        host: Host, jumpHost: Host?, network: NetworkEntry?, refusal: String? = null,
        refusalRecovery: NetworkRecovery? = null,
    ) {
        val preserve = _state.value.host?.id == host.id
        disconnect()
        connect(Configuration(host, jumpHost, network, refusal, refusalRecovery), null, preserve)
    }

    /** Reattach after returning from recovery, without replaying listing or any failed operation. */
    fun resume(
        host: Host, jumpHost: Host?, network: NetworkEntry?, refusal: String? = null,
        refusalRecovery: NetworkRecovery? = null,
    ) {
        val previous = _state.value
        if (previous.host?.id != host.id) return
        val config = Configuration(host, jumpHost, network, refusal, refusalRecovery)
        if (configuration == config && work.isAttached()) return
        disconnect()
        configuration = config
        _state.value = previous.copy(
            host = host, loading = false, error = refusal.orEmpty(),
            recovery = refusalRecovery.takeIf { refusal != null },
            loginNetwork = refusalRecovery?.takeIf { refusal != null && it.kind == RecoveryKind.LOGIN_NETWORK }?.network,
            browsingActive = false,
        )
        if (refusal == null) {
            val ticket = work.attach(SftpSession(host, jumpHost, appContext, network))
            work.commit(ticket) { _state.update { it.copy(browsingActive = true) } }
        }
    }

    private fun connect(config: Configuration, from: TermSession?, preserve: Boolean) {
        configuration = config
        val previous = if (preserve) _state.value else FilesUiState()
        _state.value = previous.copy(
            host = config.host, loading = config.refusal == null, error = config.refusal.orEmpty(), browsingActive = false,
            recovery = if (config.refusal != null) config.recovery else null,
            loginNetwork = config.recovery?.takeIf { config.refusal != null && it.kind == RecoveryKind.LOGIN_NETWORK }?.network,
        )
        if (config.refusal != null) return
        val s = SftpSession(config.host, config.jump, appContext, config.network)
        val ticket = work.attach(s)
        work.commit(ticket) { _state.update { it.copy(browsingActive = true) } }
        job = scope.launch {
            operation(ticket, { connection ->
                val terminalPath = from?.let { probeTerminalPath(it, connection, config.targetStamp) } ?: previous.terminalPath
                val start = previous.path.ifBlank { terminalPath.ifBlank { connection.homePath() } }
                Triple(start, connection.list(start), terminalPath)
            }) { state, (start, entries, terminalPath) ->
                state.copy(path = start, entries = entries, terminalPath = terminalPath)
            }
        }
    }

    fun navigate(path: String) {
        val ticket = beginOperation() ?: return
        job = scope.launch {
            operation(ticket, { connection ->
                val target = path.ifBlank { connection.homePath() }
                target to connection.list(target)
            }) { state, (target, entries) -> state.copy(path = target, entries = entries) }
        }
    }

    /** A background refresh must never resurrect a paused connection using an old configuration. */
    fun refresh() {
        if (canAutoRefresh) navigate(_state.value.path)
    }
    fun up() = navigate(RemotePath.parent(_state.value.path))

    suspend fun mkdir(name: String): Boolean {
        val cleaned = name.trim()
        if (!validDirectoryName(name)) return false
        val dir = _state.value.path.ifBlank { return false }
        val ticket = beginOperation() ?: return false
        return try {
            val listing = withContext(Dispatchers.IO) {
                work.execute(ticket) { s ->
                    s.mkdir(RemotePath.join(dir, cleaned))
                    // The mutation succeeded. A failed refresh must not invite a duplicate mkdir.
                    runCatching { s.list(dir) }
                }
            }
            if (listing.isFailure) {
                fail(ticket, listing.exceptionOrNull()!!)
                work.current(ticket)
            } else work.commit(ticket) {
                _state.update { it.copy(entries = listing.getOrThrow(), loading = false, notice = ticket.page.connection.consumeNotice().orEmpty()) }
            }
        } catch (t: CancellationException) { throw t }
        catch (t: Throwable) { fail(ticket, t); false }
    }

    /** Canonicalize and list in one operation; failure leaves both the directory and prompt intact. */
    suspend fun goto(raw: String): Boolean {
        val cleaned = raw.trim()
        if (!validRemotePath(raw)) return false
        val ticket = beginOperation() ?: return false
        return operation(ticket, { s ->
            val target = s.canonicalize(cleaned)
            target to s.list(target)
        }) { state, (target, entries) -> state.copy(path = target, entries = entries) }
    }

    private fun beginOperation(): FilesWork.Ticket<SftpSession>? {
        job?.cancel()
        job = null
        val ticket = work.begin() ?: return null
        work.commit(ticket) { _state.update { it.copy(loading = true, error = "", notice = "", loginNetwork = null, recovery = null) } }
        return ticket
    }

    private suspend fun <T> operation(
        ticket: FilesWork.Ticket<SftpSession>, action: (SftpSession) -> T,
        success: (FilesUiState, T) -> FilesUiState,
    ): Boolean = try {
        val result = withContext(Dispatchers.IO) { work.execute(ticket, action) }
        work.commit(ticket) {
            val notice = ticket.page.connection.consumeNotice().orEmpty()
            _state.update { success(it, result).copy(loading = false, error = "", notice = notice, loginNetwork = null, recovery = null) }
        }
    } catch (t: CancellationException) { throw t }
    catch (t: Throwable) { fail(ticket, t); false }

    /** One error exit for open, navigation, path input and mutation failures. */
    private fun fail(ticket: FilesWork.Ticket<SftpSession>, t: Throwable) {
        val recovery = t.networkRecovery()
        work.commit(ticket) {
            val notice = ticket.page.connection.consumeNotice()
            val base = if (t is InvalidUploadNames) appContext.localized(R.string.files_upload_name_invalid)
                else t.connectFailureText(appContext).ifBlank { t.javaClass.simpleName }
            _state.update { it.copy(
                loading = false, error = if (notice.isNullOrBlank()) base else "$notice\n$base", notice = "",
                recovery = recovery, loginNetwork = recovery?.takeIf { it.kind == RecoveryKind.LOGIN_NETWORK }?.network,
            ) }
        }
    }

    fun clearError() = _state.update { it.copy(error = "", loginNetwork = null, recovery = null) }

    /** Capture before launching the document picker; returning null grants no upload permission. */
    fun captureUploadTarget(): UploadTarget? {
        val ticket = work.snapshot() ?: return null
        var target: UploadTarget? = null
        work.commit(ticket) {
            val state = _state.value
            val config = configuration
            if (state.canUpload && config != null && config.refusal == null && state.host?.id == config.host.id) {
                target = UploadTarget(config.host, config.jump, config.network, state.path,
                    UploadDestination.from(config.host, config.jump, config.network), ticket)
            }
        }
        return target
    }

    fun isUploadTargetCurrent(target: UploadTarget): Boolean {
        var current = false
        work.commit(target.ticket) {
            val state = _state.value
            val config = configuration
            current = state.canUpload && config != null && config.refusal == null &&
                state.host?.id == target.host.id && state.path == target.directory &&
                UploadDestination.from(config.host, config.jump, config.network) == target.destination
        }
        return current
    }

    /** No catch-to-empty fallback: missing files and failed/obsolete inspections are distinct. */
    suspend fun inspectUpload(target: UploadTarget, names: List<String>): UploadInspection? {
        if (!isUploadTargetCurrent(target)) return null
        val captured = names.toList()
        return try {
            val clashes = withContext(Dispatchers.IO) {
                work.inspectNames(target.ticket, captured) { s, name -> s.stat(RemotePath.join(target.directory, name)) != null }
            } ?: return null
            if (!isUploadTargetCurrent(target)) null else UploadInspection(target, captured, clashes)
        } catch (t: CancellationException) { throw t }
        catch (t: Throwable) { fail(target.ticket, t); null }
    }

    private fun detach(): FilesWork.Page<SftpSession>? {
        job?.cancel()
        job = null
        return work.detachPage()
    }
    private fun disconnect() {
        detach()?.let { retired -> scope.launch(Dispatchers.IO) { runCatching { retired.connection.close() } } }
    }

    /** Await the browsing lease's retirement, including resources installed by late blocking IO. */
    suspend fun pauseForRecovery() {
        val retired = detach()
        _state.update { it.copy(loading = false, browsingActive = false) }
        if (retired != null) withContext(NonCancellable + Dispatchers.IO) { work.closeAndDrain(retired) }
    }

    fun close() {
        disconnect()
        configuration = null
        _state.value = FilesUiState()
    }

    fun sorted(entries: List<RemoteEntry>, sort: FilesSort, showHidden: Boolean): List<RemoteEntry> {
        val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        val cmp = when (sort) {
            FilesSort.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
            FilesSort.TIME -> compareByDescending { it.mtime }
            FilesSort.SIZE -> compareByDescending { it.size }
        }
        return visible.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.then(cmp))
    }

    private fun probeTerminalPath(from: TermSession, s: SftpSession, actualTargetStamp: String): String {
        if (!from.dataReady.value || !from.alive.value) return ""
        val binding = TerminalPathProbe.capture(from.scopeKey, actualTargetStamp, from.tmuxState.value) ?: return ""
        val connection = from.transport as? TmuxConnection ?: return ""
        val nonce = connection.tmuxNonce?.takeIf { it == binding.owner.nonce } ?: return ""
        val path = runCatching {
            val command = TmuxRemote.guardPeer(from.scripts, nonce, connection.tmuxOwnerRequired, binding.server.bootId,
                TmuxRemote.paneCwd(binding.session, binding.client))
            from.transport.exec(command)?.let(TmuxRemote::parsePaneCwd)
        }.getOrNull() ?: return ""
        fun stillCurrent(): Boolean = from.dataReady.value && from.alive.value && connection.tmuxNonce == nonce &&
            TerminalPathProbe.stillCurrent(binding, TerminalPathProbe.capture(from.scopeKey, actualTargetStamp, from.tmuxState.value))
        if (!stillCurrent()) return ""
        val canonical = runCatching { s.canonicalize(path) }.getOrDefault(path)
        return canonical.takeIf { stillCurrent() }.orEmpty()
    }
}

/** A directory hint may cross connections only within the exact source route and witnessed object. */
internal object TerminalPathProbe {
    fun capture(source: TmuxScope, actualTargetStamp: String, state: TmuxUiState): TmuxBinding? {
        if (source.targetStamp != actualTargetStamp || state.targetChanged || !state.canManage) return null
        val snapshot = state.snapshot ?: return null
        val binding = state.currentBinding ?: return null
        if (snapshot.scope != source || binding.scope != source || snapshot.observation.kind != TmuxProbeKind.READY) return null
        // Reuse the coordinator's witness validation rather than add a weaker file-browser variant.
        if (!TmuxCoordinatorRules.verified(snapshot.observation, binding.owner.nonce, true, binding.server.socketPath)) return null
        val observed = TmuxCoordinatorRules.binding(source, snapshot.observation)
        return binding.takeIf { stillCurrent(it, observed) }
    }

    fun stillCurrent(captured: TmuxBinding, current: TmuxBinding?): Boolean = current != null &&
        captured.scope == current.scope && captured.owner == current.owner && captured.client == current.client &&
        captured.session.sameInstance(current.session)
}
