package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 附加键字节编码（xterm 惯例）。E = ESC，写成常量避免源码里出现裸控制字符。 */
class KeySeqTest {

    private val e = "\u001b"

    @Test
    fun `无修饰发传统短序列`() {
        assertEquals("$e[A", KeySeq.encode(KeyId.Up))
        assertEquals("$e[D", KeySeq.encode(KeyId.Left))
        assertEquals("$e[H", KeySeq.encode(KeyId.Home))
        assertEquals("$e[F", KeySeq.encode(KeyId.End))
        assertEquals("$e[5~", KeySeq.encode(KeyId.PageUp))
        assertEquals("$e[3~", KeySeq.encode(KeyId.Delete))
        assertEquals("\t", KeySeq.encode(KeyId.Tab))
        assertEquals("\r", KeySeq.encode(KeyId.Enter))
        assertEquals(e, KeySeq.encode(KeyId.Esc))
        assertEquals("\u007f", KeySeq.encode(KeyId.Backspace))
    }

    @Test
    fun `修饰位按 1 + shift + alt·2 + ctrl·4 计算`() {
        assertEquals(1, KeySeq.modifier(ctrl = false, alt = false, shift = false))
        assertEquals(2, KeySeq.modifier(ctrl = false, alt = false, shift = true))
        assertEquals(3, KeySeq.modifier(ctrl = false, alt = true, shift = false))
        assertEquals(5, KeySeq.modifier(ctrl = true, alt = false, shift = false))
        assertEquals(8, KeySeq.modifier(ctrl = true, alt = true, shift = true))
    }

    /** 这条是本次改动的核心：以前 Ctrl+← 发的是裸 ESC[D，Ctrl 被丢掉。 */
    @Test
    fun `方向键带修饰走 CSI 参数形式`() {
        assertEquals("$e[1;5D", KeySeq.encode(KeyId.Left, ctrl = true))
        assertEquals("$e[1;3C", KeySeq.encode(KeyId.Right, alt = true))
        assertEquals("$e[1;2A", KeySeq.encode(KeyId.Up, shift = true))
        assertEquals("$e[1;5H", KeySeq.encode(KeyId.Home, ctrl = true))
    }

    @Test
    fun `波浪类带修饰在参数后加分号`() {
        assertEquals("$e[5;5~", KeySeq.encode(KeyId.PageUp, ctrl = true))
        assertEquals("$e[3;3~", KeySeq.encode(KeyId.Delete, alt = true))
        assertEquals("$e[2;2~", KeySeq.encode(KeyId.Insert, shift = true))
    }

    @Test
    fun `Shift+Tab 发 backtab`() {
        assertEquals("$e[Z", KeySeq.encode(KeyId.Tab, shift = true))
        assertEquals("$e\t", KeySeq.encode(KeyId.Tab, alt = true))
    }

    /** 行编辑型 TUI（claude code / codex）里"换行不提交"的常见约定。 */
    @Test
    fun `Alt+Enter 发 ESC 加回车`() {
        assertEquals("$e\r", KeySeq.encode(KeyId.Enter, alt = true))
        // Shift+Enter 无通行编码，保持普通回车而不是编出一个远端不认识的序列。
        assertEquals("\r", KeySeq.encode(KeyId.Enter, shift = true))
    }

    @Test
    fun `功能键 F1到F4 无修饰走 SS3 带修饰走 CSI`() {
        assertEquals("${e}OP", KeySeq.encode(KeyId.Fn(1)))
        assertEquals("${e}OS", KeySeq.encode(KeyId.Fn(4)))
        assertEquals("$e[1;5P", KeySeq.encode(KeyId.Fn(1), ctrl = true))
    }

    @Test
    fun `功能键 F5到F12 走波浪类且跳过 16 与 22`() {
        assertEquals("$e[15~", KeySeq.encode(KeyId.Fn(5)))
        assertEquals("$e[17~", KeySeq.encode(KeyId.Fn(6)))
        assertEquals("$e[21~", KeySeq.encode(KeyId.Fn(10)))
        assertEquals("$e[23~", KeySeq.encode(KeyId.Fn(11)))
        assertEquals("$e[24;2~", KeySeq.encode(KeyId.Fn(12), shift = true))
    }

    @Test
    fun `越界功能键不发任何字节`() {
        assertEquals("", KeySeq.encode(KeyId.Fn(13)))
        assertEquals("", KeySeq.encode(KeyId.Fn(0)))
    }

    @Test
    fun `单字符可叠加 Ctrl 与 Alt`() {
        assertEquals("|", KeySeq.encode(KeyId.Chars("|")))
        assertEquals("\u0003", KeySeq.encode(KeyId.Chars("c"), ctrl = true))
        assertEquals("${e}b", KeySeq.encode(KeyId.Chars("b"), alt = true))
        assertEquals("$e\u0003", KeySeq.encode(KeyId.Chars("c"), ctrl = true, alt = true))
        // 无对应控制码的字符按原样发，不吞键。
        assertEquals("1", KeySeq.encode(KeyId.Chars("1"), ctrl = true))
    }

    @Test
    fun `多字符文本按原样发送不叠加修饰`() {
        assertEquals("ls -la", KeySeq.encode(KeyId.Chars("ls -la"), ctrl = true, alt = true))
    }

    @Test
    fun `宏自带完整序列不再叠加修饰`() {
        val ctrlC = KeyId.Macro("\u0003")
        assertEquals("\u0003", KeySeq.encode(ctrlC))
        assertEquals("\u0003", KeySeq.encode(ctrlC, ctrl = true, alt = true, shift = true))
    }

    @Test
    fun `Ctrl 字符映射覆盖字母与符号`() {
        assertEquals('\u0001', KeySeq.ctrlChar('a'))
        assertEquals('\u0001', KeySeq.ctrlChar('A'))
        assertEquals('\u001a', KeySeq.ctrlChar('z'))
        assertEquals('\u0000', KeySeq.ctrlChar(' '))
        assertEquals('\u001c', KeySeq.ctrlChar('\\'))
        assertEquals('\u007f', KeySeq.ctrlChar('?'))
        assertNull(KeySeq.ctrlChar('1'))
    }
}
