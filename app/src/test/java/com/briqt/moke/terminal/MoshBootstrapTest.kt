package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshBootstrapTest {
    private val prefix = "MOSH_SERVER_NETWORK_TMOUT=86400 mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

    @Test
    fun `starts normal mosh shell when no command is supplied`() {
        assertEquals(prefix, MoshBootstrap.serverCommand())
    }

    @Test
    fun `bounds the lifetime of a server whose client never comes back`() {
        // 失联的 mosh-server 会一直占着进程和 UDP 端口（漫游语义）。正常关会话由 MoshTransport
        // 的退出序列收掉；这条前缀是应用被强停时的兜底，缺了它服务器上就会无限累积。
        assertTrue(MoshBootstrap.serverCommand().startsWith("MOSH_SERVER_NETWORK_TMOUT="))
        assertEquals(86_400, MoshBootstrap.SERVER_NETWORK_TMOUT_SECONDS)
    }

    @Test
    fun `hands a host startup command to mosh-server verbatim`() {
        // 主机自配的启动命令不加任何包装：mosh-server 按 argv 直接 execvp。
        assertEquals("$prefix -- zsh -l", MoshBootstrap.serverCommand(startupCommand = "zsh -l"))
    }

    @Test
    fun `hands the tmux wrapper to mosh-server as its child command`() {
        val cmd = MoshBootstrap.serverCommand(startupCommand = Tmux.attachOrCreateCommand("work"))
        assertTrue(cmd.startsWith("$prefix -- "))
        // mosh-server 直接 execvp 这段 argv（不经 shell），所以必须自带 `sh -c`。
        assertTrue(cmd.contains("-- sh -c '"))
        assertTrue(cmd.endsWith("sh 'work'"))
    }
}
