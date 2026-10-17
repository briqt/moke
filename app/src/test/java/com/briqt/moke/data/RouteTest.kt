package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 失效引用一律拒连、加载中等待、解不开不当成「已删除」；跳板机在时第一跳用跳板机的网络。 */
class RouteTest {
    private val tail = NetworkEntry(id = "tail", name = "家里", kind = NetworkKind.TAILSCALE)
    private val socks = NetworkEntry(id = "socks", name = "公司", kind = NetworkKind.PROXY)
    private val nets = ListState.Ready(listOf(tail, socks))

    private fun route(id: String, vararg hosts: Host, networks: ListState<NetworkEntry> = nets) =
        resolveRoute(id, ListState.Ready(hosts.toList()), networks)

    @Test fun `direct host has no jump and no network`() {
        val h = Host(id = "a")
        assertEquals(Route.Ok(h, null, null), route("a", h))
    }

    @Test fun `host network is the first hop`() {
        val h = Host(id = "a", networkId = "tail")
        assertEquals(Route.Ok(h, null, tail), route("a", h))
    }

    @Test fun `with a jump host its own network is used and the target's is ignored`() {
        val j = Host(id = "j", networkId = "socks")
        val h = Host(id = "a", jumpHostId = "j", networkId = "tail")
        assertEquals(Route.Ok(h, j, socks), route("a", h, j))
        val plainJump = Host(id = "j")
        assertEquals(Route.Ok(h, plainJump, null), route("a", h, plainJump))
    }

    @Test fun `missing references refuse instead of connecting directly`() {
        assertEquals(Route.Missing(MissingKind.HOST), route("gone", Host(id = "a")))
        assertEquals(Route.Missing(MissingKind.JUMP_HOST), route("a", Host(id = "a", jumpHostId = "deleted")))
        assertEquals(Route.Missing(MissingKind.JUMP_HOST), route("a", Host(id = "a", jumpHostId = "a")))
        assertEquals(Route.Missing(MissingKind.NETWORK), route("a", Host(id = "a", networkId = "deleted")))
        val j = Host(id = "j", networkId = "deleted")
        assertEquals(Route.Missing(MissingKind.NETWORK), route("a", Host(id = "a", jumpHostId = "j"), j))
    }

    @Test fun `a jump host with its own jump host is refused`() {
        val j2 = Host(id = "j2")
        val j = Host(id = "j", jumpHostId = "j2")
        assertEquals(Route.Missing(MissingKind.JUMP_CHAIN), route("a", Host(id = "a", jumpHostId = "j"), j, j2))
    }

    @Test fun `loading waits and unreadable is not reported as deleted`() {
        val h = Host(id = "a", networkId = "tail")
        assertEquals(Route.Loading, resolveRoute("a", ListState.Loading, nets))
        assertEquals(Route.Unreadable(UnreadableKind.HOSTS), resolveRoute("a", ListState.Unreadable, nets))
        assertEquals(Route.Loading, route("a", h, networks = ListState.Loading))
        assertEquals(Route.Unreadable(UnreadableKind.NETWORKS), route("a", h, networks = ListState.Unreadable))
        // 不用网络的主机不必等网络列表。
        val direct = Host(id = "b")
        assertEquals(Route.Ok(direct, null, null), route("b", direct, networks = ListState.Loading))
    }

    @Test fun `dangling jumps are cleared once and valid ones kept`() {
        val j = Host(id = "j")
        val keep = Host(id = "a", jumpHostId = "j")
        val dangling = Host(id = "b", jumpHostId = "deleted")
        val self = Host(id = "c", jumpHostId = "c")
        val out = clearDanglingJumps(listOf(j, keep, dangling, self))!!
        assertEquals(listOf("", "j", "", ""), out.map { it.jumpHostId })
        assertNull(clearDanglingJumps(out))
    }
}
