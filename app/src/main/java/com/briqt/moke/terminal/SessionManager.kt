package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/**
 * 一个活动终端会话。传输 / emulator / 滚屏历史都在 [session] 内，**跨页面存活**；
 * 终端页每次进入时重建 TerminalView 并 attach 到既有 [session]——因 TerminalView.updateSize
 * 对已存在的 emulator 只 resize 不重建，滚屏与连接得以保留（见 terminal-view 分析）。
 */
class TermSession(
    val id: String,
    val host: Host,
    val controller: TerminalController,
    val session: TerminalSession,
    /** 底层传输（用于 tmux 侧通道 exec 等带外能力）。 */
    val transport: TerminalTransport,
    /** 原生动态标题（转义序列设置，缺省用连接名）。展示请用 [displayTitle]。 */
    val title: StateFlow<String>,
    /** 用户自定义标题：非空则优先级最高，完全覆盖动态标题。 */
    val customTitle: MutableStateFlow<String?>,
    private val displayTitleState: MutableStateFlow<String>,
    /** 传输是否仍存活（false = 会话已结束）。 */
    val alive: StateFlow<Boolean>,
    /** 实时网络往返延迟（ms，null=未知/不适用）。 */
    val latency: StateFlow<Int?>,
    /**
     * 复制会话的消歧标记（如 "(2)"）；null=非复制。
     * 它不是标题内容：仅在同主机实际展示标题冲突时临时出现，自定义标题时永不强加。
     */
    val copyMark: String? = null,
    /** 由 tmux 面板打开的远端 session ID；断开/删除/远端消失后清空。 */
    val remoteTmuxId: MutableStateFlow<String?>,
    val startedAt: Long,
) {
    /** 最终展示标题：customTitle 优先；复制标记仅作临时冲突消歧。 */
    val displayTitle: StateFlow<String> = displayTitleState.asStateFlow()

    /** 最后活动时间（有终端输出即刷新）：用于「更新时间」排序。非响应式（普通 volatile），列表重组时读当前值即可，避免高频重排抖动。 */
    @Volatile var lastActivityAt: Long = startedAt

    /** tmux 管理完整状态；明确区分检查中、零会话、未安装与失败。 */
    val tmuxState: MutableStateFlow<TmuxUiState> = MutableStateFlow(TmuxUiState())
    val tmuxMutex = Mutex()

    /** 设自定义标题（空白视为清除，回落到动态标题）。 */
    fun setCustomTitle(t: String?) { customTitle.value = t?.trim()?.ifBlank { null } }

    internal fun updateDisplayTitle(title: String) {
        displayTitleState.value = title
    }

    companion object {
        // mosh-client 原生给窗口标题加固定前缀 "[mosh] "（mosh 1.4.0 stmclient.cc: L"[mosh] "，仅一个空格、无点）；
        // 协议已由徽标标识，展示时去掉它。防御性地允许重复与多余空白/点。
        private val MOSH_PREFIX = Regex("^(?:\\[mosh][\\s.·]*)+", RegexOption.IGNORE_CASE)

        /** 组合标题本体；复制标记由 SessionManager 根据实时冲突另行派生，不写进标题本体。 */
        fun composeTitle(useMosh: Boolean, raw: String, custom: String?): String {
            custom?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return if (useMosh) raw.replaceFirst(MOSH_PREFIX, "") else raw
        }

        fun disambiguateTitle(base: String, custom: String?, mark: String?, hasCollision: Boolean): String =
            if (custom.isNullOrBlank() && hasCollision && !mark.isNullOrBlank()) "$base $mark" else base
    }
}

/**
 * 多会话管理器（总纲 §5.6「ViewModel 持有会话列表」）。会话对象常驻 ViewModel，不随导航销毁，
 * 是"多会话"卖点的地基。cold start 无持久化（后台保活为后续里程碑），故重启后列表为空。
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    // 常驻作用域：监听动态/自定义标题并实时重新计算冲突消歧（与整个 app 同生命周期）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    /**
     * 为主机新建一个会话（传输在首次 attach 到已测量的 View 时才真正 start）。[jumpHost] 为已解析的跳板机。
     *
     * 标题：动态标题基座=`user@host`，shell 上报 OSC 标题后被替换（展示在标题行第 1 行）；
     * 连接名（设备名）由副标题第 2 行固定展示。无前缀概念。
     *
     * 复制（[carryFrom] 非空）：沿用来源当前标题与自定义标题，并生成同主机内不重复的标记。
     * 标记只在两个实际标题仍冲突时显示；标题自然分化或用户手动命名后自动消失。
     */
    fun open(
        host: Host,
        jumpHost: Host? = null,
        carryFrom: TermSession? = null,
        initialTitle: String? = null,
        remoteTmuxId: String? = null,
    ): TermSession {
        val baseTitle = baseTitleOf(host)
        val initialCustom = carryFrom?.customTitle?.value
        val mark = if (carryFrom != null) nextCopyMark(host.id) else null

        val title = MutableStateFlow(initialTitle ?: carryFrom?.title?.value ?: baseTitle)
        val customTitle = MutableStateFlow(initialCustom)
        val initialDisplay = TermSession.composeTitle(host.useMosh, title.value, initialCustom)
        val displayTitle = MutableStateFlow(initialDisplay)
        val alive = MutableStateFlow(true)
        val latency = MutableStateFlow<Int?>(null)
        val controller = TerminalController(
            context = appContext,
            onFinished = { alive.value = false; latency.value = null },
            onTitle = { t -> if (!t.isNullOrBlank()) title.value = t },
        )
        // 传输选择：偏好 mosh 的主机走 MoshTransport（SSH 引导 + native mosh-client 子进程 PTY），
        // 否则走 SshTransport（并周期探测 RTT 供状态条显示）。
        val transport = if (host.useMosh) MoshTransport(host, appContext, jumpHost)
        else SshTransport(host, appContext, jumpHost, onLatency = { latency.value = it })
        val session = TerminalSession(transport, 2000, controller)
        val ts = TermSession(
            id = UUID.randomUUID().toString(),
            host = host,
            controller = controller,
            session = session,
            transport = transport,
            title = title.asStateFlow(),
            customTitle = customTitle,
            displayTitleState = displayTitle,
            alive = alive.asStateFlow(),
            latency = latency.asStateFlow(),
            copyMark = mark,
            remoteTmuxId = MutableStateFlow(remoteTmuxId),
            startedAt = System.currentTimeMillis(),
        )
        // 有输出即刷新会话最后活动时间（供"更新时间"排序）。
        controller.onActivity = { ts.lastActivityAt = System.currentTimeMillis() }
        _sessions.update { it + ts }
        combine(title, customTitle) { _, _ -> Unit }
            .onEach { refreshDisplayTitles() }
            .launchIn(scope)
        refreshDisplayTitles()
        return ts
    }

    /**
     * 在新的干净终端连接中附加远端 tmux；同一主机同一 tmux ID 已有活会话时直接复用。
     * 这避免向任意前台程序/半输入命令盲注入 `tmux attach`，也杜绝 tmux 内再嵌套 tmux。
     */
    fun openTmux(source: TermSession, target: TmuxSession, jumpHost: Host? = null): TermSession {
        _sessions.value.firstOrNull {
            it.host.id == source.host.id && it.remoteTmuxId.value == target.id && it.alive.value
        }?.let { return it }

        val runtimeHost = source.host.copy(loginCommand = Tmux.attachCommand(target.id))
        return open(
            host = runtimeHost,
            jumpHost = jumpHost,
            initialTitle = "tmux · ${target.name}",
            remoteTmuxId = target.id,
        )
    }

    /** 根据实时标题冲突派生复制标记；用户自定义标题具有绝对优先级。 */
    private fun refreshDisplayTitles() {
        val list = _sessions.value
        val baseById = list.associate { ts ->
            ts.id to TermSession.composeTitle(ts.host.useMosh, ts.title.value, ts.customTitle.value)
        }
        val collisionCount = list.groupingBy { ts ->
            ts.host.id to baseById.getValue(ts.id)
        }.eachCount()

        list.forEach { ts ->
            val base = baseById.getValue(ts.id)
            val collides = collisionCount.getValue(ts.host.id to base) > 1
            ts.updateDisplayTitle(
                TermSession.disambiguateTitle(base, ts.customTitle.value, ts.copyMark, collides)
            )
        }
    }

    /** 断开/关闭远端 tmux 后，关联的 Moke 终端已回到普通 shell，不应继续标成“当前”。 */
    fun clearTmuxAssociation(hostId: String, remoteId: String) {
        _sessions.value
            .filter { it.host.id == hostId && it.remoteTmuxId.value == remoteId }
            .forEach { it.remoteTmuxId.value = null }
    }

    /** 外部删除 tmux 会话时也同步清理过期关联。 */
    fun reconcileTmuxAssociations(hostId: String, liveRemoteIds: Set<String>) {
        _sessions.value
            .filter {
                it.host.id == hostId &&
                    it.remoteTmuxId.value != null &&
                    it.remoteTmuxId.value !in liveRemoteIds
            }
            .forEach { it.remoteTmuxId.value = null }
    }

    /** 动态标题基座（OSC 上报前）：优先 `user@host`；缺 host 回落 displayName、缺 user 只用 host。 */
    private fun baseTitleOf(host: Host): String = when {
        host.host.isBlank() -> host.displayName
        host.username.isBlank() -> host.host
        else -> "${host.username}@${host.host}"
    }

    /** 复制会话标记 "(n)"：在同一主机现有会话已用标记中取未占用的最小 n≥2（未标记会话隐含为 1）。 */
    private fun nextCopyMark(hostId: String): String {
        val used = _sessions.value
            .filter { it.host.id == hostId }
            .mapNotNull { it.copyMark?.trim()?.removeSurrounding("(", ")")?.toIntOrNull() }
            .toSet()
        var n = 2
        while (n in used) n++
        return "($n)"
    }

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    /** 拖动重排：按给定的 id 顺序重排会话列表（仅内存）。未知 id 忽略、缺失的追加在末尾。 */
    fun reorder(orderedIds: List<String>) {
        _sessions.update { list ->
            val byId = list.associateBy { it.id }
            val front = orderedIds.mapNotNull { byId[it] }
            val rest = list.filter { it.id !in orderedIds }
            (front + rest).takeIf { it.size == list.size } ?: list
        }
    }

    /** 关闭并从列表移除（关传输幂等）。 */
    fun close(id: String) {
        val ts = get(id) ?: return
        runCatching { ts.session.finishIfRunning() }
        _sessions.update { list -> list.filterNot { it.id == id } }
        refreshDisplayTitles()
    }
}
