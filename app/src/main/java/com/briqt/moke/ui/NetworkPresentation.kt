package com.briqt.moke.ui

import com.briqt.moke.R
import com.briqt.moke.data.NetworkEntry

/** Display only: the stored address and dial parameters stay unchanged. */
internal fun proxyAddressLabel(host: String, port: Int): String {
    val displayHost = if (':' in host && !(host.startsWith('[') && host.endsWith(']'))) "[$host]" else host
    return "$displayHost:$port"
}

/** Auth keys and login-method choices are transient, not persisted network configuration. */
internal fun tailnetLoginRequiresSave(saved: NetworkEntry?, draft: NetworkEntry): Boolean =
    saved == null || saved.name != draft.name || saved.controlUrl != draft.controlUrl || saved.deviceName != draft.deviceName

internal fun tailnetPhaseLabelRes(phase: TailnetPhase, leases: Int): Int = when (phase) {
    TailnetPhase.CHECKING -> R.string.net_state_checking
    TailnetPhase.NOT_LOGGED_IN -> R.string.net_state_not_logged_in
    TailnetPhase.CONNECTING -> R.string.net_state_connecting
    TailnetPhase.WAITING_BROWSER -> R.string.net_state_waiting_browser
    TailnetPhase.WAITING_APPROVAL -> R.string.net_state_waiting_approval
    TailnetPhase.EXPIRED -> R.string.net_state_expired
    TailnetPhase.LOGGED_IN -> R.string.net_state_logged_in
    TailnetPhase.ACTIVE -> if (leases > 0) R.string.net_state_active else R.string.net_state_connected
}
