package com.briqt.moke.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real file-backed Preferences DataStores; only the Android Keystore boundary is substituted. */
abstract class PreferencesStoreFixture {
    @get:Rule val temporary = TemporaryFolder()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var nextFile = 0

    protected suspend fun preferences(key: String, raw: String): DataStore<Preferences> {
        val file = File(temporary.root, "store-${nextFile++}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store.edit { it[stringPreferencesKey(key)] = raw }
        return store
    }

    protected suspend fun raw(store: DataStore<Preferences>, key: String): String =
        store.data.first()[stringPreferencesKey(key)]!!

    protected val encrypt: (String) -> String = { "sealed:$it" }
    protected val decrypt: (String) -> String = { require(it.startsWith("sealed:")); it.removePrefix("sealed:") }

    @After fun closeStores() = runBlocking { job.cancelAndJoin() }
}

class TmuxMemoryStoreTest : PreferencesStoreFixture() {
    private val target = Host(
        id = "target", label = "Target", host = "target.example", username = "test-user",
        persistence = SessionPersistence.TMUX, tmuxSessionName = "old-session", tmuxTargetStamp = "old-stamp",
        lastConnectedAt = 50L,
    )
    private val neighbor = target.copy(id = "neighbor", host = "neighbor.example")

    private suspend fun preferences(vararg hosts: Host): DataStore<Preferences> =
        preferences("hosts_json", JSONArray().apply { hosts.forEach { put(it.toJson()) } }.toString())

    private fun store(preferences: DataStore<Preferences>) = HostStore(preferences, encrypt, decrypt)

    private suspend fun hosts(preferences: DataStore<Preferences>): List<Host> {
        val json = JSONArray(raw(preferences, "hosts_json").removePrefix("sealed:"))
        return (0 until json.length()).map { Host.fromJson(json.getJSONObject(it)) }
    }

    @Test fun `host JSON round trip keeps the remembered pair`() {
        assertEquals(target, Host.fromJson(JSONObject(target.toJson().toString())))
    }

    @Test fun `legacy JSON and same target editing never manufacture a trusted stamp`() {
        val json = target.toJson().apply { remove("tmuxTargetStamp") }
        val legacy = Host.fromJson(json)
        assertEquals("old-session", legacy.tmuxSessionName)
        assertEquals("", legacy.tmuxTargetStamp)
        val edited = (prepareHostEdit(listOf(legacy), legacy.copy(label = "Renamed", tmuxTargetStamp = "draft-stamp"), true) as StoreResult.Saved).value.saved
        assertEquals("old-session", edited.tmuxSessionName)
        assertEquals("", edited.tmuxTargetStamp)
        assertNotEquals(TmuxTargetStamp.of(edited, null, null), edited.tmuxTargetStamp)
    }

    @Test fun `remember writes a pair without rolling back concurrent metadata or cosmetic edits`() = runBlocking {
        val preferences = preferences(target, neighbor)
        val store = store(preferences)
        assertTrue(store.commit(target.copy(label = "Renamed", password = "synthetic-password"), true) is StoreResult.Saved)
        assertTrue(store.update(target.id) { it.copy(lastConnectedAt = 900L) })
        val current = hosts(preferences).first()
        assertEquals(StoreResult.Saved(Unit), store.rememberTmux(target, "verified-session", "verified-stamp"))
        assertEquals(listOf(current.copy(tmuxSessionName = "verified-session", tmuxTargetStamp = "verified-stamp"), neighbor), hosts(preferences))
        assertEquals(StoreResult.Saved(Unit), store.rememberTmux(target, "second-session", "second-stamp"))
        assertEquals(current.copy(tmuxSessionName = "second-session", tmuxTargetStamp = "second-stamp"), hosts(preferences).first())
    }

    private suspend fun rejectsChangedTarget(edit: (Host) -> Host) {
        val preferences = preferences(target, neighbor)
        val store = store(preferences)
        assertTrue(store.commit(edit(target), true) is StoreResult.Saved)
        val before = raw(preferences, "hosts_json")
        assertEquals(StoreResult.Failed(StoreFailure.CONFLICT), store.rememberTmux(target, "late-session", "late-stamp"))
        assertEquals(before, raw(preferences, "hosts_json"))
        val saved = hosts(preferences).first()
        assertEquals("", saved.tmuxSessionName)
        assertEquals("", saved.tmuxTargetStamp)
    }

    @Test fun `late remember cannot follow an edited address`() = runBlocking { rejectsChangedTarget { it.copy(host = "other.example") } }
    @Test fun `late remember cannot follow an edited port`() = runBlocking { rejectsChangedTarget { it.copy(port = 2222) } }
    @Test fun `late remember cannot follow an edited account`() = runBlocking { rejectsChangedTarget { it.copy(username = "other-user") } }
    @Test fun `late remember cannot follow an edited jump`() = runBlocking { rejectsChangedTarget { it.copy(jumpHostId = "jump-a") } }
    @Test fun `late remember cannot follow an edited direct network`() = runBlocking { rejectsChangedTarget { it.copy(networkId = "network-a") } }
    @Test fun `late remember cannot restore disabled tmux`() = runBlocking { rejectsChangedTarget { it.copy(persistence = SessionPersistence.NONE) } }

    @Test fun `a memory write queued behind an edit checks the committed target inside its own transaction`() = runBlocking {
        withTimeout(10_000) {
            val preferences = preferences(target)
            val editEntered = CompletableDeferred<Unit>()
            val rememberQueued = CompletableDeferred<Unit>()
            val releaseEdit = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            // Only gate transaction scheduling; all reads, edits and persistence still use the real DataStore.
            val gated = object : DataStore<Preferences> {
                override val data = preferences.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val call = calls.incrementAndGet()
                    if (call == 2) rememberQueued.complete(Unit)
                    return preferences.updateData { current ->
                        if (call == 1) {
                            editEntered.complete(Unit)
                            releaseEdit.await()
                        }
                        transform(current)
                    }
                }
            }
            val store = store(gated)
            val edit = async { store.commit(target.copy(host = "other.example"), true) }
            editEntered.await()
            val remember = async { store.rememberTmux(target, "late-session", "late-stamp") }
            rememberQueued.await()
            releaseEdit.complete(Unit)
            assertTrue(edit.await() is StoreResult.Saved)
            assertEquals(StoreResult.Failed(StoreFailure.CONFLICT), remember.await())
            assertEquals(listOf(target.copy(host = "other.example", tmuxSessionName = "", tmuxTargetStamp = "")), hosts(preferences))
        }
    }

    @Test fun `an overridden target network change preserves the pair and permits captured target CAS`() = runBlocking {
        val jumped = target.copy(jumpHostId = "jump-a", networkId = "hidden-a")
        val jump = neighbor.copy(id = "jump-a", networkId = "first-hop")
        val preferences = preferences(jumped, jump)
        val store = store(preferences)
        val edited = jumped.copy(networkId = "hidden-b", tmuxSessionName = "stale-name", tmuxTargetStamp = "stale-stamp")
        assertTrue(store.commit(edited, true) is StoreResult.Saved)
        assertEquals(jumped.copy(networkId = "hidden-b"), hosts(preferences).first())
        assertEquals(StoreResult.Saved(Unit), store.rememberTmux(jumped, "verified-session", "verified-stamp"))
        assertEquals(jumped.copy(networkId = "hidden-b", tmuxSessionName = "verified-session", tmuxTargetStamp = "verified-stamp"), hosts(preferences).first())
    }

    @Test fun `deleted records are not recreated by late memory writes`() = runBlocking {
        val preferences = preferences(target, neighbor)
        val store = store(preferences)
        assertEquals(StoreResult.Saved(Unit), store.remove(target.id))
        assertEquals(StoreResult.Failed(StoreFailure.MISSING), store.rememberTmux(target, "late-session", "late-stamp"))
        assertEquals(listOf(neighbor), hosts(preferences))
    }

    @Test fun `incomplete remembered pairs are rejected without changing storage`() = runBlocking {
        val preferences = preferences(target)
        val store = store(preferences)
        val before = raw(preferences, "hosts_json")
        assertEquals(StoreResult.Failed(StoreFailure.INVALID), store.rememberTmux(target, "session", ""))
        assertEquals(StoreResult.Failed(StoreFailure.INVALID), store.rememberTmux(target, "", "stamp"))
        assertEquals(before, raw(preferences, "hosts_json"))
    }

    @Test fun `unreadable storage refuses memory writes and clearing`() = runBlocking {
        val preferences = preferences("hosts_json", "unreadable-ciphertext")
        val store = HostStore(preferences, encrypt, { error("unavailable test key") })
        assertEquals(StoreResult.Failed(StoreFailure.UNREADABLE), store.rememberTmux(target, "session", "stamp"))
        assertEquals(StoreResult.Failed(StoreFailure.UNREADABLE), store.clearTmuxMemoryForNetwork("network-a"))
        assertEquals("unreadable-ciphertext", raw(preferences, "hosts_json"))
    }

    @Test fun `encryption failures leave the whole remembered pair unchanged`() = runBlocking {
        val preferences = preferences(target.copy(networkId = "network-a"))
        val store = HostStore(preferences, { error("unavailable test key") }, decrypt)
        val before = raw(preferences, "hosts_json")
        assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), store.rememberTmux(target.copy(networkId = "network-a"), "session", "stamp"))
        assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), store.clearTmuxMemoryForNetwork("network-a"))
        assertEquals(before, raw(preferences, "hosts_json"))
    }

    @Test fun `network clear follows current jump relations and ignores overridden target networks`() = runBlocking {
        val jump = target.copy(id = "jump", networkId = "first-hop")
        val jumped = target.copy(jumpHostId = jump.id, networkId = "hidden-network")
        val direct = neighbor.copy(networkId = "first-hop")
        val hiddenDirect = target.copy(id = "hidden-direct", networkId = "hidden-network")
        val missingJump = target.copy(id = "missing-jump", jumpHostId = "gone", networkId = "first-hop")
        val selfJump = target.copy(id = "self-jump", jumpHostId = "self-jump", networkId = "first-hop")
        val preferences = preferences(jump, jumped, direct, hiddenDirect, missingJump, selfJump)
        val store = store(preferences)
        assertEquals(StoreResult.Saved(Unit), store.clearTmuxMemoryForNetwork("hidden-network"))
        assertEquals(listOf(jump, jumped, direct, hiddenDirect.copy(tmuxSessionName = "", tmuxTargetStamp = ""), missingJump, selfJump), hosts(preferences))
        assertEquals(StoreResult.Saved(Unit), store.clearTmuxMemoryForNetwork("first-hop"))
        assertEquals(listOf(jump, jumped, direct, hiddenDirect).map { it.copy(tmuxSessionName = "", tmuxTargetStamp = "") } + listOf(missingJump, selfJump), hosts(preferences))
    }

    @Test fun `network clear uses the latest jump and never recreates a deleted target`() = runBlocking {
        val jump = neighbor.copy(id = "jump", networkId = "old-network")
        val jumped = target.copy(jumpHostId = jump.id, networkId = "old-network")
        val preferences = preferences(jump, jumped)
        val store = store(preferences)
        assertTrue(store.commit(jump.copy(networkId = "new-network"), true) is StoreResult.Saved)
        assertEquals(StoreResult.Saved(Unit), store.clearTmuxMemoryForNetwork("old-network"))
        assertEquals(jumped, hosts(preferences).last())
        assertEquals(StoreResult.Saved(Unit), store.remove(jumped.id))
        assertEquals(StoreResult.Saved(Unit), store.clearTmuxMemoryForNetwork("new-network"))
        assertEquals(listOf(jump.copy(networkId = "new-network", tmuxSessionName = "", tmuxTargetStamp = "")), hosts(preferences))
    }

    @Test fun `no matching network is a no-op and blank network is rejected`() = runBlocking {
        val preferences = preferences(target)
        val store = HostStore(preferences, { error("must not encrypt a no-op") }, decrypt)
        val before = raw(preferences, "hosts_json")
        assertEquals(StoreResult.Saved(Unit), store.clearTmuxMemoryForNetwork("other-network"))
        assertEquals(StoreResult.Failed(StoreFailure.INVALID), store.clearTmuxMemoryForNetwork(""))
        assertEquals(before, raw(preferences, "hosts_json"))
    }
}

class TailnetIdentityStoreTest : PreferencesStoreFixture() {
    private val tailnet = NetworkEntry(
        id = "tailnet-a", name = "Network", kind = NetworkKind.TAILSCALE, revision = 7,
        controlUrl = "https://control.example", deviceName = "test-node",
    )
    private val proxy = NetworkEntry(id = "proxy-a", name = "Proxy", revision = 3, proxyHost = "proxy.example")

    private suspend fun preferences(vararg entries: NetworkEntry): DataStore<Preferences> =
        preferences("networks_json", JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString())

    private fun store(preferences: DataStore<Preferences>) = NetworkStore(preferences, encrypt, decrypt)

    private suspend fun networks(preferences: DataStore<Preferences>): List<NetworkEntry> =
        parseNetworks(raw(preferences, "networks_json").removePrefix("sealed:"))

    @Test fun `identity revision advances durably without changing the network id or other fields`() = runBlocking {
        val preferences = preferences(tailnet, proxy)
        assertEquals(StoreResult.Saved(tailnet.copy(revision = 8)), store(preferences).advanceTailnetIdentity(tailnet.id))
        assertEquals(listOf(tailnet.copy(revision = 8), proxy), networks(preferences))
        assertEquals(StoreResult.Saved(tailnet.copy(revision = 9)), store(preferences).advanceTailnetIdentity(tailnet.id))
        assertEquals(listOf(tailnet.copy(revision = 9), proxy), networks(preferences))
    }

    @Test fun `concurrent identity advances do not lose increments`() = runBlocking {
        val preferences = preferences(tailnet)
        val store = store(preferences)
        val results = coroutineScope { (1..8).map { async { store.advanceTailnetIdentity(tailnet.id) } }.awaitAll() }
        assertTrue(results.all { it is StoreResult.Saved })
        assertEquals(tailnet.copy(revision = 15), networks(preferences).single())
    }

    @Test fun `an old tailnet form cannot roll back the current identity revision`() = runBlocking {
        val preferences = preferences(tailnet)
        val store = store(preferences)
        assertTrue(store.advanceTailnetIdentity(tailnet.id) is StoreResult.Saved)
        val stale = tailnet.copy(name = "Renamed", deviceName = "other-node", revision = 1)
        assertEquals(StoreResult.Saved(stale.copy(revision = 8)), store.commit(stale, editing = true))
        assertTrue(store.advanceTailnetIdentity(tailnet.id) is StoreResult.Saved)
        val second = stale.copy(controlUrl = "https://other-control.example")
        assertEquals(StoreResult.Saved(second.copy(revision = 9)), store.commit(second, editing = true))
        assertEquals(listOf(second.copy(revision = 9)), networks(preferences))
    }

    @Test fun `proxy edits also merge revision from the transaction`() = runBlocking {
        val preferences = preferences(proxy)
        val store = store(preferences)
        assertEquals(StoreResult.Saved(proxy.copy(password = "first", revision = 4)), store.commit(proxy.copy(password = "first", revision = 1), true))
        assertEquals(StoreResult.Saved(proxy.copy(password = "second", revision = 5)), store.commit(proxy.copy(password = "second", revision = 1), true))
    }

    @Test fun `deleted tailnets cannot be recreated by advancing identity`() = runBlocking {
        val preferences = preferences(tailnet, proxy)
        val store = store(preferences)
        assertEquals(StoreResult.Saved(Unit), store.remove(tailnet.id))
        assertEquals(StoreResult.Failed(StoreFailure.MISSING), store.advanceTailnetIdentity(tailnet.id))
        assertEquals(listOf(proxy), networks(preferences))
    }

    @Test fun `proxy and overflow revisions reject identity advance without writes`() = runBlocking {
        val overflow = tailnet.copy(revision = Int.MAX_VALUE)
        val preferences = preferences(overflow, proxy)
        val store = store(preferences)
        val before = raw(preferences, "networks_json")
        assertEquals(StoreResult.Failed(StoreFailure.INVALID), store.advanceTailnetIdentity(proxy.id))
        assertEquals(StoreResult.Failed(StoreFailure.INVALID), store.advanceTailnetIdentity(tailnet.id))
        assertEquals(before, raw(preferences, "networks_json"))
    }

    @Test fun `unreadable network storage refuses identity advance`() = runBlocking {
        val preferences = preferences("networks_json", "unreadable-ciphertext")
        val store = NetworkStore(preferences, encrypt, { error("unavailable test key") })
        assertEquals(StoreResult.Failed(StoreFailure.UNREADABLE), store.advanceTailnetIdentity(tailnet.id))
        assertEquals("unreadable-ciphertext", raw(preferences, "networks_json"))
    }

    @Test fun `identity advance encryption failures leave the prior revision untouched`() = runBlocking {
        val preferences = preferences(tailnet)
        val store = NetworkStore(preferences, { error("unavailable test key") }, decrypt)
        val before = raw(preferences, "networks_json")
        assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), store.advanceTailnetIdentity(tailnet.id))
        assertEquals(before, raw(preferences, "networks_json"))
    }
}
