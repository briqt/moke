package com.briqt.moke.terminal

// type: pure session title policy

internal data class SessionTitleOverrides(val prefix: String?, val title: String?)

internal object SessionTitlePolicy {
    private val moshPrefix = Regex("^(?:\\[mosh][\\s.·]*)+", RegexOption.IGNORE_CASE)

    fun userValue(value: String?): String? = value?.trim()?.ifBlank { null }

    fun sameTarget(source: TmuxScope?, target: TmuxScope, sourceTargetChanged: Boolean = false): Boolean =
        !sourceTargetChanged && source != null && source.targetStamp == target.targetStamp && source.networkEpoch == target.networkEpoch

    fun inherit(source: SessionTitleOverrides?, sameTarget: Boolean): SessionTitleOverrides =
        if (sameTarget && source != null) source.copy(prefix = userValue(source.prefix), title = userValue(source.title))
        else SessionTitleOverrides(null, null)

    fun oscTitle(useMosh: Boolean, raw: String): String =
        (if (useMosh) raw.replaceFirst(moshPrefix, "") else raw).trim()
            .takeIf { safeSessionText(it, SESSION_TITLE_LIMIT) }.orEmpty()

    fun automaticPrefix(scope: TmuxScope, state: TmuxUiState, alive: Boolean): String {
        if (state.targetChanged) return ""
        val ended = (state.presence as? TmuxPresence.Ended)?.lastBinding
        if (ended != null && ended.scope == scope && ended.server.witnessed) return ended.session.name
        if (!alive) return ""
        val binding = state.currentBinding ?: return ""
        val snapshot = state.snapshot ?: return ""
        return binding.session.name.takeIf {
            binding.scope == scope && snapshot.scope == scope && snapshot.verified &&
                TmuxCoordinatorRules.verified(snapshot.observation, binding.owner.nonce, true, binding.server.socketPath) &&
                TmuxCoordinatorRules.binding(scope, snapshot.observation) == binding
        }.orEmpty()
    }

    fun fallback(hostLabel: String, directory: SessionDirectory?): String =
        directory?.let { "${it.targetLabel} ${it.path}" } ?: hostLabel

    fun automaticTitle(useMosh: Boolean, paneTitle: String?, osc: String, fallback: String): String =
        if (paneTitle != null) paneTitle.takeIf { it.isNotBlank() && safeSessionText(it, SESSION_TITLE_LIMIT) } ?: fallback
        else oscTitle(useMosh, osc).ifEmpty { fallback }

    fun mainTitle(automatic: String, custom: String?, mark: String?, hasCollision: Boolean): String {
        userValue(custom)?.let { return it }
        return if (hasCollision && !mark.isNullOrBlank()) "$automatic $mark" else automatic
    }

    fun display(prefix: String?, title: String?, automaticPrefix: String, automaticTitle: String,
                mark: String? = null, hasCollision: Boolean = false): String {
        val actualPrefix = userValue(prefix) ?: automaticPrefix
        val main = mainTitle(automaticTitle, title, mark, hasCollision)
        return if (actualPrefix.isEmpty()) main else "$actualPrefix · $main"
    }
}
