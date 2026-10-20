package com.briqt.moke.terminal

import com.briqt.moke.data.Route
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.TmuxTargetStamp

// type: pure tmux launch and reuse identity policy
object TmuxLaunchPolicy {
    fun matches(scope: TmuxScope, route: Route, networkEpoch: Long): Boolean =
        route is Route.Ok && scope.networkEpoch == networkEpoch &&
            scope.targetStamp == TmuxTargetStamp.of(route.host, route.jump, route.network)

    fun remembered(route: Route): TmuxIntent {
        val current = route as? Route.Ok ?: return TmuxIntent.None
        val host = current.host
        if (host.persistence != SessionPersistence.TMUX || host.tmuxSessionName.isBlank() || host.tmuxTargetStamp.isBlank() ||
            host.tmuxTargetStamp != TmuxTargetStamp.of(host, current.jump, current.network)) return TmuxIntent.None
        return TmuxIntent.Restore(host.tmuxSessionName)
    }

    fun canReuse(
        hostId: String,
        targetStamp: String,
        target: TmuxSessionRef,
        candidateHostId: String,
        alive: Boolean,
        state: TmuxUiState,
    ): Boolean {
        if (hostId != candidateHostId || !alive || state.targetChanged) return false
        val binding = state.currentBinding ?: return false
        val snapshot = state.snapshot ?: return false
        if (!snapshot.verified || snapshot.generation <= 0 || binding.scope != snapshot.scope ||
            binding.scope.targetStamp != targetStamp || !binding.server.witnessed || !target.server.witnessed ||
            !binding.session.sameInstance(target)) return false
        val observation = snapshot.observation
        // A verified flag alone must not bless a binding left over from another peer/client observation.
        return TmuxCoordinatorRules.verified(observation, binding.owner.nonce, requireOwner = true, socket = binding.server.socketPath) &&
            TmuxCoordinatorRules.binding(binding.scope, observation) == binding
    }
}
