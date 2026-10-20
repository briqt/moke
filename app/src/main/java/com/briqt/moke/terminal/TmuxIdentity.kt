package com.briqt.moke.terminal

/** Configuration scope is not proof of a remote server or a live client. */
data class TmuxScope(
    val targetStamp: String,
    val connectionId: String,
    val networkEpoch: Long = 0L,
)

data class TmuxServerId(
    val pid: Long,
    val started: Long,
    val socketPath: String,
    val bootId: String = "",
    val startedTicks: Long = 0L,
    val socketDevice: Long = 0L,
    val socketInode: Long = 0L,
) {
    val witnessed: Boolean get() = bootId.isNotEmpty() && startedTicks > 0 && socketInode > 0
}

data class TmuxSessionRef(
    val server: TmuxServerId,
    val id: String,
    val created: Long,
    val name: String,
)

fun TmuxSession.ref(server: TmuxServerId) = TmuxSessionRef(server, id, created, name)

data class TmuxClientId(val pid: Long, val created: Long, val tty: String)
data class TmuxClient(val identity: TmuxClientId, val sessionId: String)

/** A nonce is checked against a live owner, its birth identity and control TTY. */
data class TmuxOwnerProof(
    val nonce: String,
    val pid: Long,
    val uid: Long,
    val startedTicks: Long,
    val tty: String,
)

enum class TmuxProbeKind { READY, NO_SERVER, NOT_INSTALLED, PENDING, UNKNOWN, ERROR }

data class TmuxObservation(
    val kind: TmuxProbeKind,
    val owner: TmuxOwnerProof? = null,
    val server: TmuxServerId? = null,
    val sessions: List<TmuxSession> = emptyList(),
    val clients: List<TmuxClient> = emptyList(),
    val term: String? = null,
    val error: String? = null,
    val peerBootId: String? = null,
    val defaultSocketPath: String? = null,
)

data class TmuxSnapshot(
    val scope: TmuxScope,
    val generation: Long,
    val observation: TmuxObservation,
    val verified: Boolean = false,
) {
    fun selection(session: TmuxSession): TmuxSelection? {
        val server = observation.server ?: return null
        if (!verified || !server.witnessed || observation.sessions.none { it.id == session.id && it.created == session.created }) return null
        return TmuxSelection(scope, generation, session.ref(server))
    }
}

data class TmuxSelection(
    val scope: TmuxScope,
    val generation: Long,
    val session: TmuxSessionRef,
    val expectedClient: TmuxClientId? = null,
)

fun TmuxUiState.selectionFor(session: TmuxSession): TmuxSelection? {
    val selected = snapshot?.selection(session) ?: return null
    val current = currentBinding
    return if (current != null && current.session.sameInstance(selected.session)) selected.copy(expectedClient = current.client) else selected
}

fun TmuxSessionRef.sameInstance(other: TmuxSessionRef): Boolean =
    server == other.server && id == other.id && created == other.created

data class TmuxBinding(
    val scope: TmuxScope,
    val owner: TmuxOwnerProof,
    val client: TmuxClientId,
    val session: TmuxSessionRef,
) {
    val server: TmuxServerId get() = session.server
}

sealed interface TmuxPresence {
    data object Pending : TmuxPresence
    data class Shell(val owner: TmuxOwnerProof) : TmuxPresence
    data class Attached(val binding: TmuxBinding) : TmuxPresence
    data class Unknown(val reason: TmuxUnknownReason = TmuxUnknownReason.UNAVAILABLE) : TmuxPresence
    data class Ended(val lastBinding: TmuxBinding? = null, val remoteEnded: Boolean = false) : TmuxPresence
}

enum class TmuxUnknownReason { UNAVAILABLE, UNSUPPORTED, STALE, AMBIGUOUS, TARGET_CHANGED }

/** Open and restore never create a replacement for a missing remote session. */
sealed interface TmuxIntent {
    data object None : TmuxIntent
    data class Restore(val name: String, val socketPath: String? = null) : TmuxIntent
    data class Open(val target: TmuxSessionRef, val takeOver: Boolean = false) : TmuxIntent
}

fun TmuxIntent.forReconnect(binding: TmuxBinding?): TmuxIntent = when {
    binding != null -> TmuxIntent.Open(binding.session)
    this is TmuxIntent.Open -> copy(takeOver = false)
    else -> this
}

enum class TmuxOperationCode { OK, STALE, EXISTS, FAILED, UNKNOWN }

data class TmuxOperationResult(
    val code: TmuxOperationCode,
    val output: String = "",
    val created: TmuxSessionRef? = null,
) {
    val ok: Boolean get() = code == TmuxOperationCode.OK
}

/** All commands remain pinned to this transport's authenticated destination. */
interface TmuxConnection {
    val tmuxNonce: String?
    val tmuxOwnerRequired: Boolean
    fun execTmuxOperation(command: String): String?
}
