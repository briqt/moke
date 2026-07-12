package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** 终端标题（转义序列设置，缺省用连接名）。 */
    val title: StateFlow<String>,
    /** 传输是否仍存活（false = 会话已结束）。 */
    val alive: StateFlow<Boolean>,
    /** 实时网络往返延迟（ms，null=未知/不适用）。 */
    val latency: StateFlow<Int?>,
    val startedAt: Long,
)

/**
 * 多会话管理器（总纲 §5.6「ViewModel 持有会话列表」）。会话对象常驻 ViewModel，不随导航销毁，
 * 是"多会话"卖点的地基。cold start 无持久化（后台保活为后续里程碑），故重启后列表为空。
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    /** 为主机新建一个会话（传输在首次 attach 到已测量的 View 时才真正 start）。[jumpHost] 为已解析的跳板机。 */
    fun open(host: Host, jumpHost: Host? = null): TermSession {
        val title = MutableStateFlow(host.displayName)
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
            title = title.asStateFlow(),
            alive = alive.asStateFlow(),
            latency = latency.asStateFlow(),
            startedAt = System.currentTimeMillis(),
        )
        _sessions.update { it + ts }
        return ts
    }

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    /** 关闭并从列表移除（关传输幂等）。 */
    fun close(id: String) {
        val ts = get(id) ?: return
        runCatching { ts.session.finishIfRunning() }
        _sessions.update { list -> list.filterNot { it.id == id } }
    }
}
