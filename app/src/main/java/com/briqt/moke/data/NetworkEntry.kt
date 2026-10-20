package com.briqt.moke.data

import org.json.JSONObject
import java.util.UUID

/** 一个可被主机选用的「网络」：一个 tailnet，或一个外部代理。 */
enum class NetworkKind { TAILSCALE, PROXY }

enum class ProxyType {
    SOCKS5, SOCKS4, HTTP, HTTPS;

    /** 能否转发 UDP（mosh 需要）。SOCKS5 还要看服务器是否真的允许 UDP ASSOCIATE。 */
    val carriesUdp: Boolean get() = this == SOCKS5
}

/**
 * 网络条目。凭据（代理口令）随条目一起加密存储；tailnet 的认证密钥**不在这里**——
 * 它只在首次注册时在内存里用一次，节点状态由 netbridge 经加密的状态存储保存。
 *
 * [revision]：代理配置每次改动加一，运行时据此让新连接用新实例、旧连接留在旧实例上。
 * tailnet 显式切换身份时也递增，用于持久化的 tmux 目标身份；不改变 tailnet 运行时实例 id。
 */
data class NetworkEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: NetworkKind = NetworkKind.PROXY,
    val revision: Int = 1,
    // Tailscale
    val controlUrl: String = OFFICIAL_CONTROL_URL,
    val deviceName: String = "",
    // 代理
    val proxyType: ProxyType = ProxyType.SOCKS5,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val username: String = "",
    val password: String = "",
    /** 仅 HTTPS 代理：在系统信任锚之外额外信任的证书（PEM）。 */
    val extraCaPem: String = "",
) {
    val isOfficialControl: Boolean get() = kind == NetworkKind.TAILSCALE && controlUrl == OFFICIAL_CONTROL_URL

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("revision", revision)
        put("controlUrl", controlUrl)
        put("deviceName", deviceName)
        put("proxyType", proxyType.name)
        put("proxyHost", proxyHost)
        put("proxyPort", proxyPort)
        put("username", username)
        put("password", password)
        put("extraCaPem", extraCaPem)
    }

    companion object {
        const val OFFICIAL_CONTROL_URL = "https://controlplane.tailscale.com"

        fun fromJson(o: JSONObject) = NetworkEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            kind = runCatching { NetworkKind.valueOf(o.optString("kind")) }.getOrDefault(NetworkKind.PROXY),
            revision = o.optInt("revision", 1).coerceAtLeast(1),
            controlUrl = o.optString("controlUrl", OFFICIAL_CONTROL_URL),
            deviceName = o.optString("deviceName", ""),
            proxyType = runCatching { ProxyType.valueOf(o.optString("proxyType")) }.getOrDefault(ProxyType.SOCKS5),
            proxyHost = o.optString("proxyHost", ""),
            proxyPort = o.optInt("proxyPort", 1080),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            extraCaPem = o.optString("extraCaPem", ""),
        )
    }
}
