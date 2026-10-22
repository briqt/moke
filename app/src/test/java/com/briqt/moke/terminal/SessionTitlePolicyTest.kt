package com.briqt.moke.terminal

import org.junit.Assert.*
import org.junit.Test

// type: pure independent title override tests
class SessionTitlePolicyTest {
    private val policy = SessionTitlePolicy

    @Test fun `prefix and title overrides are independent while automatic values keep updating`() {
        assertEquals("user prefix · pane one", policy.display("user prefix", null, "tmux one", "pane one"))
        assertEquals("user prefix · pane two", policy.display("user prefix", null, "tmux two", "pane two"))
        assertEquals("tmux one · user title", policy.display(null, "user title", "tmux one", "pane one"))
        assertEquals("tmux two · user title", policy.display(null, "user title", "tmux two", "pane two"))
        assertEquals("user prefix · user title", policy.display("user prefix", "user title", "tmux two", "pane two"))
    }

    @Test fun `restoring either field never freezes the other and no prefix leaves no separator`() {
        assertEquals("tmux · user title", policy.display("  ", "user title", "tmux", "pane"))
        assertEquals("user prefix · pane", policy.display("user prefix", " ", "tmux", "pane"))
        assertEquals("tmux · pane", policy.display(null, null, "tmux", "pane"))
        assertEquals("pane", policy.display(null, null, "", "pane"))
        assertEquals("user title", policy.display(null, " user title ", "", "pane"))
        assertNull(policy.userValue(" \n\t"))
    }

    @Test fun `automatic prefix uses witnessed current or last ended identity but never an unrelated last binding`() {
        val scope = TmuxScope("target", "connection", 2)
        val server = TmuxServerId(20, 10, "/socket", "12345678-1234-1234-1234-123456789abc", 15, 4, 7)
        val owner = TmuxOwnerProof("a".repeat(32), 30, 1000, 16, "/dev/pts/4")
        val client = TmuxClientId(40, 17, owner.tty)
        val session = TmuxSession("\$0", "confirmed name", 1, 1, 18)
        val binding = TmuxBinding(scope, owner, client, session.ref(server))
        val observation = TmuxObservation(TmuxProbeKind.READY, owner, server, listOf(session), listOf(TmuxClient(client, session.id)),
            peerBootId = server.bootId, defaultSocketPath = server.socketPath)
        val snapshot = TmuxSnapshot(scope, 1, observation, true)
        val state = TmuxUiState(snapshot = snapshot, presence = TmuxPresence.Attached(binding), lastBinding = binding)
        assertEquals("confirmed name", policy.automaticPrefix(scope, state, true))
        for (invalid in listOf(
            state.copy(snapshot = snapshot.copy(verified = false)), state.copy(snapshot = null),
            state.copy(snapshot = snapshot.copy(scope = scope.copy(networkEpoch = 3))),
            state.copy(snapshot = snapshot.copy(observation = observation.copy(owner = owner.copy(startedTicks = 999)))),
            state.copy(targetChanged = true),
            state.copy(snapshot = snapshot.copy(observation = observation.copy(peerBootId = "other-peer"))),
            state.copy(presence = TmuxPresence.Shell(owner)), state.copy(presence = TmuxPresence.Pending),
        )) assertEquals("", policy.automaticPrefix(scope, invalid, true))
        assertEquals("", policy.automaticPrefix(scope, state, false))
        assertEquals("", policy.automaticPrefix(scope.copy(connectionId = "other"), state, true))
        val ended = state.copy(presence = TmuxPresence.Ended(binding))
        assertEquals("confirmed name", policy.automaticPrefix(scope, ended, false))
        assertEquals("", policy.automaticPrefix(scope, ended.copy(targetChanged = true), false))
        assertEquals("", policy.automaticPrefix(scope.copy(connectionId = "other"), ended, false))
        assertNull(ended.currentPane)
    }

    @Test fun `current pane including default hostname wins over OSC without provenance guessing`() {
        assertEquals("server-hostname", policy.automaticTitle(false, "server-hostname", "OSC task", "Work"))
        assertEquals("[mosh] literal pane title", policy.automaticTitle(true, "[mosh] literal pane title", "OSC", "Work"))
        assertEquals("Work", policy.automaticTitle(false, "", "old pane OSC", "Work"))
        assertEquals("OSC task", policy.automaticTitle(true, null, "[mosh] OSC task", "Work"))
        assertEquals("Work", policy.automaticTitle(true, null, "[mosh] ", "Work"))
        assertEquals("Work", policy.automaticTitle(false, null, "bad\u001btitle", "Work"))
    }

    @Test fun `fallback uses only host when unavailable and corresponding reported target for a foreign path`() {
        assertEquals("Work", policy.fallback("Work", null))
        assertEquals("Work /full/project/path", policy.fallback("Work", SessionDirectory("/full/project/path", "key", "Work")))
        assertEquals("nested.example /nested", policy.fallback("Work", SessionDirectory("/nested", "reported", "nested.example")))
    }

    @Test fun `copy inherits each user field separately but never manufactures automatic state`() {
        val sourceScope = TmuxScope("same-account-route", "source", 4)
        val newScope = sourceScope.copy(connectionId = "copy")
        assertTrue(policy.sameTarget(sourceScope, newScope))
        for (source in listOf(SessionTitleOverrides(null, null), SessionTitleOverrides("prefix", null),
            SessionTitleOverrides(null, "title"), SessionTitleOverrides("prefix", "title"))) {
            val copied = policy.inherit(source, policy.sameTarget(sourceScope, newScope))
            assertEquals(source, copied)
            assertEquals(policy.display(source.prefix, source.title, "", "new host"),
                policy.display(copied.prefix, copied.title, "", policy.automaticTitle(false, null, "", "new host")))
        }
        assertEquals(SessionTitleOverrides("prefix", null), policy.inherit(SessionTitleOverrides(" prefix ", "  "), true))
    }

    @Test fun `target account or network incarnation change drops both inherited user fields`() {
        val sourceScope = TmuxScope("same-account-route", "source", 4)
        for (target in listOf(sourceScope.copy(targetStamp = "other-account-route"), sourceScope.copy(networkEpoch = 5))) {
            assertFalse(policy.sameTarget(sourceScope, target))
            assertEquals(SessionTitleOverrides(null, null), policy.inherit(SessionTitleOverrides("prefix", "title"),
                policy.sameTarget(sourceScope, target)))
        }
        assertFalse(policy.sameTarget(null, sourceScope))
        assertFalse(policy.sameTarget(sourceScope, sourceScope.copy(connectionId = "copy"), sourceTargetChanged = true))
        assertEquals(SessionTitleOverrides(null, null), policy.inherit(null, false))
    }

    @Test fun `copy marker decorates only colliding automatic main title never either user field`() {
        assertEquals("tmux · work (2)", policy.display(null, null, "tmux", "work", "(2)", true))
        assertEquals("user prefix · work (2)", policy.display("user prefix", null, "tmux", "work", "(2)", true))
        assertEquals("tmux · work", policy.display(null, null, "tmux", "work", "(2)", false))
        assertEquals("tmux · user title", policy.display(null, "user title", "tmux", "work", "(2)", true))
        assertEquals("user prefix · user title", policy.display("user prefix", "user title", "tmux", "work", "(2)", true))
    }
}
