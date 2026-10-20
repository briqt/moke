package com.briqt.moke.ui

import com.briqt.moke.terminal.*
import org.junit.Assert.*
import org.junit.Test

// type: file-browser terminal directory isolation regression tests
class TerminalPathProbeTest {
    private val source = TmuxScope("target-a", "connection-a", 2)
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val server = TmuxServerId(90, 100, "/tmp/private-tmux/socket", boot, 200, 7, 800)
    private val session = TmuxSession("\$4", "metadata-not-a-target", 1, 1, 1000)
    private val owner = TmuxOwnerProof("a".repeat(32), 70, 1000, 150, "/dev/pts/2")
    private val client = TmuxClientId(80, 900, owner.tty)
    private val binding = TmuxBinding(source, owner, client, session.ref(server))
    private val observation = TmuxObservation(TmuxProbeKind.READY, owner, server, listOf(session),
        listOf(TmuxClient(client, session.id)), peerBootId = boot, defaultSocketPath = server.socketPath)
    private val state = TmuxUiState(phase = TmuxPhase.READY, sessions = listOf(session),
        snapshot = TmuxSnapshot(source, 3, observation, true), presence = TmuxPresence.Attached(binding), lastBinding = binding)

    @Test fun sameTargetCanUseVerifiedExactBinding() {
        assertEquals(binding, TerminalPathProbe.capture(source, source.targetStamp, state))
    }

    @Test fun sameHostIdWithChangedDestinationMustNotQueryOldTerminalCwd() {
        assertNull(TerminalPathProbe.capture(source, "target-b", state))
    }

    @Test fun changedConnectionOrNetworkEpochIsNotTheSourceBinding() {
        for (other in listOf(source.copy(connectionId = "other"), source.copy(networkEpoch = 3), source.copy(targetStamp = "other"))) {
            assertNull(TerminalPathProbe.capture(other, other.targetStamp, state))
        }
    }

    @Test fun unknownOrEndedStateAndUnverifiedCacheCannotSupplyCwd() {
        for (candidate in listOf(state.copy(presence = TmuxPresence.Unknown()),
            state.copy(presence = TmuxPresence.Ended(binding)), state.copy(snapshot = state.snapshot!!.copy(verified = false)),
            state.copy(targetChanged = true))) {
            assertNull(TerminalPathProbe.capture(source, source.targetStamp, candidate))
        }
    }

    @Test fun snapshotMustContainTheSameServerSessionBirthAndCurrentClient() {
        for (o in listOf(observation.copy(server = server.copy(socketInode = 999)),
            observation.copy(sessions = listOf(session.copy(created = session.created + 1))),
            observation.copy(clients = listOf(TmuxClient(client.copy(created = 999), session.id))),
            observation.copy(clients = listOf(TmuxClient(client, "\$9"))))) {
            assertNull(TerminalPathProbe.capture(source, source.targetStamp, state.copy(snapshot = state.snapshot!!.copy(observation = o))))
        }
    }

    @Test fun foreignSnapshotOrChangedOwnerProofCannotSupplyCwd() {
        val base = requireNotNull(state.snapshot)
        for (snapshot in listOf(base.copy(scope = source.copy(networkEpoch = 9)),
            base.copy(observation = observation.copy(owner = owner.copy(startedTicks = 999))),
            base.copy(observation = observation.copy(peerBootId = "87654321-1234-1234-1234-123456789abc")),
            base.copy(observation = observation.copy(clients = listOf(TmuxClient(client, session.id),
                TmuxClient(client.copy(pid = 99), session.id)))))) {
            assertNull(TerminalPathProbe.capture(source, source.targetStamp, state.copy(snapshot = snapshot)))
        }
    }

    @Test fun renameIsMetadataButClientSwitchInvalidatesCapturedCwd() {
        val renamed = session.copy(name = "new-metadata").ref(server)
        assertTrue(TerminalPathProbe.stillCurrent(binding, binding.copy(session = renamed)))
        assertFalse(TerminalPathProbe.stillCurrent(binding, binding.copy(session = renamed.copy(id = "\$8"))))
        assertFalse(TerminalPathProbe.stillCurrent(binding, binding.copy(client = client.copy(created = 999))))
        assertFalse(TerminalPathProbe.stillCurrent(binding, null))
    }
}
