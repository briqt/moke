package com.briqt.moke.terminal

import com.termux.terminal.TerminalEmulator

/**
 * 终端里的链接识别（纯逻辑，无 Android 依赖，可被 JVM 单测覆盖）。
 *
 * 只认 http / https。点到的位置必须落在链接的字符上才算命中——点在空白处照旧是"弹键盘"。
 */
object TerminalLinks {

    // 链接主体：到空白、引号、尖括号或中日韩标点 / 全角符号为止（URL 后面紧跟"。"、"，"很常见）。
    private val URL = Regex("""https?://[^\s<>"'`\u3000-\u303F\uFF00-\uFFEF]+""")

    /** 结尾常见的句读，不属于链接本身。 */
    private const val TRAILING = ".,;:!?'\""

    /**
     * 向上 / 向下各最多拼接这么多字符的软换行：带签名参数的下载链接、OAuth 回调常有上千字符，
     * 按固定行数拼会在窄屏上截掉尾巴；又不至于在超长输出上无限扫描。
     */
    private const val MAX_LINK_CHARS = 2048

    /** 操作条上显示的链接超过这个长度就省略中段，首尾都留着（尾部常是区分链接的那部分）。 */
    private const val DISPLAY_MAX = 140
    private const val DISPLAY_HEAD = 88
    private const val DISPLAY_TAIL = 50

    /** 给操作条显示用的链接文本；复制、打开仍用完整链接。 */
    fun displayOf(url: String): String =
        if (url.length <= DISPLAY_MAX) url else url.take(DISPLAY_HEAD) + "…" + url.takeLast(DISPLAY_TAIL)

    /** [text] 中覆盖下标 [index] 的链接；没有则 null。 */
    fun urlAt(text: String, index: Int): String? {
        if (index !in text.indices) return null
        for (m in URL.findAll(text)) {
            if (index < m.range.first) return null
            val url = trimTrailing(m.value)
            if (index < m.range.first + url.length) return url
        }
        return null
    }

    /** 指向本机回环地址的链接（`localhost` / `127.0.0.1` / `0.0.0.0` / `[::1]`）：在手机上直接打开没有意义。 */
    fun isLocal(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
            .lowercase()
        return host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "[::1]"
    }

    /**
     * 终端第 [row] 行（相对当前滚屏位置的外部行号）第 [column] 列上的链接。
     *
     * 长链接会被软换行拆到多行，所以先把整条逻辑行拼回来再找。列 → 字符下标的换算交给
     * `getSelectedText`，它按 `TerminalRow.findStartOfColumn` 走，宽字符（中文）占两列也算得对；
     * 上游 `getWordAtLocation` 按"一列一字符"算，一行里有中文就会错位，所以不用它。
     */
    fun urlAt(emulator: TerminalEmulator, column: Int, row: Int): String? {
        val buf = emulator.screen
        val cols = emulator.mColumns
        val top = -buf.activeTranscriptRows
        val bottom = emulator.mRows - 1
        if (column !in 0 until cols || row !in top..bottom) return null
        // 两行是否属于同一逻辑行：只认终端记录的软换行标记。上游 getWordAtLocation 还把"写满整行"
        // 当续行，但恰好写满一行的链接后面若是一次真换行，下一行就会被错拼进来。代价是 tmux 这类
        // 按光标定位重绘的程序（不留软换行标记）里，跨行的长链接拼不回来。
        fun joined(r: Int) = buf.getLineWrap(r)
        val maxRows = (MAX_LINK_CHARS + cols - 1) / cols
        var start = row
        while (start > top && row - start < maxRows && joined(start - 1)) start--
        var end = row
        while (end < bottom && end - row < maxRows && joined(end)) end++

        // 点中的那一格本身必须是非空白字符（getSelectedText 会裁掉行尾空格，不能靠截断后的末字符判断）。
        if (buf.getSelectedText(column, row, column, row, true, false).isBlank()) return null
        // 截到点击列（含）为止：点中的是非空白字符，所以它恰好是最后一个字符，下标即长度 - 1。
        val upToTap = buf.getSelectedText(0, start, column, row, true, false)
        val text = buf.getSelectedText(0, start, cols, end, true, false)
        return urlAt(text, upToTap.length - 1)
    }

    private fun trimTrailing(url: String): String {
        var s = url
        while (s.isNotEmpty()) {
            val c = s.last()
            s = when {
                c in TRAILING -> s.dropLast(1)
                // 右括号只有在链接里没有配对的左括号时才去掉：保留 `…/Foo_(bar)` 这类链接的括号。
                c == ')' && s.count { it == '(' } < s.count { it == ')' } -> s.dropLast(1)
                c == ']' && s.count { it == '[' } < s.count { it == ']' } -> s.dropLast(1)
                else -> return s
            }
        }
        return s
    }
}
