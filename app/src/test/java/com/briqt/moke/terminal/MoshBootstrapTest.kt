package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshBootstrapTest {
    @Test
    fun `starts normal mosh shell when no command is supplied`() {
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8",
            MoshBootstrap.serverCommand(),
        )
    }

    @Test
    fun `hands a host startup command to mosh-server verbatim`() {
        // 主机自配的启动命令不加任何包装：mosh-server 按 argv 直接 execvp。
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -- zsh -l",
            MoshBootstrap.serverCommand(startupCommand = "zsh -l"),
        )
    }

    @Test
    fun `hands the tmux wrapper to mosh-server as its child command`() {
        val cmd = MoshBootstrap.serverCommand(startupCommand = Tmux.attachOrCreateCommand("work"))
        assertTrue(cmd.startsWith("mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -- "))
        // mosh-server 直接 execvp 这段 argv（不经 shell），所以必须自带 `sh -c`。
        assertTrue(cmd.contains("-- sh -c '"))
        assertTrue(cmd.endsWith("sh 'work'"))
    }
}
