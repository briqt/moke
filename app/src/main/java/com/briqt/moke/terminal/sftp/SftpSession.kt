package com.briqt.moke.terminal.sftp

import android.content.Context
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.SshConnector
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** 远端目录里的一项。[mtime] 是 epoch 秒（SFTP 协议口径）。 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,
    /** rwxr-xr-x 形式；解析不出时为空串。 */
    val permissions: String,
)

/** 续传判定用的远端文件身份。 */
data class RemoteStat(val size: Long, val mtime: Long, val isDir: Boolean)

/**
 * 一条独立的 SFTP 连接。
 *
 * **为什么不复用终端那条连接**：大文件传输会吃满 SSH 窗口，共用时打字会卡；独立连接还让
 * "终端重连不打断传输"成立。mosh 主机本来就没有长连接可复用。代价是打开文件页要多一次握手，
 * 换来的是行为在 SSH / mosh / 无会话三种情况下完全一致。
 *
 * 线程约定：所有方法都是**阻塞**的，调用方自己切到 IO 线程。sshj 的 SFTPClient 不保证并发安全，
 * 因此一个 [SftpSession] 同一时刻只应有一个使用者（浏览一个、每条传输各自一个）。
 */
class SftpSession(
    private val host: Host,
    private val jumpHost: Host?,
    context: Context,
) : Closeable {

    private val appContext = context.applicationContext

    /**
     * 连接期间收到的 TOFU 等提示；没有终端可写，由调用方接到 UI（失败原因/横幅）。
     * **一次性**：读取即清空——否则连接时那句"已记录主机指纹"会一直粘在后面每一条错误上，
     * 把无关的失败说成像是主机密钥出了问题。
     */
    @Volatile private var notice: String? = null

    fun consumeNotice(): String? {
        val n = notice
        notice = null
        return n
    }

    private var conn: SshConnector.Connected? = null
    private var sftp: SFTPClient? = null
    @Volatile private var closed = false

    /** 首次使用时才建连（打开文件页即连，失败有明确报错）。 */
    private fun client(): SFTPClient {
        check(!closed) { "session closed" }
        sftp?.let { return it }
        val connector = SshConnector(appContext) { notice = it }
        val c = connector.connect(host, jumpHost, heartbeat = true)
        // 心跳间隔必须显式给：只设 KeepAliveProvider.HEARTBEAT 而不给间隔等于没开，
        // 浏览连接会在用户看着列表发呆几分钟后被中间设备静默掐断（真机实测）。
        runCatching { c.client.connection.keepAlive.keepAliveInterval = 30 }
        conn = c
        return c.client.newSFTPClient().also { sftp = it }
    }

    /**
     * 浏览类操作的执行包装：连接已死时**重连一次再试**。
     *
     * 文件页会长时间停在那里（用户去看别的、传输在跑），连接被掐断是常态而不是异常；
     * 让用户对着"Software caused connection abort"点刷新是把实现细节甩给用户。
     * 只重试一次、且只对浏览类操作——传输有自己的断点与重试语义，不能在这里偷偷重来。
     */
    private fun <T> withReconnect(block: (SFTPClient) -> T): T = try {
        block(client())
    } catch (t: Throwable) {
        if (closed) throw t
        runCatching { sftp?.close() }
        runCatching { conn?.close() }
        sftp = null
        conn = null
        block(client())
    }

    /** 远端家目录的绝对路径（`.` 的规范化结果）。 */
    fun homePath(): String = withReconnect { it.canonicalize(".") }

    /** 把可能含 `~`、`..`、相对段的路径变成绝对路径。 */
    fun canonicalize(path: String): String = withReconnect { it.canonicalize(path) }

    fun list(path: String): List<RemoteEntry> = withReconnect { it.ls(path) }.map { r ->
        val a = r.attributes
        val type = a.mode.type
        RemoteEntry(
            name = r.name,
            path = RemotePath.join(path, r.name),
            // 符号链接指向目录时也应能点进去；ls 不跟随链接，故这里对链接单独 stat 一次。
            isDir = type == FileMode.Type.DIRECTORY ||
                (type == FileMode.Type.SYMLINK && runCatching { statType(r.path) }.getOrNull() == true),
            isLink = type == FileMode.Type.SYMLINK,
            size = a.size,
            mtime = a.mtime,
            permissions = permissionsOf(a.mode),
        )
    }

    private fun statType(path: String): Boolean =
        client().stat(path).mode.type == FileMode.Type.DIRECTORY

    fun stat(path: String): RemoteStat? = runCatching {
        withReconnect {
            val a = it.stat(path)
            RemoteStat(a.size, a.mtime, a.mode.type == FileMode.Type.DIRECTORY)
        }
    }.getOrNull()

    fun mkdir(path: String) = withReconnect { it.mkdir(path) }

    /**
     * 下载 [remote] 到 [sink]，从 [offset] 字节处开始（续传）。
     *
     * [onProgress] 收"已完成总字节数"（含 [offset]），[cancelled] 每块检查一次——sshj 没有软取消，
     * 靠调用方关流/断连来打断，这里给一个协作式出口以便正常收尾。
     */
    fun download(
        remote: String,
        sink: OutputStream,
        offset: Long,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        client().open(remote, EnumSet.of(OpenMode.READ)).use { file ->
            val buf = ByteArray(BUFFER)
            var pos = offset
            while (true) {
                if (cancelled()) return pos
                val n = file.read(pos, buf, 0, buf.size)
                if (n <= 0) break
                sink.write(buf, 0, n)
                pos += n
                onProgress(pos)
            }
            sink.flush()
            return pos
        }
    }

    /**
     * 上传 [source] 到 [remote]，从 [offset] 字节处续写。
     *
     * 不带 TRUNC：续传时截断就把已传的部分丢了。[offset]==0 的整传由调用方先删除/覆盖同名文件，
     * 或接受"写在已有内容之上"——上层只在确认过远端大小后才走非 0 偏移。
     */
    fun upload(
        source: InputStream,
        remote: String,
        offset: Long,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        val modes = if (offset > 0L) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        client().open(remote, modes).use { file ->
            val buf = ByteArray(BUFFER)
            var pos = offset
            while (true) {
                if (cancelled()) return pos
                val n = source.read(buf)
                if (n <= 0) break
                file.write(pos, buf, 0, n)
                pos += n
                onProgress(pos)
            }
            return pos
        }
    }

    override fun close() {
        closed = true
        runCatching { sftp?.close() }
        runCatching { conn?.close() }
        sftp = null
        conn = null
    }

    private fun permissionsOf(mode: FileMode): String = runCatching {
        val m = mode.permissionsMask
        buildString {
            append(if (mode.type == FileMode.Type.DIRECTORY) 'd' else if (mode.type == FileMode.Type.SYMLINK) 'l' else '-')
            val bits = "rwxrwxrwx"
            for (i in 0 until 9) {
                append(if (m and (1 shl (8 - i)) != 0) bits[i] else '-')
            }
        }
    }.getOrDefault("")

    companion object {
        /** 32KB：SFTP 单包上限通常 32KB 附近，再大不会更快，但内存占用要控住（v0.1.16 OOM 的教训）。 */
        const val BUFFER = 32 * 1024
    }
}
