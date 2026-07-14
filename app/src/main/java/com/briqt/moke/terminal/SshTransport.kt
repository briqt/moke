package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * SSH 传输（sshj）：
 *  - 主机密钥走 TOFU 校验（[MokeHostKeyVerifier] + [KnownHosts]）：首次记录、变更告警。
 *  - 连接与 shell 在后台线程建立；读线程把远端输出喂给 emulator，写走单线程 executor 避免阻塞 UI。
 */
class SshTransport(
    private val host: Host,
    context: Context,
    /** 跳板机（可空）：先连它，再经其 direct-tcpip 转发到 [host]。 */
    private val jumpHost: Host? = null,
    /** 实时延迟回调（ms，null=测不到）。用于终端状态条显示 RTT。 */
    private val onLatency: (Int?) -> Unit = {},
) : TerminalTransport {

    private val appContext = context.applicationContext
    private val cacheDir: File = appContext.cacheDir

    private var ssh: SSHClient? = null
    private var jump: SSHClient? = null
    private var sshSession: Session? = null
    private var shell: Session.Shell? = null
    private var out: OutputStream? = null
    private val writeExec = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false

    private fun newClient(session: TerminalSession): SSHClient {
        // 全程 SSH 层心跳，避免空闲被中间设备/服务器断开。
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.HEARTBEAT }
        return SSHClient(config).apply {
            connectTimeout = 15000
            addHostKeyVerifier(
                MokeHostKeyVerifier(KnownHosts(appContext), appContext) { msg ->
                    val b = ("\r\n" + msg + "\r\n").toByteArray(StandardCharsets.UTF_8)
                    session.processToEmulator(b, b.size)
                }
            )
        }
    }

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        Thread({
            try {
                val client = newClient(session)
                if (jumpHost != null) {
                    // 经跳板机：连 + 认证跳板 → direct-tcpip 到目标 → 在该通道上握手目标 SSH。
                    feed(session, "\r\n" + appContext.getString(R.string.ssh_via_jump, jumpHost.host) + "\r\n")
                    val j = newClient(session)
                    j.connect(jumpHost.host, jumpHost.port)
                    authenticate(j, jumpHost)
                    jump = j
                    val direct = j.newDirectConnection(host.host, host.port)
                    client.connectVia(direct)
                } else {
                    client.connect(host.host, host.port)
                }
                authenticate(client, host)
                runCatching { client.connection.keepAlive.keepAliveInterval = 30 }

                val s = client.startSession()
                s.allocatePTY("xterm-256color", columns, rows, cellWidthPixels, cellHeightPixels, emptyMap())
                val sh = s.startShell()

                ssh = client
                sshSession = s
                shell = sh
                out = sh.outputStream
                startLatencyProbe(client)

                val input = sh.inputStream
                val buf = ByteArray(8192)
                // 登录后自动执行命令：等 shell 首个输出到达、且输出**静默 ~250ms**（提示符画完、bracketed-paste/
                // 行编辑就绪）后再发；只等"首字节"仍会抢在半就绪的行编辑器里发，丢/错首字符（曾见 `<sh`/`>`）、回显重复。
                // 换行用 CR（真实回车），与附加键/文本段一致；多行逐行执行。仅发一次。
                val loginCmd = host.loginCommand
                val lastOut = java.util.concurrent.atomic.AtomicLong(0L)
                if (loginCmd.isNotBlank()) {
                    Thread({
                        while (!closed && lastOut.get() == 0L) { try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread } }
                        val deadline = System.currentTimeMillis() + 4000  // 静默等待上限，兜底
                        while (!closed && System.currentTimeMillis() < deadline &&
                            System.currentTimeMillis() - lastOut.get() < 250
                        ) { try { Thread.sleep(60) } catch (_: InterruptedException) { return@Thread } }
                        if (!closed) runCatching {
                            val payload = loginCmd.replace("\r\n", "\n").replace("\n", "\r")
                                .let { if (it.endsWith("\r")) it else it + "\r" }
                            out?.write(payload.toByteArray(StandardCharsets.UTF_8))
                            out?.flush()
                        }
                    }, "moke-ssh-login-${host.host}").start()
                }
                while (!closed) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        session.processToEmulator(buf, n)
                        lastOut.set(System.currentTimeMillis())
                    }
                }
                session.onTransportFinished(0)
            } catch (e: Exception) {
                val msg = ("\r\n" + appContext.getString(R.string.ssh_connect_failed, e.message ?: "") + "\r\n").toByteArray()
                session.processToEmulator(msg, msg.size)
                session.onTransportFinished(1)
            }
        }, "moke-ssh-${host.host}").start()
    }

    private fun feed(session: TerminalSession, msg: String) {
        val b = msg.toByteArray(StandardCharsets.UTF_8)
        session.processToEmulator(b, b.size)
    }

    /** 周期性 RTT 探测：发一个带回复的 keepalive 全局请求，测往返耗时 → 状态条延迟。 */
    private fun startLatencyProbe(client: SSHClient) {
        Thread({
            while (!closed) {
                val ms = runCatching {
                    val t0 = System.nanoTime()
                    val p = client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0))
                    // 服务器对未知 keepalive 请求回 FAILURE 也算"回复"，据此计时；异常同样代表已往返。
                    runCatching { p.retrieve(5, java.util.concurrent.TimeUnit.SECONDS) }
                    ((System.nanoTime() - t0) / 1_000_000).toInt()
                }.getOrNull()
                if (closed) break
                // ≥4500ms 视为未回复（避免把超时误报成假延迟）。
                onLatency(ms?.takeIf { it < 4500 })
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }, "moke-ssh-rtt-${host.host}").start()
    }

    private fun authenticate(client: SSHClient, h: Host) {
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
                            PasswordUtils.createOneOff(h.passphrase.toCharArray())
                        )
                    }
                    client.authPublickey(h.username, kp)
                } finally {
                    keyFile.delete()
                }
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (closed) return
        val copy = data.copyOfRange(offset, offset + count)
        writeExec.execute {
            try {
                out?.write(copy)
                out?.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // 上报窗口尺寸变化（SSH window-change）——字号调整/旋转/键盘弹收都会触发，
        // 否则远端 PTY 尺寸不变，全屏 TUI（vim/htop/tmux）的底栏/状态行会错位。
        if (closed) return
        val s = sshSession as? SessionChannel ?: return
        writeExec.execute {
            runCatching { s.changeWindowDimensions(columns, rows, cellWidthPixels, cellHeightPixels) }
        }
    }

    /**
     * 带外执行命令并返回 stdout（tmux 侧通道管理用）：在已建立的 [ssh] 连接上开新 exec 通道，静默、不占前台 PTY。
     * 未连上/已关闭返回 null（调用方据此判定"尚未就绪、可重试"）；跑通但无输出返回空串；异常返回 null。
     */
    override fun exec(command: String): String? {
        val client = ssh ?: return null
        if (closed) return null
        return runCatching {
            client.startSession().use { s ->
                val cmd = s.exec(command)
                val out = cmd.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                runCatching { cmd.join(10, java.util.concurrent.TimeUnit.SECONDS) }
                out
            }
        }.getOrNull()
    }

    override fun close() {
        closed = true
        writeExec.execute {
            runCatching { shell?.close() }
            runCatching { sshSession?.close() }
            runCatching { ssh?.disconnect() }
            runCatching { jump?.disconnect() }
        }
        writeExec.shutdown()
    }
}
