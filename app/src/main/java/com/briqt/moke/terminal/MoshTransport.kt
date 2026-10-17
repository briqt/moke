package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.localized
import android.os.ParcelFileDescriptor
import com.briqt.moke.data.Host
import com.briqt.moke.MokeApplication
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.net.UdpRelay
import com.briqt.moke.net.connectFailureText
import com.termux.terminal.JNI
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * mosh 传输（总纲 §5.5 的落地）：
 *  1. sshj 连上并执行 `mosh-server new`，解析 `MOSH CONNECT <udp_port> <base64_key>`（见 {@link MoshBootstrap}）。
 *  2. 通过 {@link JNI#createSubprocess}（forkpty，Apache-2.0）在**独立子进程**里以 PTY 运行随包的
 *     native `libmosh-client.so`（GPLv3，独立可执行文件，仅经 PTY/管道 IPC 交互，不与本应用代码链接）。
 *  3. PTY 主设备 fd 双向桥接到 {@link TerminalSession}；窗口尺寸经 ioctl(TIOCSWINSZ) 传递。
 *
 * mosh 的 UDP 漫游让"关屏/切网不断线"成为可能——移动端护城河。
 */
class MoshTransport(
    private val host: Host,
    context: Context,
    /** 跳板机（可空）：SSH 引导阶段经其转发（UDP 数据面仍需目标直连可达）。 */
    private val jumpHost: Host? = null,
    /**
     * 运行时覆盖的协议级启动命令（tmux 附加用）：非空时让 mosh-server 直接启动该交互程序，
     * 不经 shell 提示符注入。为 null 时才轮到主机自己配置的启动命令。
     */
    private val startupCommand: String? = null,
    /** 第一跳的网络（见 `resolveRoute`）；null = 直连。 */
    private val network: NetworkEntry? = null,
) : TerminalTransport, ForwardCapable {

    /** 本次交给 `mosh-server … -- …` 的程序；null=远端默认 shell。 */
    private val effectiveStartup: String? = startupCommand?.takeIf { it.isNotBlank() }
        ?: host.effectiveStartupCommand.ifBlank { null }

    private val appContext = context.applicationContext
    private val nativeLibDir = appContext.applicationInfo.nativeLibraryDir

    private var pfd: ParcelFileDescriptor? = null
    private var ptyFd: Int = -1
    private var pid: Int = -1
    private var out: FileOutputStream? = null
    private val writeExec = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var childExited = false
    private val finishReported = AtomicBoolean(false)

    /** 经网络时 mosh 的 UDP 中继（占网络的一份租约）；会话结束时归还，只归还一次。 */
    private val relay = AtomicReference<UdpRelay?>(null)
    private val networks get() = (appContext as MokeApplication).networks

    private fun releaseRelay() {
        relay.getAndSet(null)?.let { networks.closeRelay(it) }
    }

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        Thread({
            try {
                // 0) 先确认随包 native 组件在位——缺失时给出可读提示并结束，
                //    绝不放行到 JNI（否则 System.loadLibrary 抛 UnsatisfiedLinkError 会使 app 闪退）。
                val moshBin = File("$nativeLibDir/libmosh-client.so")
                val termuxBin = File("$nativeLibDir/libtermux.so")
                if (!moshBin.exists() || !termuxBin.exists()) {
                    feed(session, "\r\n" + appContext.localized(R.string.mosh_unavailable) + "\r\n")
                    failConnect(session, null)
                    return@Thread
                }

                // 经网络（不经跳板机）时 UDP 走 netbridge 中继：先开中继——网络不能转 UDP 就在这里失败，
                // 远端什么都还没起；再引导，拿到端口与密钥后才开始转发。有跳板机时 UDP 照旧直达目标。
                val udpNetwork = network?.takeIf { jumpHost == null }
                if (udpNetwork != null) {
                    feed(session, "\r\n" + appContext.localized(R.string.net_via, udpNetwork.name))
                    // tailnet：解析一次得到字面 IP，SSH 引导与中继共用（mosh-server -s 绑定 SSH 到达的地址）。
                    val target = if (udpNetwork.kind == NetworkKind.TAILSCALE) {
                        networks.resolveTarget(udpNetwork, host.host, host.port).also { bootstrapDialHost = it }
                    } else {
                        host.host // SOCKS5 代理自行解析（ATYP=3）。
                    }
                    relay.set(networks.openRelay(udpNetwork, target))
                }

                // 1) SSH 引导：执行 mosh-server new，解析 MOSH CONNECT
                feed(session, "\r\n" + appContext.localized(R.string.mosh_bootstrapping) + "\r\n")
                val bootstrap = sshBootstrap()
                serverPid = MoshBootstrap.parsePid(bootstrap.stderr)
                val connect = MoshBootstrap.parse(bootstrap.stdout)
                    ?: throw IllegalStateException(
                        appContext.localized(R.string.mosh_bootstrap_unparsed, bootstrap.stdout.trim())
                    )
                relay.get()?.let { networks.startRelay(it, udpNetwork!!, connect.port, connect.key) }
                feed(session, appContext.localized(R.string.mosh_client_starting, connect.port) + "\r\n")
                val clientHost = if (relay.get() != null) "127.0.0.1" else host.host
                val clientPort = relay.get()?.localPort ?: connect.port

                // 2) 独立子进程 + PTY 运行 native mosh-client
                val bin = "$nativeLibDir/libmosh-client.so"
                // Android 无系统 terminfo；释放随包的 terminfo 并经 TERMINFO 指给 mosh-client(ncurses)，
                // 否则 mosh-client 报 "Terminfo database could not be found" 直接退出。
                val terminfoDir = ensureTerminfo()
                val env = arrayOf(
                    "MOSH_KEY=${connect.key}",
                    "TERM=xterm-256color",
                    "TERMINFO=${terminfoDir.absolutePath}",
                    "LANG=en_US.UTF-8",
                    "LC_ALL=en_US.UTF-8",
                    "LC_CTYPE=en_US.UTF-8",
                    "HOME=${appContext.filesDir.absolutePath}",
                    "PATH=/system/bin",
                )
                // 引导期间会话已被关掉：不再起客户端（否则本地子进程与中继都没人收），由下方收掉远端。
                check(!closed) { "session closed during bootstrap" }
                val pidArr = IntArray(1)
                val fd = JNI.createSubprocess(
                    bin, appContext.filesDir.absolutePath,
                    arrayOf("mosh-client", clientHost, clientPort.toString()),
                    env, pidArr, rows, columns, cellWidthPixels, cellHeightPixels,
                )
                ptyFd = fd
                pid = pidArr[0]
                if (pid > 0) onEstablished?.invoke()
                val descriptor = ParcelFileDescriptor.adoptFd(fd)
                pfd = descriptor
                out = FileOutputStream(descriptor.fileDescriptor)

                // waitpid 是子进程退出状态的唯一真相来源，也是唯一负责结束 TerminalSession 的路径。
                // PTY 读端在 Android/Linux 上会以 EIO 表示 slave 已关闭，不能再据此判定连接失败。
                val childPid = pid
                Thread({
                    val code = try {
                        JNI.waitFor(childPid)
                    } catch (_: Throwable) {
                        if (closed) 0 else 1
                    }
                    childExited = true
                    releaseRelay()
                    finish(session, code)
                    runCatching { pfd?.close() }
                    ptyFd = -1
                }, "moke-mosh-waiter").start()

                // 连接成功后自动执行命令：mosh 握手需片刻，延迟发送再触发回车。
                // 与 SSH 侧一致：tmux 覆盖时不注入，主机自配启动命令时仍注入。
                if (startupCommand == null && host.loginCommand.isNotBlank()) {
                    Thread({
                        runCatching {
                            Thread.sleep(1500)
                            // 换行用 CR（真实回车），与 SSH/附加键/文本段一致；多行逐行执行。
                            val payload = host.loginCommand.replace("\r\n", "\n").replace("\n", "\r")
                                .let { if (it.endsWith("\r")) it else it + "\r" }
                            out?.write(payload.toByteArray(StandardCharsets.UTF_8))
                            out?.flush()
                        }
                    }, "moke-mosh-login").start()
                }

                // 3) 读线程：PTY -> emulator
                val input = FileInputStream(descriptor.fileDescriptor)
                val buf = ByteArray(8192)
                try {
                    while (!closed) {
                        val n = input.read(buf)
                        if (n == -1) break
                        if (n > 0) session.processToEmulator(buf, n)
                    }
                } catch (e: Throwable) {
                    if (!closed && !childExited && !MoshPty.isClosed(e)) {
                        feed(
                            session,
                            "\r\n" + appContext.localized(
                                R.string.mosh_connect_failed,
                                e.message ?: e.javaClass.simpleName,
                            ) + "\r\n",
                        )
                        // 非挂断型 PTY 读错误无法继续桥接；终止子进程，由 waiter 上报真实退出码。
                        if (pid > 0) {
                            runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                        }
                    }
                }
            } catch (e: Throwable) {
                // 捕获 Throwable（含 UnsatisfiedLinkError 等 Error），保证任何 native/引导失败都只是终端里报错，绝不闪退。
                if (!closed && !childExited && pid > 0) {
                    feed(
                        session,
                        "\r\n" + appContext.localized(
                            R.string.mosh_connect_failed,
                            e.message ?: e.javaClass.simpleName,
                        ) + "\r\n",
                    )
                }
                // createSubprocess 成功后仍由 waiter 收口；引导/创建阶段失败才在这里结束。
                if (pid > 0) {
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    return@Thread
                }
                releaseRelay()
                // 引导已起了 mosh-server 却没能接上：经 SSH 结束它，不留到 24h 超时。
                serverPid?.let { killOrphanServer(it) }
                if (!closed) {
                    // mosh-client 还没起来（引导 SSH 连不上 / mosh-server 回应无法解析）：属于"连接失败"。
                    failConnect(session, e.connectFailureText(appContext))
                } else {
                    finish(session, 1)
                }
            }
        }, "moke-mosh-${host.host}").start()
    }

    /** 经网络引导时实际拨的地址（tailnet 内已解析的 IP，与中继同一个）；null = 按主机名。 */
    @Volatile private var bootstrapDialHost: String? = null

    /** 引导起来的 mosh-server 进程号（stderr 里读到的）。 */
    @Volatile private var serverPid: Int? = null

    private class BootstrapOutput(val stdout: String, val stderr: String)

    /** 引导只跑一次，用完即弃的短连接（此时控制连接还没有存在的理由）。 */
    private fun sshBootstrap(): BootstrapOutput {
        return SshConnector(appContext).use(host, jumpHost, network, bootstrapDialHost) { client ->
            client.startSession().use { s ->
                val cmd = s.exec(MoshBootstrap.serverCommand(startupCommand = effectiveStartup))
                val stdout = IOUtils.readFully(cmd.inputStream).toString()
                val stderr = IOUtils.readFully(cmd.errorStream).toString()
                cmd.join()
                BootstrapOutput(stdout, stderr)
            }
        }
    }

    /** 尽力结束刚引导起来、却没有客户端接上的 mosh-server（后台进行，失败只剩 24h 兜底）。 */
    private fun killOrphanServer(pid: Int) {
        Thread({
            runCatching {
                SshConnector(appContext).use(host, jumpHost, network, bootstrapDialHost) { client ->
                    client.startSession().use { s -> s.exec("kill $pid").join(5, TimeUnit.SECONDS) }
                }
            }
        }, "moke-mosh-orphan").start()
    }

    /**
     * mosh 数据面虽是 UDP，管理 tmux 仍需一条 SSH 控制连接。失败返回 null，由 UI 明确显示并允许重试。
     */
    override fun exec(command: String): String? {
        if (closed) return null
        return runCatching {
            withControlClient { client ->
                client.startSession().use { s ->
                    val cmd = s.exec(command)
                    cmd.join(10, TimeUnit.SECONDS)
                    if (cmd.isOpen) {
                        runCatching { cmd.close() }
                        return@withControlClient null
                    }
                    cmd.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    // ---------- tmux 侧通道：空闲即回收的可复用控制连接 ----------

    private val controlLock = ReentrantLock()
    private var control: SshConnector.Connected? = null
    private var controlIdleSince = 0L
    /** 端口转发借用控制连接的次数（受 [controlLock] 保护）：大于 0 时不做空闲回收。 */
    private var forwardHolds = 0

    /** mosh-client 子进程起来时回调一次，在传输线程上调用——别在里面做阻塞工作。 */
    var onEstablished: (() -> Unit)? = null

    // mosh 本身不转发 TCP，端口转发只能走 SSH 控制连接。借用期间它必须一直在，
    // 这部分抵消了 mosh"不长期挂 TCP"的好处——只在确实有转发时才这样。
    override fun acquireForwardClient(): SSHClient? = controlLock.withLock {
        if (closed) return null
        runCatching { controlClient() }.getOrNull()?.also { forwardHolds++ }
    }

    override fun releaseForwardClient() {
        controlLock.withLock {
            if (forwardHolds > 0) forwardHolds--
            // 会话关了才归还（转发线程收尾慢于 close）：连接直接收掉，不再起回收线程。
            if (closed) {
                closeControl()
                return
            }
            controlIdleSince = System.currentTimeMillis()
            scheduleReap()
        }
    }
    private var reaper: ScheduledExecutorService? = null

    /**
     * 在控制连接上跑一段，**空闲窗口内复用同一条连接**。
     *
     * 每次进终端页就会连着触发「探测 tmux + 附加确认（最多 8 轮）+ 下发滚动绑定」，逐条新建
     * 意味着一次进页面打出十来次完整 SSH 登录：auth.log 难看，有 fail2ban / MaxStartups 的主机
     * 还可能把自己封掉。反过来也不能长期挂着 TCP —— 那会抵消 mosh「关屏/换网不断线」的意义。
     * 折中就是这里：忙的时候复用，空闲 [CONTROL_IDLE_MS] 后自动断开。
     *
     * 复用的连接可能在两次调用之间被中间设备掐断，所以失败重连一次再试（与 SftpSession 同口径）。
     */
    private fun <T> withControlClient(block: (SSHClient) -> T): T = controlLock.withLock {
        check(!closed) { "transport closed" }
        try {
            block(controlClient())
        } catch (t: Throwable) {
            if (closed) throw t
            closeControl()
            block(controlClient())
        } finally {
            controlIdleSince = System.currentTimeMillis()
            scheduleReap()
        }
    }

    private fun controlClient(): SSHClient {
        control?.takeIf { it.client.isConnected && it.client.isAuthenticated }?.let { return it.client }
        closeControl()
        val c = SshConnector(appContext).connect(host, jumpHost, network, heartbeat = true)
        runCatching { c.client.connection.keepAlive.keepAliveInterval = 30 }
        control = c
        return c.client
    }

    private fun closeControl() {
        runCatching { control?.close() }
        control = null
    }

    /** 空闲回收：拿不到锁说明正在用，改期再看，绝不打断进行中的命令。 */
    private fun scheduleReap() {
        if (closed) return
        val exec = reaper ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "moke-mosh-ctl-reaper").apply { isDaemon = true }
        }.also { reaper = it }
        runCatching {
            exec.schedule({
                if (controlLock.tryLock()) {
                    try {
                        if (closed || (forwardHolds == 0 && System.currentTimeMillis() - controlIdleSince >= CONTROL_IDLE_MS)) closeControl()
                        else scheduleReap()
                    } finally {
                        controlLock.unlock()
                    }
                } else {
                    scheduleReap()
                }
            }, CONTROL_IDLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (closed) return
        val copy = data.copyOfRange(offset, offset + count)
        writeExec.execute {
            try { out?.write(copy); out?.flush() } catch (_: Exception) {}
        }
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (ptyFd >= 0 && !closed) {
            runCatching { JNI.setPtyWindowSize(ptyFd, rows, columns, cellWidthPixels, cellHeightPixels) }
        }
    }

    override fun close() {
        val child = pid
        val stream = out
        closed = true
        // 关会话要连远端一起收掉：直接 SIGKILL 只杀本地 mosh-client，远端 mosh-server 按漫游语义
        // 会把"客户端不见了"当成网络断了继续等下一个客户端——实测每关一个会话就在服务器上留下一个
        // mosh-server（~4.5MB + 占住一个 UDP 端口），tmux 也一直显示 (attached)，直到主机重启。
        // 正解是让 mosh-client 走它自己的退出协议（Ctrl-^ 后 '.'，MOSH_ESCAPE_KEY 未改即默认 0x1E），
        // 它会通知 server 结束。整段跑在 writeExec（后台单线程）上：close 来自 UI，不能阻塞。
        writeExec.execute {
            val quitSent = child > 0 && !childExited && stream != null &&
                runCatching { stream.write(MOSH_QUIT_SEQUENCE); stream.flush(); true }.getOrDefault(false)
            if (quitSent) {
                // 等它把 shutdown 发出去并退出；一个 RTT 量级的事，超时就照旧 SIGKILL（不回退风险）。
                val deadline = System.currentTimeMillis() + QUIT_GRACE_MS
                while (!childExited && System.currentTimeMillis() < deadline) {
                    runCatching { Thread.sleep(25) }
                }
            }
            runCatching { pfd?.close() }
            if (child > 0 && !childExited) {
                runCatching { android.system.Os.kill(child, android.system.OsConstants.SIGKILL) }
            }
            // 退出握手要经中继送达，所以等它结束才归还（子进程没起来时也在这里收）。
            releaseRelay()
        }
        writeExec.shutdown()
        // 控制连接与回收线程独立于 PTY，必须一并收掉，否则会话关掉了 TCP 还挂着。
        runCatching { reaper?.shutdownNow() }
        reaper = null
        // 不能就地断开：close 来自主线程，sshj 断开时要往 socket 写 DISCONNECT，主线程网络 I/O 会抛异常、
        // 异常被吞掉、socket 没关而引用已清空——实测关掉会话后 TCP 一直挂在服务器上。
        // 正在跑一条命令时，这个线程等它结束再收尾。
        Thread({ controlLock.withLock { closeControl() } }, "moke-mosh-ctl-close").start()
    }

    private fun feed(session: TerminalSession, msg: String) {
        val b = msg.toByteArray(StandardCharsets.UTF_8)
        session.processToEmulator(b, b.size)
    }

    /** 连接建立前失败：按"连接失败"上报（与 [finish] 共用同一个只报一次的闸门）。 */
    private fun failConnect(session: TerminalSession, reason: String?) {
        if (finishReported.compareAndSet(false, true)) {
            session.onTransportConnectFailed(reason)
        }
    }

    private fun finish(session: TerminalSession, code: Int) {
        if (finishReported.compareAndSet(false, true)) {
            session.onTransportFinished(code)
        }
    }

    /** 把随包 assets/terminfo 释放到 filesDir/terminfo（幂等），返回该目录供 TERMINFO 使用。 */
    private fun ensureTerminfo(): File {
        val dir = File(appContext.filesDir, "terminfo")
        val marker = File(dir, ".ok")
        if (!marker.exists()) {
            copyAsset("terminfo", appContext.filesDir)
            marker.writeText("1")
        }
        return dir
    }

    companion object {
        /**
         * 控制连接的空闲寿命。要盖住「进终端页」那一串连续动作（探测 → 最多 8 轮附加确认 →
         * 滚动绑定 ≈ 10 秒）以及用户在面板上的连续操作，又不至于长期占着一条 TCP。
         */
        private const val CONTROL_IDLE_MS = 45_000L

        /** mosh 自身的退出序列：转义键 Ctrl-^ (0x1E) 后跟 `.`，让 mosh-client 通知远端 server 结束。 */
        internal val MOSH_QUIT_SEQUENCE = byteArrayOf(0x1E, '.'.code.toByte())

        /** 等 mosh-client 完成退出握手的上限；到点仍在就 SIGKILL 兜底。 */
        private const val QUIT_GRACE_MS = 1_200L
    }

    /** 递归复制 asset 路径 [path] 到 [destParent]/[path]。目录靠 assets.list 判断（文件返回空）。 */
    private fun copyAsset(path: String, destParent: File) {
        val am = appContext.assets
        val children = runCatching { am.list(path) }.getOrNull() ?: emptyArray()
        if (children.isEmpty()) {
            val outFile = File(destParent, path)
            outFile.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(outFile).use { input.copyTo(it) } }
        } else {
            File(destParent, path).mkdirs()
            for (c in children) copyAsset("$path/$c", destParent)
        }
    }
}

/**
 * forkpty 的 slave 关闭时，Linux/Android 对 master read 返回 EIO 而非 EOF。
 * 不依赖 Android-only ErrnoException 类型，确保相同判定可由本地 JVM 单测覆盖。
 */
internal object MoshPty {
    fun isClosed(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any { cause ->
            val message = cause.message.orEmpty().uppercase()
            message.contains("EIO") || message.contains("I/O ERROR")
        }
}
