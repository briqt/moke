package com.briqt.moke.net

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.Route
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/** Recovery is data, never inferred from translated error text. The host comes from the current UI. */
enum class RecoveryKind { LOGIN_NETWORK, VIEW_NETWORK, EDIT_NETWORK, EDIT_HOST, RETRY }
data class NetworkRecovery(val kind: RecoveryKind, val network: NetworkEntry? = null)

val NetworkException.recovery: NetworkRecovery?
    get() = when (code) {
        "NEEDS_LOGIN", "LOGIN_EXPIRED" -> NetworkRecovery(RecoveryKind.LOGIN_NETWORK, network)
        "NEEDS_MACHINE_AUTH" -> NetworkRecovery(RecoveryKind.VIEW_NETWORK, network)
        "PROXY_AUTH", "PROXY_TLS", "PROXY_NO_UDP", "PROXY_REFUSED", "INVALID_ARGUMENT", "CONTROL_CHANGED" ->
            NetworkRecovery(RecoveryKind.EDIT_NETWORK, network)
        "NOT_IN_TAILNET", "NAME_NOT_FOUND" -> NetworkRecovery(RecoveryKind.EDIT_HOST)
        "CANCELLED" -> null
        else -> NetworkRecovery(RecoveryKind.RETRY, network)
    }

/** Identity-based traversal also tolerates malformed/custom Throwable cause cycles. */
private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var next: Throwable? = this@causeChain
    while (next != null && seen.add(next)) {
        yield(next)
        next = next.cause
    }
}

fun Throwable.networkException(): NetworkException? = causeChain().filterIsInstance<NetworkException>().firstOrNull()

fun Throwable.networkRecovery(): NetworkRecovery? {
    networkException()?.let { return it.recovery }
    return if (causeChain().any {
        it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException ||
            it is UnknownHostException || it is SocketException
    }) NetworkRecovery(RecoveryKind.RETRY) else null
}

val Route.recovery: NetworkRecovery?
    get() = when (this) {
        is Route.Ok -> null
        is Route.Missing -> NetworkRecovery(RecoveryKind.EDIT_HOST)
        Route.Loading, is Route.Unreadable -> NetworkRecovery(RecoveryKind.RETRY)
    }
