package com.briqt.moke.data

/**
 * 一台主机「怎么连过去」的唯一解析结果。终端连接、重连、复制会话、tmux 附加、文件页、传输、
 * 端口转发都必须走 [resolveRoute]，不再各自解析跳板机——此前被引用的跳板机删除后，有一处会
 * 静默改成直连。失效引用一律拒连（不悄悄直连）。
 */
sealed interface Route {
    /**
     * 可以连。[network] 是**第一跳**用的网络：有跳板机时是跳板机自己的网络（OpenSSH 语义，
     * 主机自身的网络此时不生效），否则是主机的网络；null = 直连。
     */
    data class Ok(val host: Host, val jump: Host?, val network: NetworkEntry?) : Route

    /** 引用的东西不存在了（被删除），不能连。 */
    data class Missing(val what: MissingKind) : Route

    /** 主机或网络列表解不开（Keystore 失效），不能当成「已删除」。 */
    data class Unreadable(val what: UnreadableKind) : Route

    /** 冷启动列表还没读完：等待，不能判失效。 */
    data object Loading : Route
}

enum class MissingKind { HOST, JUMP_HOST, NETWORK, JUMP_CHAIN }
enum class UnreadableKind { HOSTS, NETWORKS }

fun resolveRoute(hostId: String, hosts: ListState<Host>, networks: ListState<NetworkEntry>): Route {
    val all = when (hosts) {
        ListState.Loading -> return Route.Loading
        ListState.Unreadable -> return Route.Unreadable(UnreadableKind.HOSTS)
        is ListState.Ready -> hosts.items
    }
    val host = all.firstOrNull { it.id == hostId } ?: return Route.Missing(MissingKind.HOST)
    val jump = host.jumpHostId.takeIf { it.isNotBlank() }?.let { jumpId ->
        if (jumpId == host.id) return Route.Missing(MissingKind.JUMP_HOST)
        val j = all.firstOrNull { it.id == jumpId } ?: return Route.Missing(MissingKind.JUMP_HOST)
        // 只支持一级跳板：跳板机自己又设了跳板机，明确拒绝而不是静默忽略。
        if (j.jumpHostId.isNotBlank()) return Route.Missing(MissingKind.JUMP_CHAIN)
        j
    }
    val networkId = (jump ?: host).networkId.takeIf { it.isNotBlank() }
        ?: return Route.Ok(host, jump, null)
    val network = when (networks) {
        ListState.Loading -> return Route.Loading
        ListState.Unreadable -> return Route.Unreadable(UnreadableKind.NETWORKS)
        is ListState.Ready -> networks.items.firstOrNull { it.id == networkId }
            ?: return Route.Missing(MissingKind.NETWORK)
    }
    return Route.Ok(host, jump, network)
}

/** 当前可解析、且第一跳实际依赖此网络的主机数；不把跳板覆盖的字段算成断连影响。 */
fun hostsUsingNetwork(networkId: String, hosts: ListState<Host>, networks: ListState<NetworkEntry>): Int {
    val all = (hosts as? ListState.Ready)?.items ?: return 0
    return all.count { (resolveRoute(it.id, hosts, networks) as? Route.Ok)?.network?.id == networkId }
}

/**
 * 一次性迁移：把**已经失效**的跳板引用清空（指向不存在的主机或自己）。这些主机此前实际是
 * 直连且可用的（旧版静默忽略失效跳板），清空后行为不变；此后的删除才按新规则拒连。
 * 没有可改的返回 null。
 */
fun clearDanglingJumps(hosts: List<Host>): List<Host>? {
    val ids = hosts.mapTo(HashSet()) { it.id }
    var changed = false
    val next = hosts.map { h ->
        if (h.jumpHostId.isNotBlank() && (h.jumpHostId == h.id || h.jumpHostId !in ids)) {
            changed = true
            h.copy(jumpHostId = "")
        } else h
    }
    return next.takeIf { changed }
}
