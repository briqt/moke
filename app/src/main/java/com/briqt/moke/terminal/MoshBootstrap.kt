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

    /** 生成 mosh-server 引导命令。locale 传递关键（否则 UTF-8 宽字符会乱）。 */
    fun serverCommand(locale: String = "en_US.UTF-8"): String =
        "mosh-server new -s -c 256 -l LANG=$locale"
}
