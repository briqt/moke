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
    /**
     * 列表：`id:name:windows:attached(1/0):created`，`2>/dev/null` 吞掉"无 server"之类 stderr。
     *
     * 两个坑（都在真机上踩过）：
     * 1. **分隔符不能用 TAB**——tmux 会把格式串里的不可打印字符（含 TAB）统一替换成 `_`，
     *    于是整行变成一个字段、解析出空列表（表现为"面板说没有会话"）。改用 `:`：
     *    tmux 明令会话名不得含 `:` 与 `.`，故它天然安全。
     * 2. **locale 不是 UTF-8 时，中文会话名同样被逐字节换成 `_`**——exec 通道拿到的是非交互
     *    shell，往往只有 `LANG=C`。故先从 `locale -a` 里挑一个可用的 UTF-8 locale 再跑 tmux；
     *    挑不到就退回 C（行为与从前一致，仅中文名仍显示为 `_`，不影响解析）。
     */
    const val LIST_CMD =
        "L=$(locale -a 2>/dev/null | grep -iE '^(C|en_US)\\.utf-?8$' | head -1); " +
            "LC_ALL=\${L:-C} tmux list-sessions " +
            "-F '#{session_id}:#{session_name}:#{session_windows}:#{?session_attached,1,0}:#{session_created}' 2>/dev/null"

    /**
     * 远端是否装了 tmux。必须与 [LIST_CMD] 分开问——`tmux ls` 在"装了但没有 server"时同样是空输出，
     * 无法区分"没装"和"零会话"，而入口图标的常驻与否正取决于这个区分。
     */
    const val PROBE_CMD = "command -v tmux >/dev/null 2>&1 && echo yes || echo no"

    fun parseProbe(out: String?): Boolean = out?.trim() == "yes"

    /**
     * 解析 [LIST_CMD] 输出。首段为 id、末三段为 windows/attached/created，**中间全部并回名字**——
     * 这样即便名字里意外出现分隔符（tmux 理论上不允许）也不会串位，而是原样保留。
     */
    fun parse(out: String): List<TmuxSession> = out.lineSequence()
        .mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split(':')
            if (p.size < 5) return@mapNotNull null
            TmuxSession(
                id = p[0].trim(),
                name = p.subList(1, p.size - 3).joinToString(":"),
                windows = p[p.size - 3].trim().toIntOrNull() ?: 0,
                attached = p[p.size - 2].trim() == "1",
                created = p[p.size - 1].trim().toLongOrNull() ?: 0L,
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
