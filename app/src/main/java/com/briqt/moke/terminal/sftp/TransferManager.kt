package com.briqt.moke.terminal.sftp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.briqt.moke.data.HostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * 传输队列：**串行**执行，一次一个文件。
 *
 * 为什么串行：手机上行带宽有限，并发只会让每条都慢、进度更难读，还成倍放大失败面；
 * 真要提速也该是单文件多通道，而不是多文件并发。
 *
 * 生存期与 app 进程一致（挂在 Application 上），配合 [com.briqt.moke.terminal.MokeTransferService]
 * 的前台通知在退后台/关屏时继续传。任务表持久化在 [TransferStore]：进程被杀后重进应用，
 * 中断的任务会以「已中断」出现并可继续（能续就从断点续，续不了就从 0 重来，绝不静默拼坏文件）。
 */
class TransferManager(context: Context, private val hostStore: HostStore) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver
    private val store = TransferStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /** 已请求取消的任务 id（工作线程按块检查）。 */
    private val cancelling = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var pump: Job? = null

    init {
        scope.launch {
            // 恢复上次的任务表：上次还在跑的（RUNNING/QUEUED）说明进程被杀了，标成「已中断」等用户决定，
            // 不自动重开——自动重传可能在计费网络上偷跑流量。
            val restored = store.load().map {
                if (it.active) it.copy(state = TransferState.FAILED, error = appContext.getString(R.string.transfer_interrupted))
                else it
            }
            _tasks.value = restored
        }
    }

    // ---------- 入队 ----------

    /** 上传本地文档（SAF URI）到远端目录。 */
    fun enqueueUpload(host: Host, uris: List<Uri>, remoteDir: String) {
        val added = uris.map { uri ->
            val (name, size) = queryNameSize(uri)
            TransferTask(
                hostId = host.id,
                hostLabel = host.displayName,
                direction = TransferDirection.UPLOAD,
                remotePath = RemotePath.join(remoteDir, name),
                name = name,
                localUri = uri.toString(),
                total = size,
                createdAt = System.currentTimeMillis(),
            )
        }
        addAll(added)
    }

    /** 下载远端文件到目录树 [treeUri] 下（目标文档在真正开始时才创建）。 */
    fun enqueueDownload(host: Host, entry: RemoteEntry, treeUri: Uri) {
        addAll(
            listOf(
                TransferTask(
                    hostId = host.id,
                    hostLabel = host.displayName,
                    direction = TransferDirection.DOWNLOAD,
                    remotePath = entry.path,
                    name = entry.name,
                    treeUri = treeUri.toString(),
                    total = entry.size,
                    remoteSize = entry.size,
                    remoteMtime = entry.mtime,
                    createdAt = System.currentTimeMillis(),
                )
            )
        )
    }

    private fun addAll(list: List<TransferTask>) {
        if (list.isEmpty()) return
        _tasks.update { it + list }
        persist()
        start()
    }

    // ---------- 控制 ----------

    fun cancel(id: String) {
        cancelling += id
        update(id) { it.copy(state = TransferState.CANCELLED) }
    }

    /** 继续/重试：回到队列，由工作线程判断能不能续。 */
    fun retry(id: String) {
        cancelling -= id
        update(id) { it.copy(state = TransferState.QUEUED, error = "") }
        start()
    }

    fun remove(id: String) {
        cancelling += id
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active || it.state == TransferState.FAILED } }
        persist()
    }

    // ---------- 执行 ----------

    private fun start() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (true) {
                val task = _tasks.value.firstOrNull { it.state == TransferState.QUEUED } ?: break
                runOne(task)
            }
        }
    }

    private suspend fun runOne(task: TransferTask) {
        val hosts = hostStore.hosts.first()
        val host = hosts.firstOrNull { it.id == task.hostId }
        if (host == null) {
            update(task.id) {
                it.copy(state = TransferState.FAILED, error = appContext.getString(R.string.transfer_host_gone))
            }
            return
        }
        val jump = host.jumpHostId.takeIf { it.isNotBlank() && it != host.id }
            ?.let { id -> hosts.firstOrNull { it.id == id } }

        update(task.id) { it.copy(state = TransferState.RUNNING, error = "") }
        val session = SftpSession(host, jump, appContext)
        try {
            when (task.direction) {
                TransferDirection.DOWNLOAD -> runDownload(task, session)
                TransferDirection.UPLOAD -> runUpload(task, session)
            }
        } catch (t: Throwable) {
            val cancelled = cancelling.contains(task.id)
            update(task.id) {
                it.copy(
                    state = if (cancelled) TransferState.CANCELLED else TransferState.FAILED,
                    error = if (cancelled) "" else describe(t, session.consumeNotice()),
                )
            }
        } finally {
            cancelling -= task.id
            runCatching { session.close() }
            persist()
        }
    }

    private fun runDownload(task: TransferTask, session: SftpSession) {
        val remote = session.stat(task.remotePath)
            ?: throw IllegalStateException(appContext.getString(R.string.transfer_remote_missing))

        var current = current(task.id) ?: return
        // 目标文档：第一次创建；续传时沿用上次那份。DocumentsProvider 负责重名不覆盖。
        var target = current.localUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (target == null) {
            target = createDocument(Uri.parse(current.treeUri), current.name)
            update(task.id) { it.copy(localUri = target.toString()) }
            current = current(task.id) ?: return
        }

        // 断点以**本地实际落盘长度**为准，不信任上次记的 done（进度是节流写的，可能落后）。
        val existing = localLength(target)
        var startAt = if (TransferTask.canResume(current.copy(done = existing), remote)) existing else 0L
        var out = if (startAt > 0L) {
            runCatching { resolver.openOutputStream(target, "wa") }.getOrNull().also {
                if (it == null) {
                    // 该 provider 不支持追加写：记下来，本任务及以后都按整传处理。
                    update(task.id) { t -> t.copy(appendSupported = false) }
                    startAt = 0L
                }
            }
        } else {
            null
        }
        // 不能续就必须截断（"wt"），否则新内容会写在旧内容之上，拼出一个看着正常的坏文件。
        if (out == null) {
            out = resolver.openOutputStream(target, "wt")
                ?: throw IllegalStateException(appContext.getString(R.string.transfer_local_unwritable))
        }

        update(task.id) {
            it.copy(done = startAt, total = remote.size, remoteSize = remote.size, remoteMtime = remote.mtime)
        }
        out.use { sink ->
            session.download(
                remote = task.remotePath,
                sink = sink,
                offset = startAt,
                onProgress = { progress(task.id, it) },
                cancelled = { cancelling.contains(task.id) },
            )
        }
        finish(task.id)
    }

    private fun runUpload(task: TransferTask, session: SftpSession) {
        val source = Uri.parse(task.localUri)
        val localSize = queryNameSize(source).second
        val remoteExisting = session.stat(task.remotePath)?.size ?: 0L
        val resume = TransferTask.canResumeUpload(task, remoteExisting, localSize)
        val offset = if (resume) task.done else 0L

        update(task.id) { it.copy(done = offset, total = localSize) }
        val input = resolver.openInputStream(source)
            ?: throw IllegalStateException(appContext.getString(R.string.transfer_local_unreadable))
        input.use { stream ->
            if (offset > 0L) skipExactly(stream, offset)
            session.upload(
                source = stream,
                remote = task.remotePath,
                offset = offset,
                onProgress = { progress(task.id, it) },
                cancelled = { cancelling.contains(task.id) },
            )
        }
        finish(task.id)
    }

    /**
     * SAF 的流不保证 `skip` 一次到位（甚至可能返回 0），必须循环并核对实际跳过量；
     * 跳不到就只能判定续传失败，绝不能从错误的偏移接着写。
     */
    private fun skipExactly(stream: InputStream, offset: Long) {
        var left = offset
        val scratch = ByteArray(32 * 1024)
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n <= 0) throw IllegalStateException(appContext.getString(R.string.transfer_local_seek_failed))
            left -= n
        }
    }

    private fun finish(id: String) {
        if (cancelling.contains(id)) {
            update(id) { it.copy(state = TransferState.CANCELLED) }
        } else {
            update(id) { it.copy(state = TransferState.DONE, done = if (it.total >= 0) it.total else it.done) }
        }
        persist()
    }

    // ---------- 小工具 ----------

    private fun current(id: String) = _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, f: (TransferTask) -> TransferTask) {
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** 进度更新做节流：每 250ms 或每 1MB 才推一次，避免高频重组把 UI 拖垮。 */
    private var lastProgressAt = 0L
    private fun progress(id: String, done: Long) {
        val now = System.currentTimeMillis()
        val prev = current(id)?.done ?: 0
        if (now - lastProgressAt < 250 && done - prev < 1_000_000) return
        lastProgressAt = now
        update(id) { it.copy(done = done) }
    }

    private fun persist() {
        val snapshot = _tasks.value
        scope.launch { store.save(snapshot) }
    }

    private fun createDocument(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, RemotePath.guessMime(name), name)
            ?: throw IllegalStateException(appContext.getString(R.string.transfer_create_failed))
    }

    private fun localLength(uri: Uri): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: 0L

    /** 从 SAF URI 取显示名与大小；取不到大小返回 -1（进度显示为不确定）。 */
    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = if (!c.isNull(0)) c.getString(0) else fallback
                        val s = if (!c.isNull(1)) c.getLong(1) else -1L
                        n to s
                    } else null
                }
        }.getOrNull() ?: (fallback to -1L)
    }

    /** 失败原因：优先 TOFU 等提示（没有终端可写，只能在这儿说），其次异常消息。 */
    private fun describe(t: Throwable, notice: String?): String {
        val base = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return if (notice.isNullOrBlank()) base else "$notice\n$base"
    }
}
