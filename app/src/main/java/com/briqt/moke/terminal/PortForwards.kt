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

    data class Entry(val remotePort: Int, val remoteHost: String, val localPort: Int, val state: State) {
        /** 在手机上访问这条转发的地址。 */
        val localUrl: String get() = "http://127.0.0.1:$localPort/"
    }

    private class Running(
        val forwarder: LocalPortForwarder,
        val socket: ServerSocket,
        val thread: Thread,
        @Volatile var stopped: Boolean = false,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val running = HashMap<Int, Running>()
    private val lock = Any()
    /** 会话已关（[stopAll] 之后）：还在途中的 [start] 不得再登记新转发。受 [lock] 保护。 */
    private var closed = false

    /**
     * 转发远端 [remotePort]（阻塞，勿在主线程调用）。已有同一远端端口的活动转发时直接复用。
     * [localPort] 为空或被占用时由系统分配。返回实际的本地端口；失败返回 null。
     */
    fun start(remotePort: Int, localPort: Int? = null, remoteHost: String = DEFAULT_REMOTE_HOST): Int? {
        synchronized(lock) {
            if (closed) return null
            _entries.value.firstOrNull { it.remotePort == remotePort && it.state == State.ACTIVE }?.let { return it.localPort }
        }
        val client = runCatching { transport.acquireForwardClient() }.getOrNull() ?: return fail(remotePort, remoteHost, localPort)
        val socket = runCatching { bind(localPort) }.getOrElse {
            transport.releaseForwardClient()
            return fail(remotePort, remoteHost, localPort)
        }
        val forwarder = runCatching {
            client.newLocalPortForwarder(Parameters(LOOPBACK, socket.localPort, remoteHost, remotePort), socket)
        }.getOrElse {
            runCatching { socket.close() }
            transport.releaseForwardClient()
            return fail(remotePort, remoteHost, localPort)
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
                    if (running[remotePort] === r) {
                        running.remove(remotePort)
                        _entries.update { list -> list.map { if (it.remotePort == remotePort) it.copy(state = State.FAILED) else it } }
                    }
                    true
                }
            }
            if (owned) {
                runCatching { socket.close() }
                transport.releaseForwardClient()
            }
        }, "moke-fwd-$remotePort").apply { isDaemon = true }
        r = Running(forwarder, socket, thread)
        synchronized(lock) {
            if (closed) {
                r.stopped = true
                runCatching { socket.close() }
                transport.releaseForwardClient()
                return null
            }
            running.put(remotePort, r)?.let { old -> stopRunning(old) }
            upsert(Entry(remotePort, remoteHost, socket.localPort, State.ACTIVE))
        }
        thread.start()
        return socket.localPort
    }

    /** 停止并移除一条转发（含失败条目）。 */
    fun stop(remotePort: Int) {
        synchronized(lock) {
            running.remove(remotePort)?.let { stopRunning(it) }
            _entries.update { list -> list.filterNot { it.remotePort == remotePort } }
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

    private fun upsert(e: Entry) {
        _entries.update { list -> if (list.any { it.remotePort == e.remotePort }) list.map { if (it.remotePort == e.remotePort) e else it } else list + e }
    }

    private fun fail(remotePort: Int, remoteHost: String, localPort: Int?): Int? {
        synchronized(lock) { upsert(Entry(remotePort, remoteHost, localPort ?: 0, State.FAILED)) }
        return null
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

        /**
         * 本机回环链接要转发的远端端口；没写端口时按协议默认（http 80 / https 443）。
         * 非本机链接、端口写得不对（非数字、越界）返回 null——不能猜一个端口去转发。
         */
        fun remotePortOf(url: String): Int? {
            if (!TerminalLinks.isLocal(url)) return null
            val scheme = url.substringBefore("://").lowercase()
            val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            val portText = if (authority.startsWith("[")) authority.substringAfter(']', "").removePrefix(":") else authority.substringAfter(':', "")
            if (portText.isEmpty()) return if (scheme == "https") 443 else 80
            return portText.toIntOrNull()?.takeIf { it in 1..65535 }
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
