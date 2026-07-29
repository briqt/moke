package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tmux 侧通道输出解析。真机上栽过的坑都在这里钉住：分隔符改 `:`（TAB 会被 tmux 换成 `_`）、
 * 中文名、名字里带分隔符时不许串位。
 */
class TmuxTest {

    @Test
    fun `parses list output`() {
        val out = """
            ${'$'}0:agent:2:1:1785319389
            ${'$'}7:logs:1:0:1785319390
        """.trimIndent()
        val list = Tmux.parse(out)
        assertEquals(2, list.size)
        assertEquals("\$0", list[0].id)
        assertEquals("agent", list[0].name)
        assertEquals(2, list[0].windows)
        assertTrue(list[0].attached)
        assertEquals(1785319389L, list[0].created)
        assertFalse(list[1].attached)
    }

    @Test
    fun `keeps cjk names`() {
        val list = Tmux.parse("\$1:小说写作:1:0:1784450386")
        assertEquals(1, list.size)
        assertEquals("小说写作", list[0].name)
    }

    /** 名字里出现分隔符时，末三个数值字段仍要对位，多出来的部分归还给名字。 */
    @Test
    fun `separator inside name does not shift fields`() {
        val list = Tmux.parse("\$2:a:b:3:0:1784487023")
        assertEquals(1, list.size)
        assertEquals("a:b", list[0].name)
        assertEquals(3, list[0].windows)
        assertEquals(1784487023L, list[0].created)
    }

    @Test
    fun `blank and malformed lines are skipped`() {
        val list = Tmux.parse("\n\$0:only:two\n\n\$1:ok:1:0:5\n")
        assertEquals(1, list.size)
        assertEquals("ok", list[0].name)
    }

    @Test
    fun `probe reads yes only`() {
        assertTrue(Tmux.parseProbe("yes\n"))
        assertFalse(Tmux.parseProbe("no\n"))
        assertFalse(Tmux.parseProbe(null))
        assertFalse(Tmux.parseProbe(""))
    }

    /** 单引号转义：名字含单引号时不能把远端命令行截断。 */
    @Test
    fun `quotes names safely`() {
        assertEquals("tmux new-session -d -s 'it'\\''s'", Tmux.newCmd("it's"))
    }
}
