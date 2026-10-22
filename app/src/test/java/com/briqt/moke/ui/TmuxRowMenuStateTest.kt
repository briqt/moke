package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxClientId
import com.briqt.moke.terminal.TmuxScope
import com.briqt.moke.terminal.TmuxSelection
import com.briqt.moke.terminal.TmuxServerId
import com.briqt.moke.terminal.TmuxSessionRef
import org.junit.Assert.*
import org.junit.Test

class TmuxRowMenuStateTest {
    private val server = TmuxServerId(101, 1234, "/socket", "boot", 456, 7, 8)
    private val selection = TmuxSelection(
        TmuxScope("target", "connection", 4), 1,
        TmuxSessionRef(server, "\$0", 100, "session"),
    )
    private val frozen = TmuxRowTarget(selection, clients = 0, targetLabel = "original target")

    @Test fun `an open menu follows clients in both directions without replacing its action selection`() {
        val attached = frozen.copy(selection = selection.copy(generation = 2), clients = 3)
        val enabled = tmuxRowMenuState(frozen, attached, managementEnabled = true, current = false)
        assertTrue(enabled.canManage)
        assertTrue(enabled.canTakeOver)
        assertTrue(enabled.canDetach)
        assertEquals(3, enabled.target?.clients)
        assertSame(selection, enabled.target?.selection)
        assertEquals("original target", enabled.target?.targetLabel)
        assertEquals(0, frozen.clients)

        val detached = tmuxRowMenuState(frozen, attached.copy(clients = 0), managementEnabled = true, current = false)
        assertTrue(detached.canManage) // Rename and End do not require an attached client.
        assertFalse(detached.canTakeOver)
        assertFalse(detached.canDetach)
    }

    @Test fun `a current row with clients keeps take over disabled but allows detach`() {
        val live = frozen.copy(clients = 2)
        val menu = tmuxRowMenuState(frozen, live, managementEnabled = true, current = true)
        assertTrue(menu.canManage)
        assertFalse(menu.canTakeOver)
        assertTrue(menu.canDetach)
    }

    @Test fun `fresh clients never override a legitimate disabled management gate`() {
        val menu = tmuxRowMenuState(frozen, frozen.copy(clients = 2), managementEnabled = false, current = false)
        assertFalse(menu.canManage)
        assertFalse(menu.canTakeOver)
        assertFalse(menu.canDetach)
    }

    @Test fun `missing or changed scope server session name or client never retargets an open menu`() {
        val changed = listOf(
            selection.copy(scope = selection.scope.copy(targetStamp = "other")),
            selection.copy(scope = selection.scope.copy(connectionId = "other")),
            selection.copy(scope = selection.scope.copy(networkEpoch = 5)),
            selection.copy(session = selection.session.copy(server = server.copy(startedTicks = 999))),
            selection.copy(session = selection.session.copy(id = "\$1")),
            selection.copy(session = selection.session.copy(created = 101)),
            selection.copy(session = selection.session.copy(name = "renamed")),
            selection.copy(expectedClient = TmuxClientId(201, 1200, "/dev/pts/4")),
        ).map { frozen.copy(selection = it, clients = 3) } + null
        for (live in changed) {
            val menu = tmuxRowMenuState(frozen, live, managementEnabled = true, current = false)
            assertNull(menu.target)
            assertFalse(menu.canManage)
            assertFalse(menu.canTakeOver)
            assertFalse(menu.canDetach)
        }
        assertEquals("session", frozen.selection.session.name)
    }

    @Test fun `same label on another identity cannot enable an old menu`() {
        val other = frozen.copy(selection = selection.copy(scope = selection.scope.copy(targetStamp = "other")), clients = 2)
        assertEquals(frozen.targetLabel, other.targetLabel)
        assertNull(tmuxRowMenuState(frozen, other, managementEnabled = true, current = false).target)
    }

    @Test fun `changed display label cannot rename the frozen action object`() {
        val refreshed = frozen.copy(clients = 2, targetLabel = "new label")
        val menu = tmuxRowMenuState(frozen, refreshed, managementEnabled = true, current = false)
        assertEquals("original target", menu.target?.targetLabel)
        assertEquals(2, menu.target?.clients)
    }
}
