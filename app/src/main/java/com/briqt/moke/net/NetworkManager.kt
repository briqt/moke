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
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 一次连接的一次性 SOCKS 凭据（同时是该网络上的一份租约，见 netbridge）。 */
data class DialTicket(val host: String, val port: Int, val username: String, val password: String)

/**
 * 经网络建连失败的原因：[code] 是 netbridge 的稳定错误码（NEEDS_LOGIN、NOT_IN_TAILNET、PROXY_AUTH …），
 * 界面按它说人话；[detail] 只供诊断，已去掉密钥。
 */
class NetworkException(val code: String, val network: NetworkEntry, detail: String) : IOException("$code: $detail") {
    companion object {
        /** netbridge 的错误一律是 JSON `{code, detail}`；解析不了的归为 INTERNAL。 */
        fun from(t: Throwable, network: NetworkEntry): NetworkException {
            if (t is NetworkException) return t
            val o = runCatching { JSONObject(t.message.orEmpty()) }.getOrNull()
            return NetworkException(o?.optString("code")?.ifBlank { null } ?: "INTERNAL", network, o?.optString("detail") ?: t.message.orEmpty())
        }
    }
}

/**
 * 网络（tailnet / 代理）的运行时：Application 作用域单例。
 *
 * - **按需**：第一次有连接要走某个网络时才加载 Go 库、启动该网络；没人用的网络在最后一份租约
 *   归还约 1 分钟后停止（Go 侧在仍有连接时拒绝停止，绝不打断使用中的会话）。
 * - **实例带版本**：代理改动后 `revision` 加一，新连接用新实例 `id.rN`，旧实例等它的连接都结束再回收。
 *   tailnet 只有一个实例（同一节点身份不能同时跑两个引擎）。
 * - **网卡变化**在独立线程即时上报，不排在任何长任务后面。
 */
class NetworkManager(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private var goReady = false
    private val stateStore by lazy { TailnetStateStore(app) }

    /** 已启动的实例 → 最近一次仍有租约（或刚被使用）的时刻。 */
    private val running = HashMap<String, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "moke-net-idle").apply { isDaemon = true } }
    private val ifaceThread = Executors.newSingleThreadExecutor { r -> Thread(r, "moke-net-ifaces").apply { isDaemon = true } }

    fun instanceId(e: NetworkEntry): String = when (e.kind) {
        NetworkKind.TAILSCALE -> e.id
        NetworkKind.PROXY -> "${e.id}.r${e.revision}"
    }

    /** 第一次用到网络时才加载 Go：未用网络的会话不受 Go 运行时影响。 */
    private fun ensureGo() {
        synchronized(lock) {
            if (goReady) return
            Netbridge.setDebugLogging(BuildConfig.DEBUG)
            Netbridge.setStateStore(stateStore)
            Netbridge.setLogsDir(File(app.noBackupFilesDir, "ts-logs").absolutePath)
            runCatching { Netbridge.setTrustAnchors(systemTrustAnchors()) }
            Netbridge.initialize(InterfaceSnapshot.collect(app))
            watchInterfaces()
            scheduler.scheduleWithFixedDelay({ runCatching { reapIdle() } }, IDLE_CHECK_S, IDLE_CHECK_S, TimeUnit.SECONDS)
            goReady = true
        }
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

    /** 启动（若未启动）该网络当前配置对应的实例；tailnet 等到能判定能否连接为止。 */
    fun ensureStarted(e: NetworkEntry) {
        ensureGo()
        val id = instanceId(e)
        synchronized(lock) {
            if (id !in running) {
                try {
                    when (e.kind) {
                        NetworkKind.PROXY -> Netbridge.startProxy(proxyConfig(e, id))
                        NetworkKind.TAILSCALE -> Netbridge.startNode(nodeConfig(e, authKey = ""))
                    }
                } catch (t: Throwable) {
                    val ex = NetworkException.from(t, e)
                    if (ex.code != "ALREADY_STARTED") throw ex
                }
                running[id] = SystemClock.elapsedRealtime()
            }
            running[id] = SystemClock.elapsedRealtime()
        }
        if (e.kind == NetworkKind.TAILSCALE) awaitTailnetReady(e)
    }

    /** 冷启动的节点要连上控制面才能拨号；等到 Running 或一个明确的「不能连」状态。 */
    private fun awaitTailnetReady(e: NetworkEntry) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (true) {
            val st = status(e)
            when (st?.state) {
                "Running" -> if (!st.expired) return else throw NetworkException("LOGIN_EXPIRED", e, "node key expired")
                "NeedsLogin" -> throw NetworkException(if (st.everLoggedIn) "LOGIN_EXPIRED" else "NEEDS_LOGIN", e, "node needs login")
                "NeedsMachineAuth" -> throw NetworkException("NEEDS_MACHINE_AUTH", e, "awaiting approval")
            }
            if (SystemClock.elapsedRealtime() > deadline) throw NetworkException("NOT_READY", e, "node is ${st?.state}")
            Thread.sleep(200)
        }
    }

    /** 为一次 TCP 连接取一次性凭据（启动网络、租约开始）。 */
    fun openDial(e: NetworkEntry): DialTicket {
        ensureStarted(e)
        val o = try {
            JSONObject(Netbridge.openDial(instanceId(e)))
        } catch (t: Throwable) {
            throw NetworkException.from(t, e)
        }
        val (h, p) = o.getString("addr").split(":")
        return DialTicket(h, p.toInt(), o.getString("username"), o.getString("password"))
    }

    fun cancelDial(t: DialTicket) = runCatching { Netbridge.cancelDial(t.username) }

    /** 这张凭据对应的连接为什么失败（读一次即清）。 */
    fun dialError(t: DialTicket, e: NetworkEntry): NetworkException? =
        Netbridge.dialError(t.username).takeIf { it.isNotBlank() }?.let { NetworkException.from(Exception(it), e) }

    data class NodeStatus(
        val state: String,
        val ips: List<String>,
        val tailnet: String,
        val loginName: String,
        val expired: Boolean,
        val everLoggedIn: Boolean,
        val leases: Int,
    )

    /** 已运行的 tailnet 节点的状态；没在运行返回 null（不为看状态而启动它）。 */
    fun status(e: NetworkEntry): NodeStatus? {
        if (e.kind != NetworkKind.TAILSCALE || !isRunning(e)) return null
        val o = runCatching { JSONObject(Netbridge.nodeStatus(instanceId(e))) }.getOrNull() ?: return null
        val ips = o.optJSONArray("ips")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return NodeStatus(
            state = o.optString("state"), ips = ips, tailnet = o.optString("tailnet"), loginName = o.optString("loginName"),
            expired = o.optBoolean("expired"), everLoggedIn = o.optBoolean("haveNodeKey"),
            leases = o.optInt("leases"),
        )
    }

    fun isRunning(e: NetworkEntry): Boolean = synchronized(lock) { instanceId(e) in running }

    /** 运行中实例的租约数（会话、待用凭据、中继）。 */
    fun leases(e: NetworkEntry): Int = if (isRunning(e)) Netbridge.leases(instanceId(e)).toInt() else 0

    /** tailnet 登录：带认证密钥（自建控制面可选）启动节点，返回要打开的授权页（空=已登录或用了密钥）。 */
    fun beginLogin(e: NetworkEntry, authKey: String): String {
        ensureGo()
        synchronized(lock) {
            val id = instanceId(e)
            if (authKey.isNotEmpty() && id in running) {
                // 认证密钥只在节点启动时生效：已运行的节点先停（此时它不可能承载连接）。
                runCatching { Netbridge.stop(id) }
                running.remove(id)
            }
            if (id !in running) {
                try {
                    Netbridge.startNode(nodeConfig(e, authKey))
                } catch (t: Throwable) {
                    val ex = NetworkException.from(t, e)
                    if (ex.code != "ALREADY_STARTED") throw ex
                }
            }
            running[id] = SystemClock.elapsedRealtime()
        }
        return try {
            Netbridge.loginURL(instanceId(e))
        } catch (t: Throwable) {
            throw NetworkException.from(t, e)
        }
    }

    /** 删除网络：尽力退出登录，必定清掉本地节点状态。@return 退出登录是否成功（失败时提示到控制台移除）。 */
    fun forget(e: NetworkEntry): Boolean {
        var loggedOut = true
        ensureGo()
        synchronized(lock) {
            val ids = running.keys.filter { it == e.id || it.startsWith("${e.id}.r") }
            if (e.kind == NetworkKind.TAILSCALE) {
                if (e.id !in running) {
                    // 为退出登录临时起节点，失败不影响清理。
                    runCatching { Netbridge.startNode(nodeConfig(e, "")); running[e.id] = SystemClock.elapsedRealtime() }
                }
                loggedOut = runCatching { Netbridge.logout(e.id) }.isSuccess
            }
            for (id in ids + listOf(e.id)) {
                runCatching { Netbridge.stop(id) }
                running.remove(id)
            }
        }
        if (e.kind == NetworkKind.TAILSCALE) {
            stateStore.files.deleteNetwork(e.id)
            File(app.noBackupFilesDir, "tailnet/${e.id}").deleteRecursively()
        }
        return loggedOut
    }

    /** 空闲回收：没有租约满 [IDLE_STOP_MS] 的实例停止；仍有连接的由 Go 拒绝（BUSY），下次再看。 */
    private fun reapIdle() {
        val now = SystemClock.elapsedRealtime()
        val candidates = synchronized(lock) { running.toMap() }
        for ((id, lastBusy) in candidates) {
            val leases = runCatching { Netbridge.leases(id) }.getOrDefault(0L)
            if (leases > 0) {
                synchronized(lock) { if (id in running) running[id] = now }
                continue
            }
            if (now - lastBusy < IDLE_STOP_MS) continue
            synchronized(lock) {
                if (running[id] != lastBusy) return@synchronized
                if (runCatching { Netbridge.stop(id) }.isSuccess) running.remove(id)
            }
        }
    }

    private fun nodeConfig(e: NetworkEntry, authKey: String): String = JSONObject()
        .put("id", instanceId(e))
        .put("dir", File(app.noBackupFilesDir, "tailnet/${e.id}").absolutePath)
        .put("controlURL", e.controlUrl)
        .put("authKey", authKey)
        .put("hostname", e.deviceName.ifBlank { defaultDeviceName() })
        .toString()

    private fun proxyConfig(e: NetworkEntry, id: String): String = JSONObject()
        .put("id", id)
        .put("type", when (e.proxyType) {
            ProxyType.SOCKS5 -> "socks5"
            // 名字一律交代理解析：SOCKS4 遇到名字自动走 4a。
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
        private const val IDLE_STOP_MS = 60_000L
        private const val READY_TIMEOUT_MS = 20_000L

        /** 默认本设备名：`moke-<机型>`，只留控制面接受的字符。 */
        fun defaultDeviceName(): String =
            ("moke-" + android.os.Build.MODEL).lowercase()
                .map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(63)

        /** Android 的系统 + 用户信任锚（AndroidCAStore），PEM。HTTPS 代理据此校验，而非 Go 自带的证书路径。 */
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
