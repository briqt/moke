package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreEditTest {
    private val proxy = NetworkEntry(id = "proxy", name = "Proxy", revision = 4, proxyHost = "proxy.example")

    @Test fun `proxy revision comes from the transaction not the editor snapshot`() {
        val stale = proxy.copy(revision = 1, password = "new")
        val saved = (prepareNetworkEdit(listOf(proxy), stale, editing = true) as StoreResult.Saved).value.saved
        assertEquals(5, saved.revision)
        val second = (prepareNetworkEdit(listOf(saved), stale.copy(password = "newer"), true) as StoreResult.Saved).value.saved
        assertEquals(6, second.revision)
    }

    @Test fun `renaming does not replace the running proxy instance`() {
        val saved = (prepareNetworkEdit(listOf(proxy), proxy.copy(name = "Renamed"), true) as StoreResult.Saved).value.saved
        assertEquals(4, saved.revision)
    }

    @Test fun `an edited deleted entry is not silently recreated`() {
        assertEquals(StoreResult.Failed(StoreFailure.MISSING), prepareNetworkEdit(emptyList(), proxy, true))
        assertEquals(StoreResult.Failed(StoreFailure.MISSING), prepareHostEdit(emptyList(), Host(id = "gone"), true))
    }

    @Test fun `creation cannot replace an existing entry`() {
        assertEquals(StoreResult.Failed(StoreFailure.CONFLICT), prepareNetworkEdit(listOf(proxy), proxy, false))
    }

    @Test fun `names are unique within a kind and all creation paths obey the tailnet limit`() {
        assertEquals(StoreResult.Failed(StoreFailure.DUPLICATE_NAME), prepareNetworkEdit(listOf(proxy), proxy.copy(id = "new", name = " proxy "), false))
        val tailnets = (1..MAX_TAILNET_ENTRIES).map { NetworkEntry(id = "t$it", name = "Network $it", kind = NetworkKind.TAILSCALE) }
        assertEquals(StoreResult.Failed(StoreFailure.TAILNET_LIMIT), prepareNetworkEdit(tailnets, NetworkEntry(name = "More", kind = NetworkKind.TAILSCALE), false))
        assertTrue(prepareNetworkEdit(tailnets, tailnets.first().copy(name = "Renamed"), true) is StoreResult.Saved)
        assertTrue(prepareNetworkEdit(listOf(proxy), NetworkEntry(name = "Proxy", kind = NetworkKind.TAILSCALE), false) is StoreResult.Saved)
    }

    @Test fun `host edits retain concurrent runtime metadata but disabling tmux clears its remembered name`() {
        val old = Host(id = "h", label = "old", lastConnectedAt = 50, persistence = SessionPersistence.TMUX, tmuxSessionName = "new-session")
        val draft = old.copy(label = "edited", lastConnectedAt = 1, tmuxSessionName = "stale-session")
        val saved = (prepareHostEdit(listOf(old), draft, true) as StoreResult.Saved).value.saved
        assertEquals("edited", saved.label)
        assertEquals(50L, saved.lastConnectedAt)
        assertEquals("new-session", saved.tmuxSessionName)
        val disabled = (prepareHostEdit(listOf(old), draft.copy(persistence = SessionPersistence.NONE), true) as StoreResult.Saved).value.saved
        assertEquals("", disabled.tmuxSessionName)
    }

    @Test fun `malformed decrypted or legacy JSON is protected rather than treated as empty`() {
        assertTrue(decodeHosts("cipher", { "[broken" }).unreadable)
        assertTrue(decodeHosts("[broken", { error("must not decrypt legacy data") }).unreadable)
        assertTrue(decodeHosts("cipher", { "[1]" }).unreadable)
        assertFalse(decodeHosts("cipher", { "[]" }).unreadable)
    }
}
