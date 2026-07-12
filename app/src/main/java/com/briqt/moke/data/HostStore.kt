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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_hosts")

/** 主机列表持久化（DataStore Preferences，单个 JSON 字符串键）。 */
class HostStore(private val context: Context) {

    private val key = stringPreferencesKey("hosts_json")

    val hosts: Flow<List<Host>> = context.dataStore.data.map { prefs ->
        parse(decode(prefs[key] ?: "[]"))
    }

    suspend fun save(list: List<Host>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val stored = runCatching { CredentialCrypto.encrypt(arr.toString()) }.getOrElse { arr.toString() }
        context.dataStore.edit { it[key] = stored }
    }

    /** 兼容读取：以 '[' 开头视为旧明文（下次 save 自动加密），否则解密；失败回空。 */
    private fun decode(raw: String): String = when {
        raw.isBlank() -> "[]"
        raw.trimStart().startsWith("[") -> raw
        else -> runCatching { CredentialCrypto.decrypt(raw) }.getOrDefault("[]")
    }

    suspend fun upsert(host: Host, current: List<Host>) {
        val idx = current.indexOfFirst { it.id == host.id }
        val next = current.toMutableList()
        if (idx >= 0) next[idx] = host else next.add(host)
        save(next)
    }

    suspend fun delete(host: Host, current: List<Host>) {
        save(current.filterNot { it.id == host.id })
    }

    private fun parse(json: String): List<Host> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { Host.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
