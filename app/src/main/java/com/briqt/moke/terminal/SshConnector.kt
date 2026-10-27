package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.localized
import com.briqt.moke.net.NetworkSocketFactory
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 建连 + 认证 + 跳板 + TOFU 的唯一实现。
 *
 * 终端（SSH）、mosh 引导与控制通道、SFTP 三条路径需要的是同一件事：按 [Host] 连上去并认证，
 * 必要时先连跳板机再经其 `direct-tcpip` 转发。此前这段逻辑在 `SshTransport` 与 `MoshTransport`
 * 里各有一份，加上文件功能就会变成第三份——凭据处理（私钥临时落盘）出一点差异就是安全问题。
 *
 * [onNotice] 收 TOFU 告警等人类可读提示：终端把它写进屏幕，没有终端的调用方（文件传输）应把它
 * 接到任务失败原因上——**不能静默吞掉**，主机密钥变更必须让人看见。
 */
class SshConnector(
    context: Context,
    private val onNotice: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val cacheDir: File = appContext.cacheDir

    /**
     * 新建一个未连接的客户端（含 TOFU 校验器）。[heartbeat] 供长连接用，短连接不必。
     *
     * [awaitsTrust]=首连要弹指纹确认：主机密钥校验发生在握手中间、会同步等用户点按钮，而 sshj
     * 的握手 promise 默认 30s 就超时——用户认真核对指纹的时间远比这长。这种连接把握手超时放宽到
     * 略大于确认弹窗自身的超时，否则"仔细核对完再点信任"必然连不上。
     */
    fun newClient(
        heartbeat: Boolean = false,
        awaitsTrust: Boolean = false,
        network: NetworkEntry? = null,
        /** 经网络时实际拨的地址（mosh 引导用 tailnet 内已解析的 IP）；主机密钥仍按主机名校验与记录。 */
        dialHost: String? = null,
    ): SSHClient {
        val config = DefaultConfig().apply {
            if (heartbeat) keepAliveProvider = KeepAliveProvider.HEARTBEAT
        }
        val client = if (network == null) SSHClient(config) else object : SSHClient(config) {
            // 经网络时目标名保持未解析，交给网络（MagicDNS / 代理）解析，本机不查 DNS。
            override fun makeInetSocketAddress(hostname: String, port: Int): InetSocketAddress =
                InetSocketAddress.createUnresolved(dialHost ?: hostname, port)
        }
        return client.apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            if (awaitsTrust) transport.timeoutMs = TRUST_PROMPT_TIMEOUT_MS
            if (network != null) {
                socketFactory = NetworkSocketFactory((appContext as MokeApplication).networks, network)
            }
            addHostKeyVerifier(MokeHostKeyVerifier(KnownHosts(appContext), appContext, onNotice, network?.id))
        }
    }

    /** 这台主机的密钥还没被信任过，因而这次连接会停下来等用户确认。 */
    private fun awaitsTrust(host: Host, network: NetworkEntry?): Boolean =
        !HostKeyPrompt.autoTrust && KnownHosts(appContext).stored(KnownHosts.idOf(host.host, host.port, network?.id)) == null

    /**
     * 连上 [host] 并完成认证；[jumpHost] 非空时先连它、再经 direct-tcpip 到目标。
     * [network] 是**第一跳**的网络（见 `resolveRoute`）：有跳板机时只有跳板机那条连接走它，
     * 目标经跳板机的 direct-tcpip 到达。
     *
     * 返回值里的 [Connected.jump] 必须与 [Connected.client] 一起关闭——跳板连接是目标连接的
     * 载体，先关它目标连接立刻断。
     */
    fun connect(
        host: Host,
        jumpHost: Host?,
        network: NetworkEntry? = null,
        heartbeat: Boolean = false,
        /** 见 [newClient]；只用于不经跳板机、直接经网络的连接。 */
        dialHost: String? = null,
    ): Connected {
        // Validate before creating either client (including KnownHosts / socket-factory side effects).
        if (!validTailscaleSshRoute(host, jumpHost, network)) {
            throw IOException(appContext.localized(R.string.tailscale_ssh_auth_invalid_route))
        }
        val direct = if (jumpHost == null) network else null
        val client = newClient(heartbeat, awaitsTrust(host, direct), direct, dialHost.takeIf { direct != null })
        val (_, jump) = connectSshOwned(
            target = client,
            createJump = jumpHost?.let { h -> { newClient(heartbeat, awaitsTrust(h, network), network) } },
            connectJump = { it.connect(jumpHost!!.host, jumpHost.port) },
            authenticateJump = { authenticate(it, jumpHost!!) },
            connectTarget = { j ->
                if (j == null) client.connect(host.host, host.port)
                else client.connectVia(j.newDirectConnection(host.host, host.port))
            },
            authenticateTarget = { authenticate(it, host) },
            disconnect = { it.disconnect() },
        )
        return Connected(client, jump)
    }

    /** 短生命周期连接：块执行完即断开（mosh 控制通道、一次性 SFTP 操作）。 */
    fun <T> use(host: Host, jumpHost: Host?, network: NetworkEntry?, dialHost: String? = null, block: (SSHClient) -> T): T {
        val c = connect(host, jumpHost, network, dialHost = dialHost)
        try {
            return block(c.client)
        } finally {
            c.close()
        }
    }

    /** 认证。私钥用后立即删除；Tailscale SSH 只发送 none，不尝试任何凭据或回退。 */
    fun authenticate(client: SSHClient, h: Host) {
        when (h.authType) {
            AuthType.TAILSCALE_SSH -> try {
                TailscaleSshAuth.authenticate(client, h.username, h.displayName)
            } catch (failure: TailscaleSshAuthFailure) {
                val message = when (failure.kind) {
                    TailscaleSshAuthFailureKind.CANCELLED -> appContext.localized(R.string.tailscale_ssh_auth_cancelled)
                    TailscaleSshAuthFailureKind.TIMED_OUT -> appContext.localized(R.string.tailscale_ssh_auth_timed_out)
                    TailscaleSshAuthFailureKind.REJECTED -> if (failure.detail.isBlank()) {
                        appContext.localized(R.string.tailscale_ssh_auth_rejected)
                    } else {
                        appContext.localized(R.string.tailscale_ssh_auth_rejected_detail, failure.detail)
                    }
                }
                // Do not attach the raw sshj cause; disconnect errors can contain check URLs.
                throw TailscaleSshAuthException(failure.kind, message)
            }
            AuthType.PASSWORD -> client.authPassword(h.username, h.password)
            AuthType.KEY -> if (EncryptedPkcs8.matches(h.privateKeyPem)) {
                // 加口令的 PKCS#8 不走 sshj 自己的解密（Android 上会空指针），见 EncryptedPkcs8。
                val plain = EncryptedPkcs8.decryptToPem(h.privateKeyPem, h.passphrase.toCharArray())
                val kp = PKCS8KeyFile().apply { init(StringReader(plain)) }
                client.authPublickey(h.username, kp)
            } else {
                val keyFile = File.createTempFile("moke_key_", ".pem", cacheDir)
                try {
                    keyFile.writeText(h.privateKeyPem)
                    val finder = keyPasswordFinder(h.passphrase)
                    val kp = if (finder == null) {
                        client.loadKeys(keyFile.absolutePath)
                    } else {
                        client.loadKeys(keyFile.absolutePath, finder)
                    }
                    client.authPublickey(h.username, kp)
                } finally {
                    keyFile.delete()
                }
            }
        }
    }

    /** 一次连接的全部句柄。[close] 幂等，顺序固定为先目标后跳板。 */
    class Connected(val client: SSHClient, val jump: SSHClient?) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val socketsClosed = AtomicBoolean()

        /** Unblock a transport write before asking sshj to send CHANNEL_CLOSE / DISCONNECT. */
        @Synchronized
        internal fun abortSockets() {
            if (!socketsClosed.compareAndSet(false, true)) return
            runCatching { client.socket?.close() }
            runCatching { jump?.socket?.close() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            abortSockets()
            runCatching { client.disconnect() }
            runCatching { jump?.disconnect() }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15000

        /** 首连等用户确认指纹时的握手超时（略大于 HostKeyPrompt 自己的 120s）。 */
        private const val TRUST_PROMPT_TIMEOUT_MS = 150_000
    }
}

/** Tailscale SSH must itself be the first hop, through its selected tailnet, on the SSH service port. */
internal fun validTailscaleSshRoute(host: Host, jump: Host?, network: NetworkEntry?): Boolean {
    fun firstHop(h: Host): Boolean = h.port == 22 && h.jumpHostId.isBlank() &&
        network?.kind == NetworkKind.TAILSCALE && h.networkId.isNotBlank() && network.id == h.networkId
    return (host.authType != AuthType.TAILSCALE_SSH || (jump == null && firstHop(host))) &&
        (jump == null || jump.authType != AuthType.TAILSCALE_SSH || firstHop(jump))
}

/** A nonempty key passphrase is opaque, including leading, trailing or exclusively whitespace. */
internal fun keyPasswordFinder(passphrase: String): PasswordFinder? =
    if (passphrase.isEmpty()) null else PasswordUtils.createOneOff(passphrase.toCharArray())
