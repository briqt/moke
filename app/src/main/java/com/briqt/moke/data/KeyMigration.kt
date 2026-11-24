package com.briqt.moke.data

import com.briqt.moke.terminal.KeyParseResult
import com.briqt.moke.terminal.SshKeys
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class KeyMigrationPlan(
    val keys: List<KeyEntry>,
    val hosts: List<Host>,
)

/**
 * Deterministic UUIDv3 derived from `(normalizeKeyPem(privateKeyPem), passphrase)` so interrupted
 * two-store migrations and v1 `.mokesync` backup extractions converge on the exact same [KeyEntry.id].
 */
fun deterministicKeyId(privateKeyPem: String, passphrase: String): String =
    UUID.nameUUIDFromBytes(
        ("moke-key-v1:" + normalizeKeyPem(privateKeyPem) + "\u0000" + passphrase).toByteArray(Charsets.UTF_8)
    ).toString()

fun uniqueKeyName(baseName: String, existing: Iterable<KeyEntry>, ignoreId: String = ""): String {
    val taken = existing.mapNotNull { entry ->
        if (entry.id == ignoreId) null else entry.name.trim()
    }
    return uniqueKeyNameFromNames(baseName, taken)
}

internal fun uniqueKeyNameFromNames(baseName: String, existingNames: Collection<String>): String {
    val base = baseName.trim().ifEmpty { "SSH" }
    if (existingNames.none { it.equals(base, ignoreCase = true) }) return base
    var n = 2
    while (existingNames.any { it.equals("$base ($n)", ignoreCase = true) }) n++
    return "$base ($n)"
}

/**
 * Pure migration planner: scans ALL hosts with non-blank [Host.privateKeyPem] (regardless of
 * [Host.authType] so saved keys on password/Tailscale hosts are never lost), deduplicates identical
 * `(normalizeKeyPem(privateKeyPem), passphrase)` pairs into shared [KeyEntry] items, and clears
 * orphaned passphrases or whitespace-only PEMs when [Host.privateKeyPem] has no key material.
 *
 * Returns `null` when no host holds inline key material or orphaned passphrases.
 */
internal fun planInlineKeyMigration(
    hosts: List<Host>,
    existingKeys: List<KeyEntry>,
    now: Long = System.currentTimeMillis(),
): KeyMigrationPlan? {
    if (hosts.none { it.privateKeyPem.isNotEmpty() || it.passphrase.isNotEmpty() }) return null

    val keysById = LinkedHashMap<String, KeyEntry>(existingKeys.size + hosts.size)
    val takenNames = ArrayList<String>(existingKeys.size + hosts.size)
    for (k in existingKeys) {
        keysById[k.id] = k
        takenNames += k.name.trim()
    }
    // Also index existing keys by (normalizeKeyPem(pem), passphrase) so if a user already manually
    // imported the exact same key before an old backup/record is normalized, it reuses that entry cleanly.
    val existingByMaterial = HashMap<Pair<String, String>, KeyEntry>(existingKeys.size)
    for (k in existingKeys) {
        val existingPem = normalizeKeyPem(k.privateKeyPem)
        if (existingPem.isNotEmpty()) {
            existingByMaterial.putIfAbsent(existingPem to k.passphrase, k)
        }
    }

    val migratedHosts = hosts.map { h ->
        val pem = normalizeKeyPem(h.privateKeyPem)
        if (pem.isNotEmpty()) {
            val pass = h.passphrase
            val existingMatch = existingByMaterial[pem to pass]
            val targetId = existingMatch?.id ?: deterministicKeyId(pem, pass)
            if (targetId !in keysById) {
                val parsed = (SshKeys.inspectPrivateKey(pem, pass) as? KeyParseResult.Ok)?.key
                val rawName = when {
                    parsed != null && parsed.comment.isNotBlank() -> parsed.comment
                    h.displayName.isNotBlank() -> h.displayName
                    parsed != null && parsed.algorithmLabel.isNotBlank() -> parsed.algorithmLabel
                    else -> SshKeys.guessKeyKindLabel(pem)
                }
                val finalName = uniqueKeyNameFromNames(rawName, takenNames)
                takenNames += finalName
                val entry = KeyEntry(
                    id = targetId,
                    name = finalName,
                    privateKeyPem = pem,
                    passphrase = pass,
                    publicKey = parsed?.let { SshKeys.withCommentIfBlank(it, finalName) }.orEmpty(),
                    fingerprint = parsed?.fingerprint.orEmpty(),
                    algorithmLabel = parsed?.algorithmLabel ?: SshKeys.guessKeyKindLabel(pem),
                    createdAt = now,
                )
                keysById[targetId] = entry
                existingByMaterial[pem to pass] = entry
            }
            h.copy(keyId = targetId, privateKeyPem = "", passphrase = "")
        } else if (h.privateKeyPem.isNotEmpty() || h.passphrase.isNotEmpty()) {
            h.copy(privateKeyPem = "", passphrase = "")
        } else {
            h
        }
    }
    return KeyMigrationPlan(keys = keysById.values.toList(), hosts = migratedHosts)
}

/**
 * Crash-safe, idempotent two-store migration (`KeyStore` first, `HostStore` second) guarded by
 * [mutex] and requiring both stores to be [ListState.Ready]. Inline host keys are cleared only
 * after verifying the target [KeyEntry.id] is durably present in [keyStore].
 */
internal suspend fun migrateInlineKeysIfNeeded(
    hostStore: HostStore,
    keyStore: KeyStore,
    mutex: Mutex,
    now: Long = System.currentTimeMillis(),
): Boolean = mutex.withLock {
    migrateInlineKeysLocked(hostStore, keyStore, now)
}

internal suspend fun migrateInlineKeysLocked(
    hostStore: HostStore,
    keyStore: KeyStore,
    now: Long = System.currentTimeMillis(),
): Boolean {
    val hostState = hostStore.state.first() as? ListState.Ready ?: return false
    val keyState = keyStore.state.first() as? ListState.Ready ?: return false
    if (hostState.items.none { it.privateKeyPem.isNotEmpty() || it.passphrase.isNotEmpty() }) return true

    val snapshotHosts = hostState.items
    val keysOk = keyStore.update { currentKeys ->
        val plan = planInlineKeyMigration(snapshotHosts, currentKeys, now) ?: return@update null
        plan.keys.takeIf { it != currentKeys }
    }
    if (!keysOk) return false

    val durableKeys = (keyStore.state.first() as? ListState.Ready)?.items ?: return false
    val durableIds = durableKeys.mapTo(HashSet()) { it.id }

    return hostStore.updateAll { currentHosts ->
        val plan = planInlineKeyMigration(currentHosts, durableKeys, now) ?: return@updateAll null
        val byId = currentHosts.associateBy { it.id }
        val allDurable = plan.hosts.all { next ->
            val prev = byId[next.id]
            prev == null || normalizeKeyPem(prev.privateKeyPem).isEmpty() || next.keyId in durableIds
        }
        if (!allDurable) return@updateAll null
        plan.hosts.takeIf { it != currentHosts }
    }
}
