package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionChannel
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
    /**
     * 运行时覆盖的协议级启动命令（tmux 附加用）：非空时在已分配 PTY 的 SSH command channel
     * 直接执行，不启动 shell/模拟键入。为 null 时才轮到主机自己配置的启动命令。
     */
    private val startupCommand: String? = null,
) : TerminalTransport {

    /** 本次真正要 exec 的命令；null=启动远端默认 login shell。 */
    private val effectiveStartup: String? = startupCommand?.takeIf { it.isNotBlank() }
        ?: host.effectiveStartupCommand.ifBlank { null }

    private val appContext = context.applicationContext

    private var ssh: SSHClient? = null
    private var jump: SSHClient? = null
    private var sshSession: SessionChannel? = null
    private var out: OutputStream? = null
    private val writeExec = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        Thread({
            try {
                // TOFU 告警等提示直接写进终端画面（这条连接天然有屏幕可写）。
                val connector = SshConnector(appContext) { msg ->
                    val b = ("\r\n" + msg + "\r\n").toByteArray(StandardCharsets.UTF_8)
                    session.processToEmulator(b, b.size)
                }
                if (jumpHost != null) {
                    feed(session, "\r\n" + appContext.getString(R.string.ssh_via_jump, jumpHost.host) + "\r\n")
                }
                // 全程 SSH 层心跳，避免空闲被中间设备/服务器断开。
                val conn = connector.connect(host, jumpHost, heartbeat = true)
                val client = conn.client
                jump = conn.jump
                runCatching { client.connection.keepAlive.keepAliveInterval = 30 }

                val s = client.startSession()
                s.allocatePTY("xterm-256color", columns, rows, cellWidthPixels, cellHeightPixels, emptyMap())
                // tmux 等专用交互程序必须走 SSH 协议级 exec + PTY。过去先 startShell 再延迟键入，
                // 会受 shell init、提示符、行编辑器和时序影响，所谓“专用连接”仍可能完全没执行。
                // 主机配置的启动命令**不加 sh 包装**：Windows OpenSSH 的 exec 走 cmd/powershell，
                // 任何 POSIX 包装都会当场失败——这正是这个功能的主要目标之一。命令若立刻退出，
                // 由下面的退出码提示如实说明，不假装还在。
                val channel = if (effectiveStartup == null) s.startShell() else s.exec(effectiveStartup)

                ssh = client
                sshSession = s as? SessionChannel
                out = channel.outputStream
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
                        feed(session, "\r\n" + appContext.getString(
                            R.string.startup_command_exited, effectiveStartup, code
                        ) + "\r\n")
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
        val s = sshSession ?: return
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
                // 先等远端命令结束；超时后主动关通道并返回失败。旧实现先 readBytes 再 join，
                // 命令若不结束会永久卡在读取处，所谓 10 秒超时实际上永远走不到。
                cmd.join(10, java.util.concurrent.TimeUnit.SECONDS)
                if (cmd.isOpen) {
                    runCatching { cmd.close() }
                    null
                } else {
                    cmd.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    override fun close() {
        closed = true
        writeExec.execute {
            runCatching { sshSession?.close() }
            runCatching { ssh?.disconnect() }
            runCatching { jump?.disconnect() }
        }
        writeExec.shutdown()
    }
}
