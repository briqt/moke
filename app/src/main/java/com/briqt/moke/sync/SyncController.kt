package com.briqt.moke.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.briqt.moke.LocaleManager
import com.briqt.moke.data.Host
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.KeyEntry
import com.briqt.moke.data.KeyStore
import com.briqt.moke.data.ListState
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.NetworkStore
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.StoreResult
import com.briqt.moke.data.proxyConnectionChanged
import com.briqt.moke.data.sameTmuxTarget
import com.briqt.moke.data.uniqueKeyName
import com.briqt.moke.terminal.KnownHosts
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 一个远端备份：文件信息 + 从文件名解析出的元数据。 */
data class RemoteBackup(val file: RemoteFile, val parsed: BackupName.Parsed?, val fromThisDevice: Boolean) {
    val name: String get() = file.name

    /** 列表排序：文件名里的时间优先，解析不了的用服务器修改时间。 */
    val sortKey: Long get() = parsed?.time?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: file.modifiedMillis ?: 0L
}

sealed interface SyncError {
    data class WebDav(val failure: WebDavFailure, val status: Int = 0) : SyncError
    /** 本机主机、密钥或网络库解不开 / 还没读到。 */
    data object LocalUnreadable : SyncError
    data object NotConfigured : SyncError
    data object Storage : SyncError
}

sealed interface SyncOutcome<out T> {
    data class Ok<T>(val value: T) : SyncOutcome<T>
    data class Failed(val error: SyncError) : SyncOutcome<Nothing>
}

sealed interface DecryptOutcome {
    data class Ok(val bundle: SyncBundle) : DecryptOutcome
    data object WrongPassword : DecryptOutcome
    data object NewerFormat : DecryptOutcome
    data object Corrupt : DecryptOutcome
}

data class ApplyResult(
    val failed: Set<RestoreCategory>,
    /** 恢复后需要在本机登录的 tailnet。 */
    val needsLogin: List<NetworkEntry>,
    val languageChanged: Boolean,
)

/**
 * 「配置网盘」的编排：上传本机配置为加密快照、列出/下载/删除远端文件、按 [RestorePlan] 写回本机。
 *
 * 网络的删除与身份重置走 [Ops]（即 ViewModel 现有的删除网络 / 退出登录流程：取消登录、关闭会话、
 * 清 tmux 记忆、忘记节点状态），不在这里另写一套。
 */
class SyncController(
    private val context: Context,
    private val configStore: SyncConfigStore,
    private val hostStore: HostStore,
    private val keyStore: KeyStore,
    private val networkStore: NetworkStore,
    private val settingsStore: SettingsStore,
    private val knownHosts: KnownHosts,
    private val ops: Ops,
) {
    interface Ops {
        suspend fun <T> withStoreLock(block: suspend () -> T): T
        suspend fun deleteNetwork(entry: NetworkEntry): StoreResult<Unit>
        suspend fun resetTailnetIdentity(entry: NetworkEntry): StoreResult<Unit>
        suspend fun hasLoggedIn(entry: NetworkEntry): Boolean
        fun refreshNetworks()
        fun defaultDeviceName(): String
    }

    val settings: Flow<SyncSettings?> = configStore.settings

    fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()

    private val appVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** 先连上、建好目录，成功后才保存；[syncPassword] 为 null 表示沿用已保存的同步密码。 */
    suspend fun testAndSave(webDav: WebDavConfig, syncPassword: String?): SyncOutcome<Unit> = guard {
        val password = syncPassword ?: configStore.settings.first()?.syncPassword ?: return@guard SyncOutcome.Failed(SyncError.NotConfigured)
        WebDavClient(webDav).ensureDirectory()
        if (configStore.save(SyncSettings(webDav, password))) SyncOutcome.Ok(Unit) else SyncOutcome.Failed(SyncError.Storage)
    }

    suspend fun clearSettings() = configStore.clear()

    suspend fun list(): SyncOutcome<List<RemoteBackup>> = withClient { client ->
        val me = BackupName.sanitize(deviceName())
        SyncOutcome.Ok(client.list().map { f ->
            val parsed = BackupName.parse(f.name)
            RemoteBackup(f, parsed, parsed?.device == me)
        }.sortedWith(compareByDescending<RemoteBackup> { it.sortKey }.thenByDescending { it.name }))
    }

    suspend fun upload(): SyncOutcome<String> = withClient { client ->
        val cfg = configStore.settings.first() ?: return@withClient SyncOutcome.Failed(SyncError.NotConfigured)
        val bundle = buildBundle() ?: return@withClient SyncOutcome.Failed(SyncError.LocalUnreadable)
        val blob = withContext(Dispatchers.Default) { SyncCrypto.encrypt(bundle.encode(), cfg.syncPassword) }
        val now = LocalDateTime.now()
        for (suffix in 1..9) {
            val name = BackupName.build(now, deviceName(), appVersion, suffix = suffix)
            if (client.create(name, blob)) return@withClient SyncOutcome.Ok(name)
        }
        SyncOutcome.Failed(SyncError.WebDav(WebDavFailure.PROTOCOL, 412))
    }

    suspend fun download(name: String): SyncOutcome<ByteArray> = withClient { client -> SyncOutcome.Ok(client.download(name)) }

    suspend fun delete(name: String): SyncOutcome<Unit> = withClient { client -> client.delete(name); SyncOutcome.Ok(Unit) }

    /** [password] 为 null 时用已保存的同步密码。 */
    suspend fun decrypt(blob: ByteArray, password: String?): DecryptOutcome = withContext(Dispatchers.Default) {
        val pass = password ?: configStore.settings.first()?.syncPassword.orEmpty()
        try {
            DecryptOutcome.Ok(SyncBundle.decode(SyncCrypto.decrypt(blob, pass)))
        } catch (e: SyncCrypto.CryptoException) {
            when (e.failure) {
                SyncCrypto.Failure.WRONG_PASSWORD -> DecryptOutcome.WrongPassword
                SyncCrypto.Failure.NEWER_FORMAT -> DecryptOutcome.NewerFormat
                SyncCrypto.Failure.NOT_BACKUP, SyncCrypto.Failure.CORRUPT -> DecryptOutcome.Corrupt
            }
        } catch (_: SyncBundle.NewerFormatException) {
            DecryptOutcome.NewerFormat
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DecryptOutcome.Corrupt
        }
    }

    /** 恢复前的本机快照；主机、密钥或网络库解不开时为 null（此时不能安全写入）。 */
    suspend fun localState(): LocalState? {
        val hosts = hostStore.state.first() as? ListState.Ready ?: return null
        val keys = keyStore.state.first() as? ListState.Ready ?: return null
        val networks = networkStore.state.first() as? ListState.Ready ?: return null
        return LocalState(
            hosts = hosts.items,
            networks = networks.items,
            knownHosts = knownHosts.all(),
            hostGroupOrder = settingsStore.hostGroupOrder.first(),
            hostCollapsedGroups = settingsStore.hostCollapsedGroups.first().toList(),
            keys = keys.items,
        )
    }

    fun plan(bundle: SyncBundle, local: LocalState, categories: Set<RestoreCategory>, mode: RestoreMode): RestorePlan =
        RestorePlanner.plan(bundle, local, categories, mode, ops.defaultDeviceName())

    /**
     * 按计划写回。顺序：网络（先做身份重置与删除）→ 密钥 → 主机 → 指纹 → 偏好。每类在自己的事务里写，
     * 写入时以存储里的**当前值**为准对齐运行态字段（预览之后可能有连接刷新了最近连接时间、tmux 记忆）。
     */
    suspend fun apply(plan: RestorePlan, bundle: SyncBundle): ApplyResult {
        val failed = mutableSetOf<RestoreCategory>()
        plan.networks?.let { np ->
            var ok = true
            np.identityReset.forEach { if (ops.resetTailnetIdentity(it) is StoreResult.Failed) ok = false }
            np.diff.removed.forEach { if (ops.deleteNetwork(it) is StoreResult.Failed) ok = false }
            val removedIds = np.diff.removed.map { it.id }.toSet()
            val written = ops.withStoreLock { networkStore.update { current -> reconcileNetworks(current, np.result, removedIds) } }
            if (!ok || !written) failed += RestoreCategory.NETWORKS
        }
        plan.keys?.let { kp ->
            val removedIds = kp.diff.removed.map { it.id }.toSet()
            val written = ops.withStoreLock {
                keyStore.update { current ->
                    val next = reconcileKeys(current, kp.result, removedIds)
                    next.takeIf { it != current }
                }
            }
            if (!written) failed += RestoreCategory.KEYS
        }
        plan.hosts?.let { hp ->
            val removedIds = hp.diff.removed.map { it.id }.toSet()
            val written = ops.withStoreLock { hostStore.updateAll { current -> reconcileHosts(current, hp.result, removedIds) } }
            if (written) {
                settingsStore.setHostGroupOrder(hp.groupOrder)
                settingsStore.setHostCollapsedGroups(hp.collapsedGroups.toSet())
            } else failed += RestoreCategory.HOSTS
        }
        plan.fingerprints?.let { fp ->
            if (!knownHosts.replaceAll(fp.result)) failed += RestoreCategory.FINGERPRINTS
        }
        var languageChanged = false
        if (plan.settings) {
            try {
                settingsStore.importSyncable(bundle.settings)
                val lang = bundle.language
                if (lang != null && lang in setOf(LocaleManager.SYSTEM, LocaleManager.ZH, LocaleManager.EN) && lang != LocaleManager.currentTag(context)) {
                    LocaleManager.setTag(context, lang)
                    languageChanged = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += RestoreCategory.SETTINGS
            }
        }
        ops.refreshNetworks()
        val tailnets = (networkStore.state.first() as? ListState.Ready)?.items.orEmpty().filter { it.kind == NetworkKind.TAILSCALE }
        val needsLogin = tailnets.filter { e -> runCatching { !ops.hasLoggedIn(e) }.getOrDefault(true) }
        return ApplyResult(failed, needsLogin, languageChanged)
    }

    private suspend fun buildBundle(): SyncBundle? {
        val local = localState() ?: return null
        return SyncBundle(
            meta = BundleMeta(appVersion, BackupName.PLATFORM_ANDROID, deviceName(), OffsetDateTime.now().toString()),
            hosts = local.hosts,
            hostGroupOrder = local.hostGroupOrder,
            hostCollapsedGroups = local.hostCollapsedGroups,
            networks = local.networks,
            knownHosts = local.knownHosts,
            settings = settingsStore.exportSyncable(),
            language = LocaleManager.currentTag(context),
            keys = local.keys,
        )
    }

    private suspend fun <T> withClient(block: suspend (WebDavClient) -> SyncOutcome<T>): SyncOutcome<T> = guard {
        val cfg = configStore.settings.first() ?: return@guard SyncOutcome.Failed(SyncError.NotConfigured)
        block(WebDavClient(cfg.webDav))
    }

    private suspend fun <T> guard(block: suspend () -> SyncOutcome<T>): SyncOutcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WebDavException) {
        SyncOutcome.Failed(SyncError.WebDav(e.failure, e.status))
    } catch (_: Exception) {
        SyncOutcome.Failed(SyncError.WebDav(WebDavFailure.PROTOCOL))
    }

    companion object {
        internal fun reconcileKeys(current: List<KeyEntry>, planned: List<KeyEntry>, removedIds: Set<String>): List<KeyEntry> {
            val byId = current.associateBy { it.id }
            val plannedIds = planned.map { it.id }.toSet()
            val out = mutableListOf<KeyEntry>()
            for (p in planned) {
                val c = byId[p.id]
                val merged = p.copy(
                    name = uniqueKeyName(p.name, out, ignoreId = p.id),
                    createdAt = c?.createdAt ?: p.createdAt,
                )
                out += merged
            }
            for (c in current) {
                if (c.id !in plannedIds && c.id !in removedIds) {
                    out += c.copy(name = uniqueKeyName(c.name, out, ignoreId = c.id))
                }
            }
            return out
        }

        /**
         * 计划里的网络列表对齐到存储当前值：tailnet 保留本机的版本号与设备名，代理连接参数变了才升版本；
         * 本机在预览之后新建的条目保留，计划里移除的不再写回。
         */
        internal fun reconcileNetworks(current: List<NetworkEntry>, planned: List<NetworkEntry>, removedIds: Set<String>): List<NetworkEntry> {
            val byId = current.associateBy { it.id }
            val plannedIds = planned.map { it.id }.toSet()
            return planned.map { p ->
                val c = byId[p.id] ?: return@map p
                when (p.kind) {
                    NetworkKind.TAILSCALE -> c.copy(name = p.name, controlUrl = p.controlUrl)
                    NetworkKind.PROXY -> p.copy(revision = c.revision + if (c.kind == p.kind && proxyConnectionChanged(c, p)) 1 else 0)
                }
            } + current.filter { it.id !in plannedIds && it.id !in removedIds }
        }

        /** 同上：主机的最近连接时间以当前值为准，tmux 记忆只在目标未变时保留。 */
        internal fun reconcileHosts(current: List<Host>, planned: List<Host>, removedIds: Set<String>): List<Host> {
            val byId = current.associateBy { it.id }
            val plannedIds = planned.map { it.id }.toSet()
            return planned.map { p ->
                val c = byId[p.id] ?: return@map p
                val keepTmux = c.persistence == SessionPersistence.TMUX && p.persistence == SessionPersistence.TMUX && sameTmuxTarget(c, p)
                p.copy(
                    lastConnectedAt = c.lastConnectedAt,
                    tmuxSessionName = if (keepTmux) c.tmuxSessionName else "",
                    tmuxTargetStamp = if (keepTmux) c.tmuxTargetStamp else "",
                )
            } + current.filter { it.id !in plannedIds && it.id !in removedIds }
        }
    }
}
