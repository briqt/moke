package com.briqt.moke.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_networks")

/** Encrypted network settings. Failed reads and failed encryption never replace the stored data. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStore(private val context: Context) {
    private val key = stringPreferencesKey("networks_json")
    private val reads = MutableStateFlow(0)
    private val decoded: Flow<DecodedHosts> = reads.flatMapLatest {
        context.networkDataStore.data.map { decode(it[key]) }
            .catch { emit(DecodedHosts("[]", true)) }
    }.flowOn(Dispatchers.IO)

    val state: Flow<ListState<NetworkEntry>> = decoded.map { d ->
        if (d.unreadable) ListState.Unreadable else ListState.Ready(parseNetworks(d.json))
    }

    fun refresh() { reads.update { it + 1 } }
    private fun decode(raw: String?): DecodedHosts = decodeHosts(raw, CredentialCrypto::decrypt)

    suspend fun commit(entry: NetworkEntry, editing: Boolean): StoreResult<NetworkEntry> = withContext(Dispatchers.IO) {
        try {
            var result: StoreResult<NetworkEntry> = StoreResult.Failed(StoreFailure.UNREADABLE)
            context.networkDataStore.edit { prefs ->
                val decoded = decode(prefs[key])
                if (decoded.unreadable) return@edit
                when (val prepared = prepareNetworkEdit(parseNetworks(decoded.json), entry, editing)) {
                    is StoreResult.Failed -> result = prepared
                    is StoreResult.Saved -> {
                        val encrypted = encodeNetworks(prepared.value.items, CredentialCrypto::encrypt)
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
            context.networkDataStore.edit { prefs ->
                val decoded = decode(prefs[key])
                if (decoded.unreadable) return@edit
                val current = parseNetworks(decoded.json)
                if (current.none { it.id == id }) { result = StoreResult.Saved(Unit); return@edit }
                val encrypted = encodeNetworks(current.filterNot { it.id == id }, CredentialCrypto::encrypt)
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

    suspend fun save(list: List<NetworkEntry>): Boolean = withContext(Dispatchers.IO) {
        val encrypted = encodeNetworks(list, CredentialCrypto::encrypt) ?: return@withContext false
        var written = false
        context.networkDataStore.edit { prefs ->
            if (decode(prefs[key]).unreadable) return@edit
            prefs[key] = encrypted
            written = true
        }
        written
    }

    suspend fun update(transform: (List<NetworkEntry>) -> List<NetworkEntry>?): Boolean = withContext(Dispatchers.IO) {
        var written = false
        context.networkDataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (current.unreadable) return@edit
            val next = transform(parseNetworks(current.json)) ?: return@edit
            prefs[key] = encodeNetworks(next, CredentialCrypto::encrypt) ?: return@edit
            written = true
        }
        written
    }
}

internal fun parseNetworks(json: String): List<NetworkEntry> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { NetworkEntry.fromJson(arr.getJSONObject(it)) }
}.getOrDefault(emptyList())

internal fun encodeNetworks(list: List<NetworkEntry>, encrypt: (String) -> String): String? {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return runCatching { encrypt(arr.toString()) }.getOrNull()
}
