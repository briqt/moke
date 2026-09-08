package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「顺手记一下」类字段（最近连接时间、tmux 会话名）必须按字段改，不能整条覆盖。
 *
 * 出过的 bug：调用方交出的是会话**打开那一刻**的 Host 快照，`upsert` 整条替换 → 会话开着时
 * 编辑主机，再点复制会话/重新连接，编辑就被静默回滚（tmuxSessionName 同样被吃掉）。
 */
class HostUpdateTest {

    private val list = listOf(
        Host(id = "a", label = "alpha", host = "10.0.0.1"),
        Host(id = "b", label = "beta", host = "10.0.0.2"),
    )

    @Test
    fun `只改目标条目，其它条目原样保留`() {
        val next = updateHostIn(list, "b") { it.copy(lastConnectedAt = 42L) }!!
        assertEquals(listOf("a", "b"), next.map { it.id })
        assertEquals(42L, next[1].lastConnectedAt)
        assertEquals(list[0], next[0])
    }

    @Test
    fun `改的是磁盘上的当前值，而不是调用方手里的旧快照`() {
        // 模拟：会话打开时拿到 snapshot，之后用户把名字改成 edited 并落盘。
        val snapshot = list[0]
        val edited = list.map { if (it.id == "a") it.copy(label = "edited") else it }

        val next = updateHostIn(edited, snapshot.id) { it.copy(lastConnectedAt = 7L) }!!

        assertEquals("edited", next[0].label)
        assertEquals(7L, next[0].lastConnectedAt)
    }

    @Test
    fun `记不到的主机不写`() {
        assertNull(updateHostIn(list, "gone") { it.copy(lastConnectedAt = 1L) })
    }
}
