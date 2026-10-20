package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TmuxRememberTargetTest(
    private val caseName: String,
    private val current: Host,
    private val draft: Host,
    private val expectedSessionName: String,
) {
    @Test
    fun `remembered tmux session only survives edits to the same target`() {
        val prepared = (prepareHostEdit(listOf(current), draft, editing = true) as StoreResult.Saved).value

        assertEquals("$caseName keeps the host identity", current.id, prepared.saved.id)
        assertEquals("$caseName remembered session", expectedSessionName, prepared.saved.tmuxSessionName)
        assertEquals("$caseName persisted session", expectedSessionName, prepared.items.single().tmuxSessionName)
        val expectedStamp = if (expectedSessionName.isEmpty()) "" else current.tmuxTargetStamp
        assertEquals("$caseName remembered stamp", expectedStamp, prepared.saved.tmuxTargetStamp)
        assertEquals("$caseName persisted stamp", expectedStamp, prepared.items.single().tmuxTargetStamp)
    }

    @Test
    fun `edits retain the transaction connection time rather than the stale draft`() {
        val prepared = (prepareHostEdit(listOf(current), draft, editing = true) as StoreResult.Saved).value

        assertEquals("$caseName saved connection time", current.lastConnectedAt, prepared.saved.lastConnectedAt)
        assertEquals("$caseName persisted connection time", current.lastConnectedAt, prepared.items.single().lastConnectedAt)
    }

    companion object {
        private const val REMEMBERED_SESSION = "remembered-session"
        private val remembered = Host(
            id = "target-host",
            label = "Target",
            host = "target.example",
            port = 22,
            username = "test-user",
            group = "Test group",
            lastConnectedAt = 500L,
            persistence = SessionPersistence.TMUX,
            tmuxSessionName = REMEMBERED_SESSION,
            tmuxTargetStamp = "current-stamp",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            editCase("target address changes") { it.copy(host = "other-target.example") },
            editCase("target port changes") { it.copy(port = 2222) },
            editCase("target account changes") { it.copy(username = "other-user") },
            editCase("jump host is added") { it.copy(jumpHostId = "jump-a") },
            editCase("jump host is replaced", current = remembered.copy(jumpHostId = "jump-a")) {
                it.copy(jumpHostId = "jump-b")
            },
            editCase("jump host is removed", current = remembered.copy(jumpHostId = "jump-a")) {
                it.copy(jumpHostId = "")
            },
            editCase("network is added") { it.copy(networkId = "network-a") },
            editCase("network is replaced", current = remembered.copy(networkId = "network-a")) {
                it.copy(networkId = "network-b")
            },
            editCase("network is removed", current = remembered.copy(networkId = "network-a")) {
                it.copy(networkId = "")
            },
            editCase("tmux is disabled") { it.copy(persistence = SessionPersistence.NONE) },
            editCase(
                "jump overrides an edited target network",
                current = remembered.copy(jumpHostId = "jump-a", networkId = "network-a"),
                expectedSessionName = REMEMBERED_SESSION,
            ) { it.copy(networkId = "network-b") },
            editCase("target is unchanged", expectedSessionName = REMEMBERED_SESSION) { it },
            editCase("only label changes", expectedSessionName = REMEMBERED_SESSION) {
                it.copy(label = "Renamed target")
            },
            editCase("only group changes", expectedSessionName = REMEMBERED_SESSION) {
                it.copy(group = "Other group")
            },
            editCase("only password changes", expectedSessionName = REMEMBERED_SESSION) {
                it.copy(password = "synthetic-password")
            },
        )

        private fun editCase(
            name: String,
            current: Host = remembered,
            expectedSessionName: String = "",
            edit: (Host) -> Host,
        ): Array<Any> = arrayOf(
            name,
            current,
            // The editor snapshot predates a runtime metadata update in the store.
            edit(current).copy(lastConnectedAt = 10L, tmuxSessionName = "stale-session", tmuxTargetStamp = "stale-stamp"),
            expectedSessionName,
        )
    }
}
