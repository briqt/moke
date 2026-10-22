package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxSelection

internal data class TmuxRowTarget(val selection: TmuxSelection, val clients: Int, val targetLabel: String)

internal data class TmuxRowMenuState(
    val target: TmuxRowTarget?,
    val canManage: Boolean,
    val canTakeOver: Boolean,
    val canDetach: Boolean,
)

/** Refresh impact facts only for the opened identity; never replace its action selection. */
internal fun tmuxRowMenuState(
    frozen: TmuxRowTarget,
    live: TmuxRowTarget?,
    managementEnabled: Boolean,
    current: Boolean,
): TmuxRowMenuState {
    val target = live?.takeIf {
        frozen.selection.scope == it.selection.scope && frozen.selection.session == it.selection.session &&
            frozen.selection.expectedClient == it.selection.expectedClient
    }?.let { frozen.copy(clients = it.clients) }
    val canManage = managementEnabled && target != null
    val hasClients = (target?.clients ?: 0) > 0
    return TmuxRowMenuState(target, canManage, canManage && hasClients && !current, canManage && hasClients)
}
