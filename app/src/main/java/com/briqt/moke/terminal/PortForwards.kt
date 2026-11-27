package com.briqt.moke.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 能为端口转发提供一条已认证 SSH 连接的传输。
 *
 * SSH 会话借用自己的连接；mosh 会话借用它的 SSH 控制连接——借用期间控制连接不得被空闲回收，
 * 最后一个转发停止后 [releaseForwardClient] 归还，恢复回收（不长期挂着 TCP 才符合 mosh 的初衷）。
 */
interface ForwardCapable {
    /** 借一条可用于 direct-tcpip 的连接（可能阻塞：mosh 需要时会现建控制连接）；不可用返回 null。 */
    fun acquireForwardClient(): SSHClient?

    /** 归还一次 [acquireForwardClient]。 */
    fun releaseForwardClient()
}

/**
 * 一个会话上的本地端口转发（-L）：手机 `127.0.0.1:localPort` → 远端 `remoteHost:remotePort`。
 *
 * 只监听回环地址，不暴露给局域网。连接断了（网络切换、mosh 控制连接被掐）转发随之失效，
 * 条目保留为 [State.FAILED]，由用户在面板里重试或停止。
 */
class PortForwards(private val transport: ForwardCapable) {

    enum class State { ACTIVE, FAILED }

    /** [parsePorts] 的结果：合法端口（去重保序）与无法识别的原文片段。 */
    data class Parsed(val ports: List<Int>, val invalid: List<String>)

    data class Target(val port: Int, val host: String = DEFAULT_REMOTE_HOST) {
        val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    }

    data class Entry(val target: Target, val localPort: Int, val state: State) {
        /** 在手机上访问这条转发的地址。 */
        val localUrl: String get() = "http://127.0.0.1:$localPort/"
    }

    private class Running(
        val client: SSHClient,
        val forwarder: LocalPortForwarder,
        val socket: ServerSocket,
        val thread: Thread,
        @Volatile var stopped: Boolean = false,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val running = HashMap<Target, Running>()
    private val lock = Any()
    /** 会话已关（[stopAll] 之后）：还在途中的 [start] 不得再登记新转发。受 [lock] 保护。 */
    private var closed = false

    fun start(remotePort: Int): Int? = start(Target(remotePort))

    /**
     * 转发远端 [target]（阻塞，勿在主线程调用）。只复用远端地址与端口都相同的活动转发。
     * [localPort] 为空或被占用时由系统分配。返回实际的本地端口；失败返回 null。
     */
    fun start(target: Target, localPort: Int? = null): Int? {
        synchronized(lock) {
            if (closed) return null
            activePort(target)?.let { return it }
        }
        val client = runCatching { transport.acquireForwardClient() }.getOrNull() ?: return fail(target, localPort)
        val socket = runCatching { bind(localPort) }.getOrElse {
            transport.releaseForwardClient()
            return fail(target, localPort)
        }
        val forwarder = runCatching {
            client.newLocalPortForwarder(Parameters(LOOPBACK, socket.localPort, target.host, target.port), socket)
        }.getOrElse {
            runCatching { socket.close() }
            transport.releaseForwardClient()
            return fail(target, localPort)
        }
        lateinit var r: Running
        val thread = Thread({
            while (!r.stopped) {
                val err = runCatching { forwarder.listen() }.exceptionOrNull()
                // sshj 的 listen 遇到一次连接打不开（远端端口还没人监听）就整体抛出、不再 accept。
                // 一次连接失败不该让整条转发失效：连接还在就接着监听。
                if (err is SSHException && !r.stopped && !socket.isClosed && client.isConnected && client.isAuthenticated) continue
                break
            }
            // listen 返回 = 被停止或连接断了；前者由 stop 收尾，后者标记失败并归还连接。
            val owned = synchronized(lock) {
                if (r.stopped) false else {
                    r.stopped = true
                    if (running[target] === r) {
                        running.remove(target)
                        _entries.update { list -> list.map { if (it.target == target) it.copy(state = State.FAILED) else it } }
                    }
                    true
                }
            }
            if (owned) {
                runCatching { socket.close() }
                transport.releaseForwardClient()
            }
        }, "moke-fwd-${target.port}").apply { isDaemon = true }
        r = Running(client, forwarder, socket, thread)
        synchronized(lock) {
            // 建连期间同一目标可能已建立；只复用仍有可用 SSH 连接的监听。
            val existing = activePort(target)
            if (closed || existing != null || !client.isConnected || !client.isAuthenticated) {
                runCatching { socket.close() }
                transport.releaseForwardClient()
                if (closed) return null
                return existing ?: fail(target, localPort)
            }
            running.put(target, r)?.let { stopRunning(it) }
            upsert(Entry(target, socket.localPort, State.ACTIVE))
        }
        thread.start()
        return socket.localPort
    }

    /** 停止并移除一条转发（含失败条目）。 */
    fun stop(target: Target) {
        synchronized(lock) {
            running.remove(target)?.let { stopRunning(it) }
            _entries.update { list -> list.filterNot { it.target == target } }
        }
    }

    /** 会话关闭时收尾。 */
    fun stopAll() {
        synchronized(lock) {
            closed = true
            running.values.forEach { stopRunning(it) }
            running.clear()
            _entries.value = emptyList()
        }
    }

    private fun stopRunning(r: Running) {
        if (r.stopped) return
        r.stopped = true
        // listen 还没跑起来时 forwarder.close() 打断不了它，之后 accept 在已关的 socket 上会空转；
        // 自己先打断监听线程，listen 一进去就退出。
        r.thread.interrupt()
        runCatching { r.forwarder.close() }
        runCatching { r.socket.close() }
        transport.releaseForwardClient()
    }

    private fun activePort(target: Target): Int? = running[target]?.takeIf {
        !it.socket.isClosed && it.client.isConnected && it.client.isAuthenticated
    }?.socket?.localPort

    private fun upsert(e: Entry) {
        _entries.update { list -> if (list.any { it.target == e.target }) list.map { if (it.target == e.target) e else it } else list + e }
    }

    private fun fail(target: Target, localPort: Int?): Int? {
        synchronized(lock) {
            if (closed) return null
            activePort(target)?.let { return it }
            running.remove(target)?.let { stopRunning(it) }
            upsert(Entry(target, localPort ?: 0, State.FAILED))
            return null
        }
    }

    /** 优先用指定的本地端口；被占用（或未指定）时交给系统分配。 */
    private fun bind(localPort: Int?): ServerSocket {
        if (localPort != null) {
            runCatching {
                return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(LOOPBACK, localPort)) }
            }
        }
        return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(LOOPBACK, 0)) }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /**
         * 远端目标默认用 `localhost`，交给远端 sshd 解析：dev server 常只监听 `::1`（Node 17+ 上
         * `localhost` 先解析成 IPv6），写死 `127.0.0.1` 会连不上。
         */
        const val DEFAULT_REMOTE_HOST = "localhost"

        /**
         * 解析"连接后自动转发的端口"：逗号 / 空白分隔，去重保序。非法项（非数字、越界）放进 invalid，
         * 由编辑页提示，不静默丢弃。
         */
        fun parsePorts(text: String): Parsed {
            val ports = LinkedHashSet<Int>()
            val invalid = ArrayList<String>()
            text.split(',', '\uFF0C', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
                val p = token.toIntOrNull()
                if (p != null && p in 1..65535) ports.add(p) else invalid.add(token)
            }
            return Parsed(ports.toList(), invalid)
        }

        /** 解析远端回环链接，保留显式地址；通配监听地址仍按默认回环目标打开。 */
        fun targetOf(url: String): Target? {
            val authority = TerminalLinks.localAuthorityOf(url) ?: return null
            val suffix = authority.portSuffix
            val port = if (suffix.isEmpty() || suffix == ":") {
                if (authority.scheme == "https") 443 else 80
            } else {
                val digits = suffix.drop(1)
                if (suffix[0] != ':' || digits.any { it !in '0'..'9' }) return null
                digits.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            val host = when (authority.host) {
                "0.0.0.0" -> DEFAULT_REMOTE_HOST
                "[::1]" -> "::1"
                else -> authority.host
            }
            return Target(port, host)
        }

        /**
         * 把远端的本机链接改写成手机上转发后的地址：只换主机与端口，保留协议、路径、查询与锚点。
         * 例：`http://localhost:5173/app?x=1` + 本地 41234 → `http://127.0.0.1:41234/app?x=1`。
         */
        fun rewriteToLocal(url: String, localPort: Int): String {
            val scheme = url.substringBefore("://")
            val rest = url.substringAfter("://")
            val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val tail = if (cut < 0) "" else rest.substring(cut)
            return "$scheme://127.0.0.1:$localPort$tail"
        }
    }
}
