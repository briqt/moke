package com.briqt.moke.terminal

/**
 * 远端一个 tmux 会话（侧通道 list-sessions 解析所得）。
 * [id]（#{session_id} 如 $0）是当前 tmux server 生命周期内的精确句柄；[name] 是跨连接恢复身份。
 */
data class TmuxSession(
    val id: String,
    val name: String,
    val windows: Int,
    /** 当前附加到该会话的 tmux client 数。 */
    val clients: Int,
    val created: Long,   // epoch 秒
)

enum class TmuxPhase {
    IDLE,
    CHECKING,
    READY,
    NOT_INSTALLED,
    ERROR,
}

/**
 * tmux 管理面板的完整状态。不能再用空列表同时表示「没探测、零会话、失败」：
 * 那会把真实错误伪装成“没有会话”，用户也无从重试。
 */
data class TmuxUiState(
    val phase: TmuxPhase = TmuxPhase.IDLE,
    val sessions: List<TmuxSession> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface TmuxDiscovery {
    data object NotInstalled : TmuxDiscovery
    data class Ready(val sessions: List<TmuxSession>) : TmuxDiscovery
    data object Malformed : TmuxDiscovery
}

data class TmuxActionResult(
    val ok: Boolean,
    val output: String,
)

/** tmux 侧通道命令与输出解析（纯逻辑，无副作用）。 */
object Tmux {
    private const val DISCOVERY_READY = "__MOKE_TMUX__:ready"
    private const val DISCOVERY_MISSING = "__MOKE_TMUX__:missing"
    private const val ACTION_PREFIX = "__MOKE_TMUX_RC__:"
    private val LEGACY_LOGIN_COMMAND = Regex(
        """^\s*tmux\s+attach-session\s+-t\s+['"]?\$\d+['"]?\s*$"""
    )

    /**
     * 探测 + 列表一次完成，避免 mosh 控制链为一次刷新重复建立两条 SSH 连接。
     *
     * - `tmux -u` 是 tmux 官方的强制 UTF-8 输出开关，不再依赖远端恰好装有 `locale/grep/head`
     *   或非交互 shell 的 LANG；中文名可稳定返回。
     * - 分隔符不能用 TAB：tmux 会把格式串里的不可打印字符替换成 `_`。改用会话名不允许出现的
     *   `:`，并从行尾反向解析数值字段。
     * - `list-sessions` 在“已安装但还没有 tmux server”时退出非零，仍是合法的零会话状态。
     */
    const val DISCOVER_CMD =
        "if ! command -v tmux >/dev/null 2>&1; then " +
            "printf '$DISCOVERY_MISSING\\n'; " +
            "else printf '$DISCOVERY_READY\\n'; " +
            "tmux -u list-sessions " +
            "-F '#{session_id}:#{session_name}:#{session_windows}:#{session_attached}:#{session_created}' " +
            "2>/dev/null || true; fi"

    fun parseDiscovery(out: String): TmuxDiscovery {
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return TmuxDiscovery.Malformed
        if (lines.first().trim() == DISCOVERY_MISSING) return TmuxDiscovery.NotInstalled
        if (lines.first().trim() != DISCOVERY_READY) return TmuxDiscovery.Malformed

        val sessionLines = lines.drop(1)
        val sessions = sessionLines.mapNotNull(::parseSessionLine)
        return if (sessions.size == sessionLines.size) {
            TmuxDiscovery.Ready(sessions)
        } else {
            TmuxDiscovery.Malformed
        }
    }

    private fun parseSessionLine(line: String): TmuxSession? {
        val p = line.split(':')
        if (p.size < 5) return null
        val id = p[0].trim()
        val windows = p[p.size - 3].trim().toIntOrNull() ?: return null
        val clients = p[p.size - 2].trim().toIntOrNull() ?: return null
        val created = p[p.size - 1].trim().toLongOrNull() ?: return null
        if (!id.startsWith("$")) return null
        return TmuxSession(
            id = id,
            name = p.subList(1, p.size - 3).joinToString(":"),
            windows = windows,
            clients = clients,
            created = created,
        )
    }

    // 单引号安全包裹（防远端 shell 对空格/$ 等做扩展）。
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun newCmd(name: String) = "tmux new-session -d -s ${q(name)}"
    fun renameCmd(id: String, name: String) = "tmux rename-session -t ${q(id)} ${q(name)}"
    fun detachCmd(id: String) = "tmux detach-client -s ${q(id)}"
    fun killCmd(id: String) = "tmux kill-session -t ${q(id)}"

    /**
     * 把管理命令包装成可解析的结果。stderr 合入结果，失败原因才能在 UI 中显示；
     * 首行固定返回退出码，后续为 tmux 原始输出。
     */
    fun actionCmd(command: String): String =
        "O=\$({ $command; } 2>&1); R=\$?; " +
            "printf '$ACTION_PREFIX%s\\n' \"\$R\"; printf '%s' \"\$O\""

    fun parseAction(out: String): TmuxActionResult? {
        val firstBreak = out.indexOf('\n')
        val first = (if (firstBreak >= 0) out.substring(0, firstBreak) else out).trim()
        if (!first.startsWith(ACTION_PREFIX)) return null
        val code = first.removePrefix(ACTION_PREFIX).toIntOrNull() ?: return null
        val body = if (firstBreak >= 0) out.substring(firstBreak + 1).trim() else ""
        return TmuxActionResult(ok = code == 0, output = body)
    }

    /**
     * 本地关联跨刷新收敛：名称优先（跨 tmux server 生命周期），ID 仅作为远端手工重命名的兜底。
     * server 重启会从 `$0` 重新编号，所以绝不能在旧名称仍存在时优先相信碰巧复用的 ID。
     */
    fun resolveAssociation(
        remoteId: String?,
        remoteName: String?,
        sessions: List<TmuxSession>,
    ): TmuxSession? =
        remoteName?.let { name -> sessions.firstOrNull { it.name == name } }
            ?: remoteId?.let { id -> sessions.firstOrNull { it.id == id } }

    /**
     * 在一个新的、干净的 Moke 终端连接里原子地恢复 tmux，绝不注入当前前台输入。
     *
     * attach-session + session_id 有两个竞态：tmux server 重启后 `$N` 会失效；列表刷新与真正
     * attach 之间会话也可能被关闭。`new-session -A -s name` 由 tmux 自身原子地“存在则附加，
     * 不存在则创建”，名称还是用户可识别、可跨连接恢复的稳定身份。`-D` 是 tmux 对应的接管语义。
     */
    fun attachOrCreateCommand(name: String, detachOthers: Boolean = false) =
        "tmux new-session -A${if (detachOthers) " -D" else ""} -s ${q(name)}"

    /**
     * rc.1 曾把运行时 attach 命令误写回 Host.loginCommand。稳定数字 ID 由 tmux server 临时分配，
     * 跨 server 生命周期无效；只清理这一精确的旧版生成形态，不碰按名称或包含其它逻辑的用户命令。
     */
    fun isLegacyInjectedLoginCommand(command: String): Boolean =
        LEGACY_LOGIN_COMMAND.matches(command)
}
