package com.briqt.moke.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyStoreTest : PreferencesStoreFixture() {
    private val entryA = KeyEntry(
        id = "key-a",
        name = "Work Key",
        privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----",
        passphrase = "secret",
        publicKey = "ssh-ed25519 AAA Work Key",
        fingerprint = "SHA256:abc123",
        algorithmLabel = "Ed25519",
        createdAt = 1_700_000_000_000L,
    )
    private val entryB = entryA.copy(
        id = "key-b",
        name = "Home Key",
        passphrase = "",
        createdAt = 1_700_000_005_000L,
    )

    private suspend fun preferences(vararg entries: KeyEntry): DataStore<Preferences> =
        preferences("keys_json", encrypt(JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()))

    private fun store(preferences: DataStore<Preferences>) = KeyStore(preferences, encrypt, decrypt)

    private suspend fun storedKeys(preferences: DataStore<Preferences>): List<KeyEntry> =
        parseKeys(raw(preferences, "keys_json").removePrefix("sealed:"))

    @Test
    fun `KeyEntry JSON round trip preserves all fields`() {
        assertEquals(entryA, KeyEntry.fromJson(JSONObject(entryA.toJson().toString())))
    }

    @Test
    fun `commit creates renames rejects duplicate names and deletes`() = runBlocking {
        val prefs = preferences(entryA)
        val store = store(prefs)

        assertEquals(ListState.Ready(listOf(entryA)), store.state.first())
        assertEquals(StoreResult.Saved(entryB), store.commit(entryB, editing = false))
        assertEquals(listOf(entryA, entryB), storedKeys(prefs))

        // Duplicate name (case-insensitive) is rejected on both create and edit.
        assertEquals(
            StoreResult.Failed(StoreFailure.DUPLICATE_NAME),
            store.commit(entryB.copy(id = "key-c", name = " work key "), editing = false),
        )
        assertEquals(
            StoreResult.Failed(StoreFailure.DUPLICATE_NAME),
            store.commit(entryB.copy(name = "WORK KEY"), editing = true),
        )

        // Rename preserves original createdAt timestamp.
        val renamed = store.commit(entryA.copy(name = "  Renamed Key  ", createdAt = 999L), editing = true)
        assertEquals(StoreResult.Saved(entryA.copy(name = "Renamed Key")), renamed)

        // Deleting removes only the target entry; editing a deleted entry fails with MISSING.
        assertEquals(StoreResult.Saved(Unit), store.remove(entryA.id))
        assertEquals(listOf(entryB), storedKeys(prefs))
        assertEquals(StoreResult.Failed(StoreFailure.MISSING), store.commit(entryA, editing = true))
    }

    @Test
    fun `unreadable storage refuses all writes and preserves stored ciphertext`() = runBlocking {
        val prefs = preferences("keys_json", "unreadable-ciphertext")
        val store = KeyStore(prefs, encrypt, { error("keystore invalidated") })

        assertEquals(ListState.Unreadable, store.state.first())
        assertEquals(StoreResult.Failed(StoreFailure.UNREADABLE), store.commit(entryA, editing = false))
        assertEquals(StoreResult.Failed(StoreFailure.UNREADABLE), store.remove(entryA.id))
        assertFalse(store.save(listOf(entryA)))
        assertFalse(store.update { listOf(entryA) })
        assertEquals("unreadable-ciphertext", raw(prefs, "keys_json"))
    }

    @Test
    fun `encryption failures never fall back to plaintext`() = runBlocking {
        val prefs = preferences(entryA)
        val before = raw(prefs, "keys_json")
        val store = KeyStore(prefs, { error("keystore unavailable") }, decrypt)

        assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), store.commit(entryB, editing = false))
        assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), store.remove(entryA.id))
        assertFalse(store.save(listOf(entryA, entryB)))
        assertFalse(store.update { listOf(entryA, entryB) })
        assertEquals(before, raw(prefs, "keys_json"))
        assertTrue(raw(prefs, "keys_json").startsWith("sealed:"))
    }
}
