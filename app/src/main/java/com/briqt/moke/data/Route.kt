package com.briqt.moke.data

/**
 * 一台主机「怎么连过去」的唯一解析结果。终端连接、重连、复制会话、tmux 附加、文件页、传输、
 * 端口转发都必须走 [resolveRoute]，不再各自解析跳板机——此前被引用的跳板机删除后，有一处会
 * 静默改成直连。失效引用一律拒连（不悄悄直连或回退）。
 */
sealed interface Route {
    /**
     * 可以连。[network] 是**第一跳**用的网络：有跳板机时是跳板机自己的网络（OpenSSH 语义，
     * 主机自身的网络此时不生效），否则是主机的网络；null = 直连。
     * [host] 与 [jump] 若使用独立密钥库（[Host.keyId]），此处已在内存中填入对应私钥与口令。
     */
    data class Ok(val host: Host, val jump: Host?, val network: NetworkEntry?) : Route

    /** 引用的东西不存在了（被删除或未选择），不能连。[targetHostId] 非空时（如跳板机密钥缺失）直接指向需编辑的目标主机。 */
    data class Missing(val what: MissingKind, val targetHostId: String? = null) : Route

    /** 主机、密钥或网络列表解不开（Keystore 失效），不能当成「已删除」。 */
    data class Unreadable(val what: UnreadableKind) : Route

    /** 冷启动列表还没读完：等待，不能判失效。 */
    data object Loading : Route
}

enum class MissingKind { HOST, JUMP_HOST, NETWORK, JUMP_CHAIN, KEY, JUMP_KEY }
enum class UnreadableKind { HOSTS, NETWORKS, KEYS }

/** Whether this host actively relies on [KeyStore] rather than password/Tailscale or unmigrated legacy inline PEM. */
internal fun Host.usesKeyStore(): Boolean =
    authType == AuthType.KEY && !(keyId.isBlank() && privateKeyPem.isNotBlank())

fun resolveRoute(
    hostId: String,
    hosts: ListState<Host>,
    networks: ListState<NetworkEntry>,
    keys: ListState<KeyEntry> = ListState.Ready(emptyList()),
): Route {
    val base = resolveNetworkRoute(hostId, hosts, networks)
    if (base !is Route.Ok) return base
    val jump = base.jump?.let { rawJump ->
        when (val resolved = hydrateKey(rawJump, keys, MissingKind.JUMP_KEY, missingTargetHostId = rawJump.id)) {
            is HydratedHost.Ok -> resolved.host
            is HydratedHost.Blocked -> return resolved.route
        }
    }
    val host = when (val resolved = hydrateKey(base.host, keys, MissingKind.KEY)) {
        is HydratedHost.Ok -> resolved.host
        is HydratedHost.Blocked -> return resolved.route
    }
    return Route.Ok(host, jump, base.network)
}

private sealed interface HydratedHost {
    data class Ok(val host: Host) : HydratedHost
    data class Blocked(val route: Route) : HydratedHost
}

private fun hydrateKey(
    host: Host,
    keys: ListState<KeyEntry>,
    missingKind: MissingKind,
    missingTargetHostId: String? = null,
): HydratedHost {
    if (!host.usesKeyStore()) return HydratedHost.Ok(host)
    val items = when (keys) {
        ListState.Loading -> return HydratedHost.Blocked(Route.Loading)
        ListState.Unreadable -> return HydratedHost.Blocked(Route.Unreadable(UnreadableKind.KEYS))
        is ListState.Ready -> keys.items
    }
    if (host.keyId.isBlank()) return HydratedHost.Blocked(Route.Missing(missingKind, missingTargetHostId))
    val entry = items.firstOrNull { it.id == host.keyId }
        ?: return HydratedHost.Blocked(Route.Missing(missingKind, missingTargetHostId))
    if (entry.privateKeyPem.isBlank()) return HydratedHost.Blocked(Route.Missing(missingKind, missingTargetHostId))
    return HydratedHost.Ok(host.copy(privateKeyPem = entry.privateKeyPem, passphrase = entry.passphrase))
}

private fun resolveNetworkRoute(hostId: String, hosts: ListState<Host>, networks: ListState<NetworkEntry>): Route {
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
    return all.count { (resolveNetworkRoute(it.id, hosts, networks) as? Route.Ok)?.network?.id == networkId }
}

/** 当前连接路径（自身或有效一级跳板）实际使用此密钥的主机数。 */
fun hostsUsingKey(keyId: String, hosts: ListState<Host>): Int {
    if (keyId.isBlank()) return 0
    val all = (hosts as? ListState.Ready)?.items ?: return 0
    val byId = all.associateBy { it.id }
    fun usesKey(h: Host): Boolean = h.usesKeyStore() && h.keyId == keyId
    return all.count { h ->
        val jump = h.jumpHostId.takeIf { it.isNotBlank() && it != h.id }
            ?.let { byId[it] }
            ?.takeIf { it.jumpHostId.isBlank() }
        usesKey(h) || (jump != null && usesKey(jump))
    }
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
