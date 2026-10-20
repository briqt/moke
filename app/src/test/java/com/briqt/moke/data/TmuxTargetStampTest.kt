package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TmuxTargetStampTest(
    private val caseName: String,
    private val before: Route.Ok,
    private val after: Route.Ok,
    private val expectedSame: Boolean,
) {
    @Test
    fun `stamp follows the effective route rather than cosmetic or credential fields`() {
        val first = TmuxTargetStamp.of(before.host, before.jump, before.network)
        val second = TmuxTargetStamp.of(after.host, after.jump, after.network)

        assertTrue(first.matches(Regex("tmux-target-v1:[0-9a-f]{64}")))
        assertEquals(caseName, expectedSame, first == second)
    }

    companion object {
        private val target = Host(id = "target", host = "target.example", username = "test-user", networkId = "proxy-a")
        private val proxy = NetworkEntry(id = "proxy-a", name = "Proxy", revision = 4, proxyHost = "proxy.example")
        private val tailnet = NetworkEntry(
            id = "tailnet-a", name = "Network", kind = NetworkKind.TAILSCALE, revision = 7,
            controlUrl = "https://control.example", deviceName = "test-node",
        )
        private val jump = Host(id = "jump-a", host = "jump.example", username = "jump-user", networkId = tailnet.id)
        private val direct = Route.Ok(target, null, proxy)
        private val plain = Route.Ok(target.copy(networkId = ""), null, null)
        private val jumped = Route.Ok(target.copy(jumpHostId = jump.id), jump, tailnet)
        private val tailnetRoute = Route.Ok(target.copy(networkId = tailnet.id), null, tailnet)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            case("target address", direct, direct.copy(host = target.copy(host = "other.example")), false),
            case("target port", direct, direct.copy(host = target.copy(port = 2222)), false),
            case("target account is case sensitive", direct, direct.copy(host = target.copy(username = "Test-User")), false),
            case("target DNS normalization", direct, direct.copy(host = target.copy(host = " TARGET.EXAMPLE ")), true),
            case("target cosmetics and auth", direct, direct.copy(host = target.copy(
                id = "copy", label = "Renamed", group = "Other", password = "new-password", authType = AuthType.KEY,
                privateKeyPem = "synthetic-key", passphrase = "synthetic-passphrase", lastConnectedAt = 900L,
                useMosh = true, startupCommand = "test-shell", loginCommand = "test-command", forwardPorts = "8080",
                persistence = SessionPersistence.TMUX, tmuxSessionName = "session", tmuxTargetStamp = "old-stamp",
            )), true),
            case("proxy revision", direct, direct.copy(network = proxy.copy(revision = 5)), false),
            case("proxy address", direct, direct.copy(network = proxy.copy(proxyHost = "other-proxy.example")), false),
            case("proxy port", direct, direct.copy(network = proxy.copy(proxyPort = 1081)), false),
            case("proxy type", direct, direct.copy(network = proxy.copy(proxyType = ProxyType.HTTPS)), false),
            case("proxy DNS normalization", direct, direct.copy(network = proxy.copy(proxyHost = " PROXY.EXAMPLE ")), true),
            case("network id", direct, direct.copy(host = target.copy(networkId = "proxy-b"), network = proxy.copy(id = "proxy-b")), false),
            case("network kind", direct, direct.copy(network = proxy.copy(kind = NetworkKind.TAILSCALE)), false),
            case("proxy cosmetics auth and unused tailnet settings", direct, direct.copy(network = proxy.copy(
                name = "Renamed", username = "proxy-user", password = "synthetic-secret", extraCaPem = "synthetic-ca",
                deviceName = "other-node", controlUrl = "https://unused.example",
            )), true),
            case("tailnet control URL", tailnetRoute, tailnetRoute.copy(network = tailnet.copy(controlUrl = "https://other-control.example")), false),
            case("same control URL new identity revision", tailnetRoute, tailnetRoute.copy(network = tailnet.copy(revision = 8)), false),
            case("tailnet control URL normalization", tailnetRoute, tailnetRoute.copy(network = tailnet.copy(controlUrl = "HTTPS://CONTROL.EXAMPLE:443/")), true),
            case("tailnet path is case sensitive", tailnetRoute.copy(network = tailnet.copy(controlUrl = "https://control.example/one")),
                tailnetRoute.copy(network = tailnet.copy(controlUrl = "https://control.example/One")), false),
            case("tailnet cosmetics and unused proxy settings", tailnetRoute, tailnetRoute.copy(network = tailnet.copy(
                name = "Renamed", deviceName = "other-node", proxyHost = "unused.example", proxyPort = 8888,
                proxyType = ProxyType.HTTP, username = "unused-user", password = "synthetic-secret", extraCaPem = "synthetic-ca",
            )), true),
            case("jump address", jumped, jumped.copy(jump = jump.copy(host = "other-jump.example")), false),
            case("jump port", jumped, jumped.copy(jump = jump.copy(port = 2222)), false),
            case("jump account", jumped, jumped.copy(jump = jump.copy(username = "other-jump-user")), false),
            case("jump cosmetics and auth", jumped, jumped.copy(jump = jump.copy(
                label = "Renamed", group = "Other", password = "new-password", authType = AuthType.KEY,
                privateKeyPem = "synthetic-key", passphrase = "synthetic-passphrase", lastConnectedAt = 900L,
            )), true),
            case("jump overrides the target network", jumped, jumped.copy(host = jumped.host.copy(networkId = "hidden-network")), true),
            case("jump first hop network changes", jumped, jumped.copy(jump = jump.copy(networkId = proxy.id), network = proxy), false),
            case("jump first hop identity advances", jumped, jumped.copy(network = tailnet.copy(revision = 8)), false),
            case("removing jump changes the route", jumped, tailnetRoute, false),
            case("direct has no network", plain, direct, false),
            case("missing jump cannot masquerade as direct", plain, plain.copy(host = plain.host.copy(jumpHostId = "gone")), false),
            case("missing network cannot masquerade as direct", plain, plain.copy(host = plain.host.copy(networkId = "gone")), false),
            case("IPv6 interface scopes remain case sensitive",
                plain.copy(host = plain.host.copy(host = "fe80::1%scopeA")),
                plain.copy(host = plain.host.copy(host = "fe80::1%scopea")), false),
            case("canonical fields cannot collide through delimiters",
                plain.copy(host = plain.host.copy(host = "x", username = "y:22:z")),
                plain.copy(host = plain.host.copy(host = "x:22:y", username = "z")), false),
        )

        private fun case(name: String, before: Route.Ok, after: Route.Ok, same: Boolean): Array<Any> =
            arrayOf(name, before, after, same)
    }
}
