package com.briqt.moke.terminal

/**
 * mosh 引导契约解析（总纲 §5.5）：SSH 进服务器执行 `mosh-server new ...`，
 * 服务器打印 `MOSH CONNECT <udp_port> <base64_key>`，据此再启动 mosh-client。
 *
 * v0.1 仅落地"引导链"里可纯逻辑验证的一环（命令生成 + stdout 解析，含单测）；
 * native mosh-client（NDK/独立子进程）集成为 M2（总纲风险 1）。
 */
object MoshBootstrap {

    /** key = 22 个 base64 字符（128-bit AES-OCB 密钥，无 padding）。 */
    private val REGEX = Regex("""MOSH CONNECT (\d{1,5}) ([A-Za-z0-9/+]{22})""")

    data class Connect(val port: Int, val key: String)

    /** 从 mosh-server 的 stdout 解析连接信息；解析失败或端口越界返回 null。 */
    fun parse(output: String): Connect? {
        val m = REGEX.find(output) ?: return null
        val port = m.groupValues[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Connect(port, m.groupValues[2])
    }

    /**
     * 无客户端联络时 mosh-server 自我了断的时限（秒）。
     *
     * mosh-server 按漫游语义设计：客户端消失只当作"网络断了"，会一直等下一个客户端。
     * moke 从不复用既有 mosh-server（每次连接都重新引导一条），所以失联的 server 一律是垃圾——
     * 但它仍占着一个进程（实测 ~4.5MB RSS）和一个 UDP 端口，直到主机重启。正常关闭会话时由
     * {@link MoshTransport#close} 让 mosh-client 走退出协议收掉远端；这里是兜底：应用被强停/
     * 进程被杀、退出序列没送达时，远端最终也会自己消失。
     *
     * 取 24h：远超关屏、切网、Doze 等真实断流窗口（客户端每 3s 一次心跳），又能封住无限累积。
     * tmux 会话不受影响——它活在独立的 tmux server 里，与 mosh 无关。
     */
    const val SERVER_NETWORK_TMOUT_SECONDS = 86_400

    /**
     * 生成 mosh-server 引导命令。locale 传递关键（否则 UTF-8 宽字符会乱）。
     *
     * [startupCommand] 非空时由 mosh-server 直接启动该程序，而不是先启动默认 shell 再模拟输入。
     * tmux 需要真实 PTY；mosh 本身已提供 PTY 语义，这条路径能避开提示符/行编辑器时序。
     *
     * `MOSH_SERVER_NETWORK_TMOUT` 走命令前缀而非 `-l`：`-l` 是给子进程（shell）的环境，
     * 这个变量要读进 mosh-server 自己的环境才生效。SSH exec 由远端登录 shell 执行，前缀赋值可用。
     */
    fun serverCommand(
        locale: String = "en_US.UTF-8",
        startupCommand: String? = null,
    ): String = buildString {
        append("MOSH_SERVER_NETWORK_TMOUT=")
        append(SERVER_NETWORK_TMOUT_SECONDS)
        append(" mosh-server new -s -c 256 -l LANG=")
        append(locale)
        startupCommand?.takeIf { it.isNotBlank() }?.let {
            append(" -- ")
            append(it)
        }
    }
}
