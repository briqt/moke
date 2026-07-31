package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
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
    fun `starts tmux directly instead of injecting into a shell`() {
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -- tmux attach-session -t '\$7'",
            MoshBootstrap.serverCommand(startupCommand = Tmux.attachCommand("\$7")),
        )
    }
}
