package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File

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
    fun newClient(heartbeat: Boolean = false, awaitsTrust: Boolean = false): SSHClient {
        val config = DefaultConfig().apply {
            if (heartbeat) keepAliveProvider = KeepAliveProvider.HEARTBEAT
        }
        return SSHClient(config).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            if (awaitsTrust) transport.timeoutMs = TRUST_PROMPT_TIMEOUT_MS
            addHostKeyVerifier(MokeHostKeyVerifier(KnownHosts(appContext), appContext, onNotice))
        }
    }

    /** 这台主机的密钥还没被信任过，因而这次连接会停下来等用户确认。 */
    private fun awaitsTrust(host: Host): Boolean =
        !HostKeyPrompt.autoTrust && KnownHosts(appContext).stored(KnownHosts.idOf(host.host, host.port)) == null

    /**
     * 连上 [host] 并完成认证；[jumpHost] 非空时先连它、再经 direct-tcpip 到目标。
     *
     * 返回值里的 [Connected.jump] 必须与 [Connected.client] 一起关闭——跳板连接是目标连接的
     * 载体，先关它目标连接立刻断。
     */
    fun connect(host: Host, jumpHost: Host?, heartbeat: Boolean = false): Connected {
        val client = newClient(heartbeat, awaitsTrust(host))
        var jump: SSHClient? = null
        try {
            if (jumpHost != null) {
                val j = newClient(heartbeat, awaitsTrust(jumpHost))
                j.connect(jumpHost.host, jumpHost.port)
                authenticate(j, jumpHost)
                jump = j
                client.connectVia(j.newDirectConnection(host.host, host.port))
            } else {
                client.connect(host.host, host.port)
            }
            authenticate(client, host)
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            runCatching { jump?.disconnect() }
            throw e
        }
        return Connected(client, jump)
    }

    /** 短生命周期连接：块执行完即断开（mosh 控制通道、一次性 SFTP 操作）。 */
    fun <T> use(host: Host, jumpHost: Host?, block: (SSHClient) -> T): T {
        val c = connect(host, jumpHost)
        try {
            return block(c.client)
        } finally {
            c.close()
        }
    }

    /** 密码 / 私钥认证。私钥必须先落盘（sshj 的 loadKeys 只吃路径），用完立即删除。 */
    fun authenticate(client: SSHClient, h: Host) {
        when (h.authType) {
            AuthType.PASSWORD -> client.authPassword(h.username, h.password)
            AuthType.KEY -> {
                val keyFile = File.createTempFile("moke_key_", ".pem", cacheDir)
                try {
                    keyFile.writeText(h.privateKeyPem)
                    val kp = if (h.passphrase.isBlank()) {
                        client.loadKeys(keyFile.absolutePath)
                    } else {
                        client.loadKeys(
                            keyFile.absolutePath,
                            PasswordUtils.createOneOff(h.passphrase.toCharArray()),
                        )
                    }
                    client.authPublickey(h.username, kp)
                } finally {
                    keyFile.delete()
                }
            }
        }
    }

    /** 一次连接的全部句柄。[close] 幂等，顺序固定为先目标后跳板。 */
    class Connected(val client: SSHClient, val jump: SSHClient?) {
        fun close() {
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
