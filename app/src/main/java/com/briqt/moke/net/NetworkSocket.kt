package com.briqt.moke.net

import com.briqt.moke.data.NetworkEntry
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 经某个网络建立的 TCP 连接：连到 netbridge 的回环 SOCKS5，用这次连接专属的一次性凭据握手。
 *
 * 目标地址必须是**未解析**的（`InetSocketAddress.createUnresolved`）：名字交给网络去解析
 * （tailnet 的 MagicDNS / 代理），本机不查 DNS——否则会泄露名字并绕过选定的网络。
 * 握手失败时向 netbridge 查这张凭据的失败原因，抛 [NetworkException]。
 */
class NetworkSocket(private val manager: NetworkManager, private val network: NetworkEntry) : Socket() {

    /** 逻辑上的远端端口（目标端口），而不是回环 SOCKS 的端口。 */
    @Volatile private var targetPort = 0

    /**
     * 报告目标端口：sshj 用 `socket.port` 作为主机密钥校验与 known-hosts 的端口
     * （`SocketClient.getRemotePort`），回环 SOCKS 的随机端口会让指纹记在错误的键下。
     */
    override fun getPort(): Int = targetPort

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("unsupported address")
        targetPort = target.port
        // 名字保持未解析；字面 IP 也按原样交给网络（由网络判定是否可达/在 tailnet 内）。
        val host = if (target.isUnresolved) target.hostString else target.address.hostAddress
        val ticket = manager.openDial(network)
        try {
            super.connect(InetSocketAddress(InetAddress.getByName(ticket.host), ticket.port), timeout)
            // 回环 SOCKS 握手不能无限阻塞：进程被冻结或 Go 侧异常时，否则会一直占着这张租约。
            soTimeout = HANDSHAKE_TIMEOUT_MS
            handshake(ticket, host, target.port)
            soTimeout = 0
        } catch (t: Throwable) {
            // 无论回环 TCP 是否连上，失败后都要归还凭据，否则租约会一直把网络实例钉住。
            manager.cancelDial(ticket)
            runCatching { close() }
            throw t
        }
    }

    private fun handshake(ticket: DialTicket, host: String, port: Int) {
        val out = getOutputStream()
        val inp = DataInputStream(getInputStream())
        out.write(byteArrayOf(5, 1, 2))
        out.flush()
        val method = ByteArray(2).also { inp.readFully(it) }
        if (method[0].toInt() != 5 || method[1].toInt() != 2) throw IOException("loopback SOCKS refused the method")
        val user = ticket.username.toByteArray()
        val pass = ticket.password.toByteArray()
        out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        out.flush()
        val auth = ByteArray(2).also { inp.readFully(it) }
        if (auth[1].toInt() != 0) throw IOException("loopback SOCKS rejected the credentials")
        out.write(socksTarget(host, port))
        out.flush()
        val head = ByteArray(4).also { inp.readFully(it) }
        if (head[1].toInt() != 0) {
            throw manager.dialError(ticket, network)
                ?: NetworkException("INTERNAL", network, "SOCKS reply ${head[1].toInt() and 0xff}")
        }
        // BND.ADDR / BND.PORT（netbridge 恒为 IPv4 0.0.0.0:0）。
        val skip = when (head[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> inp.readUnsignedByte()
            else -> throw IOException("invalid SOCKS address type")
        }
        inp.readFully(ByteArray(skip + 2))
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 30_000
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /**
         * SOCKS5 CONNECT 的目标地址。字面 IP 用 ATYP=1/4 上送，名字用 ATYP=3 交给网络解析：
         * 旧实现一律按域名编码，IPv6 字面量（含 `:`）会被回环 SOCKS 拒绝并报成 INTERNAL。
         */
        internal fun socksTarget(host: String, port: Int): ByteArray {
            val head = when {
                host.contains(':') -> {
                    val a = InetAddress.getByName(host)
                    if (a !is Inet6Address) throw IOException("invalid IPv6 target")
                    byteArrayOf(5, 1, 0, 4) + a.address
                }
                IPV4.matches(host) -> {
                    val a = InetAddress.getByName(host)
                    if (a !is Inet4Address) throw IOException("invalid IPv4 target")
                    byteArrayOf(5, 1, 0, 1) + a.address
                }
                else -> {
                    val name = host.toByteArray()
                    if (name.isEmpty() || name.size > 255) throw IOException("invalid target host")
                    byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name
                }
            }
            return head + byteArrayOf((port shr 8).toByte(), port.toByte())
        }
    }
}

class NetworkSocketFactory(private val manager: NetworkManager, private val network: NetworkEntry) : SocketFactory() {
    override fun createSocket(): Socket = NetworkSocket(manager, network)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
}
