package com.briqt.moke.net

import com.briqt.moke.data.NetworkEntry
import java.io.DataInputStream
import java.io.IOException
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
        var connected = false
        try {
            super.connect(InetSocketAddress(InetAddress.getByName(ticket.host), ticket.port), timeout)
            connected = true
            handshake(ticket, host, target.port)
        } catch (t: Throwable) {
            if (!connected) manager.cancelDial(ticket)
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
        val name = host.toByteArray()
        if (name.isEmpty() || name.size > 255) throw IOException("invalid target host")
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
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
}

class NetworkSocketFactory(private val manager: NetworkManager, private val network: NetworkEntry) : SocketFactory() {
    override fun createSocket(): Socket = NetworkSocket(manager, network)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
}
