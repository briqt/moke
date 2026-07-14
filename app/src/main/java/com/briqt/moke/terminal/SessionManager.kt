package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** 原生动态标题（转义序列设置，缺省用连接名）。展示请用 [displayTitle]。 */
    val title: StateFlow<String>,
    /** 用户自定义标题：非空则优先级最高，完全覆盖动态标题与前缀。 */
    val customTitle: MutableStateFlow<String?>,
    /** 标题前缀：叠加在动态标题之前（customTitle 存在时忽略）。 */
    val titlePrefix: MutableStateFlow<String?>,
    /** 最终展示标题：customTitle 优先，否则「前缀 + 去掉 mosh 原生前缀后的动态标题」。 */
    val displayTitle: StateFlow<String>,
    /** 传输是否仍存活（false = 会话已结束）。 */
    val alive: StateFlow<Boolean>,
    /** 实时网络往返延迟（ms，null=未知/不适用）。 */
    val latency: StateFlow<Int?>,
    /** 复制会话的不重复标记（如 "(2)"）；null=非复制。附加在展示标题最末，区分同源会话。 */
    val copyMark: String? = null,
    val startedAt: Long,
) {
    /** 设自定义标题（空白视为清除，回落到动态标题）。 */
    fun setCustomTitle(t: String?) { customTitle.value = t?.trim()?.ifBlank { null } }
    /** 设标题前缀（空白视为清除）。 */
    fun setTitlePrefix(p: String?) { titlePrefix.value = p?.trim()?.ifBlank { null } }

    companion object {
        // mosh-client 原生给窗口标题加固定前缀 "[mosh] "（mosh 1.4.0 stmclient.cc: L"[mosh] "，仅一个空格、无点）；
        // 协议已由徽标标识，展示时去掉它。防御性地允许重复与多余空白/点。
        private val MOSH_PREFIX = Regex("^(?:\\[mosh][\\s.·]*)+", RegexOption.IGNORE_CASE)

        /** 组合最终展示标题（见 [displayTitle] 语义）。[mark] 为复制会话的不重复标记，附加在最末以区分同源会话。 */
        fun composeTitle(useMosh: Boolean, raw: String, custom: String?, prefix: String?, mark: String? = null): String {
            custom?.trim()?.takeIf { it.isNotEmpty() }?.let { return appendMark(it, mark) }
            val base = if (useMosh) raw.replaceFirst(MOSH_PREFIX, "") else raw
            val p = prefix?.trim()?.takeIf { it.isNotEmpty() }
            return appendMark(if (p != null) "$p $base" else base, mark)
        }

        private fun appendMark(t: String, mark: String?) = if (mark.isNullOrBlank()) t else "$t $mark"
    }
}

/**
 * 多会话管理器（总纲 §5.6「ViewModel 持有会话列表」）。会话对象常驻 ViewModel，不随导航销毁，
 * 是"多会话"卖点的地基。cold start 无持久化（后台保活为后续里程碑），故重启后列表为空。
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    // 常驻作用域：派生 displayTitle 的 stateIn 用（与 SessionManager 同生命周期，即整个 app）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    /**
     * 为主机新建一个会话（传输在首次 attach 到已测量的 View 时才真正 start）。[jumpHost] 为已解析的跳板机。
     *
     * 标题默认：无前缀（连接名改由副标题固定展示，与前缀无关）；动态标题基座=`user@host`，
     * shell 上报 OSC 标题后被替换。前缀改为纯手动可选项。
     *
     * 复制（[carryFrom] 非空）：沿用来源会话当前的自定义标题与前缀，并生成一个同主机内不重复的标记
     * （如 "(2)"）附加在标题末尾，避免两个同源会话看起来一模一样。
     */
    fun open(host: Host, jumpHost: Host? = null, carryFrom: TermSession? = null): TermSession {
        val baseTitle = baseTitleOf(host)
        // 不设默认前缀；复制时沿用来源前缀，新建会话无前缀（连接名由副标题固定展示）。
        val initialPrefix = carryFrom?.titlePrefix?.value
        val initialCustom = carryFrom?.customTitle?.value
        val mark = if (carryFrom != null) nextCopyMark(host.id) else null

        val title = MutableStateFlow(baseTitle)
        val customTitle = MutableStateFlow(initialCustom)
        val titlePrefix = MutableStateFlow(initialPrefix)
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
        val displayTitle = combine(title, customTitle, titlePrefix) { raw, custom, prefix ->
            TermSession.composeTitle(host.useMosh, raw, custom, prefix, mark)
        }.stateIn(scope, SharingStarted.Eagerly,
            TermSession.composeTitle(host.useMosh, baseTitle, initialCustom, initialPrefix, mark))
        val ts = TermSession(
            id = UUID.randomUUID().toString(),
            host = host,
            controller = controller,
            session = session,
            title = title.asStateFlow(),
            customTitle = customTitle,
            titlePrefix = titlePrefix,
            displayTitle = displayTitle,
            alive = alive.asStateFlow(),
            latency = latency.asStateFlow(),
            copyMark = mark,
            startedAt = System.currentTimeMillis(),
        )
        _sessions.update { it + ts }
        return ts
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
    }
}
