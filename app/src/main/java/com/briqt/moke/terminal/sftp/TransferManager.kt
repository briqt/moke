package com.briqt.moke.terminal.sftp

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.MissingKind
import com.briqt.moke.data.NetworkStore
import com.briqt.moke.data.Route
import com.briqt.moke.data.resolveRoute
import com.briqt.moke.localized
import com.briqt.moke.net.connectFailureText
import com.briqt.moke.net.refusalText
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPException

internal enum class AttemptAbort {
    CANCELLED,
    INTERRUPTED,
}

internal class TransferAttempt(
    val taskId: String,
    val attemptId: Long,
) {
    val abortReason = AtomicReference<AttemptAbort?>(null)
    private val closeable = AtomicReference<AutoCloseable?>(null)

    val isAborted: Boolean get() = abortReason.get() != null

    fun attach(resource: AutoCloseable, abortAsync: (AutoCloseable) -> Unit) {
        closeable.set(resource)
        if (isAborted && closeable.compareAndSet(resource, null)) {
            abortAsync(resource)
        }
    }

    fun detach(resource: AutoCloseable) {
        closeable.compareAndSet(resource, null)
    }

    fun abort(reason: AttemptAbort, abortAsync: (AutoCloseable) -> Unit) {
        if (!abortReason.compareAndSet(null, reason) && reason == AttemptAbort.CANCELLED) {
            abortReason.set(AttemptAbort.CANCELLED)
        }
        closeable.getAndSet(null)?.let(abortAsync)
    }
}

/**
 * 传输队列：**串行**执行，一次一个文件。
 *
 * 生存期与 app 进程一致（挂在 Application 上），配合 [com.briqt.moke.terminal.MokeTransferService]
 * 的前台通知在退后台/关屏时继续传。任务表持久化在 [TransferStore]：进程被杀后重进应用，
 * 中断的任务会以「已中断」出现。下载可继续；上传目标/覆盖授权仅留内存，重启后须从文件页重新选择，
 * 不能按可能已更改的主机配置续写未知目标。
 */
class TransferManager internal constructor(
    private val appContext: Context?,
    private val scope: CoroutineScope,
    private val backingStore: BackingStore,
    private val routeResolver: suspend (String) -> Route,
    private val strings: Strings,
    private val customExecutor: AttemptExecutor? = null,
) {

    constructor(context: Context, hostStore: HostStore) : this(
        appContext = context.applicationContext,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        backingStore = StoreAdapter(TransferStore(context.applicationContext)),
        routeResolver = { hostId ->
            val networkStore = NetworkStore(context.applicationContext)
            resolveRoute(hostId, hostStore.state.first(), networkStore.state.first())
        },
        strings = ContextStrings(context.applicationContext),
        customExecutor = null,
    )

    internal interface BackingStore {
        suspend fun load(): List<TransferTask>
        suspend fun save(list: List<TransferTask>)
    }

    internal interface Strings {
        fun interrupted(): String
        fun hostGone(): String
        fun routeRefusal(route: Route): String
        fun destinationUnverified(): String
        fun destinationChanged(): String
        fun remoteMissing(): String
        fun localUnwritable(): String
        fun overwriteRequired(): String
        fun localUnreadable(): String
        fun localSeekFailed(): String
        fun createFailed(): String
        fun describeFailure(t: Throwable, notice: String?): String
    }

    internal interface AttemptCallbacks {
        val isAborted: Boolean
        fun attachCloseable(resource: AutoCloseable, abortAsync: (AutoCloseable) -> Unit)
        fun detachCloseable(resource: AutoCloseable)
        fun updateTask(transform: (TransferTask) -> TransferTask)
        fun reportProgress(done: Long)
        fun currentTask(): TransferTask?
    }

    internal fun interface AttemptExecutor {
        suspend fun execute(
            task: TransferTask,
            route: Route.Ok,
            allowOverwrite: Boolean,
            callbacks: AttemptCallbacks,
        ): Pair<Long, String?>
    }

    private class StoreAdapter(private val delegate: TransferStore) : BackingStore {
        override suspend fun load(): List<TransferTask> = delegate.load()
        override suspend fun save(list: List<TransferTask>) = delegate.save(list)
    }

    private class ContextStrings(private val context: Context) : Strings {
        override fun interrupted(): String = context.localized(R.string.transfer_interrupted)
        override fun hostGone(): String = context.localized(R.string.transfer_host_gone)
        override fun routeRefusal(route: Route): String = route.refusalText(context).orEmpty()
        override fun destinationUnverified(): String = context.localized(R.string.transfer_destination_unverified)
        override fun destinationChanged(): String = context.localized(R.string.transfer_destination_changed)
        override fun remoteMissing(): String = context.localized(R.string.transfer_remote_missing)
        override fun localUnwritable(): String = context.localized(R.string.transfer_local_unwritable)
        override fun overwriteRequired(): String = context.localized(R.string.transfer_overwrite_required)
        override fun localUnreadable(): String = context.localized(R.string.transfer_local_unreadable)
        override fun localSeekFailed(): String = context.localized(R.string.transfer_local_seek_failed)
        override fun createFailed(): String = context.localized(R.string.transfer_create_failed)
        override fun describeFailure(t: Throwable, notice: String?): String {
            val base = t.connectFailureText(context).takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            return if (notice.isNullOrBlank()) base else "$notice\n$base"
        }
    }

    private val resolver: ContentResolver
        get() = checkNotNull(appContext) { "ContentResolver requires Android Context" }.contentResolver

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /**
     * Protects in-memory state transitions of [_tasks], [generations], and [activeAttempts]
     * without side effects inside CAS lambdas. Lock order: `pumpLock` -> `stateLock`; never perform I/O inside either.
     */
    private val stateLock = Any()
    private val generations = HashMap<String, Long>()
    private val activeAttempts = HashMap<String, TransferAttempt>()

    private var pump: Job? = null
    private val pumpLock = Any()
    private data class UploadPermission(val destination: UploadDestination, val allowOverwrite: Boolean)
    // A restored task has no implicit overwrite permission. Existing task JSON stays unchanged.
    private val uploadPermissions = ConcurrentHashMap<String, UploadPermission>()

    private data class ClaimedAttempt(val task: TransferTask, val attempt: TransferAttempt)

    init {
        scope.launch {
            // 恢复上次的任务表：上次还在跑的（RUNNING/QUEUED）说明进程被杀了，标成「已中断」等用户决定，
            // 不自动重开——自动重传可能在计费网络上偷跑流量。
            val restored = backingStore.load().map {
                if (it.active) it.copy(state = TransferState.FAILED, error = strings.interrupted())
                else it
            }
            if (restored.isNotEmpty()) {
                synchronized(stateLock) {
                    val existingIds = _tasks.value.mapTo(HashSet()) { it.id }
                    val nonConflicting = restored.filterNot { it.id in existingIds }
                    nonConflicting.forEach { generations[it.id] = 1L }
                    _tasks.value = nonConflicting + _tasks.value
                }
            }
        }
    }

    // ---------- 入队 ----------

    /** 上传本地文档（SAF URI）到远端目录。 */
    suspend fun enqueueUpload(
        host: Host, uris: List<Uri>, remoteDir: String,
        names: List<String>? = null, destination: UploadDestination,
        overwriteNames: Set<String> = emptySet(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (names != null && names.size != uris.size) return@withContext false
        val metadata = uris.map { queryNameSize(it) }
        val checkedNames = names ?: metadata.map { it.first }
        if (checkedNames.any { !validUploadName(it) } || checkedNames.distinct().size != checkedNames.size) return@withContext false
        val added = uris.mapIndexed { index, uri ->
            val name = checkedNames[index]
            TransferTask(
                hostId = host.id,
                hostLabel = host.displayName,
                direction = TransferDirection.UPLOAD,
                remotePath = RemotePath.join(remoteDir, name),
                name = name,
                localUri = uri.toString(),
                total = metadata[index].second,
                createdAt = System.currentTimeMillis(),
            ).also { uploadPermissions[it.id] = UploadPermission(destination, name in overwriteNames) }
        }
        addAll(added)
        true
    }

    /**
     * 下载远端文件。[treeUri] 为空表示用默认落点（系统「下载」下的 `Moke` 子目录），
     * 非空则落到用户自己选的目录树。目标文档在真正开始传时才创建。
     */
    fun enqueueDownload(host: Host, entry: RemoteEntry, treeUri: Uri?) {
        addAll(
            listOf(
                TransferTask(
                    hostId = host.id,
                    hostLabel = host.displayName,
                    direction = TransferDirection.DOWNLOAD,
                    remotePath = entry.path,
                    name = entry.name,
                    treeUri = treeUri?.toString().orEmpty(),
                    total = entry.size,
                    remoteSize = entry.size,
                    remoteMtime = entry.mtime,
                    createdAt = System.currentTimeMillis(),
                )
            )
        )
    }

    internal fun enqueueTasksForTest(list: List<TransferTask>, uploadDestination: UploadDestination? = null, allowOverwrite: Boolean = false) {
        if (uploadDestination != null) {
            list.filter { it.direction == TransferDirection.UPLOAD }.forEach {
                uploadPermissions[it.id] = UploadPermission(uploadDestination, allowOverwrite)
            }
        }
        addAll(list)
    }

    private fun addAll(list: List<TransferTask>) {
        if (list.isEmpty()) return
        synchronized(stateLock) {
            list.forEach { generations[it.id] = 1L }
            _tasks.value = _tasks.value + list
        }
        persist()
        start()
    }

    // ---------- 控制 ----------

    private fun abortResourceOffMain(resource: AutoCloseable) {
        if (resource is SftpSession) {
            resource.abort()
        } else {
            scope.launch(Dispatchers.IO) { runCatching { resource.close() } }
        }
    }

    fun cancel(id: String) {
        val attempt = synchronized(stateLock) {
            _tasks.value = _tasks.value.map {
                if (it.id == id) it.copy(state = TransferState.CANCELLED) else it
            }
            activeAttempts.remove(id)
        }
        attempt?.abort(AttemptAbort.CANCELLED, ::abortResourceOffMain)
        persist()
    }

    /** 系统前台服务配额用尽时优雅暂停活动任务，保留断点供后续继续。 */
    fun interruptActive() {
        val message = strings.interrupted()
        val aborts = mutableListOf<TransferAttempt>()
        synchronized(stateLock) {
            _tasks.value = _tasks.value.map { task ->
                when (task.state) {
                    TransferState.RUNNING, TransferState.QUEUED -> {
                        activeAttempts.remove(task.id)?.let(aborts::add)
                        task.copy(state = TransferState.FAILED, error = message)
                    }
                    else -> task
                }
            }
        }
        aborts.forEach { it.abort(AttemptAbort.INTERRUPTED, ::abortResourceOffMain) }
        persist()
    }

    /** 继续/重试：递增代际并回到队列，正在收尾的旧尝试不得覆盖新尝试。 */
    fun retry(id: String) {
        val stale = synchronized(stateLock) {
            generations[id] = (generations[id] ?: 1L) + 1L
            _tasks.value = _tasks.value.map {
                if (it.id == id) it.copy(state = TransferState.QUEUED, error = "") else it
            }
            activeAttempts.remove(id)
        }
        stale?.abort(AttemptAbort.INTERRUPTED, ::abortResourceOffMain)
        persist()
        start()
    }

    fun remove(id: String) {
        val stale = synchronized(stateLock) {
            generations[id] = (generations[id] ?: 1L) + 1L
            _tasks.value = _tasks.value.filterNot { it.id == id }
            activeAttempts.remove(id)
        }
        uploadPermissions.remove(id)
        stale?.abort(AttemptAbort.CANCELLED, ::abortResourceOffMain)
        persist()
    }

    fun clearFinished() {
        val removed = synchronized(stateLock) {
            val before = _tasks.value
            val kept = before.filter { it.active || it.state == TransferState.FAILED }
            _tasks.value = kept
            before.filterNot { it.active || it.state == TransferState.FAILED }
        }
        removed.forEach { uploadPermissions.remove(it.id) }
        persist()
    }

    // ---------- 执行 ----------

    private fun start() {
        synchronized(pumpLock) {
            if (pump != null) return
            pump = scope.launch { pumpLoop() }
        }
    }

    private fun claimNextQueued(): ClaimedAttempt? = synchronized(stateLock) {
        val task = _tasks.value.firstOrNull { it.state == TransferState.QUEUED } ?: return@synchronized null
        val gen = generations[task.id] ?: 1L
        generations[task.id] = gen
        val attempt = TransferAttempt(task.id, gen)
        activeAttempts[task.id] = attempt
        ClaimedAttempt(task, attempt)
    }

    private suspend fun pumpLoop() {
        val self = kotlin.coroutines.coroutineContext[Job]
        try {
            while (true) {
                val claimed = synchronized(pumpLock) {
                    claimNextQueued() ?: run { pump = null; null }
                } ?: return
                runCatching { runOne(claimed.task, claimed.attempt) }.onFailure { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    failAttempt(claimed.attempt, t, null)
                }
            }
        } finally {
            synchronized(pumpLock) { if (pump === self) pump = null }
        }
    }

    private fun isCurrentAttemptLocked(attempt: TransferAttempt): Boolean =
        generations[attempt.taskId] == attempt.attemptId

    private fun currentForAttempt(attempt: TransferAttempt): TransferTask? = synchronized(stateLock) {
        if (!isCurrentAttemptLocked(attempt)) null else _tasks.value.firstOrNull { it.id == attempt.taskId }
    }

    private fun updateForAttempt(attempt: TransferAttempt, transform: (TransferTask) -> TransferTask) {
        synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt)) return
            _tasks.value = _tasks.value.map { if (it.id == attempt.taskId) transform(it) else it }
        }
    }

    private fun markRunning(attempt: TransferAttempt): TransferTask? = synchronized(stateLock) {
        if (!isCurrentAttemptLocked(attempt) || attempt.isAborted) return null
        val current = _tasks.value.firstOrNull { it.id == attempt.taskId } ?: return null
        if (current.state != TransferState.QUEUED) return null
        val running = current.copy(state = TransferState.RUNNING, error = "")
        _tasks.value = _tasks.value.map { if (it.id == attempt.taskId) running else it }
        running
    }

    private fun failBeforeRunning(attempt: TransferAttempt, reason: String) {
        val changed = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt) || attempt.isAborted) return@synchronized false
            val current = _tasks.value.firstOrNull { it.id == attempt.taskId } ?: return@synchronized false
            if (current.state != TransferState.QUEUED) return@synchronized false
            _tasks.value = _tasks.value.map {
                if (it.id == attempt.taskId) it.copy(state = TransferState.FAILED, error = reason) else it
            }
            activeAttempts.remove(attempt.taskId, attempt)
            true
        }
        if (changed) persist()
    }

    private fun finishAttempt(attempt: TransferAttempt, pos: Long) {
        var uploadCompleted: TransferTask? = null
        val changed = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            activeAttempts.remove(attempt.taskId, attempt)
            val abort = attempt.abortReason.get()
            _tasks.value = _tasks.value.map { task ->
                if (task.id != attempt.taskId) return@map task
                when (abort) {
                    AttemptAbort.INTERRUPTED -> task.copy(
                        done = pos,
                        state = TransferState.FAILED,
                        error = strings.interrupted(),
                    )
                    AttemptAbort.CANCELLED -> task.copy(
                        done = pos,
                        state = TransferState.CANCELLED,
                    )
                    null -> {
                        val completed = task.copy(
                            state = TransferState.DONE,
                            done = if (task.total >= 0) task.total else pos,
                        )
                        if (completed.direction == TransferDirection.UPLOAD) {
                            uploadCompleted = completed
                        }
                        completed
                    }
                }
            }
            if (abort == null) {
                uploadPermissions.remove(attempt.taskId)
            }
            true
        }
        if (changed) {
            uploadCompleted?.let { onUploadDone?.invoke(it.hostId, RemotePath.parent(it.remotePath)) }
            persist()
        }
    }

    private fun failAttempt(attempt: TransferAttempt, t: Throwable, notice: String?) {
        val changed = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            activeAttempts.remove(attempt.taskId, attempt)
            val abort = attempt.abortReason.get()
            _tasks.value = _tasks.value.map { task ->
                if (task.id != attempt.taskId) return@map task
                when (abort) {
                    AttemptAbort.INTERRUPTED -> task.copy(
                        state = TransferState.FAILED,
                        error = strings.interrupted(),
                    )
                    AttemptAbort.CANCELLED -> task.copy(
                        state = TransferState.CANCELLED,
                        error = "",
                    )
                    null -> task.copy(
                        state = TransferState.FAILED,
                        error = strings.describeFailure(t, notice),
                    )
                }
            }
            true
        }
        if (changed) persist()
    }

    private fun detachAttempt(attempt: TransferAttempt) {
        synchronized(stateLock) {
            activeAttempts.remove(attempt.taskId, attempt)
        }
    }

    private suspend fun runOne(initialTask: TransferTask, attempt: TransferAttempt) {
        try {
            // 与终端同一条路由规则：失效的跳板机或网络一律不连，绝不退回直连。
            val route = routeResolver(initialTask.hostId)
            if (route !is Route.Ok) {
                val reason = if (route == Route.Missing(MissingKind.HOST)) strings.hostGone()
                else strings.routeRefusal(route)
                failBeforeRunning(attempt, reason)
                return
            }

            val permission = uploadPermissions[initialTask.id]
            if (initialTask.direction == TransferDirection.UPLOAD && !uploadDestinationMatches(permission?.destination, route)) {
                val reason = if (permission == null) strings.destinationUnverified() else strings.destinationChanged()
                failBeforeRunning(attempt, reason)
                return
            }

            val runningTask = markRunning(attempt) ?: return
            val callbacks = object : AttemptCallbacks {
                override val isAborted: Boolean
                    get() = attempt.isAborted || currentForAttempt(attempt) == null
                override fun attachCloseable(resource: AutoCloseable, abortAsync: (AutoCloseable) -> Unit) =
                    attempt.attach(resource, abortAsync)
                override fun detachCloseable(resource: AutoCloseable) = attempt.detach(resource)
                override fun updateTask(transform: (TransferTask) -> TransferTask) =
                    updateForAttempt(attempt, transform)
                override fun reportProgress(done: Long) = progress(attempt, done)
                override fun currentTask(): TransferTask? = currentForAttempt(attempt)
            }

            if (customExecutor != null) {
                val (pos, _) = customExecutor.execute(runningTask, route, permission?.allowOverwrite == true, callbacks)
                finishAttempt(attempt, pos)
                return
            }

            val ctx = checkNotNull(appContext) { "SftpSession requires Android Context" }
            val session = SftpSession(route.host, route.jump, ctx, route.network)
            callbacks.attachCloseable(session) { (it as SftpSession).abort() }
            try {
                val pos = when (runningTask.direction) {
                    TransferDirection.DOWNLOAD -> runDownload(runningTask, session, callbacks)
                    TransferDirection.UPLOAD -> runUpload(runningTask, session, permission?.allowOverwrite == true, callbacks)
                }
                finishAttempt(attempt, pos)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failAttempt(attempt, t, session.consumeNotice())
            } finally {
                callbacks.detachCloseable(session)
                runCatching { session.close() }
            }
        } finally {
            detachAttempt(attempt)
        }
    }

    private fun runDownload(task: TransferTask, session: SftpSession, callbacks: AttemptCallbacks): Long {
        val remote = session.stat(task.remotePath)
            ?: throw IllegalStateException(strings.remoteMissing())

        var current = callbacks.currentTask() ?: return task.done
        // 目标文档：第一次创建；续传时沿用上次那份。DocumentsProvider 负责重名不覆盖。
        var target = current.localUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (target == null) {
            target = createTarget(current)
            callbacks.updateTask { it.copy(localUri = target.toString()) }
            current = callbacks.currentTask() ?: return task.done
        }

        // 断点以**本地实际落盘长度**为准，不信任上次记的 done（进度是节流写的，可能落后）。
        val existing = localLength(target)
        var startAt = if (TransferTask.canResume(current.copy(done = existing), remote)) existing else 0L
        var out = if (startAt > 0L) {
            runCatching { resolver.openOutputStream(target, "wa") }.getOrNull().also {
                if (it == null) {
                    // 该 provider 不支持追加写：记下来，本任务及以后都按整传处理。
                    callbacks.updateTask { t -> t.copy(appendSupported = false) }
                    startAt = 0L
                }
            }
        } else {
            null
        }
        // 不能续就必须截断，否则新内容会写在旧内容之上，拼出一个看着正常的坏文件。
        // 新建的那一次用默认模式（"w"）创建文件与目录；只有在本地已有残留字节时才需要显式截断。
        if (out == null) {
            out = if (existing == 0L) {
                resolver.openOutputStream(target)
            } else {
                runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
                    ?: resolver.openOutputStream(target)
            } ?: throw IllegalStateException(strings.localUnwritable())
        }

        callbacks.updateTask {
            it.copy(done = startAt, total = remote.size, remoteSize = remote.size, remoteMtime = remote.mtime)
        }
        return out.use { sink ->
            session.download(
                remote = task.remotePath,
                sink = sink,
                offset = startAt,
                onProgress = callbacks::reportProgress,
                cancelled = { callbacks.isAborted },
            )
        }
    }

    private fun runUpload(
        task: TransferTask,
        session: SftpSession,
        allowOverwrite: Boolean,
        callbacks: AttemptCallbacks,
    ): Long {
        val source = Uri.parse(task.localUri)
        val localSize = queryNameSize(source).second
        val remote = session.stat(task.remotePath)
        val resume = TransferTask.canResumeUpload(task, remote?.size ?: 0L, localSize)
        val offset = if (resume) task.done else 0L
        if (offset == 0L && remote != null && !allowOverwrite) {
            throw IllegalStateException(strings.overwriteRequired())
        }
        if (callbacks.isAborted) return offset
        callbacks.updateTask { it.copy(done = offset, total = localSize) }
        val input = resolver.openInputStream(source)
            ?: throw IllegalStateException(strings.localUnreadable())
        return try {
            input.use { stream ->
                if (offset > 0L) skipExactly(stream, offset)
                session.upload(
                    source = stream,
                    remote = task.remotePath,
                    offset = offset,
                    onProgress = callbacks::reportProgress,
                    cancelled = { callbacks.isAborted },
                    allowOverwrite = allowOverwrite,
                )
            }
        } catch (t: SFTPException) {
            // EXCL closes the check/open race. A newly appeared file still requires explicit consent.
            if (offset == 0L && !allowOverwrite && runCatching { session.stat(task.remotePath) != null }.getOrDefault(false)) {
                throw IllegalStateException(strings.overwriteRequired(), t)
            }
            throw t
        }
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
            if (n <= 0) throw IllegalStateException(strings.localSeekFailed())
            left -= n
        }
    }

    /** 上传成功回调（hostId, 远端目录）：上层据此刷新正在看的那个目录。 */
    var onUploadDone: ((String, String) -> Unit)? = null

    // ---------- 小工具 ----------

    /** 进度更新做节流：每 250ms 或每 1MB 才推一次，避免高频重组把 UI 拖垮。 */
    private var lastProgressAt = 0L
    private fun progress(attempt: TransferAttempt, done: Long) {
        val now = System.currentTimeMillis()
        val prev = currentForAttempt(attempt)?.done ?: 0
        if (now - lastProgressAt < 250 && done - prev < 1_000_000) return
        lastProgressAt = now
        updateForAttempt(attempt) { it.copy(done = done) }
    }

    private fun persist() {
        val snapshot = synchronized(stateLock) { _tasks.value }
        scope.launch { backingStore.save(snapshot) }
    }

    /**
     * 目标文档：用户选过目录就落那儿，否则落默认的「下载/Moke」。
     *
     * 用户选的目录可能已经不可用（被删掉、被撤授权、SD 卡拔了）。这时不能就这么失败——
     * 回落到默认落点并让上层忘掉这个目录，下次直接用默认的。
     */
    private fun createTarget(task: TransferTask): Uri {
        val tree = task.treeUri.takeIf { it.isNotBlank() } ?: return createInDownloads(task.name)
        return runCatching { createInTree(Uri.parse(tree), task.name) }.getOrElse {
            onTreeUnusable?.invoke()
            createInDownloads(task.name)
        }
    }

    /** 用户选的下载目录已不可用时回调（上层据此清掉记住的目录）。 */
    var onTreeUnusable: (() -> Unit)? = null

    private fun createInTree(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, RemotePath.guessMime(name), name)
            ?: throw IllegalStateException(strings.createFailed())
    }

    /**
     * 默认落点：系统「下载」下的 `Moke` 子目录，经 MediaStore 写入——**零存储权限、零选择器**。
     * 同名由 MediaStore 自己让位（`name (1).ext`），不覆盖。
     */
    private fun createInDownloads(name: String): Uri {
        // 先试子目录；部分系统不会为 RELATIVE_PATH 里不存在的子目录自动建文件夹，
        // 故插入后立即探一次写入：不通就删掉无效记录，退回「下载」根目录。
        insertDownload(name, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_SUBDIR")?.let { uri ->
            if (touchable(uri)) return uri
            runCatching { resolver.delete(uri, null, null) }
        }
        val fallback = insertDownload(name, Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException(strings.createFailed())
        if (!touchable(fallback)) {
            runCatching { resolver.delete(fallback, null, null) }
            throw IllegalStateException(strings.createFailed())
        }
        return fallback
    }

    private fun insertDownload(name: String, relativePath: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, RemotePath.guessMime(name))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    /** 探一次能否真的写入（同时把 0 字节文件落地，后续的截断/追加模式才有对象）。 */
    private fun touchable(uri: Uri): Boolean =
        runCatching { resolver.openOutputStream(uri)?.use { } != null }.getOrDefault(false)

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

    companion object {
        /** 默认下载子目录（系统「下载」之下）。 */
        const val DEFAULT_SUBDIR = "Moke"
    }
}
