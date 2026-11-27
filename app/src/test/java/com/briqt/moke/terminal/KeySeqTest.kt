package com.briqt.moke.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 附加键字节编码（xterm 惯例）。E = ESC，写成常量避免源码里出现裸控制字符。 */
class KeySeqTest {

    private val e = "\u001b"
    private val cursorKeys: List<Triple<KeyId, Char, Int>> = listOf(
        Triple(KeyId.Up, 'A', KeyEvent.KEYCODE_DPAD_UP),
        Triple(KeyId.Down, 'B', KeyEvent.KEYCODE_DPAD_DOWN),
        Triple(KeyId.Right, 'C', KeyEvent.KEYCODE_DPAD_RIGHT),
        Triple(KeyId.Left, 'D', KeyEvent.KEYCODE_DPAD_LEFT),
        Triple(KeyId.Home, 'H', KeyEvent.KEYCODE_MOVE_HOME),
        Triple(KeyId.End, 'F', KeyEvent.KEYCODE_MOVE_END),
    )

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
        assertEquals("\u0008", KeySeq.encode(KeyId.Backspace, ctrl = true))
        assertEquals("$e\u007f", KeySeq.encode(KeyId.Backspace, alt = true))
        assertEquals("$e\u0008", KeySeq.encode(KeyId.Backspace, ctrl = true, alt = true))
        assertEquals(
            KeyHandler.getCode(KeyEvent.KEYCODE_DEL, KeyHandler.KEYMOD_CTRL or KeyHandler.KEYMOD_ALT, false, false),
            KeySeq.encode(KeyId.Backspace, ctrl = true, alt = true),
        )
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
    fun `四方向与HomeEnd无修饰时跟随光标应用模式`() {
        for ((key, final, _) in cursorKeys) {
            assertEquals("$e[$final", KeySeq.encode(key, cursorKeysApplication = false))
            assertEquals("${e}O$final", KeySeq.encode(key, cursorKeysApplication = true))
        }
    }

    @Test
    fun `四方向与HomeEnd带任何修饰时两种模式均走CSI`() {
        for ((key, final, _) in cursorKeys) {
            for (bits in 1..7) {
                for (application in listOf(false, true)) {
                    assertEquals(
                        "$key modifiers=$bits application=$application",
                        "$e[1;${bits + 1}$final",
                        KeySeq.encode(
                            key,
                            ctrl = bits and 4 != 0,
                            alt = bits and 2 != 0,
                            shift = bits and 1 != 0,
                            cursorKeysApplication = application,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `光标编码在全部模式和修饰组合下与内核一致`() {
        for ((key, _, keyCode) in cursorKeys) {
            for (bits in 0..7) {
                val ctrl = bits and 4 != 0
                val alt = bits and 2 != 0
                val shift = bits and 1 != 0
                val keyMod = (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or
                    (if (alt) KeyHandler.KEYMOD_ALT else 0) or
                    (if (shift) KeyHandler.KEYMOD_SHIFT else 0)
                for (application in listOf(false, true)) {
                    assertEquals(
                        "$key modifiers=$bits application=$application",
                        KeyHandler.getCode(keyCode, keyMod, application, false),
                        KeySeq.encode(key, ctrl, alt, shift, cursorKeysApplication = application),
                    )
                }
            }
        }
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

    /** 多行输入场景中"换行不直接提交"的常见约定。 */
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
    fun `光标应用模式不影响字面文本的修饰语义`() {
        assertEquals(
            "$e\u0003",
            KeySeq.encode(KeyId.Chars("c"), ctrl = true, alt = true, shift = true, cursorKeysApplication = true),
        )
        assertEquals(
            "ls -la",
            KeySeq.encode(KeyId.Chars("ls -la"), ctrl = true, alt = true, shift = true, cursorKeysApplication = true),
        )
        assertEquals("c", KeySeq.encode(KeyId.Chars("c"), shift = true, cursorKeysApplication = true))
    }

    @Test
    fun `宏自带完整序列不再叠加修饰或转换光标模式`() {
        for (bytes in listOf("\u0003", "$e[A", "${e}OA")) {
            val macro = KeyId.Macro(bytes)
            for (application in listOf(false, true)) {
                assertEquals(bytes, KeySeq.encode(macro, cursorKeysApplication = application))
                assertEquals(
                    bytes,
                    KeySeq.encode(macro, ctrl = true, alt = true, shift = true, cursorKeysApplication = application),
                )
            }
        }
    }

    @Test
    fun `Ctrl 字符映射覆盖字母 符号与标准控制数字`() {
        assertEquals('\u0001', KeySeq.ctrlChar('a'))
        assertEquals('\u0001', KeySeq.ctrlChar('A'))
        assertEquals('\u001a', KeySeq.ctrlChar('z'))
        assertEquals('\u0000', KeySeq.ctrlChar(' '))
        assertEquals('\u0000', KeySeq.ctrlChar('@'))
        assertEquals('\u0000', KeySeq.ctrlChar('2'))
        assertEquals('\u001b', KeySeq.ctrlChar('['))
        assertEquals('\u001b', KeySeq.ctrlChar('3'))
        assertEquals('\u001c', KeySeq.ctrlChar('\\'))
        assertEquals('\u001c', KeySeq.ctrlChar('4'))
        assertEquals('\u001d', KeySeq.ctrlChar(']'))
        assertEquals('\u001d', KeySeq.ctrlChar('5'))
        assertEquals('\u001e', KeySeq.ctrlChar('^'))
        assertEquals('\u001e', KeySeq.ctrlChar('~'))
        assertEquals('\u001e', KeySeq.ctrlChar('6'))
        assertEquals('\u001f', KeySeq.ctrlChar('_'))
        assertEquals('\u001f', KeySeq.ctrlChar('/'))
        assertEquals('\u001f', KeySeq.ctrlChar('-'))
        assertEquals('\u001f', KeySeq.ctrlChar('7'))
        assertEquals('\u007f', KeySeq.ctrlChar('?'))
        assertEquals('\u007f', KeySeq.ctrlChar('8'))
        assertNull(KeySeq.ctrlChar('1'))
        assertNull(KeySeq.ctrlChar('9'))
        assertNull(KeySeq.ctrlChar('0'))
    }

    @Test
    fun `无修饰单步组合键自然继承外部粘滞修饰 带修饰单步与双步前缀保持独立`() {
        val bareSlash = KeyId.Combo(listOf(KeyStep(KeyId.Chars("/"))))
        assertEquals("/", KeySeq.encode(bareSlash))
        assertEquals("\u001f", KeySeq.encode(bareSlash, ctrl = true))
        assertEquals("$e/", KeySeq.encode(bareSlash, alt = true))

        val bareLetter = KeyId.Combo(listOf(KeyStep(KeyId.Chars("a"))))
        assertEquals("a", KeySeq.encode(bareLetter))
        assertEquals("A", KeySeq.encode(bareLetter, shift = true))
        assertEquals("\u0001", KeySeq.encode(bareLetter, ctrl = true, shift = true))
        assertEquals("${e}A", KeySeq.encode(bareLetter, alt = true, shift = true))

        val bareUp = KeyId.Combo(listOf(KeyStep(KeyId.Up)))
        assertEquals("$e[1;5A", KeySeq.encode(bareUp, ctrl = true))

        val tmuxDetach = KeyId.Combo(
            listOf(
                KeyStep(KeyId.Chars("b"), ctrl = true),
                KeyStep(KeyId.Chars("d")),
            ),
        )
        assertEquals("\u0002d", KeySeq.encode(tmuxDetach))
        assertEquals("\u0002d", KeySeq.encode(tmuxDetach, ctrl = true, alt = true, shift = true))

        val tmuxChooseDetach = KeyId.Combo(
            listOf(
                KeyStep(KeyId.Chars("b"), ctrl = true),
                KeyStep(KeyId.Chars("d"), shift = true),
            ),
        )
        assertEquals("\u0002D", KeySeq.encode(tmuxChooseDetach))

        val emacsSave = KeyId.Combo(
            listOf(
                KeyStep(KeyId.Chars("x"), ctrl = true),
                KeyStep(KeyId.Chars("s"), ctrl = true),
            ),
        )
        assertEquals("\u0018\u0013", KeySeq.encode(emacsSave))

        val altBackWord = KeyId.Combo(listOf(KeyStep(KeyId.Chars("b"), alt = true)))
        val altShiftBackWord = KeyId.Combo(listOf(KeyStep(KeyId.Chars("b"), alt = true, shift = true)))
        assertEquals("${e}b", KeySeq.encode(altBackWord))
        assertEquals("${e}b", KeySeq.encode(altBackWord, ctrl = true, shift = true))
        assertEquals("${e}B", KeySeq.encode(altShiftBackWord))

        val tmuxPaneUp = KeyId.Combo(
            listOf(
                KeyStep(KeyId.Chars("b"), ctrl = true),
                KeyStep(KeyId.Up),
            ),
        )
        assertEquals("\u0002$e[A", KeySeq.encode(tmuxPaneUp, cursorKeysApplication = false))
        assertEquals("\u0002${e}OA", KeySeq.encode(tmuxPaneUp, cursorKeysApplication = true))
    }

    @Test
    fun `连发判定覆盖导航删除及单步组合但不对双步前缀连发`() {
        listOf(
            KeyId.Up, KeyId.Down, KeyId.Left, KeyId.Right,
            KeyId.Backspace, KeyId.Delete, KeyId.PageUp, KeyId.PageDown,
            KeyId.Combo(listOf(KeyStep(KeyId.Left, ctrl = true))),
            KeyId.Combo(listOf(KeyStep(KeyId.Backspace, alt = true))),
        ).forEach { repeatable ->
            org.junit.Assert.assertTrue("$repeatable should repeat", KeySeq.isRepeatable(repeatable))
        }

        listOf(
            KeyId.Esc, KeyId.Tab, KeyId.Enter, KeyId.Home, KeyId.End, KeyId.Insert,
            KeyId.Fn(1), KeyId.Chars("a"), KeyId.Macro("\u0003"),
            KeyId.Combo(listOf(KeyStep(KeyId.Chars("b"), ctrl = true), KeyStep(KeyId.Up))),
            KeyId.Combo(listOf(KeyStep(KeyId.Chars("d"), alt = true))),
        ).forEach { nonRepeatable ->
            org.junit.Assert.assertFalse("$nonRepeatable should not repeat", KeySeq.isRepeatable(nonRepeatable))
        }
    }

    @Test
    fun `序列可读回显覆盖控制码 CSI序列与末尾裸ESC加左括号`() {
        assertEquals("ESC[1;5D", KeySeq.describeBytes("$e[1;5D"))
        assertEquals("ESCOA", KeySeq.describeBytes("${e}OA"))
        assertEquals("ESC [", KeySeq.describeBytes("$e["))
        assertEquals("ESC O", KeySeq.describeBytes("${e}O"))
        assertEquals("^B ESC [", KeySeq.describeBytes("\u0002$e["))
        assertEquals("ESC [ ^\\", KeySeq.describeBytes("$e[\u001c"))
        assertEquals("^B d", KeySeq.describeBytes("\u0002d"))
        assertEquals("^X ^S", KeySeq.describeBytes("\u0018\u0013"))
        assertEquals("ESC b", KeySeq.describeBytes("${e}b"))
        assertEquals("^@ TAB CR DEL \u2423", KeySeq.describeBytes("\u0000\t\r\u007f "))
    }
}
