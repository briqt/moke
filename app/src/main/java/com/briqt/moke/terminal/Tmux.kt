package com.briqt.moke.terminal

/** 远端一个 tmux 会话（侧通道 list-sessions 解析所得）。[id]（#{session_id} 如 $0）为稳定 key。 */
data class TmuxSession(
    val id: String,
    val name: String,
    val windows: Int,
    val attached: Boolean,
    val created: Long,   // epoch 秒
)

/** tmux 侧通道命令与输出解析（纯逻辑，无副作用）。字段以制表符分隔。 */
object Tmux {
    // 列表：id \t name \t windows \t attached(1/0) \t created；2>/dev/null 吞掉"无 server"之类 stderr。
    const val LIST_CMD =
        "tmux list-sessions -F '#{session_id}\t#{session_name}\t#{session_windows}\t#{?session_attached,1,0}\t#{session_created}' 2>/dev/null"

    fun parse(out: String): List<TmuxSession> = out.lineSequence()
        .mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            TmuxSession(
                id = p[0].trim(),
                name = p[1],
                windows = p[2].trim().toIntOrNull() ?: 0,
                attached = p[3].trim() == "1",
                created = p[4].trim().toLongOrNull() ?: 0L,
            )
        }.toList()

    // 单引号安全包裹（防远端 shell 对空格/$ 等做扩展）。
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun newCmd(name: String) = "tmux new-session -d -s ${q(name)}"
    fun renameCmd(id: String, name: String) = "tmux rename-session -t ${q(id)} ${q(name)}"
    fun killCmd(id: String) = "tmux kill-session -t ${q(id)}"

    /** 附加需 TTY → 不走侧通道，注入当前前台 PTY 执行（会显示在用户画面，符合预期）。按名附加，末尾回车。 */
    fun attachInput(name: String) = "tmux attach -t ${q(name)}\r"
}
