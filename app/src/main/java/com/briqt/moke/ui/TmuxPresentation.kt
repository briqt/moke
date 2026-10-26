package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxPresence
import com.briqt.moke.terminal.TmuxIntent
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.terminal.TmuxUnknownReason
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.selectionFor
import kotlin.coroutines.cancellation.CancellationException

/** Only an acknowledged success may close a choice/editor; cancellation is not a failed reply. */
internal suspend fun completeTmuxUiAction(
    action: suspend () -> Boolean,
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
) {
    val succeeded = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (succeeded) onSuccess() else onFailure()
}

internal fun tmuxTargetLabel(protocol: String, username: String, host: String, port: Int, viaJump: String = ""): String = buildString {
    append(protocol)
    append(" · ")
    if (username.isNotBlank()) { append(username); append('@') }
    val address = if (':' in host && !host.startsWith('[')) "[$host]" else host
    append(address)
    if (port != 22) { append(':'); append(port) }
    if (viaJump.isNotBlank()) { append(" · "); append(viaJump) }
}

/** Presentation decisions use witnessed identities, never a title or remembered name. */
internal fun isCurrentTmuxRow(state: TmuxUiState, row: TmuxSession): Boolean {
    val binding = state.currentBinding ?: return false
    val snapshot = state.snapshot ?: return false
    return snapshot.scope == binding.scope && snapshot.observation.server == binding.server &&
        binding.session.id == row.id && binding.session.created == row.created
}

internal fun currentTmuxSelection(state: TmuxUiState): TmuxSelection? {
    val snapshot = state.snapshot ?: return null
    if (!snapshot.verified || snapshot.observation.server?.witnessed != true) return null
    val binding = state.currentBinding ?: return null
    val row = state.sessions.singleOrNull { isCurrentTmuxRow(state, it) } ?: return null
    return state.selectionFor(row)?.takeIf {
        it.scope == binding.scope && it.session == binding.session && it.expectedClient == binding.client
    }
}

/** A known unsupported backend or missing lifetime carrier cannot recover through refresh. */
internal fun canRefreshTmuxObservation(state: TmuxUiState): Boolean =
    state.phase != TmuxPhase.ERROR || when ((state.presence as? TmuxPresence.Unknown)?.reason) {
        TmuxUnknownReason.UNSUPPORTED, TmuxUnknownReason.RECONNECT_REQUIRED -> false
        else -> true
    }

/** This requests observation, not proof that a tmux client exists. */
internal fun observesCurrentTmux(useMosh: Boolean, persistenceTmux: Boolean, intent: TmuxIntent): Boolean =
    useMosh || persistenceTmux || intent != TmuxIntent.None

private fun hasTmuxCloseContext(state: TmuxUiState): Boolean =
    state.currentBinding != null || state.lastBinding != null ||
        (state.presence as? TmuxPresence.Ended)?.lastBinding != null ||
        (state.presence as? TmuxPresence.Ended)?.remoteEnded == true || state.restoreMissing || state.targetChanged

/** Captured when a close request is made; live refreshes must not retarget its destructive action. */
internal data class SessionCloseTarget(
    val title: String,
    val targetLabel: String,
    val selection: TmuxSelection?,
    val otherClients: Int,
    val unknown: Boolean,
)

internal fun freezeSessionCloseTarget(
    state: TmuxUiState,
    title: String,
    targetLabel: String,
    observeCurrent: Boolean = true,
): SessionCloseTarget {
    val selection = currentTmuxSelection(state)
    val clients = selection?.let { selected ->
        state.sessions.firstOrNull { it.id == selected.session.id && it.created == selected.session.created }?.clients
    } ?: 0
    return SessionCloseTarget(
        title = title,
        targetLabel = targetLabel,
        selection = selection,
        otherClients = if (selection == null) 0 else (clients - 1).coerceAtLeast(0),
        unknown = selection == null && !state.restoreMissing &&
            (state.presence as? TmuxPresence.Ended)?.remoteEnded != true &&
            (hasTmuxCloseContext(state) || (observeCurrent &&
                (state.presence is TmuxPresence.Unknown || state.presence is TmuxPresence.Pending))),
    )
}

internal fun requiresSessionCloseChoice(state: TmuxUiState, confirmClose: Boolean, observeCurrent: Boolean = true): Boolean =
    confirmClose || hasTmuxCloseContext(state) ||
        (observeCurrent && (state.presence is TmuxPresence.Pending || state.presence is TmuxPresence.Unknown))

internal fun sessionClosePresentationState(
    state: TmuxUiState,
    target: SessionCloseTarget,
    observeCurrent: Boolean = true,
): TmuxUiState = if (!observeCurrent && !hasTmuxCloseContext(state) && target.selection == null && !target.unknown) {
    state.copy(message = null)
} else state

internal fun shouldPollTmux(observeCurrent: Boolean, panelOpen: Boolean, closeTarget: SessionCloseTarget?): Boolean =
    observeCurrent || panelOpen || closeTarget?.unknown == true

internal enum class TerminalEndNotice { REMOTE_ENDED, TARGET_CHANGED, LEFT_TMUX, CONNECTION_FAILED, CONNECTION_ENDED }

internal fun terminalEndNotice(state: TmuxUiState, alive: Boolean, connectFailed: Boolean): TerminalEndNotice? = when {
    state.targetChanged -> TerminalEndNotice.TARGET_CHANGED
    state.restoreMissing || (state.presence as? TmuxPresence.Ended)?.remoteEnded == true -> TerminalEndNotice.REMOTE_ENDED
    !alive && connectFailed -> TerminalEndNotice.CONNECTION_FAILED
    state.presence is TmuxPresence.Ended -> if (state.presence.lastBinding != null || state.lastBinding != null) {
        TerminalEndNotice.LEFT_TMUX
    } else TerminalEndNotice.CONNECTION_ENDED
    alive -> null
    else -> TerminalEndNotice.CONNECTION_ENDED
}

internal fun shouldRevealTerminalAfterTmuxAction(success: Boolean, state: TmuxUiState): Boolean =
    success && (state.presence is TmuxPresence.Ended || state.restoreMissing)

internal fun canReconnectFromNotice(notice: TerminalEndNotice): Boolean =
    notice != TerminalEndNotice.REMOTE_ENDED && notice != TerminalEndNotice.TARGET_CHANGED

/** Shared by every explicit create entry; a fresh permit never authorizes changing an old binding. */
internal fun canCreateTmuxFromEndNotice(state: TmuxUiState): Boolean =
    !state.busy && !state.targetChanged && (state.canCreate ||
        ((state.restoreMissing || state.presence is TmuxPresence.Ended) && state.canStartNewConnection))
