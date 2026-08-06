package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 协议级启动命令的生效口径（与 tmux 会话持久化互斥）。 */
class HostStartupCommandTest {

    @Test
    fun `空启动命令表示默认登录 shell`() {
        assertEquals("", Host().effectiveStartupCommand)
    }

    @Test
    fun `普通主机用配置的启动命令并去掉首尾空白`() {
        val h = Host(startupCommand = "  powershell.exe  ")
        assertEquals("powershell.exe", h.effectiveStartupCommand)
    }

    @Test
    fun `会话持久化为 tmux 时启动命令让位`() {
        val h = Host(startupCommand = "powershell.exe", persistence = SessionPersistence.TMUX)
        assertEquals("", h.effectiveStartupCommand)
    }

    @Test
    fun `只有空白的启动命令等同于未设置`() {
        assertEquals("", Host(startupCommand = "   ").effectiveStartupCommand)
    }
}
