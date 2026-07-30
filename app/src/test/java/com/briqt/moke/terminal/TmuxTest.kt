package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tmux 侧通道输出解析。真机上栽过的坑都在这里钉住：显式状态头、UTF-8、稳定 ID、
 * 名字解析、真实 client 数和命令错误回传。
 */
class TmuxTest {

    @Test
    fun `parses discovery output`() {
        val out = """
            __MOKE_TMUX__:ready
            ${'$'}0:agent:2:1:1785319389
            ${'$'}7:logs:1:3:1785319390
        """.trimIndent()
        val result = Tmux.parseDiscovery(out) as TmuxDiscovery.Ready
        val list = result.sessions
        assertEquals(2, list.size)
        assertEquals("\$0", list[0].id)
        assertEquals("agent", list[0].name)
        assertEquals(2, list[0].windows)
        assertEquals(1, list[0].clients)
        assertEquals(1785319389L, list[0].created)
        assertEquals(3, list[1].clients)
    }

    @Test
    fun `keeps cjk names`() {
        val result = Tmux.parseDiscovery("__MOKE_TMUX__:ready\n\$1:小说写作:1:0:1784450386")
            as TmuxDiscovery.Ready
        assertEquals("小说写作", result.sessions.single().name)
    }

    /** 名字里出现分隔符时，末三个数值字段仍要对位，多出来的部分归还给名字。 */
    @Test
    fun `separator inside name does not shift fields`() {
        val result = Tmux.parseDiscovery("__MOKE_TMUX__:ready\n\$2:a:b:3:0:1784487023")
            as TmuxDiscovery.Ready
        val session = result.sessions.single()
        assertEquals("a:b", session.name)
        assertEquals(3, session.windows)
        assertEquals(1784487023L, session.created)
    }

    @Test
    fun `malformed response is not disguised as empty list`() {
        assertTrue(
            Tmux.parseDiscovery("__MOKE_TMUX__:ready\n\$0:only:two") is TmuxDiscovery.Malformed
        )
        assertTrue(Tmux.parseDiscovery("") is TmuxDiscovery.Malformed)
    }

    @Test
    fun `distinguishes missing tmux from an empty server`() {
        assertTrue(Tmux.parseDiscovery("__MOKE_TMUX__:missing\n") is TmuxDiscovery.NotInstalled)
        val empty = Tmux.parseDiscovery("__MOKE_TMUX__:ready\n") as TmuxDiscovery.Ready
        assertTrue(empty.sessions.isEmpty())
    }

    /** 单引号转义：名字含单引号时不能把远端命令行截断。 */
    @Test
    fun `quotes names safely`() {
        assertEquals("tmux new-session -d -s 'it'\\''s'", Tmux.newCmd("it's"))
    }

    @Test
    fun `parses action status and error output`() {
        val failed = Tmux.parseAction("__MOKE_TMUX_RC__:1\nduplicate session: work")
        assertEquals(false, failed?.ok)
        assertEquals("duplicate session: work", failed?.output)

        val success = Tmux.parseAction("__MOKE_TMUX_RC__:0\n")
        assertEquals(true, success?.ok)
        assertEquals("", success?.output)
        assertEquals(null, Tmux.parseAction("unexpected"))
    }

    @Test
    fun `attaches by stable id`() {
        assertEquals("tmux attach-session -t '\$7'", Tmux.attachCommand("\$7"))
        assertEquals("tmux detach-client -s '\$7'", Tmux.detachCmd("\$7"))
    }

    @Test
    fun `discovery forces tmux utf8 output`() {
        assertTrue(Tmux.DISCOVER_CMD.contains("tmux -u list-sessions"))
    }
}
