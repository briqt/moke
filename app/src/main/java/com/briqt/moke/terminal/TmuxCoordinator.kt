package com.briqt.moke.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// type: tmux coordinator

enum class TmuxProblem {
    CONNECTION_ENDED, PROBE_UNAVAILABLE, INVALID_RESPONSE, IDENTITY_UNKNOWN, TARGET_CHANGED,
    OBJECT_CHANGED, ACTION_FAILED, RESULT_UNKNOWN, NAME_EXISTS, NOT_INSTALLED, REMOTE_ENDED,
    PLATFORM_UNSUPPORTED, OWNER_UNAVAILABLE,
}

/**
 * Owns one terminal's immutable source scope. Bridge implementations must execute on that source's
 * transport, not resolve a newer route. No mutation is retried, and observing never launches tmux.
 * The optional parser is a test seam; parsed observations still pass the production witness checks.
 */
class TmuxCoordinator(
    val scope: TmuxScope,
    val intent: TmuxIntent,
    val scripts: TmuxScripts,
    val bridge: Bridge,
    private val ownerNonce: () -> String?,
    private val requireOwner: Boolean,
    private val dataReady: () -> Boolean,
    private val dataAlive: () -> Boolean,
    val state: MutableStateFlow<TmuxUiState>,
    private val message: (TmuxProblem) -> String,
    private val clockNanos: () -> Long = System::nanoTime,
    private val observationParser: (String, String) -> TmuxObservation? = TmuxRemote::parseObservation,
    private val observerCapability: () -> TmuxObserverCapability? = { null },
) {
    interface Bridge {
        suspend fun observe(command: String): String?
        suspend fun operation(command: String): String?
    }

    // IO is serialized, but lifecycle transitions never wait for a network response. The monitor
    // also makes an EOF/close racing with publication atomic, including on different JVM threads.
    private val io = Mutex()
    private val lifecycle = Any()
    private var closed = false
    private var dataTerminated = false
    private var readEpoch = 0L
    private var generation = state.value.snapshot?.takeIf { it.scope == scope }?.generation ?: 0L
    private var pinnedSocket = state.value.lastBinding?.takeIf { it.scope == scope }?.server?.socketPath
        ?: when (intent) {
            is TmuxIntent.Open -> intent.target.server.socketPath
            is TmuxIntent.Restore -> intent.socketPath
            TmuxIntent.None -> null
        }
        ?: state.value.snapshot?.takeIf { it.scope == scope && it.verified }?.observation?.let {
            it.server?.socketPath ?: it.defaultSocketPath
        }
    private var pinnedPeer = state.value.lastBinding?.takeIf { it.scope == scope }?.server?.bootId
        ?: (intent as? TmuxIntent.Open)?.target?.server?.bootId
        ?: state.value.snapshot?.takeIf { it.scope == scope && it.verified }?.observation?.peerBootId

    init {
        state.update { previous ->
            val oldSnapshot = previous.snapshot?.scope?.let { it != scope } == true
            val endedBinding = (previous.presence as? TmuxPresence.Ended)?.lastBinding
            val oldBinding = listOfNotNull(previous.currentBinding, previous.lastBinding, endedBinding).any { it.scope != scope }
            if (oldSnapshot || oldBinding) previous.copy(phase = TmuxPhase.IDLE, sessions = emptyList(),
                snapshot = null, presence = TmuxPresence.Pending, lastBinding = null, restoreMissing = false,
                busy = false, refreshing = false, message = null) else previous
        }
    }

    private data class ReadTicket(
        val generation: Long,
        val epoch: Long,
        val nonce: String?,
        val socket: String?,
        val previousServer: TmuxServerId?,
    )
    private data class Fresh(val snapshot: TmuxSnapshot, val ticket: ReadTicket)
    private enum class Mutation { RENAME, DETACH, KILL }

    /** Timer refreshes coalesce: an in-flight read/action consumes all overlapping timer ticks. */
    suspend fun refresh(): TmuxUiState {
        if (!io.tryLock()) return state.value
        try {
            readLocked()
            return state.value
        } finally {
            io.unlock()
        }
    }

    /** A later harmless generation is fine; the frozen server/id/birth and client must still match. */
    suspend fun validateSelection(selection: TmuxSelection, requireCurrent: Boolean = false): Boolean = io.withLock {
        if (!checkIssued(selection)) return@withLock false
        val fresh = readLocked() ?: return@withLock false
        synchronized(lifecycle) {
            if (!freshStillLive(fresh)) return@synchronized false
            checkFreshSelection(selection, fresh.snapshot, requireCurrent)
        }
    }

    suspend fun create(name: String): TmuxSessionRef? = io.withLock {
        if (!beginAction(create = true)) return@withLock null
        try {
            val fresh = readLocked() ?: return@withLock null
            val command = try {
                val observation = fresh.snapshot.observation
                TmuxRemote.create(name, observation.server?.socketPath ?: observation.defaultSocketPath)
            } catch (_: IllegalArgumentException) {
                actionFailure(TmuxProblem.ACTION_FAILED)
                return@withLock null
            }
            val guarded = synchronized(lifecycle) {
                if (!freshStillLive(fresh) || state.value.targetChanged || state.value.restoreMissing ||
                    state.value.presence is TmuxPresence.Ended) return@synchronized null
                guard(fresh, command)
            } ?: return@withLock null
            val result = operate(guarded) ?: return@withLock null
            synchronized(lifecycle) {
                if (closed) return@synchronized null
                val created = result.created
                val observed = fresh.snapshot.observation
                val expectedSocket = observed.server?.socketPath ?: observed.defaultSocketPath
                if (created == null || !TmuxCoordinatorRules.strongServer(created.server) ||
                    created.server.bootId != observed.peerBootId || created.server.socketPath != expectedSocket ||
                    (observed.server != null && created.server != observed.server) ||
                    !TmuxCoordinatorRules.validRef(created) || created.name != name
                ) {
                    actionFailure(TmuxProblem.RESULT_UNKNOWN)
                    return@synchronized null
                }
                // CREATED proves creation, not attachment. A subsequent observation owns list counts
                // and binding; do not invent windows/clients from the reduced CREATED reference.
                state.update { it.copy(snapshot = it.snapshot?.copy(verified = false), message = null) }
                created
            }
        } finally {
            finishAction()
        }
    }

    suspend fun rename(selection: TmuxSelection, name: String): Boolean = mutate(selection, Mutation.RENAME, name)
    suspend fun detach(selection: TmuxSelection): Boolean = mutate(selection, Mutation.DETACH)
    suspend fun kill(selection: TmuxSelection): Boolean = mutate(selection, Mutation.KILL)

    /** Apply another client's acknowledged rename only to an already witnessed exact object. */
    fun renamed(target: TmuxSessionRef, newName: String) = synchronized(lifecycle) {
        if (closed || !TmuxCoordinatorRules.validRef(target.copy(name = newName))) return@synchronized
        val previous = state.value
        fun known(binding: TmuxBinding?) = binding?.scope == scope && TmuxCoordinatorRules.sameObject(binding.session, target)
        val snapshot = previous.snapshot?.takeIf { it.scope == scope && it.observation.server == target.server }
        val knownSession = snapshot?.observation?.sessions?.singleOrNull { it.id == target.id && it.created == target.created }
        if (knownSession == null && !known(previous.currentBinding) && !known(previous.lastBinding) &&
            !known((previous.presence as? TmuxPresence.Ended)?.lastBinding)) return@synchronized
        // The ACK is newer than every read already in flight. Publish its metadata atomically
        // with invalidating those tickets, without cancelling an independently submitted operation.
        ++readEpoch
        val nextGeneration = ++generation
        state.update { current ->
            fun renamed(binding: TmuxBinding?): TmuxBinding? = binding?.let {
                if (known(it)) it.copy(session = it.session.copy(name = newName)) else it
            }
            val updated = current.snapshot?.let { cached ->
                if (cached.scope != scope || cached.observation.server != target.server) cached else {
                    val sessions = cached.observation.sessions.map { session ->
                        if (session.id == target.id && session.created == target.created) session.copy(name = newName) else session
                    }
                    // A merged rename generation did not re-observe any pane. Never rebadge an
                    // in-flight/previous pane sample with this newer generation.
                    cached.copy(generation = nextGeneration, observation = cached.observation.copy(sessions = sessions,
                        clients = cached.observation.clients.map { it.copy(pane = null) }))
                }
            }
            current.copy(snapshot = updated,
                sessions = if (updated?.scope == scope && updated.observation.server == target.server)
                    updated.observation.sessions else current.sessions,
                presence = when (val presence = current.presence) {
                    is TmuxPresence.Attached -> presence.copy(binding = renamed(presence.binding)!!)
                    is TmuxPresence.Ended -> presence.copy(lastBinding = renamed(presence.lastBinding))
                    else -> presence
                }, lastBinding = renamed(current.lastBinding))
        }
    }

    /** EOF invalidates reads, not an already submitted independent control operation's ACK. */
    fun dataEnded() = synchronized(lifecycle) {
        if (!closed) endData()
    }

    /** Manual disposal invalidates both reads and operations, without cancelling Bridge's work. */
    fun close() = synchronized(lifecycle) {
        if (!closed) {
            endData()
            closed = true
            state.update { it.copy(busy = false, refreshing = false) }
        }
    }

    fun reconnectIntent(): TmuxIntent = synchronized(lifecycle) {
        intent.forReconnect(state.value.lastBinding?.takeIf { it.scope == scope })
    }

    private suspend fun mutate(selection: TmuxSelection, kind: Mutation, name: String? = null): Boolean = io.withLock {
        if (!checkIssued(selection) || !beginAction()) return@withLock false
        try {
            val fresh = readLocked() ?: return@withLock false
            val target = selection.session // Never follow a switched client to a different session.
            val command = try {
                when (kind) {
                    Mutation.RENAME -> TmuxRemote.rename(target, requireNotNull(name))
                    Mutation.DETACH -> TmuxRemote.detach(target)
                    Mutation.KILL -> TmuxRemote.kill(target)
                }
            } catch (_: IllegalArgumentException) {
                actionFailure(TmuxProblem.ACTION_FAILED)
                return@withLock false
            }
            var binding: TmuxBinding? = null
            val guarded = synchronized(lifecycle) {
                if (!freshStillLive(fresh) || !checkFreshSelection(selection, fresh.snapshot, false)) return@synchronized null
                binding = state.value.currentBinding?.takeIf { TmuxCoordinatorRules.sameObject(it.session, target) }
                guard(fresh, command)
            } ?: return@withLock false
            val result = operate(guarded) ?: return@withLock false
            synchronized(lifecycle) {
                if (closed) return@synchronized false
                if (result.created != null) {
                    actionFailure(TmuxProblem.RESULT_UNKNOWN)
                    return@synchronized false
                }
                applyMutation(kind, target, name, binding)
                true
            }
        } finally {
            finishAction()
        }
    }

    private fun beginAction(create: Boolean = false): Boolean = synchronized(lifecycle) {
        if (closed || dataTerminated ||
            (create && (state.value.restoreMissing || state.value.presence is TmuxPresence.Ended))) return@synchronized false
        if (create && state.value.targetChanged) {
            state.update { it.copy(message = message(TmuxProblem.TARGET_CHANGED)) }
            return@synchronized false
        }
        state.update { it.copy(busy = true, message = if (it.restoreMissing) it.message else null) }
        true
    }

    private fun finishAction() = synchronized(lifecycle) {
        if (!closed) state.update { it.copy(busy = false) }
    }

    private fun checkIssued(selection: TmuxSelection): Boolean = synchronized(lifecycle) {
        if (closed || dataTerminated) return@synchronized false
        val issuedGeneration = state.value.snapshot?.takeIf { it.scope == scope }?.generation ?: 0L
        val valid = selection.scope == scope && selection.generation in 1..issuedGeneration
        if (!valid) state.update { it.copy(message = message(TmuxProblem.OBJECT_CHANGED)) }
        valid
    }

    private fun checkFreshSelection(selection: TmuxSelection, snapshot: TmuxSnapshot, requireCurrent: Boolean): Boolean {
        val valid = TmuxCoordinatorRules.selectionMatches(selection, scope, snapshot, state.value.currentBinding, requireCurrent)
        if (!valid) state.update { it.copy(message = message(TmuxProblem.OBJECT_CHANGED)) }
        return valid
    }

    private fun freshStillLive(fresh: Fresh): Boolean {
        if (closed || dataTerminated || fresh.ticket.epoch != readEpoch) return false
        if (!dataReady()) return false
        if (!dataAlive()) { endData(); return false }
        if (ownerNonce() != fresh.ticket.nonce) {
            uncertain(TmuxProblem.IDENTITY_UNKNOWN)
            return false
        }
        return fresh.snapshot.scope == scope && fresh.snapshot.verified
    }

    private fun guard(fresh: Fresh, command: String): String = TmuxRemote.guardPeer(
        scripts, fresh.ticket.nonce, requireOwner, fresh.snapshot.observation.peerBootId, command,
    )

    private suspend fun readLocked(): Fresh? {
        val ticket = synchronized(lifecycle) {
            // Missing remote targets may leave a live fallback shell. Only terminal lifecycle
            // termination, not the last presence label, makes later observations inadmissible.
            if (closed || dataTerminated) return@synchronized null
            if (!dataReady()) {
                uncertain(TmuxProblem.PROBE_UNAVAILABLE, pending = true)
                return@synchronized null
            }
            if (!dataAlive()) { endData(); return@synchronized null }
            when (observerCapability()) {
                TmuxObserverCapability.Unsupported -> {
                    uncertain(TmuxProblem.PLATFORM_UNSUPPORTED, reason = TmuxUnknownReason.UNSUPPORTED)
                    return@synchronized null
                }
                TmuxObserverCapability.Failure -> if (requireOwner) {
                    // Mosh cannot recover a missing lifetime carrier by observing over SSH.
                    uncertain(TmuxProblem.OWNER_UNAVAILABLE, reason = TmuxUnknownReason.RECONNECT_REQUIRED)
                    return@synchronized null
                }
                // SSH's startup preflight may have failed transiently. A fresh ownerless
                // observation can still prove a management list, never a current binding.
                else -> Unit
            }
            val nonce = ownerNonce()
            if (requireOwner && nonce == null) {
                // Data is already ready: a missing carrier is not an owner record still publishing.
                uncertain(TmuxProblem.OWNER_UNAVAILABLE, reason = TmuxUnknownReason.RECONNECT_REQUIRED)
                return@synchronized null
            }
            if (nonce != null && !TmuxCoordinatorRules.validNonce(nonce)) {
                uncertain(TmuxProblem.IDENTITY_UNKNOWN)
                return@synchronized null
            }
            state.update { it.copy(phase = if (it.snapshot == null) TmuxPhase.CHECKING else it.phase, refreshing = true) }
            // A list alone does not establish a current server. Only our witnessed binding or an
            // explicit frozen Open can distinguish a lost control socket from a dead old server.
            val previousServer = state.value.lastBinding?.takeIf { it.scope == scope }?.server
                ?.takeIf(TmuxCoordinatorRules::strongServer)
                ?: (intent as? TmuxIntent.Open)?.target?.server?.takeIf(TmuxCoordinatorRules::strongServer)
            ReadTicket(++generation, readEpoch, nonce, pinnedSocket, previousServer)
        } ?: return null
        try {
            return observeLocked(ticket)
        } finally {
            // IO is serialized; neither a stale publication nor cancellation can leave the indicator
            // running after this actual read ends. Lifecycle disposal may clear it earlier.
            synchronized(lifecycle) { state.update { it.copy(refreshing = false) } }
        }
    }

    private suspend fun observeLocked(ticket: ReadTicket): Fresh? {
        val requestId = "r_${ticket.generation}_${clockNanos().toULong().toString(16)}"
        var problem = TmuxProblem.PROBE_UNAVAILABLE
        val observation = try {
            val output = bridge.observe(TmuxRemote.observe(scripts, requestId, ticket.nonce, requireOwner,
                ticket.socket, previousServer = ticket.previousServer))
            if (output == null) null else {
                problem = TmuxProblem.INVALID_RESPONSE
                observationParser(output, requestId)
            }
        } catch (cancelled: CancellationException) {
            synchronized(lifecycle) {
                if (readTicketLive(ticket)) uncertain(TmuxProblem.PROBE_UNAVAILABLE)
            }
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return synchronized(lifecycle) {
            if (!readTicketLive(ticket)) return@synchronized null
            if (!dataReady()) { uncertain(TmuxProblem.PROBE_UNAVAILABLE, pending = true); return@synchronized null }
            if (!dataAlive()) { endData(); return@synchronized null }
            if (ownerNonce() != ticket.nonce) {
                uncertain(TmuxProblem.IDENTITY_UNKNOWN)
                return@synchronized null
            }
            if (observation == null) {
                uncertain(problem)
                return@synchronized null
            }
            if (pinnedPeer != null && observation.peerBootId != null && observation.peerBootId != pinnedPeer) {
                // A different peer does not prove anything about the old peer's remote sessions.
                state.update { it.copy(targetChanged = true) }
                uncertain(TmuxProblem.TARGET_CHANGED, reason = TmuxUnknownReason.TARGET_CHANGED)
                return@synchronized null
            }
            if (!TmuxCoordinatorRules.verified(observation, ticket.nonce, requireOwner, ticket.socket)) {
                when (observation.kind) {
                    TmuxProbeKind.NOT_INSTALLED -> uncertain(TmuxProblem.NOT_INSTALLED, phase = TmuxPhase.NOT_INSTALLED,
                        reason = TmuxUnknownReason.UNSUPPORTED)
                    TmuxProbeKind.UNSUPPORTED -> uncertain(TmuxProblem.PLATFORM_UNSUPPORTED,
                        reason = TmuxUnknownReason.UNSUPPORTED)
                    TmuxProbeKind.PENDING -> uncertain(TmuxProblem.IDENTITY_UNKNOWN, pending = true)
                    TmuxProbeKind.ERROR -> uncertain(TmuxProblem.PROBE_UNAVAILABLE)
                    TmuxProbeKind.UNKNOWN -> uncertain(TmuxProblem.IDENTITY_UNKNOWN)
                    else -> uncertain(TmuxProblem.IDENTITY_UNKNOWN)
                }
                return@synchronized null
            }
            pinnedSocket = observation.server?.socketPath ?: observation.defaultSocketPath
            pinnedPeer = observation.peerBootId
            val snapshot = TmuxSnapshot(scope, ticket.generation, observation, verified = true)
            state.update { previous ->
                val binding = TmuxCoordinatorRules.binding(scope, observation)
                val last = binding ?: previous.lastBinding?.takeIf { it.scope == scope }?.let { old ->
                    val session = observation.sessions.singleOrNull {
                        observation.server == old.server && TmuxCoordinatorRules.sameObject(it.ref(old.server), old.session)
                    }
                    if (session == null) old else old.copy(session = session.ref(old.server))
                }
                val missing = binding == null && (previous.restoreMissing || TmuxCoordinatorRules.targetMissing(intent, last, observation))
                val presence = when {
                    binding != null -> TmuxPresence.Attached(binding)
                    missing -> TmuxPresence.Ended(last, remoteEnded = true)
                    observation.owner == null -> TmuxPresence.Unknown()
                    observation.clients.count { it.identity.tty == observation.owner.tty } > 1 ->
                        TmuxPresence.Unknown(TmuxUnknownReason.AMBIGUOUS)
                    observation.clients.none { it.identity.tty == observation.owner.tty } &&
                        (intent == TmuxIntent.None || last != null) -> TmuxPresence.Shell(observation.owner)
                    // A requested first attachment has not yet been witnessed; zero clients is not
                    // proof that Open/Restore completed, nor permission to replace the target.
                    else -> TmuxPresence.Pending
                }
                previous.copy(phase = TmuxPhase.READY, sessions = observation.sessions,
                    snapshot = snapshot, presence = presence, lastBinding = last,
                    restoreMissing = if (binding != null) false else previous.restoreMissing || missing,
                    message = if (missing) message(TmuxProblem.REMOTE_ENDED) else null)
            }
            Fresh(snapshot, ticket)
        }
    }

    private fun readTicketLive(ticket: ReadTicket) = !closed && !dataTerminated &&
        ticket.epoch == readEpoch && ticket.generation == generation

    private fun uncertain(
        problem: TmuxProblem,
        pending: Boolean = false,
        phase: TmuxPhase = if (pending) TmuxPhase.CHECKING else TmuxPhase.ERROR,
        reason: TmuxUnknownReason = TmuxUnknownReason.UNAVAILABLE,
    ) {
        state.update {
            it.copy(phase = phase, snapshot = it.snapshot?.copy(verified = false),
                presence = if (it.presence is TmuxPresence.Ended) it.presence else
                    if (pending) TmuxPresence.Pending else TmuxPresence.Unknown(reason),
                message = message(problem))
        }
    }

    private fun endData() {
        dataTerminated = true
        ++readEpoch
        state.update {
            val ended = it.presence as? TmuxPresence.Ended
            val last = it.currentBinding ?: it.lastBinding?.takeIf { binding -> binding.scope == scope }
            it.copy(snapshot = it.snapshot?.copy(verified = false), refreshing = false,
                phase = if (it.phase == TmuxPhase.IDLE || it.phase == TmuxPhase.CHECKING) TmuxPhase.ERROR else it.phase,
                presence = TmuxPresence.Ended(last, ended?.remoteEnded == true), lastBinding = last,
                message = if (ended?.remoteEnded == true) it.message else message(TmuxProblem.CONNECTION_ENDED))
        }
    }

    private suspend fun operate(command: String): TmuxOperationResult? {
        val result = try {
            bridge.operation(command)?.let(TmuxRemote::parseOperation)
        } catch (cancelled: CancellationException) {
            actionFailure(TmuxProblem.RESULT_UNKNOWN)
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return synchronized(lifecycle) {
            if (closed) return@synchronized null
            if (result?.ok == true) result else {
                actionFailure(when (result?.code) {
                    TmuxOperationCode.STALE -> TmuxProblem.OBJECT_CHANGED
                    TmuxOperationCode.EXISTS -> TmuxProblem.NAME_EXISTS
                    TmuxOperationCode.FAILED -> TmuxProblem.ACTION_FAILED
                    else -> TmuxProblem.RESULT_UNKNOWN
                })
                null
            }
        }
    }

    private fun actionFailure(problem: TmuxProblem) = synchronized(lifecycle) {
        if (!closed) uncertain(problem)
    }

    private fun applyMutation(kind: Mutation, target: TmuxSessionRef, name: String?, current: TmuxBinding?) {
        val endsCurrent = current != null && kind != Mutation.RENAME
        val killsCurrent = current != null && kind == Mutation.KILL
        if (endsCurrent) { dataTerminated = true; ++readEpoch }
        val nextGeneration = ++generation
        state.update { previous ->
            fun renamed(binding: TmuxBinding?): TmuxBinding? = binding?.let {
                if (kind == Mutation.RENAME && TmuxCoordinatorRules.sameObject(it.session, target))
                    it.copy(session = it.session.copy(name = requireNotNull(name))) else it
            }
            val last = renamed(previous.lastBinding ?: current)
            val snapshot = previous.snapshot?.let { s ->
                if (s.scope != scope || s.observation.server != target.server) s.copy(verified = false) else {
                    val observation = s.observation
                    val sessions = observation.sessions.mapNotNull { session ->
                        if (!TmuxCoordinatorRules.sameObject(session.ref(target.server), target)) session else when (kind) {
                            Mutation.KILL -> null
                            Mutation.RENAME -> session.copy(name = requireNotNull(name))
                            Mutation.DETACH -> session.copy(clients = 0)
                        }
                    }
                    val clients = (if (kind == Mutation.RENAME) observation.clients else
                        observation.clients.filterNot { it.sessionId == target.id }).map { it.copy(pane = null) }
                    s.copy(generation = nextGeneration, observation = observation.copy(sessions = sessions, clients = clients),
                        verified = s.verified && !dataTerminated && !endsCurrent)
                }
            }
            val presence = when {
                endsCurrent -> TmuxPresence.Ended(last ?: current, remoteEnded = killsCurrent)
                previous.presence is TmuxPresence.Attached -> TmuxPresence.Attached(renamed(previous.presence.binding)!!)
                previous.presence is TmuxPresence.Ended -> previous.presence.copy(lastBinding = last)
                else -> previous.presence
            }
            previous.copy(snapshot = snapshot, sessions = snapshot?.observation?.sessions ?: previous.sessions,
                presence = presence, lastBinding = last,
                restoreMissing = if (endsCurrent) killsCurrent else previous.restoreMissing,
                message = when {
                    killsCurrent -> message(TmuxProblem.REMOTE_ENDED)
                    endsCurrent -> message(TmuxProblem.CONNECTION_ENDED)
                    else -> previous.message.takeIf { previous.restoreMissing }
                })
        }
    }
}

/** Pure identity checks, shared by production observation, validation and action paths. */
internal object TmuxCoordinatorRules {
    private val nonce = Regex("[a-f0-9]{32}")
    private val boot = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val tty = tmuxTtyPattern
    private val id = Regex("\\$[0-9]+")
    private val paneId = Regex("%[0-9]{1,10}")
    fun validNonce(value: String) = nonce.matches(value)
    fun strongServer(server: TmuxServerId) = server.witnessed && server.pid > 1 && server.started > 0 &&
        server.socketDevice >= 0 && boot.matches(server.bootId) && validSocket(server.socketPath)
    private fun validSocket(value: String) = value.startsWith('/') && value.none(Char::isISOControl)
    private fun validName(value: String) = value.isNotEmpty() && value.none(Char::isISOControl) && ':' !in value && '.' !in value
    fun validRef(ref: TmuxSessionRef) = strongServer(ref.server) && id.matches(ref.id) && ref.created > 0 && validName(ref.name)
    fun sameObject(a: TmuxSessionRef, b: TmuxSessionRef) = a.server == b.server && a.id == b.id && a.created == b.created
    private fun validPane(pane: TmuxPane) = paneId.matches(pane.id) &&
        safeSessionText(pane.title, SESSION_TITLE_LIMIT) &&
        (pane.path == null || (pane.path.startsWith('/') && safeSessionText(pane.path, SESSION_PATH_LIMIT)))

    fun verified(o: TmuxObservation, expectedNonce: String?, requireOwner: Boolean, socket: String?): Boolean {
        if (o.peerBootId?.let(boot::matches) != true) return false
        val owner = o.owner
        if (requireOwner && owner == null) return false
        if (owner != null && (owner.nonce != expectedNonce || !validNonce(owner.nonce) || owner.pid <= 1 ||
            owner.uid < 0 || !tmuxBirthMatchesTty(owner.startedTicks, owner.tty))) return false
        return when (o.kind) {
            TmuxProbeKind.NO_SERVER -> o.server == null && o.sessions.isEmpty() && o.clients.isEmpty() &&
                o.defaultSocketPath?.let { validSocket(it) && (socket == null || socket == it) } == true
            TmuxProbeKind.READY -> {
                val server = o.server ?: return false
                strongServer(server) && server.bootId == o.peerBootId && (socket == null || socket == server.socketPath) &&
                    (o.defaultSocketPath == null || o.defaultSocketPath == server.socketPath) &&
                    o.sessions.isNotEmpty() && o.sessions.all { validRef(it.ref(server)) && it.windows > 0 && it.clients >= 0 } &&
                    o.sessions.map { it.id }.distinct().size == o.sessions.size &&
                    o.sessions.map { it.name }.distinct().size == o.sessions.size &&
                    (owner == null || (owner.startedTicks < 0) == (server.startedTicks < 0)) &&
                    o.clients.all { c -> c.identity.pid > 1 && c.identity.created > 0 && tmuxBirthMatchesTty(server.startedTicks, c.identity.tty) &&
                        o.sessions.count { it.id == c.sessionId && (c.sessionCreated == null || it.created == c.sessionCreated) } == 1 &&
                        (c.pane == null || (c.sessionCreated != null && validPane(c.pane))) } &&
                    o.clients.map { it.identity.pid }.distinct().size == o.clients.size &&
                    o.clients.map { it.identity.tty }.distinct().size == o.clients.size
            }
            else -> false
        }
    }

    fun binding(scope: TmuxScope, o: TmuxObservation): TmuxBinding? {
        val owner = o.owner ?: return null
        val server = o.server ?: return null
        val client = o.clients.singleOrNull { it.identity.tty == owner.tty } ?: return null
        if (client.identity.pid <= 1 || client.identity.created <= 0 || !tty.matches(client.identity.tty)) return null
        val session = o.sessions.singleOrNull { it.id == client.sessionId } ?: return null
        return TmuxBinding(scope, owner, client.identity, session.ref(server))
    }

    fun selectionMatches(
        selection: TmuxSelection,
        scope: TmuxScope,
        snapshot: TmuxSnapshot,
        binding: TmuxBinding?,
        requireCurrent: Boolean,
    ): Boolean {
        if (selection.scope != scope || snapshot.scope != scope || !snapshot.verified ||
            selection.generation !in 1..snapshot.generation || snapshot.observation.server != selection.session.server ||
            !validRef(selection.session) || snapshot.observation.sessions.singleOrNull {
                sameObject(it.ref(selection.session.server), selection.session)
            } == null) return false
        if (requireCurrent || selection.expectedClient != null) {
            if (binding == null || binding.scope != scope || !sameObject(binding.session, selection.session)) return false
            if (selection.expectedClient != null && selection.expectedClient != binding.client) return false
        }
        return true
    }

    fun targetMissing(intent: TmuxIntent, last: TmuxBinding?, o: TmuxObservation): Boolean {
        // A new frame's actual client binding wins over the initial launch intent. Once bound,
        // lastBinding is only a frozen fallback; it is never reconciled globally by name or $0.
        if (last != null) return o.server != last.server || o.sessions.none { sameObject(it.ref(last.server), last.session) }
        return when (intent) {
            TmuxIntent.None -> false
            is TmuxIntent.Restore -> o.sessions.none { it.name == intent.name }
            is TmuxIntent.Open -> o.server != intent.target.server || o.sessions.none { sameObject(it.ref(intent.target.server), intent.target) }
        }
    }
}
