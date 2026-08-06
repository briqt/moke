package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * attach 载体与 TERM 协商。这些断言钉住 rc.3「附加全挂」的根因修复：
 * 远端缺 `xterm-256color` 的 terminfo 条目时 tmux 会拒绝启动，必须在命令里就地协商 TERM，
 * 且失败要回落登录壳而不是让通道直接 EOF。
 */
class TmuxAttachTest {

    @Test
    fun `attach negotiates TERM inside the command`() {
        val cmd = Tmux.attachOrCreateCommand("work")
        // 连接即附加发生在任何侧通道探测之前，所以协商必须内联。
        assertTrue(cmd.contains("for t in xterm-256color screen-256color xterm vt100"))
        assertTrue(cmd.contains("tput clear"))
        assertTrue(cmd.contains("export TERM"))
    }

    @Test
    fun `already negotiated term is tried first without duplicates`() {
        val cmd = Tmux.attachOrCreateCommand("work", term = "screen-256color")
        assertTrue(cmd.contains("for t in screen-256color xterm-256color xterm vt100"))
        assertEquals(1, Regex("screen-256color").findAll(cmd).count())
    }

    @Test
    fun `bogus term from the remote is never interpolated`() {
        val cmd = Tmux.attachOrCreateCommand("work", term = "x; rm -rf /")
        assertTrue(!cmd.contains("rm -rf"))
        assertTrue(cmd.contains("for t in xterm-256color"))
    }

    @Test
    fun `attach keeps a login shell on failure instead of ending the channel`() {
        val cmd = Tmux.attachOrCreateCommand("work")
        // 不能 exec tmux：那样 tmux 失败即通道 EOF，UI 只剩「会话已结束」、错误无处可看。
        assertTrue(!cmd.contains("exec tmux"))
        assertTrue(cmd.contains("tmux -u new-session -A -s \"\$1\""))
        assertTrue(cmd.contains("moke: tmux not found on this host"))
        assertTrue(cmd.contains("moke: tmux exited"))
        assertEquals(2, Regex("""exec \$\{SHELL:-sh} -l""").findAll(cmd).count())
    }

    @Test
    fun `clean detach exits zero so the tab ends normally`() {
        assertTrue(Tmux.attachOrCreateCommand("work").contains("[ \$ec -eq 0 ] && exit 0"))
    }

    @Test
    fun `take over passes tmux detach-others flag`() {
        assertTrue(Tmux.attachOrCreateCommand("work", detachOthers = true).contains("new-session -A -D -s"))
    }

    @Test
    fun `session name goes through argv and is quote-safe`() {
        val cmd = Tmux.attachOrCreateCommand("it's work")
        assertTrue(cmd.endsWith("sh 'it'\\''s work'"))
        // 名字只经 argv 传入，命令体里始终是 "$1"。
        assertTrue(cmd.contains("-s \"\$1\""))
    }

    @Test
    fun `discovery reports the negotiated term alongside sessions`() {
        val out = """
            __MOKE_TMUX__:ready
            __MOKE_TERM__:screen-256color
            ${'$'}0:小说写作:2:1:1754400000
        """.trimIndent()
        val d = Tmux.parseDiscovery(out) as TmuxDiscovery.Ready
        assertEquals("screen-256color", d.term)
        assertEquals(1, d.sessions.size)
        assertEquals("小说写作", d.sessions[0].name)
    }

    @Test
    fun `discovery without a term line stays valid`() {
        val out = "__MOKE_TMUX__:ready\n\$0:work:1:0:1754400000"
        val d = Tmux.parseDiscovery(out) as TmuxDiscovery.Ready
        assertNull(d.term)
        assertEquals(1, d.sessions.size)
    }

    @Test
    fun `client count distinguishes attached from fell-back-to-shell`() {
        assertEquals(1, Tmux.parseClientCount("1\n"))
        assertEquals(0, Tmux.parseClientCount("0"))
        // 数不出来 ≠ 未附加：无法确认时不该清掉关联。
        assertNull(Tmux.parseClientCount(null))
        assertNull(Tmux.parseClientCount("no server running"))
    }

    @Test
    fun `default session name is tmux-safe`() {
        // tmux 的会话名不能含 ':' 与 '.'。
        assertEquals("tokyo-ssh", Tmux.defaultSessionName("tokyo ssh"))
        assertEquals("web-1", Tmux.defaultSessionName("web:1"))
        assertEquals("root-10-0-0-1", Tmux.defaultSessionName("root@10.0.0.1"))
        assertEquals("小说写作", Tmux.defaultSessionName("小说写作"))
        assertEquals("moke", Tmux.defaultSessionName("   "))
        assertEquals("moke", Tmux.defaultSessionName("..."))
    }
}
