package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 失效引用一律拒连、加载中等待、解不开不当成「已删除」；跳板机在时第一跳用跳板机的网络。 */
class RouteTest {
    private val tail = NetworkEntry(id = "tail", name = "家里", kind = NetworkKind.TAILSCALE)
    private val socks = NetworkEntry(id = "socks", name = "公司", kind = NetworkKind.PROXY)
    private val nets = ListState.Ready(listOf(tail, socks))
    private val keyA = KeyEntry(id = "k-a", name = "Key A", privateKeyPem = "PEM-A", passphrase = "pw-a")
    private val keyJump = KeyEntry(id = "k-j", name = "Key Jump", privateKeyPem = "PEM-J", passphrase = "")
    private val readyKeys = ListState.Ready(listOf(keyA, keyJump))

    private fun route(
        id: String,
        vararg hosts: Host,
        networks: ListState<NetworkEntry> = nets,
        keys: ListState<KeyEntry> = readyKeys,
    ) = resolveRoute(id, ListState.Ready(hosts.toList()), networks, keys)

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

    @Test fun `key references hydrate onto host and jump and refuse when missing or unreadable`() {
        val j = Host(id = "j", authType = AuthType.KEY, keyId = "k-j")
        val h = Host(id = "a", authType = AuthType.KEY, keyId = "k-a", jumpHostId = "j")
        assertEquals(
            Route.Ok(
                host = h.copy(privateKeyPem = "PEM-A", passphrase = "pw-a"),
                jump = j.copy(privateKeyPem = "PEM-J", passphrase = ""),
                network = null,
            ),
            route("a", h, j),
        )

        // Missing or blank keyId refuses without falling back.
        assertEquals(Route.Missing(MissingKind.KEY), route("a", Host(id = "a", authType = AuthType.KEY, keyId = "")))
        assertEquals(Route.Missing(MissingKind.KEY), route("a", Host(id = "a", authType = AuthType.KEY, keyId = "deleted")))
        assertEquals(
            Route.Missing(MissingKind.JUMP_KEY, targetHostId = "j"),
            route("a", h, Host(id = "j", authType = AuthType.KEY, keyId = "deleted")),
        )

        // When keyId is non-blank, KeyStore always wins over any stale inline privateKeyPem,
        // and a KeyEntry with blank privateKeyPem is refused.
        val hostWithStaleInline = Host(id = "s", authType = AuthType.KEY, keyId = "deleted", privateKeyPem = "STALE-INLINE")
        assertEquals(Route.Missing(MissingKind.KEY), route("s", hostWithStaleInline))
        val emptyKeyEntry = KeyEntry(id = "k-empty", name = "Empty", privateKeyPem = "   ")
        assertEquals(
            Route.Missing(MissingKind.KEY),
            route("e", Host(id = "e", authType = AuthType.KEY, keyId = "k-empty"), keys = ListState.Ready(listOf(emptyKeyEntry))),
        )

        // Loading / Unreadable KeyStore only blocks hosts that actually need KeyStore resolution.
        assertEquals(Route.Loading, route("a", h, j, keys = ListState.Loading))
        assertEquals(Route.Unreadable(UnreadableKind.KEYS), route("a", h, j, keys = ListState.Unreadable))
        val pwHost = Host(id = "pw", authType = AuthType.PASSWORD, password = "secret")
        assertEquals(Route.Ok(pwHost, null, null), route("pw", pwHost, keys = ListState.Unreadable))
        val legacyInline = Host(id = "leg", authType = AuthType.KEY, privateKeyPem = "INLINE-PEM")
        assertEquals(Route.Ok(legacyInline, null, null), route("leg", legacyInline, keys = ListState.Loading))
    }

    @Test fun `hostsUsingKey counts direct and jump-host dependencies without double counting`() {
        val jump = Host(id = "j", authType = AuthType.KEY, keyId = "k-j", networkId = "tail")
        val viaJumpPw = Host(id = "h1", authType = AuthType.PASSWORD, jumpHostId = "j")
        val viaJumpSameKey = Host(id = "h2", authType = AuthType.KEY, keyId = "k-j", jumpHostId = "j")
        val directKeyA = Host(id = "h3", authType = AuthType.KEY, keyId = "k-a", networkId = "tail")
        val pwWithSavedKeyId = Host(id = "h4", authType = AuthType.PASSWORD, keyId = "k-a")
        val state = ListState.Ready(listOf(jump, viaJumpPw, viaJumpSameKey, directKeyA, pwWithSavedKeyId))

        assertEquals(3, hostsUsingKey("k-j", state))
        assertEquals(1, hostsUsingKey("k-a", state))
        assertEquals(0, hostsUsingKey("unused", state))
        // Network usage count remains accurate even when keys are not passed or deleted.
        assertEquals(4, hostsUsingNetwork("tail", state, nets))
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
