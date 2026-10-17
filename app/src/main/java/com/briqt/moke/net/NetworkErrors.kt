package com.briqt.moke.net

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.data.MissingKind
import com.briqt.moke.data.Route
import com.briqt.moke.data.UnreadableKind
import com.briqt.moke.localized

/** 把 netbridge 的错误码说成人话（网络名带进句子，便于分辨是哪个网络）。 */
fun NetworkException.describe(context: Context): String {
    val name = network.name.ifBlank { network.proxyHost }
    val id = when (code) {
        "NEEDS_LOGIN" -> R.string.net_err_needs_login
        "LOGIN_EXPIRED" -> R.string.net_err_login_expired
        "NEEDS_MACHINE_AUTH" -> R.string.net_err_needs_machine_auth
        "NOT_READY" -> R.string.net_err_not_ready
        "NOT_IN_TAILNET" -> R.string.net_err_not_in_tailnet
        "NAME_NOT_FOUND" -> R.string.net_err_name_not_found
        "TARGET_UNREACHABLE" -> return context.localized(R.string.net_err_target_unreachable)
        "TARGET_REFUSED" -> return context.localized(R.string.net_err_target_refused)
        "TIMEOUT" -> return context.localized(R.string.net_err_timeout)
        "PROXY_UNREACHABLE" -> R.string.net_err_proxy_unreachable
        "PROXY_AUTH" -> R.string.net_err_proxy_auth
        "PROXY_REFUSED" -> R.string.net_err_proxy_refused
        "PROXY_TLS" -> R.string.net_err_proxy_tls
        "PROXY_NO_UDP" -> R.string.net_err_proxy_no_udp
        else -> return context.localized(R.string.net_err_internal, code)
    }
    return context.localized(id, name)
}

/** 这些失败要先去登录网络（失败条据此给出「登录」）。 */
val NetworkException.needsLogin: Boolean get() = code in setOf("NEEDS_LOGIN", "LOGIN_EXPIRED", "NEEDS_MACHINE_AUTH")

/** 连接失败的统一说法：网络错误说人话，其余沿用原消息。 */
fun Throwable.connectFailureText(context: Context): String =
    (this as? NetworkException)?.describe(context) ?: message ?: javaClass.simpleName

/** 不能连的路由为什么不能连。 */
fun Route.refusalText(context: Context): String? = when (this) {
    is Route.Ok -> null
    Route.Loading -> context.localized(R.string.route_loading)
    is Route.Unreadable -> context.localized(
        if (what == UnreadableKind.HOSTS) R.string.hosts_unreadable_title else R.string.route_unreadable_networks
    )
    is Route.Missing -> context.localized(when (what) {
        MissingKind.HOST -> R.string.route_missing_host
        MissingKind.JUMP_HOST -> R.string.route_missing_jump
        MissingKind.NETWORK -> R.string.route_missing_network
        MissingKind.JUMP_CHAIN -> R.string.route_jump_chain
    })
}
