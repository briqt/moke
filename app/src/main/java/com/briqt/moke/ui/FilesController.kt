package com.briqt.moke.ui

import android.content.Context
import com.briqt.moke.data.FilesSort
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.Tmux
import com.briqt.moke.terminal.sftp.RemoteEntry
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.SftpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件页的一屏状态。[terminalPath] 非空表示"终端当前目录"可用（用于 ⋮ 里的回跳）。 */
data class FilesUiState(
    val host: Host? = null,
    val path: String = "",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val notice: String = "",
    val terminalPath: String = "",
)

/**
 * 文件页的会话与导航。一次打开 = 一条独立 SFTP 连接（见 `设计/文件传输-SFTP.md` §3.3），
 * 离开页面即断开；传输不走这条连接，所以关掉它不会打断正在传的文件。
 */
class FilesController(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    private var session: SftpSession? = null
    private var job: Job? = null

    /**
     * 打开某台主机的文件页。[from] 非空时尝试取"终端当前目录"作为起点。
     *
     * 取当前目录只在 tmux 会话上做得到（`#{pane_current_path}`）：普通登录壳的 cwd 属于那个
     * shell 进程，侧通道另开的 exec 看不到，硬猜只会给出错误的路径。取不到就落到家目录，
     * 不假装知道。
     */
    fun open(host: Host, jumpHost: Host?, from: TermSession?) {
        close()
        _state.value = FilesUiState(host = host, loading = true)
        job = scope.launch {
            val s = SftpSession(host, jumpHost, appContext)
            session = s
            runCatching {
                withContext(Dispatchers.IO) {
                    val terminalPath = from?.let { probeTerminalPath(it, s) } ?: ""
                    val start = terminalPath.ifBlank { s.homePath() }
                    Triple(start, s.list(start), terminalPath)
                }
            }.onSuccess { (start, entries, terminalPath) ->
                _state.update {
                    it.copy(
                        path = start,
                        entries = entries,
                        loading = false,
                        terminalPath = terminalPath,
                        notice = s.consumeNotice().orEmpty(),
                    )
                }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = describe(t, s.consumeNotice())) }
            }
        }
    }

    fun navigate(path: String) {
        val s = session ?: return
        job?.cancel()
        _state.update { it.copy(loading = true, error = "") }
        job = scope.launch {
            runCatching { withContext(Dispatchers.IO) { s.list(path) } }
                .onSuccess { list -> _state.update { it.copy(path = path, entries = list, loading = false) } }
                // 进不去就留在原地（常见于无权限目录），把原因说清楚，而不是清空成"空目录"。
                .onFailure { t -> _state.update { it.copy(loading = false, error = describe(t, s.consumeNotice())) } }
        }
    }

    fun refresh() = navigate(_state.value.path)

    fun up() = navigate(RemotePath.parent(_state.value.path))

    fun mkdir(name: String) {
        val s = session ?: return
        val target = RemotePath.join(_state.value.path, name.trim())
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { s.mkdir(target) } }
                .onSuccess { refresh() }
                .onFailure { t -> _state.update { it.copy(error = describe(t, s.consumeNotice())) } }
        }
    }

    /** 跳转到用户输入的路径（可含 `~`、相对段，交给远端 canonicalize）。 */
    fun goto(raw: String) {
        val s = session ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { s.canonicalize(raw.trim()) } }
                .onSuccess { navigate(it) }
                .onFailure { t -> _state.update { it.copy(error = describe(t, s.consumeNotice())) } }
        }
    }

    fun clearError() = _state.update { it.copy(error = "") }

    fun close() {
        job?.cancel()
        job = null
        val s = session
        session = null
        _state.value = FilesUiState()
        if (s != null) scope.launch { withContext(Dispatchers.IO) { runCatching { s.close() } } }
    }

    /** 排序：目录永远在前，同类之间按用户选的维度比。 */
    fun sorted(entries: List<RemoteEntry>, sort: FilesSort, showHidden: Boolean): List<RemoteEntry> {
        val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        val cmp = when (sort) {
            FilesSort.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
            FilesSort.TIME -> compareByDescending { it.mtime }
            FilesSort.SIZE -> compareByDescending { it.size }
        }
        return visible.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.then(cmp))
    }

    private fun probeTerminalPath(from: TermSession, s: SftpSession): String {
        val name = from.remoteTmuxName.value ?: return ""
        val out = runCatching { from.transport.exec(Tmux.paneCwdCmd(name)) }.getOrNull()?.trim().orEmpty()
        if (out.isBlank() || !out.startsWith("/")) return ""
        // 只取第一行：display-message 正常只输出一行，异常时可能带告警。
        val path = out.lineSequence().first().trim()
        return runCatching { s.canonicalize(path) }.getOrDefault(path)
    }

    private fun describe(t: Throwable, notice: String?): String {
        val base = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return if (notice.isNullOrBlank()) base else "$notice\n$base"
    }
}
