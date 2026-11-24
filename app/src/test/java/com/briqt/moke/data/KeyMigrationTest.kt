package com.briqt.moke.data

import com.briqt.moke.terminal.KeyGenAlgorithm
import com.briqt.moke.terminal.SshKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMigrationTest : PreferencesStoreFixture() {

    @Test
    fun `deduplicates identical key and passphrase across hosts including non-KEY auth types`() {
        val k1 = SshKeys.generate(KeyGenAlgorithm.ED25519, passphrase = "", comment = "ops@ workstation")
        val k2 = SshKeys.generate(KeyGenAlgorithm.ED25519, passphrase = "pw1", comment = "")
        val pemWithCrlf = "  " + k1.privateKeyPem.replace("\n", "\r\n") + "\n "

        val h1 = Host(id = "h1", label = "Prod 1", host = "10.0.0.1", username = "root", authType = AuthType.KEY, privateKeyPem = k1.privateKeyPem)
        val h2 = Host(id = "h2", label = "Prod 2", host = "10.0.0.2", username = "root", authType = AuthType.PASSWORD, password = "p", privateKeyPem = pemWithCrlf)
        val h3 = Host(id = "h3", label = "Bastion", host = "10.0.0.3", username = "ops", authType = AuthType.KEY, privateKeyPem = k2.privateKeyPem, passphrase = "pw1")
        val h4 = Host(id = "h4", label = "Bastion", host = "10.0.0.4", username = "ops", authType = AuthType.TAILSCALE_SSH, privateKeyPem = "-----BEGIN LEGACY BROKEN-----", passphrase = "")
        val h5 = Host(id = "h5", label = "Orphan", host = "10.0.0.5", username = "ops", authType = AuthType.PASSWORD, passphrase = "stale-passphrase")

        val plan = planInlineKeyMigration(listOf(h1, h2, h3, h4, h5), emptyList(), now = 1234L)!!
        assertEquals(3, plan.keys.size)

        val expectedId1 = deterministicKeyId(k1.privateKeyPem, "")
        val expectedId2 = deterministicKeyId(k2.privateKeyPem, "pw1")
        val expectedId3 = deterministicKeyId("-----BEGIN LEGACY BROKEN-----", "")
        assertNotEquals(expectedId1, expectedId2)

        // Naming priority: embedded comment -> first host displayName -> disambiguated " (2)".
        assertEquals("ops@ workstation", plan.keys[0].name)
        assertEquals("Bastion", plan.keys[1].name)
        assertEquals("Bastion (2)", plan.keys[2].name)

        // h1 and h2 share expectedId1; h2 retains AuthType.PASSWORD and its password!
        assertEquals(expectedId1, plan.hosts[0].keyId)
        assertEquals("", plan.hosts[0].privateKeyPem)
        assertEquals(expectedId1, plan.hosts[1].keyId)
        assertEquals(AuthType.PASSWORD, plan.hosts[1].authType)
        assertEquals("p", plan.hosts[1].password)
        assertEquals("", plan.hosts[1].privateKeyPem)

        assertEquals(expectedId2, plan.hosts[2].keyId)
        assertEquals("", plan.hosts[2].passphrase)

        // Unparseable legacy PEM is still preserved without data loss.
        assertEquals(expectedId3, plan.hosts[3].keyId)
        assertEquals(AuthType.TAILSCALE_SSH, plan.hosts[3].authType)
        assertEquals("-----BEGIN LEGACY BROKEN-----", plan.keys[2].privateKeyPem)

        // Orphaned passphrase with blank PEM is cleared.
        assertEquals("", plan.hosts[4].passphrase)
        assertEquals("", plan.hosts[4].keyId)

        // Subsequent pass is a clean no-op.
        assertNull(planInlineKeyMigration(plan.hosts, plan.keys, now = 5678L))
    }

    @Test
    fun `two-store migration is idempotent across simulated crash after KeyStore write`() = runBlocking {
        val gen = SshKeys.generate(KeyGenAlgorithm.ED25519, comment = "deploy-key")
        val host = Host(
            id = "h1", label = "App", host = "app.example", username = "deploy",
            authType = AuthType.KEY, privateKeyPem = gen.privateKeyPem,
            lastConnectedAt = 88L, persistence = SessionPersistence.TMUX,
            tmuxSessionName = "main", tmuxTargetStamp = "stamp-1",
        )
        val expectedKeyId = deterministicKeyId(gen.privateKeyPem, "")
        val preExistingEntry = KeyEntry(
            id = expectedKeyId,
            name = "deploy-key",
            privateKeyPem = gen.privateKeyPem,
            passphrase = "",
            publicKey = gen.publicKey,
            fingerprint = gen.fingerprint,
            algorithmLabel = gen.algorithmLabel,
            createdAt = 1000L,
        )

        // Simulate crash after KeyStore wrote `preExistingEntry` but before HostStore cleared `privateKeyPem`.
        val hostPrefs = preferences("hosts_json", encrypt(JSONArray().put(host.toJson()).toString()))
        val keyPrefs = preferences("keys_json", encrypt(JSONArray().put(preExistingEntry.toJson()).toString()))
        val hostStore = HostStore(hostPrefs, encrypt, decrypt)
        val keyStore = KeyStore(keyPrefs, encrypt, decrypt)

        assertTrue(migrateInlineKeysIfNeeded(hostStore, keyStore, Mutex(), now = 2000L))

        val keysAfter = (keyStore.state.first() as ListState.Ready).items
        assertEquals(listOf(preExistingEntry), keysAfter) // No duplicate created; original createdAt kept!

        val hostsAfter = (hostStore.state.first() as ListState.Ready).items
        assertEquals(
            listOf(host.copy(keyId = expectedKeyId, privateKeyPem = "", passphrase = "")),
            hostsAfter,
        )
    }

    @Test
    fun `unreadable or failing KeyStore aborts before clearing HostStore inline keys`() = runBlocking {
        val gen = SshKeys.generate(KeyGenAlgorithm.ED25519, comment = "safe")
        val host = Host(id = "h1", authType = AuthType.KEY, privateKeyPem = gen.privateKeyPem, passphrase = "")

        val hostPrefs = preferences("hosts_json", encrypt(JSONArray().put(host.toJson()).toString()))
        val unreadableKeyPrefs = preferences("keys_json", "corrupt-cipher")
        val hostStore = HostStore(hostPrefs, encrypt, decrypt)
        val unreadableKeyStore = KeyStore(unreadableKeyPrefs, encrypt, { error("unreadable") })

        assertFalse(migrateInlineKeysIfNeeded(hostStore, unreadableKeyStore, Mutex()))
        assertEquals(gen.privateKeyPem, (hostStore.state.first() as ListState.Ready).items.single().privateKeyPem)

        // Also test KeyStore encryption failure during migration.
        val emptyKeyPrefs = preferences("keys_json", encrypt("[]"))
        val failingKeyStore = KeyStore(emptyKeyPrefs, { error("encrypt failed") }, decrypt)
        assertFalse(migrateInlineKeysIfNeeded(hostStore, failingKeyStore, Mutex()))
        assertEquals(gen.privateKeyPem, (hostStore.state.first() as ListState.Ready).items.single().privateKeyPem)
    }

    @Test
    fun `normalizes UTF-8 BOM in deterministicKeyId and matches equals ignoreCase on Unicode key names`() {
        val gen = SshKeys.generate(KeyGenAlgorithm.ED25519, comment = "bom-test")
        val withBomAndCrlf = "\uFEFF" + gen.privateKeyPem.replace("\n", "\r\n") + "\r\n"
        assertEquals(deterministicKeyId(gen.privateKeyPem, "p"), deterministicKeyId(withBomAndCrlf, "p"))

        val h1 = Host(id = "h1", authType = AuthType.KEY, privateKeyPem = gen.privateKeyPem)
        val h2 = Host(id = "h2", authType = AuthType.KEY, privateKeyPem = withBomAndCrlf)
        val plan = planInlineKeyMigration(listOf(h1, h2), emptyList(), now = 1L)!!
        assertEquals(1, plan.keys.size)
        assertEquals(plan.hosts[0].keyId, plan.hosts[1].keyId)

        // Turkish dotted capital 'İ' equals 'i' under Kotlin/JVM equals(ignoreCase = true),
        // matching prepareKeyEdit and FormValidation.duplicateKeyName.
        val existing = listOf(KeyEntry(id = "k1", name = "İstanbul"))
        assertEquals("istanbul (2)", uniqueKeyName("istanbul", existing))
    }
}
