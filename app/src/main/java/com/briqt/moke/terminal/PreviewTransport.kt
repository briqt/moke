package com.briqt.moke.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import java.nio.charset.StandardCharsets

/**
 * 外观预览用的样张传输：start 时喂一段紧凑样张（Latin + 中文 + 符号 + 0-15 色带），之后不再有 I/O，
 * 也不 finish（预览不显示"会话结束"）。样张用**基础 0-15 号色**与默认前/背景，故切换配色后
 * 对预览 emulator 调 `mColors.reset()` 即可即时换色。非可连接入口。
 */
class PreviewTransport : TerminalTransport {

    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        redraw(session)
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {}
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {}
    override fun close() {}

    companion object {
        private const val E = ""
        /**
         * 重绘样张：样张自带清屏 + 清回滚 + 光标归位，可反复调用。
         * 字号 / 字间距 / 字体变化会改列数触发内核 reflow，而 reflow 丢弃"全空格"行
         * （色带正是背景色空格 → 被判为空行丢掉）；每次几何变化重绘即可保证色带常在、内容顶格。
         */
        fun redraw(session: TerminalSession) {
            val b = SAMPLE.toByteArray(StandardCharsets.UTF_8)
            session.processToEmulator(b, b.size)
        }

        // 样张 5 行（末行不换行，避免多占一行光标行）：提示符 / 中文+符号 / 编程符号 / 两条 0-15 色带。
        private val SAMPLE = buildString {
            append("${E}[3J${E}[2J${E}[H")   // 清回滚 + 清屏 + 光标归位（自包含，可反复重绘）
            // 提示符（green + blue 走 0-15 号，随配色变）
            append("${E}[32mmoke${E}[0m:${E}[34m~${E}[0m\$ ls -la\r\n")
            // git 状态风格 + 中文
            append("${E}[31m●${E}[0m ${E}[35mmain${E}[0m ${E}[32m✓${E}[0m clean  你好世界 Hello\r\n")
            // 编程符号（默认前景，看字形/连字/对齐）
            append("${E}[90m{} () [] != -> :: >= 0O1lI |\\/${E}[0m\r\n")
            // 0-15 号色带（背景块），随配色即时变
            append(" ")
            for (i in 0..7) append("${E}[48;5;${i}m  ")
            append("${E}[0m\r\n ")
            for (i in 8..15) append("${E}[48;5;${i}m  ")
            append("${E}[0m")
        }
    }
}
