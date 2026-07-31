package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import android.os.ParcelFileDescriptor
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.termux.terminal.JNI
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    /** 非空时让 mosh-server 直接启动该交互程序（tmux 等），不经 shell 提示符注入。 */
    private val startupCommand: String? = null,
) : TerminalTransport {

    private val appContext = context.applicationContext
    private val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
    private val cacheDir: File = appContext.cacheDir

    private var pfd: ParcelFileDescriptor? = null
    private var ptyFd: Int = -1
    private var pid: Int = -1
    private var out: FileOutputStream? = null
    private val writeExec = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var childExited = false
    private val finishReported = AtomicBoolean(false)

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        Thread({
            try {
                // 0) 先确认随包 native 组件在位——缺失时给出可读提示并结束，
                //    绝不放行到 JNI（否则 System.loadLibrary 抛 UnsatisfiedLinkError 会使 app 闪退）。
                val moshBin = File("$nativeLibDir/libmosh-client.so")
                val termuxBin = File("$nativeLibDir/libtermux.so")
                if (!moshBin.exists() || !termuxBin.exists()) {
                    feed(session, "\r\n" + appContext.getString(R.string.mosh_unavailable) + "\r\n")
                    finish(session, 1)
                    return@Thread
                }

                // 1) SSH 引导：执行 mosh-server new，解析 MOSH CONNECT
                feed(session, "\r\n" + appContext.getString(R.string.mosh_bootstrapping) + "\r\n")
                val bootstrap = sshBootstrap()
                val connect = MoshBootstrap.parse(bootstrap)
                    ?: throw IllegalStateException("未从 mosh-server 输出解析到 MOSH CONNECT：\n$bootstrap")
                feed(session, appContext.getString(R.string.mosh_client_starting, connect.port) + "\r\n")

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
                val pidArr = IntArray(1)
                val fd = JNI.createSubprocess(
                    bin, appContext.filesDir.absolutePath,
                    arrayOf("mosh-client", host.host, connect.port.toString()),
                    env, pidArr, rows, columns, cellWidthPixels, cellHeightPixels,
                )
                ptyFd = fd
                pid = pidArr[0]
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
                    finish(session, code)
                    runCatching { pfd?.close() }
                    ptyFd = -1
                }, "moke-mosh-waiter").start()

                // 连接成功后自动执行命令：mosh 握手需片刻，延迟发送再触发回车。
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
                            "\r\n" + appContext.getString(
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
                if (!closed && !childExited) {
                    feed(
                        session,
                        "\r\n" + appContext.getString(
                            R.string.mosh_connect_failed,
                            e.message ?: e.javaClass.simpleName,
                        ) + "\r\n",
                    )
                }
                // createSubprocess 成功后仍由 waiter 收口；引导/创建阶段失败才在这里结束。
                if (pid > 0) {
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                } else {
                    finish(session, 1)
                }
            }
        }, "moke-mosh-${host.host}").start()
    }

    private fun sshBootstrap(): String {
        return withSshClient { client ->
            client.startSession().use { s ->
                val cmd = s.exec(MoshBootstrap.serverCommand(startupCommand = startupCommand))
                val stdout = IOUtils.readFully(cmd.inputStream).toString()
                cmd.join()
                stdout
            }
        }
    }

    /**
     * mosh 数据面虽是 UDP，管理 tmux 仍可按需建立一条短生命周期 SSH 控制连接。
     * 每次独立连接，不与 mosh 漫游生命周期耦合；失败返回 null，由 UI 明确显示并允许重试。
     */
    override fun exec(command: String): String? {
        if (closed) return null
        return runCatching {
            withSshClient { client ->
                client.startSession().use { s ->
                    val cmd = s.exec(command)
                    cmd.join(10, TimeUnit.SECONDS)
                    if (cmd.isOpen) {
                        runCatching { cmd.close() }
                        return@withSshClient null
                    }
                    cmd.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    private fun <T> withSshClient(block: (SSHClient) -> T): T {
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = 15000
        client.addHostKeyVerifier(MokeHostKeyVerifier(KnownHosts(appContext), appContext) {})
        var jClient: SSHClient? = null
        try {
            if (jumpHost != null) {
                val j = SSHClient(DefaultConfig())
                j.connectTimeout = 15000
                j.addHostKeyVerifier(MokeHostKeyVerifier(KnownHosts(appContext), appContext) {})
                j.connect(jumpHost.host, jumpHost.port)
                authenticate(j, jumpHost)
                client.connectVia(j.newDirectConnection(host.host, host.port))
                jClient = j
            } else {
                client.connect(host.host, host.port)
            }
            authenticate(client, host)
            return block(client)
        } finally {
            runCatching { client.disconnect() }
            runCatching { jClient?.disconnect() }
        }
    }

    private fun authenticate(client: SSHClient, h: Host) {
        when (h.authType) {
            AuthType.PASSWORD -> client.authPassword(h.username, h.password)
            AuthType.KEY -> {
                val keyFile = File.createTempFile("moke_key_", ".pem", cacheDir)
                try {
                    keyFile.writeText(h.privateKeyPem)
                    val kp = if (h.passphrase.isBlank()) client.loadKeys(keyFile.absolutePath)
                    else client.loadKeys(keyFile.absolutePath, PasswordUtils.createOneOff(h.passphrase.toCharArray()))
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
            try { out?.write(copy); out?.flush() } catch (_: Exception) {}
        }
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (ptyFd >= 0 && !closed) {
            runCatching { JNI.setPtyWindowSize(ptyFd, rows, columns, cellWidthPixels, cellHeightPixels) }
        }
    }

    override fun close() {
        closed = true
        writeExec.execute {
            runCatching { pfd?.close() }
            if (pid > 0) runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        }
        writeExec.shutdown()
    }

    private fun feed(session: TerminalSession, msg: String) {
        val b = msg.toByteArray(StandardCharsets.UTF_8)
        session.processToEmulator(b, b.size)
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
