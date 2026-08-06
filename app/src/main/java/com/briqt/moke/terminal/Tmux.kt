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

    /**
     * [term] 是远端**真的可用**的最佳 TERM（协商结果，见 [Tmux.DISCOVER_CMD]）；null 表示远端没有
     * 判定工具，保持默认值。
     */
    data class Ready(val sessions: List<TmuxSession>, val term: String? = null) : TmuxDiscovery
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
    private const val TERM_PREFIX = "__MOKE_TERM__:"

    /** moke 渲染内核对齐 xterm；只在远端确实没有该条目时才逐级降级。 */
    const val DEFAULT_TERM = "xterm-256color"
    private val TERM_CANDIDATES = listOf("xterm-256color", "screen-256color", "xterm", "vt100")
    private val TERM_SAFE = Regex("""^[A-Za-z0-9._-]{1,32}$""")
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
    /**
     * TERM 协商：挑出远端**真的能用**的第一个候选条目。
     *
     * 必须用 `tput clear` 判定而不是 `infocmp`：实测有主机的 `infocmp` 能找到条目、但该条目缺
     * `clear` 能力，tmux 仍会以 `open terminal failed: terminal does not support clear` 拒绝启动
     * （这正是「面板新建正常、附加全挂」的根因）。两个工具都没有时不输出，调用方保持默认值。
     */
    private val TERM_PROBE =
        "moke_t=''; for t in ${TERM_CANDIDATES.joinToString(" ")}; do " +
            "if command -v tput >/dev/null 2>&1; then " +
            "TERM=\"\$t\" tput clear >/dev/null 2>&1 && { moke_t=\$t; break; }; " +
            "elif infocmp \"\$t\" >/dev/null 2>&1; then moke_t=\$t; break; fi; done; " +
            "[ -n \"\$moke_t\" ] && printf '$TERM_PREFIX%s\\n' \"\$moke_t\"; "

    val DISCOVER_CMD =
        "if ! command -v tmux >/dev/null 2>&1; then " +
            "printf '$DISCOVERY_MISSING\\n'; " +
            "else printf '$DISCOVERY_READY\\n'; " +
            TERM_PROBE +
            "tmux -u list-sessions " +
            "-F '#{session_id}:#{session_name}:#{session_windows}:#{session_attached}:#{session_created}' " +
            "2>/dev/null || true; fi"

    fun parseDiscovery(out: String): TmuxDiscovery {
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return TmuxDiscovery.Malformed
        if (lines.first().trim() == DISCOVERY_MISSING) return TmuxDiscovery.NotInstalled
        if (lines.first().trim() != DISCOVERY_READY) return TmuxDiscovery.Malformed

        val rest = lines.drop(1)
        val term = rest.firstOrNull { it.trim().startsWith(TERM_PREFIX) }
            ?.trim()?.removePrefix(TERM_PREFIX)
            ?.takeIf { TERM_SAFE.matches(it) }
        val sessionLines = rest.filterNot { it.trim().startsWith(TERM_PREFIX) }
        val sessions = sessionLines.mapNotNull(::parseSessionLine)
        return if (sessions.size == sessionLines.size) {
            TmuxDiscovery.Ready(sessions, term)
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
     *
     * 三处要点（rc.3 附加全挂的教训）：
     * 1. **TERM 用协商结果**。远端没有 `xterm-256color` 条目时 tmux 直接拒绝启动，而登录壳无感，
     *    于是表现成「新建正常、附加秒关」。协商见 [DISCOVER_CMD]。
     * 2. **不 `exec tmux`**。tmux 非零退出时回落登录壳，错误留在屏幕上而不是通道立刻 EOF、
     *    UI 只剩「会话已结束」。干净 detach（exit 0）仍让整条命令 exit 0，标签正常结束。
     * 3. 会话名走 `$1`（argv），不参与 shell 解析；`-u` 强制 UTF-8，中文会话名才正确。
     *
     * 该串同时用于 SSH 的 `exec`（已分配 PTY）与 mosh 的 `mosh-server … -- …`（mosh-server 直接
     * execvp，故必须自带 `sh -c`）。
     */
    fun attachOrCreateCommand(
        name: String,
        detachOthers: Boolean = false,
        term: String? = null,
    ): String {
        val flag = if (detachOthers) " -D" else ""
        // 候选顺序：已协商到的值优先（避免重复试探），其后是默认梯度。协商**内联在命令里**，
        // 因为「连接时就附加」发生在任何侧通道探测之前，此时还没有可用的协商结果。
        val candidates = (listOfNotNull(term?.takeIf { TERM_SAFE.matches(it) }) + TERM_CANDIDATES)
            .distinct()
            .joinToString(" ")
        return "sh -c '" +
            "for t in $candidates; do " +
            "if command -v tput >/dev/null 2>&1; then " +
            "TERM=\"\$t\" tput clear >/dev/null 2>&1 && { TERM=\$t; export TERM; break; }; " +
            "elif infocmp \"\$t\" >/dev/null 2>&1; then TERM=\$t; export TERM; break; fi; done; " +
            "command -v tmux >/dev/null 2>&1 || " +
            "{ echo \"moke: tmux not found on this host\"; exec \${SHELL:-sh} -l; }; " +
            "tmux -u new-session -A$flag -s \"\$1\"; " +
            "ec=\$?; [ \$ec -eq 0 ] && exit 0; " +
            "echo \"moke: tmux exited (\$ec)\"; exec \${SHELL:-sh} -l' sh ${q(name)}"
    }

    /**
     * 取会话当前活动 pane 的工作目录（文件页的起始路径）。
     * 只有 tmux 会话拿得到：普通登录壳的 cwd 属于那个 shell 进程，侧通道另开的 exec 看不到。
     */
    fun paneCwdCmd(name: String) =
        "tmux display-message -p -t ${q(name)} '#{pane_current_path}' 2>/dev/null || true"

    /**
     * 附加确认：数一下该会话当前的 tmux 客户端。attach 命令发出不等于附加成功（TERM 不可用、
     * tmux 启动失败都会回落登录壳），不核对就会出现「UI 说在 tmux 里、其实是普通 shell」。
     */
    fun clientsCmd(name: String) =
        "tmux -u list-clients -t ${q(name)} -F 'c' 2>/dev/null | grep -c '^c' || true"

    /** 解析 [clientsCmd] 的输出；无法解析返回 null（视为"无法确认"，不等于未附加）。 */
    fun parseClientCount(out: String?): Int? =
        out?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    /**
     * 由连接名派生默认会话名（选择器预填）。tmux 的会话名不允许含 `:`（目标语法分隔符）与 `.`，
     * 空白也会给 `-t` 带来歧义，统一折叠成 `-`；全部不可用时回落 `moke`。
     */
    fun defaultSessionName(label: String): String =
        label.trim()
            .replace(Regex("""[^\p{L}\p{N}_-]+"""), "-")
            .trim('-')
            .take(32)
            .ifBlank { "moke" }

    /**
     * rc.1 曾把运行时 attach 命令误写回 Host.loginCommand。稳定数字 ID 由 tmux server 临时分配，
     * 跨 server 生命周期无效；只清理这一精确的旧版生成形态，不碰按名称或包含其它逻辑的用户命令。
     */
    fun isLegacyInjectedLoginCommand(command: String): Boolean =
        LEGACY_LOGIN_COMMAND.matches(command)
}
