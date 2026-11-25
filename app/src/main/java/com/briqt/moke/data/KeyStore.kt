package com.briqt.moke.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class KeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    val publicKey: String = "",
    val fingerprint: String = "",
    val algorithmLabel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("privateKeyPem", privateKeyPem)
        put("passphrase", passphrase)
        put("publicKey", publicKey)
        put("fingerprint", fingerprint)
        put("algorithmLabel", algorithmLabel)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): KeyEntry {
            val privateKeyPem = o.optString("privateKeyPem", "")
            val passphrase = o.optString("passphrase", "")
            val rawId = o.optString("id", "").trim()
            val normalizedPem = normalizeKeyPem(privateKeyPem)
            val id = when {
                rawId.isNotEmpty() -> rawId
                normalizedPem.isNotEmpty() -> deterministicKeyId(normalizedPem, passphrase)
                else -> ""
            }
            return KeyEntry(
                id = id,
                name = o.optString("name", ""),
                privateKeyPem = privateKeyPem,
                passphrase = passphrase,
                publicKey = o.optString("publicKey", ""),
                fingerprint = o.optString("fingerprint", ""),
                algorithmLabel = o.optString("algorithmLabel", ""),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }
}

private val Context.keyDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_keys")

/** Encrypted SSH key store. Unreadable storage and encryption failures never overwrite stored data. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    constructor(context: Context) : this(context.keyDataStore, CredentialCrypto::encrypt, CredentialCrypto::decrypt)

    private val key = stringPreferencesKey("keys_json")
    private val reads = MutableStateFlow(0)
    private val decoded: Flow<DecodedHosts> = reads.flatMapLatest {
        dataStore.data.map { decode(it[key]) }
            .catch { emit(DecodedHosts("[]", true)) }
    }.flowOn(Dispatchers.IO)

    val state: Flow<ListState<KeyEntry>> = decoded.map { d ->
        if (d.unreadable) ListState.Unreadable else ListState.Ready(parseKeys(d.json))
    }

    fun refresh() { reads.update { it + 1 } }
    private fun decode(raw: String?): DecodedHosts = decodeHosts(raw, decrypt)

    suspend fun commit(entry: KeyEntry, editing: Boolean): StoreResult<KeyEntry> = withContext(Dispatchers.IO) {
        try {
            var result: StoreResult<KeyEntry> = StoreResult.Failed(StoreFailure.UNREADABLE)
            dataStore.edit { prefs ->
                val decoded = decode(prefs[key])
                if (decoded.unreadable) return@edit
                when (val prepared = prepareKeyEdit(parseKeys(decoded.json), entry, editing)) {
                    is StoreResult.Failed -> result = prepared
                    is StoreResult.Saved -> {
                        val encrypted = encodeKeys(prepared.value.items, encrypt)
                        if (encrypted == null) result = StoreResult.Failed(StoreFailure.ENCRYPTION) else {
                            prefs[key] = encrypted
                            result = StoreResult.Saved(prepared.value.saved)
                        }
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoreResult.Failed(StoreFailure.IO)
        }
    }

    suspend fun remove(id: String): StoreResult<Unit> = withContext(Dispatchers.IO) {
        try {
            var result: StoreResult<Unit> = StoreResult.Failed(StoreFailure.UNREADABLE)
            dataStore.edit { prefs ->
                val decoded = decode(prefs[key])
                if (decoded.unreadable) return@edit
                val current = parseKeys(decoded.json)
                if (current.none { it.id == id }) { result = StoreResult.Saved(Unit); return@edit }
                val encrypted = encodeKeys(current.filterNot { it.id == id }, encrypt)
                if (encrypted == null) result = StoreResult.Failed(StoreFailure.ENCRYPTION) else {
                    prefs[key] = encrypted
                    result = StoreResult.Saved(Unit)
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoreResult.Failed(StoreFailure.IO)
        }
    }

    suspend fun save(list: List<KeyEntry>): Boolean = withContext(Dispatchers.IO) {
        val encrypted = encodeKeys(list, encrypt) ?: return@withContext false
        try {
            var written = false
            dataStore.edit { prefs ->
                if (decode(prefs[key]).unreadable) return@edit
                prefs[key] = encrypted
                written = true
            }
            written
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Read-modify-write inside a single DataStore transaction; returning null from [transform]
     * leaves storage untouched while still reporting readable status (`true`).
     */
    suspend fun update(transform: (List<KeyEntry>) -> List<KeyEntry>?): Boolean = withContext(Dispatchers.IO) {
        try {
            var ok = false
            dataStore.edit { prefs ->
                val current = decode(prefs[key])
                if (current.unreadable) return@edit
                val next = transform(parseKeys(current.json))
                if (next != null) prefs[key] = encodeKeys(next, encrypt) ?: return@edit
                ok = true
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}

internal fun parseKeys(json: String): List<KeyEntry> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length())
        .mapNotNull { i ->
            arr.optJSONObject(i)?.let(KeyEntry::fromJson)?.takeIf {
                it.id.isNotBlank() && normalizeKeyPem(it.privateKeyPem).isNotEmpty()
            }
        }
        .distinctBy { it.id }
}.getOrDefault(emptyList())

internal fun encodeKeys(list: List<KeyEntry>, encrypt: (String) -> String): String? {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return runCatching { encrypt(arr.toString()) }.getOrNull()
}

/** Canonicalize line endings, leading UTF-8 BOM, and outer whitespace for SSH key material. */
fun normalizeKeyPem(pem: String): String =
    pem.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
