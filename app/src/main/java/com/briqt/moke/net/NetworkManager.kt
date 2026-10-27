package com.briqt.moke.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import com.briqt.moke.BuildConfig
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.go.netbridge.Netbridge
import com.briqt.moke.go.netbridge.StateStore
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 一次连接的一次性 SOCKS 凭据（同时是该网络上的一份租约，见 netbridge）。 */
data class DialTicket(val host: String, val port: Int, val username: String, val password: String)

/** mosh 的 UDP 中继：mosh-client 发往回环 [localPort]，由 netbridge 经网络转给 mosh-server。 */
data class UdpRelay(val id: String, val localPort: Int)

/** 经网络建连失败的稳定错误码；[detail] 只供诊断，已去掉密钥。 */
class NetworkException(val code: String, val network: NetworkEntry, detail: String) : IOException("$code: $detail") {
    companion object {
        /** netbridge 的错误一律是 JSON `{code, detail}`；解析不了的归为 INTERNAL。 */
        fun from(t: Throwable, network: NetworkEntry): NetworkException {
            if (t is NetworkException) return t
            if (t is NetworkRuntime.Failure) return NetworkException(t.code, network, t.message.orEmpty())
            val o = runCatching { JSONObject(t.message.orEmpty()) }.getOrNull()
            return NetworkException(o?.optString("code")?.ifBlank { null } ?: "INTERNAL", network, o?.optString("detail") ?: t.message.orEmpty())
        }
    }
}

/**
 * Application 作用域、按需启动的网络运行时。生命周期按网络串行；元数据查询不等待 Go 或磁盘。
 * 代理用 revision 区分实例，tailnet 同时只运行一个身份。网卡上报不经过生命周期工作线程。
 */
class NetworkManager(context: Context) {
    private val app = context.applicationContext
    // EncryptedStateFiles synchronizes each store instance, so never share one across networks.
    private val stateStores = ConcurrentHashMap<String, TailnetStateStore>()
    private fun stateStore(id: String) = stateStores.getOrPut(id) { TailnetStateStore(app) }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "moke-net-idle").apply { isDaemon = true } }
    private val idleWorkers = Executors.newCachedThreadPool { r -> Thread(r, "moke-net-reap").apply { isDaemon = true } }
    private val ifaceThread = Executors.newSingleThreadExecutor { r -> Thread(r, "moke-net-ifaces").apply { isDaemon = true } }
    private val runtime: NetworkRuntime = NetworkRuntime(
        bridge = object : NetworkRuntime.Bridge {
            override fun initialize() {
                Netbridge.setDebugLogging(BuildConfig.DEBUG)
                Netbridge.setStateStore(object : StateStore {
                    override fun read(networkID: String, key: String): ByteArray? = stateStore(networkID).read(networkID, key)
                    override fun write(networkID: String, key: String, value: ByteArray?) = stateStore(networkID).write(networkID, key, value)
                })
                Netbridge.setLogsDir(File(app.noBackupFilesDir, "ts-logs").absolutePath)
                runCatching { Netbridge.setTrustAnchors(systemTrustAnchors()) }
                Netbridge.initialize(InterfaceSnapshot.collect(app))
                watchInterfaces()
                scheduler.scheduleWithFixedDelay(
                    { runCatching { runtime.reapIdle { task -> idleWorkers.execute(task) } } },
                    IDLE_CHECK_S, IDLE_CHECK_S, TimeUnit.SECONDS,
                )
            }
            override fun start(network: NetworkRuntime.Network) = go {
                if (network.tailnet) Netbridge.startNode(network.configuration) else Netbridge.startProxy(network.configuration)
            }
            override fun stop(id: String) = go { Netbridge.stop(id) }
            override fun logout(id: String) = go { Netbridge.logout(id) }
            override fun status(id: String): NetworkRuntime.Status = go {
                val o = JSONObject(Netbridge.nodeStatus(id))
                val ips = o.optJSONArray("ips")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                NetworkRuntime.Status(
                    o.optString("state"), ips, o.optString("tailnet"), o.optString("loginName"),
                    o.optBoolean("expired"), o.optBoolean("haveNodeKey"), o.optInt("leases"),
                )
            }
            override fun leases(id: String): Int = go { Netbridge.leases(id).toInt() }
            override fun loginUrl(id: String): String = go { Netbridge.loginURL(id) }
            override fun sweepTickets() { Netbridge.sweepTickets() }
        },
        files = object : NetworkRuntime.IdentityFiles {
            override fun hasLoggedIn(id: String) = loginMarker(id).exists()
            override fun hasState(id: String) =
                identityPath("tailnet-state", id).list()?.any { !it.endsWith(".tmp") } == true ||
                    identityPath("tailnet", id).exists()
            override fun markLoggedIn(id: String) {
                val marker = loginMarker(id)
                if (marker.exists()) return
                marker.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("cannot create login marker directory") }
                marker.writeText("1")
            }
            override fun clearLoggedIn(id: String) {
                val marker = loginMarker(id)
                if (marker.exists() && !marker.delete()) throw IOException("cannot remove login marker")
            }
            override fun deleteState(id: String) {
                if (!identityPath("tailnet-state", id).deleteRecursively()) throw IOException("cannot remove node state")
                if (!identityPath("tailnet", id).deleteRecursively()) throw IOException("cannot remove node directory")
            }
        },
        now = SystemClock::elapsedRealtime,
        pause = { Thread.sleep(200) },
    )

    fun instanceId(e: NetworkEntry): String = when (e.kind) {
        NetworkKind.TAILSCALE -> e.id
        NetworkKind.PROXY -> "${e.id}.r${e.revision}"
    }

    /** No configuration, Android path lookup, or other IO is needed for metadata queries. */
    private fun ref(e: NetworkEntry) = NetworkRuntime.Network(
        e.id, instanceId(e), e.kind == NetworkKind.TAILSCALE, "",
        controlUrl = if (e.kind == NetworkKind.TAILSCALE) e.controlUrl else "",
    )
    private fun configured(e: NetworkEntry, authKey: String = "") = ref(e).copy(
        configuration = if (e.kind == NetworkKind.TAILSCALE) nodeConfig(e, authKey) else proxyConfig(e, instanceId(e)),
    )
    private fun loginMarker(id: String) = identityPath("tailnet-login", id)
    private fun identityPath(directory: String, id: String): File {
        require(id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it in "-_." } &&
            id != "." && id != "..") { "invalid network id" }
        return File(File(app.noBackupFilesDir, directory), id)
    }

    private fun watchInterfaces() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val report = { ifaceThread.execute { runCatching { Netbridge.replaceInterfaces(InterfaceSnapshot.collect(app)) } } }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { report() }
            override fun onLost(network: Network) { report() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { report() }
        })
    }

    /** 启动当前配置对应的实例；运行中的设备名改动只在下次真正启动节点时生效。 */
    fun ensureStarted(e: NetworkEntry) = guard(e) { runtime.ensureStarted(configured(e)) }
    fun isRunning(e: NetworkEntry): Boolean = runtime.isRunning(ref(e))
    fun isKnownExpired(e: NetworkEntry): Boolean = runtime.isKnownExpired(ref(e))

    /** 纯元数据快照；null 表示尚未读取标记，不能当成「未登录」。 */
    fun loggedInSnapshot(e: NetworkEntry): Boolean? = runtime.loggedInSnapshot(ref(e))

    /** 不启动 Go；初次读标记会做磁盘 IO，供后台刷新调用。之后读缓存。 */
    fun hasLoggedIn(e: NetworkEntry): Boolean = runtime.hasLoggedIn(ref(e))

    /** 最近一次状态/租约采样；不在调用线程同步查询 Go。 */
    fun leases(e: NetworkEntry): Int = runtime.leases(ref(e))

    /** 后台刷新租约数；单个网络慢不会持有全局元数据锁。 */
    fun refreshLeases(e: NetworkEntry): Int = guard(e) { runtime.refreshLeases(ref(e)) }

    /** 启动与取票共用同一网络的 IO gate，空闲回收不能插到二者之间。 */
    fun openDial(e: NetworkEntry): DialTicket = guard(e) {
        runtime.withStarted(configured(e), discard = { cancelDial(it) }) {
            val o = JSONObject(Netbridge.openDial(instanceId(e)))
            val (h, p) = o.getString("addr").split(":")
            DialTicket(h, p.toInt(), o.getString("username"), o.getString("password"))
        }
    }
    fun cancelDial(t: DialTicket) = runCatching { Netbridge.cancelDial(t.username) }
    fun dialError(t: DialTicket, e: NetworkEntry): NetworkException? =
        Netbridge.dialError(t.username).takeIf { it.isNotBlank() }?.let { NetworkException.from(Exception(it), e) }

    /** Explicit device discovery starts only this network; the runtime rejects retired identities. */
    fun devices(e: NetworkEntry): List<TailnetDevice> = guard(e) {
        if (e.kind != NetworkKind.TAILSCALE) throw NetworkRuntime.Failure("INVALID_ARGUMENT", "network is not a tailnet")
        runtime.withStarted(configured(e)) { parseTailnetDevices(Netbridge.nodePeers(instanceId(e))) }
    }

    /** 只查选定 tailnet 的节点，不走系统 DNS。 */
    fun resolveTarget(e: NetworkEntry, host: String, port: Int): String = guard(e) {
        runtime.withStarted(configured(e)) {
            val hostPort = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            Netbridge.resolveTarget(instanceId(e), hostPort).substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        }
    }

    /** 中继从创建起占一份 Go 租约，用完必须 [closeRelay]。 */
    fun openRelay(e: NetworkEntry, targetHost: String): UdpRelay = guard(e) {
        runtime.withStarted(configured(e), discard = { closeRelay(it) }) {
            val id = "mosh-" + java.util.UUID.randomUUID().toString().take(8)
            val o = JSONObject(Netbridge.openUDPRelay(
                JSONObject().put("id", id).put("networkID", instanceId(e)).put("targetHost", targetHost).toString(),
            ))
            UdpRelay(id, o.getInt("localPort"))
        }
    }
    fun startRelay(r: UdpRelay, e: NetworkEntry, port: Int, key: String) = guard(e) {
        Netbridge.startUDPRelay(r.id, port.toLong(), key)
    }
    fun closeRelay(r: UdpRelay) { runCatching { Netbridge.closeUDPRelay(r.id) } }

    data class NodeStatus(
        val state: String,
        val ips: List<String>,
        val tailnet: String,
        val loginName: String,
        val expired: Boolean,
        val everLoggedIn: Boolean,
        val leases: Int,
    )

    /** 后台查询已运行的节点，不为看状态启动 Go；旧身份/旧运行实例的结果直接丢弃。 */
    fun status(e: NetworkEntry): NodeStatus? = runCatching {
        runtime.status(ref(e))?.let { NodeStatus(it.state, it.ips, it.tailnet, it.loginName, it.expired, it.everLoggedIn, it.leases) }
    }.getOrNull()

    /** 在派发登录 Job 前安装请求 hold；仅操作元数据，主线程可用。 */
    fun holdLogin(e: NetworkEntry, attempt: Long) = runtime.holdLogin(ref(e), attempt)

    /** 带 attempt 时只释放匹配请求；取消协程不代表阻塞的 Go 调用已经退出。 */
    fun endLogin(e: NetworkEntry, attempt: Long? = null) = runtime.endLogin(ref(e), attempt)

    /** 显式 attempt 必须先 holdLogin；过期/取消的请求不会返回迟到 URL。 */
    fun beginLogin(e: NetworkEntry, authKey: String, attempt: Long? = null): String = guard(e) {
        runtime.beginLogin(configured(e, authKey), freshIdentity = authKey.isNotEmpty(), attempt)
    }

    /** 入口先使旧身份失效；停止成功前绝不删除节点状态。BUSY 身份留待安全回收。 */
    fun forget(e: NetworkEntry): Boolean {
        val deletion = runtime.invalidateIdentity(ref(e))
        return guard(e) { runtime.forget(configured(e), deletion) }
    }

    private inline fun <T> guard(e: NetworkEntry, action: () -> T): T = try { action() } catch (t: Throwable) {
        throw NetworkException.from(t, e)
    }
    private inline fun <T> go(action: () -> T): T = try { action() } catch (t: Throwable) {
        val o = runCatching { JSONObject(t.message.orEmpty()) }.getOrNull()
        throw NetworkRuntime.Failure(o?.optString("code")?.ifBlank { null } ?: "INTERNAL", o?.optString("detail") ?: t.message.orEmpty())
    }

    private fun nodeConfig(e: NetworkEntry, authKey: String): String = JSONObject()
        .put("id", instanceId(e))
        .put("dir", identityPath("tailnet", e.id).absolutePath)
        .put("controlURL", e.controlUrl)
        .put("authKey", authKey)
        .put("hostname", e.deviceName.ifBlank { defaultDeviceName() })
        .toString()

    private fun proxyConfig(e: NetworkEntry, id: String): String = JSONObject()
        .put("id", id)
        .put("type", when (e.proxyType) {
            ProxyType.SOCKS5 -> "socks5"
            ProxyType.SOCKS4 -> "socks4a"
            ProxyType.HTTP -> "http"
            ProxyType.HTTPS -> "https"
        })
        .put("host", e.proxyHost)
        .put("port", e.proxyPort)
        .put("username", e.username)
        .put("password", if (e.proxyType == ProxyType.SOCKS4) "" else e.password)
        .apply { if (e.proxyType == ProxyType.HTTPS && e.extraCaPem.isNotBlank()) put("tlsRootsPEM", e.extraCaPem) }
        .toString()

    companion object {
        private const val IDLE_CHECK_S = 15L

        /** 默认本设备名：`moke-<机型>`，只留控制面接受的字符。 */
        fun defaultDeviceName(): String = defaultDeviceNameForModel(android.os.Build.MODEL)

        /** Android 的系统 + 用户信任锚（AndroidCAStore），PEM。 */
        fun systemTrustAnchors(): String {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            return Collections.list(ks.aliases()).mapNotNull { ks.getCertificate(it) as? X509Certificate }.joinToString("") { c ->
                "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(c.encoded, Base64.DEFAULT) + "-----END CERTIFICATE-----\n"
            }
        }
    }
}

/** 网卡快照（netbridge 的 JSON 格式），附带当前默认路由所在网卡名（同官方 Android 客户端）。 */
object InterfaceSnapshot {
    fun collect(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val list = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().sortedBy { it.index }
        val def = runCatching { cm?.getLinkProperties(cm.activeNetwork)?.interfaceName }.getOrNull()
            ?.takeIf { n -> list.any { it.name == n } }
        val root = JSONObject()
        if (def != null) root.put("defaultInterface", def)
        return root.put("interfaces", JSONArray(list.map { i ->
            JSONObject().put("index", i.index).put("name", i.name).put("mtu", runCatching { i.mtu }.getOrDefault(0))
                .put("up", runCatching { i.isUp }.getOrDefault(false))
                .put("loopback", runCatching { i.isLoopback }.getOrDefault(false))
                .put("pointToPoint", runCatching { i.isPointToPoint }.getOrDefault(false))
                .put("multicast", runCatching { i.supportsMulticast() }.getOrDefault(false))
                .put("addresses", JSONArray(i.interfaceAddresses.mapNotNull { a ->
                    a.address?.hostAddress?.substringBefore('%')?.let { ip ->
                        JSONObject().put("ip", ip).put("prefix", a.networkPrefixLength.toInt())
                    }
                }))
        })).toString()
    }
}
