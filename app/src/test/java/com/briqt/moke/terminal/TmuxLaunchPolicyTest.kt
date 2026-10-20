package com.briqt.moke.terminal

import com.briqt.moke.data.Host
import com.briqt.moke.data.MissingKind
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.Route
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.TmuxTargetStamp
import com.briqt.moke.data.UnreadableKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// type: pure tmux launch route and memory regression tests
class TmuxLaunchPolicyTest {
    private val network = NetworkEntry(id = "network", name = "Network", kind = NetworkKind.TAILSCALE, revision = 7,
        controlUrl = "https://control.example")
    private val host = Host(id = "host", host = "target.example", username = "test-user", networkId = network.id,
        persistence = SessionPersistence.TMUX, tmuxSessionName = "chosen session")
    private val route = Route.Ok(host, null, network)
    private val stamp = TmuxTargetStamp.of(host, null, network)
    private val scope = TmuxScope(stamp, "connection", 3)
    private val remembered = route.copy(host = host.copy(tmuxTargetStamp = stamp))

    @Test fun `matching route accepts the current network epoch`() {
        assertTrue(TmuxLaunchPolicy.matches(scope, route, 3))
    }

    @Test fun `different local connection ids do not change the same route`() {
        assertTrue(TmuxLaunchPolicy.matches(scope.copy(connectionId = "other-connection"), route, 3))
    }

    @Test fun `old network epoch is not a matching scope`() {
        assertFalse(TmuxLaunchPolicy.matches(scope, route, 4))
    }

    @Test fun `different target stamp is not a matching scope`() {
        assertFalse(TmuxLaunchPolicy.matches(scope.copy(targetStamp = "other-stamp"), route, 3))
    }

    @Test fun `a changed target address is not a matching route`() {
        assertFalse(TmuxLaunchPolicy.matches(scope, route.copy(host = host.copy(host = "other-target.example")), 3))
    }

    @Test fun `a new tailnet identity is not a matching route`() {
        assertFalse(TmuxLaunchPolicy.matches(scope, route.copy(network = network.copy(revision = 8)), 3))
    }

    @Test fun `jump first hop overrides inactive target network metadata`() {
        val jump = Host(id = "jump", host = "jump.example", username = "jump-user", networkId = network.id)
        val jumped = route.copy(host = host.copy(jumpHostId = jump.id), jump = jump)
        val jumpedScope = scope.copy(targetStamp = TmuxTargetStamp.of(jumped.host, jump, network))
        assertTrue(TmuxLaunchPolicy.matches(jumpedScope, jumped.copy(host = jumped.host.copy(networkId = "hidden-network")), 3))
        assertFalse(TmuxLaunchPolicy.matches(jumpedScope, jumped.copy(jump = jump.copy(host = "other-jump.example")), 3))
    }

    @Test fun `a signed remembered name restores without trimming the actual name`() {
        assertEquals(TmuxIntent.Restore("chosen session"), TmuxLaunchPolicy.remembered(remembered))
    }

    @Test fun `legacy name without a stamp is never automatically endorsed`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(route))
    }

    @Test fun `a mismatched memory stamp never restores`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(remembered.copy(host = remembered.host.copy(tmuxTargetStamp = "other-stamp"))))
    }

    @Test fun `blank remembered name does not request a restore`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(remembered.copy(host = remembered.host.copy(tmuxSessionName = "   "))))
    }

    @Test fun `plain SSH persistence never restores even with an old signed pair`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(remembered.copy(host = remembered.host.copy(persistence = SessionPersistence.NONE))))
    }

    @Test fun `a changed target account invalidates remembered preference`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(remembered.copy(host = remembered.host.copy(username = "other-user"))))
    }

    @Test fun `a new tailnet identity invalidates remembered preference at the same control URL`() {
        assertEquals(TmuxIntent.None, TmuxLaunchPolicy.remembered(remembered.copy(network = network.copy(revision = 8))))
    }

    @Test fun `cosmetic and host credential edits keep a matching remembered preference`() {
        val edited = remembered.copy(host = remembered.host.copy(label = "Renamed", group = "Other", password = "synthetic-password", lastConnectedAt = 900L))
        assertEquals(TmuxIntent.Restore("chosen session"), TmuxLaunchPolicy.remembered(edited))
    }

    @Test fun `unresolved unreadable and loading routes cannot match or restore`() {
        val routes: List<Route> = MissingKind.entries.map { Route.Missing(it) } +
            UnreadableKind.entries.map { Route.Unreadable(it) } + Route.Loading
        routes.forEach {
            assertFalse(it.toString(), TmuxLaunchPolicy.matches(scope, it, 3))
            assertEquals(it.toString(), TmuxIntent.None, TmuxLaunchPolicy.remembered(it))
        }
    }
}

// type: independent parameterized tmux reuse identity regression tests
@RunWith(Parameterized::class)
class TmuxLaunchPolicyReuseTest(
    private val caseName: String,
    private val hostId: String,
    private val targetStamp: String,
    private val target: TmuxSessionRef,
    private val candidateHostId: String,
    private val alive: Boolean,
    private val state: TmuxUiState,
    private val expected: Boolean,
) {
    @Test fun `reuse requires one live coherent witnessed attachment for the requested host`() {
        assertEquals(caseName, expected, TmuxLaunchPolicy.canReuse(hostId, targetStamp, target, candidateHostId, alive, state))
    }

    companion object {
        private const val BOOT = "12345678-1234-1234-1234-123456789abc"
        private const val OTHER_BOOT = "87654321-4321-4321-4321-cba987654321"
        private val scope = TmuxScope("target-stamp", "connection", 4)
        private val server = TmuxServerId(90, 100, "/tmp/tmux-test/default", BOOT, 200, 7, 800)
        private val row = TmuxSession("\$0", "chosen", 1, 1, 1000)
        private val ref = row.ref(server)
        private val owner = TmuxOwnerProof("a".repeat(32), 70, 1000, 150, "/dev/pts/2")
        private val client = TmuxClient(TmuxClientId(80, 900, owner.tty), row.id)
        private val binding = TmuxBinding(scope, owner, client.identity, ref)
        private val observation = TmuxObservation(TmuxProbeKind.READY, owner, server, listOf(row), listOf(client),
            peerBootId = BOOT, defaultSocketPath = server.socketPath)
        private val snapshot = TmuxSnapshot(scope, 5, observation, verified = true)
        private val attached = TmuxUiState(phase = TmuxPhase.READY, sessions = listOf(row), snapshot = snapshot,
            presence = TmuxPresence.Attached(binding), lastBinding = binding)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            case("live witnessed attachment", expected = true),
            case("target display name can change without changing its incarnation", target = ref.copy(name = "renamed"), expected = true),
            case("busy is not a new identity policy", state = attached.copy(busy = true), expected = true),
            case("cross host id with identical stamp and exact ref", candidateHostId = "other-host"),
            case("dead transport", alive = false),
            case("invalidated target", state = attached.copy(targetChanged = true)),
            case("missing snapshot", state = attached.copy(snapshot = null)),
            case("unverified snapshot", state = attached.copy(snapshot = snapshot.copy(verified = false))),
            case("unissued snapshot generation", state = attached.copy(snapshot = snapshot.copy(generation = 0))),
            case("ended is not current even with a retained exact last binding", state = attached.copy(presence = TmuxPresence.Ended(binding))),
            case("unknown is not current even with a retained exact last binding", state = attached.copy(presence = TmuxPresence.Unknown())),
            case("binding and frame use different local connections", state = attached.copy(snapshot = snapshot.copy(scope = scope.copy(connectionId = "other-connection")))),
            case("binding and frame use different network epochs", state = attached.copy(snapshot = snapshot.copy(scope = scope.copy(networkEpoch = 5)))),
            case("binding and frame use different target stamps", state = attached.copy(snapshot = snapshot.copy(scope = scope.copy(targetStamp = "other-stamp")))),
            case("requested stamp differs", targetStamp = "other-stamp"),
            case("requested session id differs", target = ref.copy(id = "\$1")),
            case("requested session incarnation differs", target = ref.copy(created = row.created + 1)),
            case("requested peer boot differs", target = ref.copy(server = server.copy(bootId = OTHER_BOOT))),
            case("requested server process incarnation differs", target = ref.copy(server = server.copy(startedTicks = 201))),
            case("requested socket inode differs", target = ref.copy(server = server.copy(socketInode = 801))),
            case("requested server is unwitnessed", target = ref.copy(server = server.copy(bootId = ""))),
            observedCase("verified flag cannot hide a changed peer", observation.copy(peerBootId = OTHER_BOOT)),
            observedCase("verified flag cannot hide a different server", observation.copy(server = server.copy(bootId = OTHER_BOOT), peerBootId = OTHER_BOOT)),
            observedCase("verified flag cannot hide an unwitnessed server", observation.copy(server = server.copy(socketInode = 0))),
            observedCase("verified flag cannot hide an absent owner", observation.copy(owner = null)),
            observedCase("binding owner nonce is stale", observation.copy(owner = owner.copy(nonce = "b".repeat(32)))),
            observedCase("binding owner process incarnation is stale", observation.copy(owner = owner.copy(startedTicks = owner.startedTicks + 1))),
            observedCase("current client is missing", observation.copy(clients = emptyList())),
            observedCase("client pid and tty are reused with a new creation time", observation.copy(clients = listOf(client.copy(identity = client.identity.copy(created = 901))))),
            observedCase("client tty no longer matches the owner", observation.copy(clients = listOf(client.copy(identity = client.identity.copy(tty = "/dev/pts/3"))))),
            observedCase("duplicate client tty is ambiguous", observation.copy(clients = listOf(client, client.copy(identity = client.identity.copy(pid = 81))))),
            observedCase("snapshot session id has been reincarnated", observation.copy(sessions = listOf(row.copy(created = row.created + 1)))),
            observedCase("binding name and snapshot row disagree", observation.copy(sessions = listOf(row.copy(name = "renamed")))),
            observedCase("client moved to another session", observation.copy(sessions = listOf(row, row.copy(id = "\$1", name = "other", created = 1001)), clients = listOf(client.copy(sessionId = "\$1")))),
            observedCase("snapshot does not observe an installed server", observation.copy(kind = TmuxProbeKind.UNKNOWN)),
        )

        private fun observedCase(name: String, observation: TmuxObservation): Array<Any> =
            case(name, state = attached.copy(snapshot = snapshot.copy(observation = observation)))

        private fun case(
            name: String,
            hostId: String = "host",
            targetStamp: String = scope.targetStamp,
            target: TmuxSessionRef = ref,
            candidateHostId: String = "host",
            alive: Boolean = true,
            state: TmuxUiState = attached,
            expected: Boolean = false,
        ): Array<Any> = arrayOf(name, hostId, targetStamp, target, candidateHostId, alive, state, expected)
    }
}
