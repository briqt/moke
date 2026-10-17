package com.briqt.moke.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_networks")

/**
 * 网络条目持久化：规则与 [HostStore] 相同——Keystore 加密、解不开时拒绝写入、加密失败绝不退回明文；
 * 文件已在备份规则里排除（密钥不随备份走）。
 */
class NetworkStore(private val context: Context) {

    private val key = stringPreferencesKey("networks_json")

    private val decoded: Flow<DecodedHosts> = context.networkDataStore.data.map { decode(it[key]) }

    val state: Flow<ListState<NetworkEntry>> = decoded.map { d ->
        if (d.unreadable) ListState.Unreadable else ListState.Ready(parseNetworks(d.json))
    }

    private fun decode(raw: String?): DecodedHosts = decodeHosts(raw, CredentialCrypto::decrypt)

    /** @return 是否真的落盘；解不开或加密失败时 false。 */
    suspend fun save(list: List<NetworkEntry>): Boolean {
        val encrypted = encodeNetworks(list, CredentialCrypto::encrypt) ?: return false
        var written = false
        context.networkDataStore.edit { prefs ->
            if (decode(prefs[key]).unreadable) return@edit
            prefs[key] = encrypted
            written = true
        }
        return written
    }

    /** 读-改-写在同一事务里完成；[transform] 返回 null 表示不改。 */
    suspend fun update(transform: (List<NetworkEntry>) -> List<NetworkEntry>?): Boolean {
        var written = false
        context.networkDataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (current.unreadable) return@edit
            val next = transform(parseNetworks(current.json)) ?: return@edit
            prefs[key] = encodeNetworks(next, CredentialCrypto::encrypt) ?: return@edit
            written = true
        }
        return written
    }
}

internal fun parseNetworks(json: String): List<NetworkEntry> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { NetworkEntry.fromJson(arr.getJSONObject(it)) }
}.getOrDefault(emptyList())

/** 序列化 + 加密；加密失败返回 null（绝不退回明文）。 */
internal fun encodeNetworks(list: List<NetworkEntry>, encrypt: (String) -> String): String? {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return runCatching { encrypt(arr.toString()) }.getOrNull()
}
