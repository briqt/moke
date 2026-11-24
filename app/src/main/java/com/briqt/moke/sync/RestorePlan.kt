package com.briqt.moke.sync

import com.briqt.moke.data.Host
import com.briqt.moke.data.KeyEntry
import com.briqt.moke.data.MAX_TAILNET_ENTRIES
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.normalizeKeyPem
import com.briqt.moke.data.sameTmuxTarget
import com.briqt.moke.data.uniqueKeyName
import com.briqt.moke.data.usesKeyStore

enum class RestoreCategory { HOSTS, KEYS, NETWORKS, FINGERPRINTS, SETTINGS }

/** 合并：文件里有的新增或覆盖，本机独有的保留。替换：勾上的类别变得和文件完全一致。 */
enum class RestoreMode { MERGE, REPLACE }

/** 恢复前的本机快照（只读）。 */
data class LocalState(
    val hosts: List<Host>,
    val networks: List<NetworkEntry>,
    val knownHosts: Map<String, String>,
    val hostGroupOrder: List<String>,
    val hostCollapsedGroups: List<String>,
    val keys: List<KeyEntry> = emptyList(),
)

data class Diff<T>(val added: List<T>, val updated: List<T>, val removed: List<T>) {
    val isEmpty: Boolean get() = added.isEmpty() && updated.isEmpty() && removed.isEmpty()
}

data class KeyPlan(
    /** 写入后的完整列表（顺序即最终顺序）。 */
    val result: List<KeyEntry>,
    val diff: Diff<KeyEntry>,
)

data class NetworkPlan(
    /** 写入后的完整列表（顺序即最终顺序）。 */
    val result: List<NetworkEntry>,
    val diff: Diff<NetworkEntry>,
    /** 控制服务器变了的本机 tailnet（旧条目）：要先按退出登录处理，再写新配置。 */
    val identityReset: List<NetworkEntry>,
    /** 超出 tailnet 数量上限、没有写入的文件条目。 */
    val skipped: List<NetworkEntry>,
)

data class HostPlan(
    val result: List<Host>,
    val diff: Diff<Host>,
    val groupOrder: List<String>,
    val collapsedGroups: List<String>,
)

data class FingerprintPlan(val result: Map<String, String>, val added: Int, val updated: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && updated == 0 && removed == 0
}

data class RestorePlan(
    val mode: RestoreMode,
    val hosts: HostPlan?,
    val networks: NetworkPlan?,
    val fingerprints: FingerprintPlan?,
    val settings: Boolean,
    /** 恢复后引用的网络在本机不存在的主机数（用户取消了自动勾选的「网络」时会出现）。 */
    val danglingNetworkRefs: Int,
    val keys: KeyPlan? = null,
    /** 恢复后引用的密钥在本机不存在的主机数（用户取消了自动勾选的「密钥」时会出现）。 */
    val danglingKeyRefs: Int = 0,
) {
    val hasRemovals: Boolean get() =
        hosts?.diff?.removed.orEmpty().isNotEmpty() ||
            keys?.diff?.removed.orEmpty().isNotEmpty() ||
            networks?.diff?.removed.orEmpty().isNotEmpty() ||
            (fingerprints?.removed ?: 0) > 0
    val hasChanges: Boolean get() =
        settings || hosts?.diff?.isEmpty == false ||
            keys?.diff?.isEmpty == false ||
            networks?.diff?.isEmpty == false ||
            fingerprints?.isEmpty == false
}

/**
 * 计算一次恢复会做什么。纯函数：不读存储、不碰运行时，便于单测守住「不丢数据、不复活、不串号」。
 */
object RestorePlanner {

    /**
     * 文件密钥 id → 本机密钥 id。同 id 直接对应；否则「私钥与口令完全相同」的本机条目视为同一把密钥，
     * 避免两台设备各自独立导入同一把私钥后恢复时重复生成两份，并把主机引用改指过去。
     */
    fun keyMapping(file: List<KeyEntry>, local: List<KeyEntry>): Map<String, String> {
        val localIds = local.map { it.id }.toSet()
        val fileIds = file.map { it.id }.toSet()
        val taken = mutableSetOf<String>()
        val map = mutableMapOf<String, String>()
        file.filter { it.id in localIds }.forEach { map[it.id] = it.id; taken += it.id }
        file.filter { it.id !in localIds }.forEach { f ->
            local.firstOrNull { l -> l.id !in taken && l.id !in fileIds && equivalentKey(f, l) }?.let { l ->
                map[f.id] = l.id
                taken += l.id
            }
        }
        return map
    }

    private fun equivalentKey(a: KeyEntry, b: KeyEntry): Boolean =
        normalizeKeyPem(a.privateKeyPem) == normalizeKeyPem(b.privateKeyPem) && a.passphrase == b.passphrase

    /** 勾了主机但没勾密钥时，文件里的主机（或其有效一级跳板）是否会实际依赖本机不存在、而文件里有的密钥。 */
    fun keysRequiredByHosts(bundle: SyncBundle, local: LocalState): Boolean {
        val mapping = keyMapping(bundle.keys, local.keys)
        val localIds = local.keys.filter { it.privateKeyPem.isNotBlank() }.map { it.id }.toSet()
        val fileIds = bundle.keys.filter { it.privateKeyPem.isNotBlank() }.map { it.id }.toSet()
        val hostsById = local.hosts.associateBy { it.id } + bundle.hosts.associateBy { it.id }
        fun needsFileKey(h: Host): Boolean =
            h.usesKeyStore() && h.keyId.isNotBlank() && (mapping[h.keyId] ?: h.keyId) !in localIds && h.keyId in fileIds
        return bundle.hosts.any { h ->
            val jump = h.jumpHostId.takeIf { it.isNotBlank() && it != h.id }
                ?.let(hostsById::get)
                ?.takeIf { it.jumpHostId.isBlank() }
            needsFileKey(h) || (jump != null && needsFileKey(jump))
        }
    }

    /**
     * 文件网络 id → 本机网络 id。同 id 直接对应；否则「同类型、同名、同目标」的本机条目视为同一个网络，
     * 这样新设备上先手动建过同一个 tailnet 的，恢复后仍保留本机的登录状态，主机引用也改指过去。
     */
    fun networkMapping(file: List<NetworkEntry>, local: List<NetworkEntry>): Map<String, String> {
        val localIds = local.map { it.id }.toSet()
        val fileIds = file.map { it.id }.toSet()
        val taken = mutableSetOf<String>()
        val map = mutableMapOf<String, String>()
        file.filter { it.id in localIds }.forEach { map[it.id] = it.id; taken += it.id }
        file.filter { it.id !in localIds }.forEach { f ->
            local.firstOrNull { l -> l.id !in taken && l.id !in fileIds && equivalent(f, l) }?.let { l ->
                map[f.id] = l.id
                taken += l.id
            }
        }
        return map
    }

    private fun equivalent(a: NetworkEntry, b: NetworkEntry): Boolean =
        a.kind == b.kind && a.name.trim().equals(b.name.trim(), ignoreCase = true) && when (a.kind) {
            NetworkKind.TAILSCALE -> a.controlUrl == b.controlUrl
            NetworkKind.PROXY -> a.proxyType == b.proxyType && a.proxyHost == b.proxyHost && a.proxyPort == b.proxyPort
        }

    /** 勾了主机但没勾网络时，文件里的主机是否会引用本机不存在、而文件里有的网络（界面据此自动勾上网络）。 */
    fun networksRequiredByHosts(bundle: SyncBundle, local: LocalState): Boolean {
        val mapping = networkMapping(bundle.networks, local.networks)
        val localIds = local.networks.map { it.id }.toSet()
        val fileIds = bundle.networks.map { it.id }.toSet()
        return bundle.hosts.any { h ->
            h.networkId.isNotBlank() && (mapping[h.networkId] ?: h.networkId) !in localIds && h.networkId in fileIds
        }
    }

    fun plan(
        bundle: SyncBundle,
        local: LocalState,
        categories: Set<RestoreCategory>,
        mode: RestoreMode,
        defaultDeviceName: String,
    ): RestorePlan {
        val keyMap = keyMapping(bundle.keys, local.keys)
        val netMap = networkMapping(bundle.networks, local.networks)
        val keys = if (RestoreCategory.KEYS in categories) planKeys(bundle.keys, local.keys, keyMap, mode) else null
        val networks = if (RestoreCategory.NETWORKS in categories) planNetworks(bundle.networks, local.networks, netMap, mode, defaultDeviceName) else null
        val hosts = if (RestoreCategory.HOSTS in categories) planHosts(bundle, local, netMap, keyMap, mode) else null
        val fingerprints = if (RestoreCategory.FINGERPRINTS in categories) planFingerprints(bundle.knownHosts, local.knownHosts, netMap, mode) else null
        val finalKeyIds = (keys?.result ?: local.keys).filter { it.privateKeyPem.isNotBlank() }.map { it.id }.toSet()
        val finalNetworkIds = (networks?.result ?: local.networks).map { it.id }.toSet()
        val finalHosts = hosts?.result ?: local.hosts
        val finalHostsById = finalHosts.associateBy { it.id }
        fun missingActiveKey(h: Host): Boolean = h.usesKeyStore() && (h.keyId.isBlank() || h.keyId !in finalKeyIds)
        val danglingKeys = finalHosts.count { h ->
            val jump = h.jumpHostId.takeIf { it.isNotBlank() && it != h.id }
                ?.let(finalHostsById::get)
                ?.takeIf { it.jumpHostId.isBlank() }
            missingActiveKey(h) || (jump != null && missingActiveKey(jump))
        }
        val danglingNetworks = finalHosts.count { it.networkId.isNotBlank() && it.networkId !in finalNetworkIds }
        return RestorePlan(
            mode = mode,
            hosts = hosts,
            networks = networks,
            fingerprints = fingerprints,
            settings = RestoreCategory.SETTINGS in categories,
            danglingNetworkRefs = danglingNetworks,
            keys = keys,
            danglingKeyRefs = danglingKeys,
        )
    }

    private fun planKeys(
        file: List<KeyEntry>,
        local: List<KeyEntry>,
        mapping: Map<String, String>,
        mode: RestoreMode,
    ): KeyPlan {
        val byId = local.associateBy { it.id }
        val mappedLocalIds = file.mapNotNull { mapping[it.id] }.toSet()
        // Disambiguate names only against untouched local keys + already-planned file keys so
        // swapping names between two existing keys ("Prod" <-> "Prod Old") stays 100% idempotent.
        val namingPool = if (mode == RestoreMode.MERGE) {
            local.filter { it.id !in mappedLocalIds }.toMutableList()
        } else {
            mutableListOf()
        }
        val result = if (mode == RestoreMode.MERGE) local.toMutableList() else mutableListOf()
        val added = mutableListOf<KeyEntry>()
        val updated = mutableListOf<KeyEntry>()
        for (f in file) {
            val target = mapping[f.id]?.let(byId::get)
            val cleanPem = normalizeKeyPem(f.privateKeyPem)
            if (target != null) {
                val merged = f.copy(
                    id = target.id,
                    name = uniqueKeyName(f.name, namingPool, ignoreId = target.id),
                    privateKeyPem = cleanPem,
                    createdAt = target.createdAt,
                )
                namingPool += merged
                if (!SyncBundle.sameSyncedKey(target, merged)) updated += merged
                val idx = result.indexOfFirst { it.id == target.id }
                if (idx >= 0) result[idx] = merged else result += merged
            } else {
                val fresh = f.copy(
                    name = uniqueKeyName(f.name, namingPool, ignoreId = f.id),
                    privateKeyPem = cleanPem,
                )
                namingPool += fresh
                result += fresh
                added += fresh
            }
        }
        val kept = result.map { it.id }.toSet()
        val removed = if (mode == RestoreMode.REPLACE) local.filter { it.id !in kept } else emptyList()
        return KeyPlan(result, Diff(added, updated, removed))
    }

    private fun planNetworks(
        file: List<NetworkEntry>,
        local: List<NetworkEntry>,
        mapping: Map<String, String>,
        mode: RestoreMode,
        defaultDeviceName: String,
    ): NetworkPlan {
        val byId = local.associateBy { it.id }
        val result = if (mode == RestoreMode.MERGE) local.toMutableList() else mutableListOf()
        val added = mutableListOf<NetworkEntry>()
        val updated = mutableListOf<NetworkEntry>()
        val reset = mutableListOf<NetworkEntry>()
        val skipped = mutableListOf<NetworkEntry>()
        for (f in file) {
            val target = mapping[f.id]?.let(byId::get)
            if (target != null) {
                val merged = when (f.kind) {
                    NetworkKind.TAILSCALE -> target.copy(name = f.name, controlUrl = f.controlUrl)
                    NetworkKind.PROXY -> f.copy(id = target.id, revision = target.revision)
                }
                if (f.kind == NetworkKind.TAILSCALE && target.controlUrl != f.controlUrl) reset += target
                if (!SyncBundle.sameSyncedNetwork(target, merged)) updated += merged
                val idx = result.indexOfFirst { it.id == target.id }
                if (idx >= 0) result[idx] = merged else result += merged
            } else {
                if (f.kind == NetworkKind.TAILSCALE && result.count { it.kind == NetworkKind.TAILSCALE } >= MAX_TAILNET_ENTRIES) {
                    skipped += f
                    continue
                }
                val fresh = f.copy(
                    name = uniqueName(f, result),
                    revision = 1,
                    deviceName = if (f.kind == NetworkKind.TAILSCALE) defaultDeviceName else f.deviceName,
                )
                result += fresh
                added += fresh
            }
        }
        val kept = result.map { it.id }.toSet()
        val removed = if (mode == RestoreMode.REPLACE) local.filter { it.id !in kept } else emptyList()
        return NetworkPlan(result, Diff(added, updated, removed), reset, skipped)
    }

    /** 同类型重名时加「 (2)」「 (3)」…（网络名同类型内唯一是存储层的约束）。 */
    private fun uniqueName(entry: NetworkEntry, existing: List<NetworkEntry>): String {
        val taken = existing.filter { it.kind == entry.kind && it.id != entry.id }.map { it.name.trim().lowercase() }.toSet()
        val base = entry.name.trim()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    private fun planHosts(
        bundle: SyncBundle,
        local: LocalState,
        netMapping: Map<String, String>,
        keyMapping: Map<String, String>,
        mode: RestoreMode,
    ): HostPlan {
        val byId = local.hosts.associateBy { it.id }
        val result = if (mode == RestoreMode.MERGE) local.hosts.toMutableList() else mutableListOf()
        val added = mutableListOf<Host>()
        val updated = mutableListOf<Host>()
        for (raw in bundle.hosts) {
            val f = raw.copy(
                networkId = if (raw.networkId.isNotBlank()) netMapping[raw.networkId] ?: raw.networkId else raw.networkId,
                keyId = if (raw.keyId.isNotBlank()) keyMapping[raw.keyId] ?: raw.keyId else raw.keyId,
            )
            val old = byId[f.id]
            if (old != null) {
                val keepTmux = old.persistence == SessionPersistence.TMUX && f.persistence == SessionPersistence.TMUX && sameTmuxTarget(old, f)
                val merged = f.copy(
                    lastConnectedAt = old.lastConnectedAt,
                    tmuxSessionName = if (keepTmux) old.tmuxSessionName else "",
                    tmuxTargetStamp = if (keepTmux) old.tmuxTargetStamp else "",
                )
                if (!SyncBundle.sameSyncedHost(old, merged)) updated += merged
                val idx = result.indexOfFirst { it.id == old.id }
                if (idx >= 0) result[idx] = merged else result += merged
            } else {
                val fresh = f.copy(lastConnectedAt = 0L, tmuxSessionName = "", tmuxTargetStamp = "")
                result += fresh
                added += fresh
            }
        }
        val kept = result.map { it.id }.toSet()
        val removed = if (mode == RestoreMode.REPLACE) local.hosts.filter { it.id !in kept } else emptyList()
        val (order, collapsed) = if (mode == RestoreMode.MERGE) {
            (local.hostGroupOrder + bundle.hostGroupOrder.filterNot { it in local.hostGroupOrder }) to local.hostCollapsedGroups
        } else bundle.hostGroupOrder to bundle.hostCollapsedGroups
        return HostPlan(result, Diff(added, updated, removed), order, collapsed)
    }

    private fun planFingerprints(
        file: Map<String, String>,
        local: Map<String, String>,
        mapping: Map<String, String>,
        mode: RestoreMode,
    ): FingerprintPlan {
        val remapped = file.mapKeys { (k, _) -> remapKey(k, mapping) }
        val result = if (mode == RestoreMode.MERGE) local + remapped else remapped
        val added = remapped.keys.count { it !in local }
        val updated = remapped.count { (k, v) -> local[k] != null && local[k] != v }
        val removed = if (mode == RestoreMode.REPLACE) local.keys.count { it !in remapped } else 0
        return FingerprintPlan(result, added, updated, removed)
    }

    /** 经网络到达的指纹键是 `<networkId>/host:port`，网络 id 被映射时一起改。 */
    internal fun remapKey(key: String, mapping: Map<String, String>): String {
        val slash = key.indexOf('/')
        if (slash <= 0) return key
        val target = mapping[key.substring(0, slash)] ?: return key
        return target + key.substring(slash)
    }
}
