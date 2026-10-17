package com.briqt.moke.terminal

import android.content.Context

/**
 * 已知主机指纹存储（TOFU）。用 SharedPreferences（同步 API，数据量小，适合在连接线程读写）。
 * key = "host:port"，value = "SHA256:<base64>"（算法见 [HostKeyFingerprint]；v0.1.20 及以前存的是旧格式，
 * 连接时由 [MokeHostKeyVerifier] 识别并改存）。
 */
class KnownHosts(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("moke_known_hosts", Context.MODE_PRIVATE)

    fun stored(id: String): String? = prefs.getString(id, null)

    fun store(id: String, fingerprint: String) {
        prefs.edit().putString(id, fingerprint).apply()
    }

    /**
     * 忘记某主机的指纹（下次连接重新 TOFU）。
     *
     * 指纹以 `host:port` 为键、与连接条目的生命周期无关：服务器换过密钥时，删除并重建连接
     * **不会**清除它，用户会卡在「主机密钥已变更」上（社区实报）。所以删除连接与编辑页的
     * 显式动作都要走这里。
     */
    fun forget(id: String) {
        prefs.edit().remove(id).apply()
    }

    companion object {
        fun idOf(host: String, port: Int) = "$host:$port"

        /**
         * 经某个网络（tailnet / 代理）到达时，同一个 `host:port` 可能是另一台机器（如不同 tailnet
         * 里同名的节点），指纹按网络分开记：`<networkId>/host:port`。直连保持旧键，存量无需迁移。
         */
        fun idOf(host: String, port: Int, networkId: String?) =
            if (networkId.isNullOrBlank()) idOf(host, port) else "$networkId/${idOf(host, port)}"
    }
}
