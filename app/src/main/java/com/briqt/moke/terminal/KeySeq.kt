package com.briqt.moke.terminal

/**
 * 附加键的按键标识：UI 只描述"按了哪个键"，字节序列一律由 [KeySeq.encode] 统一生成。
 *
 * 这样修饰键（Ctrl/Alt/Shift）才能与特殊键组合——早期实现把字节写死在按键上直接 write，
 * 绕开了修饰状态，Ctrl+← / Shift+Tab 这类组合发出去的是裸序列。
 */
sealed interface KeyId {
    data object Esc : KeyId
    data object Tab : KeyId
    data object Enter : KeyId
    data object Backspace : KeyId
    data object Delete : KeyId
    data object Insert : KeyId
    data object Home : KeyId
    data object End : KeyId
    data object PageUp : KeyId
    data object PageDown : KeyId
    data object Up : KeyId
    data object Down : KeyId
    data object Left : KeyId
    data object Right : KeyId

    /** 功能键 F1–F12。 */
    data class Fn(val n: Int) : KeyId

    /** 字面文本（符号键等）。单字符时可与 Ctrl/Alt 组合，多字符按原样发送。 */
    data class Chars(val text: String) : KeyId

    /** 宏：已经是完整字节序列（如 Ctrl+C），不再叠加当前修饰。 */
    data class Macro(val bytes: String) : KeyId
}

/**
 * 按 xterm 惯例把 [KeyId] + 修饰键编码为终端字节序列。纯逻辑、无 Android 依赖，便于单测。
 *
 * 修饰位（xterm）：`mod = 1 + shift·1 + alt·2 + ctrl·4`，无修饰时不写参数（发传统短序列，
 * 兼容性最好）。TERM 由连接时协商，恒为 xterm-256color / screen-256color 之一，支持这套编码。
 *
 * 源码里一律用 `\u001b` 等转义写控制字符，不写裸字节——裸字节会让文件被当成二进制，grep/diff 都不好用。
 */
object KeySeq {

    const val ESC = "\u001b"
    private const val DEL = "\u007f"
    private const val BS = "\u0008"

    fun encode(key: KeyId, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        // 宏自带完整序列（Ctrl+C 等），叠加修饰只会把它拆坏。
        if (key is KeyId.Macro) return key.bytes

        val mod = modifier(ctrl, alt, shift)
        return when (key) {
            // Esc 没有带修饰的通行编码，忽略修饰直接发。
            KeyId.Esc -> ESC
            KeyId.Tab -> when {
                shift -> "$ESC[Z"          // backtab：Shift+Tab 的标准序列
                alt -> "$ESC\t"
                else -> "\t"
            }
            // Shift+Enter 无通行编码（各终端自定义）；Alt+Enter 发 ESC+CR，
            // 是 claude code / codex 这类行编辑 TUI 里"换行但不提交"的常见约定。
            KeyId.Enter -> if (alt) "$ESC\r" else "\r"
            // 退格默认发 DEL(0x7f)（与 stty erase 的常规配置一致）；Ctrl 发 BS(0x08)。
            KeyId.Backspace -> when {
                ctrl -> BS
                alt -> "$ESC$DEL"
                else -> DEL
            }
            KeyId.Up -> cursor('A', mod)
            KeyId.Down -> cursor('B', mod)
            KeyId.Right -> cursor('C', mod)
            KeyId.Left -> cursor('D', mod)
            KeyId.Home -> cursor('H', mod)
            KeyId.End -> cursor('F', mod)
            KeyId.Insert -> tilde(2, mod)
            KeyId.Delete -> tilde(3, mod)
            KeyId.PageUp -> tilde(5, mod)
            KeyId.PageDown -> tilde(6, mod)
            is KeyId.Fn -> function(key.n, mod)
            is KeyId.Chars -> chars(key.text, ctrl, alt)
            is KeyId.Macro -> key.bytes   // 已在上面返回，此分支仅为穷尽
        }
    }

    /** xterm 修饰参数；1 表示"无修饰"。 */
    fun modifier(ctrl: Boolean, alt: Boolean, shift: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /** 光标类：无修饰发 `ESC[A`，带修饰发 `ESC[1;mA`。 */
    private fun cursor(final: Char, mod: Int): String =
        if (mod == 1) "$ESC[$final" else "$ESC[1;$mod$final"

    /** `~` 类（Ins/Del/PgUp/PgDn/F5+）：`ESC[n~` / `ESC[n;m~`。 */
    private fun tilde(n: Int, mod: Int): String =
        if (mod == 1) "$ESC[$n~" else "$ESC[$n;$mod~"

    /** F1–F4 无修饰走 SS3（`ESCOP`），带修饰改用 CSI 形式；F5–F12 走 `~` 类；越界不发字节。 */
    private fun function(n: Int, mod: Int): String {
        if (n in 1..4) {
            val final = "PQRS"[n - 1]
            return if (mod == 1) "${ESC}O$final" else "$ESC[1;$mod$final"
        }
        val param = FN_TILDE[n] ?: return ""
        return tilde(param, mod)
    }

    /** F5–F12 的 `~` 参数（16 与 22 空缺是 xterm 的历史遗留）。 */
    private val FN_TILDE = mapOf(
        5 to 15, 6 to 17, 7 to 18, 8 to 19, 9 to 20, 10 to 21, 11 to 23, 12 to 24,
    )

    /** 字面文本：单字符可叠加 Ctrl（控制码）与 Alt（ESC 前缀）；多字符按原样发。 */
    private fun chars(text: String, ctrl: Boolean, alt: Boolean): String {
        if (text.length != 1) return text
        val base = if (ctrl) (ctrlChar(text[0])?.toString() ?: text) else text
        return if (alt) "$ESC$base" else base
    }

    /** Ctrl+字符 → 控制码；无对应控制码时返回 null（按原字符发）。 */
    fun ctrlChar(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c.code - 96).toChar()
        in 'A'..'Z' -> (c.code - 64).toChar()
        ' ', '@' -> '\u0000'
        '[' -> '\u001b'
        '\\' -> '\u001c'
        ']' -> '\u001d'
        '^' -> '\u001e'
        '_' -> '\u001f'
        '?' -> '\u007f'
        else -> null
    }
}
