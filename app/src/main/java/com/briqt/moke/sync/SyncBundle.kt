package com.briqt.moke.sync

import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import org.json.JSONArray
import org.json.JSONObject

/** 一个偏好值（只支持 DataStore 里实际用到的几种类型）。 */
sealed interface PrefValue {
    data class S(val v: String) : PrefValue
    data class B(val v: Boolean) : PrefValue
    data class F(val v: Float) : PrefValue
    data class I(val v: Int) : PrefValue
}

data class BundleMeta(
    val appVersion: String,
    val platform: String,
    val device: String,
    /** ISO 8601 带时区偏移。 */
    val createdAt: String,
)

/**
 * 一份备份的明文内容。只含可以跨设备的配置：主机去掉运行态（最近连接、tmux 记忆），
 * tailnet 只留 id/名称/控制服务器（登录状态与本设备名称属于本机）。
 */
data class SyncBundle(
    val meta: BundleMeta,
    val hosts: List<Host>,
    val hostGroupOrder: List<String>,
    val hostCollapsedGroups: List<String>,
    val networks: List<NetworkEntry>,
    val knownHosts: Map<String, String>,
    val settings: Map<String, PrefValue>,
    /** 应用内语言标签（"" = 跟随系统）；null = 文件里没有。 */
    val language: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("meta", JSONObject().apply {
            put("app", "moke")
            put("version", meta.appVersion)
            put("platform", meta.platform)
            put("device", meta.device)
            put("createdAt", meta.createdAt)
        })
        put("hosts", JSONObject().apply {
            put("items", JSONArray().apply { hosts.forEach { put(exportHost(it)) } })
            put("groupOrder", JSONArray(hostGroupOrder))
            put("collapsedGroups", JSONArray(hostCollapsedGroups))
        })
        put("networks", JSONArray().apply { networks.forEach { put(exportNetwork(it)) } })
        put("knownHosts", JSONObject().apply { knownHosts.forEach { (k, v) -> put(k, v) } })
        put("settings", JSONObject().apply {
            settings.forEach { (k, v) ->
                put(k, when (v) {
                    is PrefValue.S -> JSONObject().put("t", "s").put("v", v.v)
                    is PrefValue.B -> JSONObject().put("t", "b").put("v", v.v)
                    is PrefValue.F -> JSONObject().put("t", "f").put("v", v.v.toDouble())
                    is PrefValue.I -> JSONObject().put("t", "i").put("v", v.v)
                })
            }
        })
        language?.let { put("language", it) }
    }

    fun encode(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    class NewerFormatException : Exception()

    companion object {
        /** 正文模式版本；新增字段不必升，语义不兼容时才升。 */
        const val FORMAT = 1

        /** 去掉本机运行态后的主机 JSON。 */
        fun exportHost(h: Host): JSONObject = h.toJson().apply {
            remove("lastConnectedAt")
            remove("tmuxSessionName")
            remove("tmuxTargetStamp")
        }

        fun exportNetwork(e: NetworkEntry): JSONObject = when (e.kind) {
            NetworkKind.TAILSCALE -> JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("kind", e.kind.name)
                put("controlUrl", e.controlUrl)
            }
            NetworkKind.PROXY -> e.toJson().apply { remove("revision") }
        }

        /** 只比较会同步的部分：用于判断「有没有变」（Android 自带的 org.json 没有 similar）。 */
        fun sameSyncedHost(a: Host, b: Host): Boolean = syncedHost(a) == syncedHost(b)
        fun sameSyncedNetwork(a: NetworkEntry, b: NetworkEntry): Boolean = syncedNetwork(a) == syncedNetwork(b)

        private fun syncedHost(h: Host) = h.copy(lastConnectedAt = 0L, tmuxSessionName = "", tmuxTargetStamp = "")
        private fun syncedNetwork(e: NetworkEntry) =
            if (e.kind == NetworkKind.TAILSCALE) NetworkEntry(id = e.id, name = e.name, kind = e.kind, controlUrl = e.controlUrl)
            else e.copy(revision = 1)

        /** @throws NewerFormatException 文件来自更新的版本、本版本读不懂。 */
        fun decode(bytes: ByteArray): SyncBundle {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            if (o.optInt("format", 0) > FORMAT) throw NewerFormatException()
            val meta = o.optJSONObject("meta") ?: JSONObject()
            val hosts = o.optJSONObject("hosts") ?: JSONObject()
            val items = hosts.optJSONArray("items") ?: JSONArray()
            val networks = o.optJSONArray("networks") ?: JSONArray()
            val known = o.optJSONObject("knownHosts") ?: JSONObject()
            val settings = o.optJSONObject("settings") ?: JSONObject()
            return SyncBundle(
                meta = BundleMeta(
                    appVersion = meta.optString("version"),
                    platform = meta.optString("platform"),
                    device = meta.optString("device"),
                    createdAt = meta.optString("createdAt"),
                ),
                hosts = (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::importHost) },
                hostGroupOrder = strings(hosts.optJSONArray("groupOrder")),
                hostCollapsedGroups = strings(hosts.optJSONArray("collapsedGroups")),
                networks = (0 until networks.length()).mapNotNull { i -> networks.optJSONObject(i)?.let(::importNetwork) },
                knownHosts = known.keys().asSequence().mapNotNull { k -> known.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
                settings = settings.keys().asSequence().mapNotNull { k -> settings.optJSONObject(k)?.let(::prefValue)?.let { k to it } }.toMap(),
                language = if (o.has("language")) o.optString("language") else null,
            )
        }

        private fun importHost(o: JSONObject): Host? {
            if (o.optString("id").isBlank()) return null
            return Host.fromJson(o).copy(lastConnectedAt = 0L, tmuxSessionName = "", tmuxTargetStamp = "")
        }

        private fun importNetwork(o: JSONObject): NetworkEntry? {
            if (o.optString("id").isBlank() || o.optString("name").isBlank()) return null
            return syncedNetwork(NetworkEntry.fromJson(o))
        }

        private fun prefValue(o: JSONObject): PrefValue? = runCatching {
            when (o.getString("t")) {
                "s" -> PrefValue.S(o.getString("v"))
                "b" -> PrefValue.B(o.getBoolean("v"))
                "f" -> PrefValue.F(o.getDouble("v").toFloat())
                "i" -> PrefValue.I(o.getInt("v"))
                else -> null
            }
        }.getOrNull()

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
