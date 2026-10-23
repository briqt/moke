package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.localized
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.connectFailureText
import com.briqt.moke.net.networkException
import com.termux.terminal.TerminalScrollToken
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

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
    /** 第一跳的网络（见 `resolveRoute`）；null = 直连。 */
    private val network: NetworkEntry? = null,
    /** 经网络连不上时报给会话（需要登录时失败条给出「登录」）。 */
    private val onNetworkError: (NetworkException) -> Unit = {},
    /** 实时延迟回调（ms，null=测不到）。用于终端状态条显示 RTT。 */
    private val onLatency: (Int?) -> Unit = {},
    /**
     * 运行时覆盖的协议级启动命令（tmux 附加用）：非空时在已分配 PTY 的 SSH command channel
     * 直接执行，不启动 shell/模拟键入。为 null 时才轮到主机自己配置的启动命令。
     */
    private val startupCommand: String? = null,
    private val observerNonce: String? = null,
    private val tmuxScripts: TmuxScripts? = null,
    /** Only the explicit tmux picker/create path may observe a default login shell. */
    private val observeDefaultShell: Boolean = false,
) : TerminalTransport, ForwardCapable, TmuxConnection {

    @Volatile override var tmuxNonce: String? = null
        private set
    override val tmuxOwnerRequired: Boolean = false

    override fun execTmuxOperation(command: String): String? {
        if (closed) return null
        // Ownership deliberately does not enter resources: data EOF/close must not lose this ACK.
        return runCatching {
            execTmuxOwned(SshConnector(appContext), host, jumpHost, network, command)
        }.getOrNull()
    }

    /** 本次真正要 exec 的命令；null=启动远端默认 login shell。 */
    private val effectiveStartup: String? = startupCommand?.takeIf { it.isNotBlank() }
        ?: host.effectiveStartupCommand.ifBlank { null }

    private val appContext = context.applicationContext

    @Volatile private var connected: SshConnector.Connected? = null
    @Volatile private var ssh: SSHClient? = null
    @Volatile private var sshSession: SessionChannel? = null
    @Volatile private var out: OutputStream? = null
    private val resources = TransportResources()
    private val closed get() = resources.closed
    private val inputQueue = TerminalInputQueue("moke-ssh-input") { if (closed) null else out }
    private val windowSize = TerminalSizeState(inputQueue::execute)
    /** shell / exec 通道已经打开：此后的异常是"中断"，此前的是"连接失败"。 */
    private val established get() = resources.established
    @Volatile private var latencyThread: Thread? = null
    @Volatile private var loginThread: Thread? = null
    private val closeRequested = AtomicBoolean()

    /** 连接建立（shell / exec 通道已开）时回调一次，在传输线程上调用——别在里面做阻塞工作。 */
    var onEstablished: (() -> Unit)? = null

    // 端口转发直接借用会话自己的连接：它和会话同生共死，无需另外维持。
    override fun acquireForwardClient(): SSHClient? = ssh?.takeIf { established && !closed && it.isConnected && it.isAuthenticated }

    override fun releaseForwardClient() {}

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (!resources.start()) {
            if (closed && !resources.started) resources.finish { session.onTransportFinished(0) }
            return
        }
        windowSize.initialize(TerminalWindowSize(columns, rows, cellWidthPixels, cellHeightPixels))
        Thread({
            try {
                // TOFU 告警等提示直接写进终端画面（这条连接天然有屏幕可写）。
                val connector = SshConnector(appContext) { msg ->
                    val b = ("\r\n" + msg + "\r\n").toByteArray(StandardCharsets.UTF_8)
                    session.processToEmulator(b, b.size)
                }
                // 建连要花几秒（DNS + TCP + 认证），主机不可达时还要等满超时。此前这段时间里
                // 屏幕上一个字都没有，用户分不清「正在连」和「已经卡死」——mosh 侧一直有引导提示，
                // SSH 这边补齐。
                val target = buildString {
                    if (host.username.isNotBlank()) {
                        append(host.username)
                        append('@')
                    }
                    append(host.host)
                    if (host.port != 22) {
                        append(':')
                        append(host.port)
                    }
                }
                feed(session, "\r\n" + appContext.localized(R.string.ssh_connecting, target) + "\r\n")
                if (jumpHost != null) {
                    feed(session, appContext.localized(R.string.ssh_via_jump, jumpHost.host) + "\r\n")
                }
                if (network != null) {
                    feed(session, appContext.localized(R.string.net_via, network.name) + "\r\n")
                }
                // 全程 SSH 层心跳，避免空闲被中间设备/服务器断开。
                var selectedStartup = TmuxStartup(effectiveStartup)
                withSshChannel(
                    resources,
                    connect = { connector.connect(host, jumpHost, network, heartbeat = true).also { connected = it } },
                    disconnect = { it.close() },
                    startSession = { conn ->
                        runCatching { conn.client.connection.keepAlive.keepAliveInterval = 30 }
                        selectedStartup = sshTmuxStartup(
                            startupCommand, effectiveStartup, observerNonce, tmuxScripts,
                            observeDefaultShell = observeDefaultShell,
                        ) { supportsTmuxObserver(conn.client) }
                        conn.client.startSession()
                    },
                    closeSession = { it.close() },
                    allocatePty = { channel ->
                        val size = windowSize.current()
                        channel.allocatePTY("xterm-256color", size.columns, size.rows,
                            size.cellWidthPixels, size.cellHeightPixels, emptyMap())
                    },
                    // Only an explicitly requested, supported tmux carrier is wrapped; preserve startShell otherwise.
                    openChannel = { s ->
                        val command = selectedStartup.command
                        (if (command == null) s.startShell() else s.exec(command)).also {
                            tmuxNonce = selectedStartup.nonce
                        }
                    },
                    beforeSessionClose = { it.abortSockets() },
                ) { conn, s, channel ->
                    val client = conn.client
                    ssh = client
                    sshSession = s as? SessionChannel
                    out = channel.outputStream
                    val resizeSession = sshSession
                    windowSize.attach { size ->
                        if (!closed) resizeSession?.changeWindowDimensions(size.columns, size.rows,
                            size.cellWidthPixels, size.cellHeightPixels)
                    }
                    if (!resources.establish { onEstablished?.invoke() }) return@withSshChannel
                    startLatencyProbe(client)

                    val input = channel.inputStream
                    val buf = ByteArray(8192)
                    // 登录后自动执行命令：等 shell 首个输出到达、且输出**静默 ~250ms**（提示符画完、bracketed-paste/
                    // 行编辑就绪）后再发；只等"首字节"仍会抢在半就绪的行编辑器里发，丢/错首字符（曾见 `<sh`/`>`）、回显重复。
                    // 换行用 CR（真实回车），与附加键/文本段一致；多行逐行执行。仅发一次。
                    // tmux 覆盖（startupCommand 非空）时不注入：那条通道上跑的是 tmux 自身。
                    // 主机自配启动命令时仍注入——"登录后自动执行"对 cmd/powershell 之类同样成立。
                    val loginCmd = host.loginCommand
                    val lastOut = java.util.concurrent.atomic.AtomicLong(0L)
                    if (startupCommand == null && loginCmd.isNotBlank()) {
                        loginThread = Thread({
                            while (!closed && lastOut.get() == 0L) { try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread } }
                            val deadline = System.currentTimeMillis() + 4000  // 静默等待上限，兜底
                            while (!closed && System.currentTimeMillis() < deadline &&
                                System.currentTimeMillis() - lastOut.get() < 250
                            ) { try { Thread.sleep(60) } catch (_: InterruptedException) { return@Thread } }
                            if (!closed) runCatching {
                                val payload = loginCmd.replace("\r\n", "\n").replace("\n", "\r")
                                    .let { if (it.endsWith("\r")) it else it + "\r" }
                                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                                write(bytes, 0, bytes.size)
                            }
                        }, "moke-ssh-login-${host.host}").also { it.start() }
                    }
                    while (!closed) {
                        val n = input.read(buf)
                        if (n == -1) break
                        if (n > 0) {
                            session.processToEmulator(buf, n)
                            lastOut.set(System.currentTimeMillis())
                        }
                    }
                    // 启动命令非零退出（拼错、远端没这个程序、参数不对）：把退出码摆出来，
                    // 否则屏幕上只剩一句"会话已结束"，用户无从判断是自己写错还是网络断了。
                    if (startupCommand == null && effectiveStartup != null) {
                        // exit-status 是通道关闭前后才到的异步消息：EOF 之后立刻读通常还是 null，
                        // 必须先等通道真正 close（真机实测：不等就永远拿不到退出码）。
                        val code = runCatching {
                            sshSession?.join(2, java.util.concurrent.TimeUnit.SECONDS)
                            sshSession?.exitStatus
                        }.getOrNull()
                        if (code != null && code != 0) {
                            feed(session, "\r\n" + appContext.localized(
                                R.string.startup_command_exited, effectiveStartup, code
                            ) + "\r\n")
                        }
                    }
                }
                resources.finish { session.onTransportFinished(0) }
            } catch (t: Throwable) {
                // 主动关闭期间迟到的认证/会话错误不是连接失败，也不能再次宣告 established。
                resources.finish {
                    if (closeRequested.get()) {
                        session.onTransportFinished(0)
                    } else if (!established) {
                        t.networkException()?.let(onNetworkError)
                        session.onTransportConnectFailed(t.connectFailureText(appContext))
                    } else {
                        feed(session, "\r\n" + appContext.localized(R.string.ssh_connection_lost, t.message ?: "") + "\r\n")
                        session.onTransportFinished(1)
                    }
                }
            } finally {
                resources.stop().invoke()
                stopAuxiliaryWork()
                out = null
                sshSession = null
                ssh = null
                connected = null
            }
        }, "moke-ssh-${host.host}").start()
    }

    private fun feed(session: TerminalSession, msg: String) {
        val b = msg.toByteArray(StandardCharsets.UTF_8)
        session.processToEmulator(b, b.size)
    }

    /** 周期性 RTT 探测：发一个带回复的 keepalive 全局请求，测往返耗时 → 状态条延迟。 */
    private fun startLatencyProbe(client: SSHClient) {
        latencyThread = Thread({
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
        }, "moke-ssh-rtt-${host.host}").also { it.start() }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (!closed) inputQueue.write(data, offset, count)
    }

    override fun writeScroll(data: ByteArray, offset: Int, count: Int, token: TerminalScrollToken) {
        if (!closed) inputQueue.writeScroll(data, offset, count, token)
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // 上报窗口尺寸变化（SSH window-change）——字号调整/旋转/键盘弹收都会触发，
        // 否则远端 PTY 尺寸不变，全屏 TUI（vim/htop/tmux）的底栏/状态行会错位。
        if (closed) return
        windowSize.update(TerminalWindowSize(columns, rows, cellWidthPixels, cellHeightPixels))
    }

    /**
     * 带外执行命令并返回 stdout（tmux 侧通道管理用）：在已建立的 [ssh] 连接上开新 exec 通道，静默、不占前台 PTY。
     * 未连上/已关闭返回 null（调用方据此判定"尚未就绪、可重试"）；跑通但无输出返回空串；异常返回 null。
     */
    override fun exec(command: String): String? {
        val client = ssh ?: return null
        if (closed) return null
        return runCatching { execTmuxChannel(client, command) }.getOrNull()
    }

    private fun stopAuxiliaryWork() {
        latencyThread?.interrupt()
        loginThread?.interrupt()
        inputQueue.shutdownNow()
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        val cleanup = resources.stop()
        stopAuxiliaryWork()
        // A blocked channel write must not hold the only path that can close its connection.
        // close() is called by UI; both sshj DISCONNECT and channel close stay off that thread.
        val connection = connected
        Thread({ connection?.abortSockets(); cleanup() }, "moke-ssh-close").start()
    }
}
