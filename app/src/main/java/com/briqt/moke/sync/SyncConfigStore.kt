package com.briqt.moke.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.briqt.moke.data.CredentialCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_sync")

/** 本机保存的同步设置：WebDAV 服务与同步密码。只在本机，不进备份文件。 */
data class SyncSettings(val webDav: WebDavConfig, val syncPassword: String)

/**
 * 同步设置的持久化：整条 JSON 经 Keystore 加密后存 DataStore（规则同主机库；系统备份已排除）。
 * 解不开（Keystore 密钥失效）时按「未配置」处理——这里只有可重新填写的信息，没有不可恢复的数据。
 */
class SyncConfigStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    constructor(context: Context) : this(context.syncDataStore, CredentialCrypto::encrypt, CredentialCrypto::decrypt)

    private val key = stringPreferencesKey("config")

    val settings: Flow<SyncSettings?> = dataStore.data
        .map { prefs -> prefs[key]?.let { raw -> runCatching { parse(decrypt(raw)) }.getOrNull() } }
        .catch { emit(null) }

    /** @return 是否落盘；加密失败时不写（绝不退回明文）。 */
    suspend fun save(value: SyncSettings): Boolean {
        val encrypted = runCatching { encrypt(toJson(value)) }.getOrNull() ?: return false
        dataStore.edit { it[key] = encrypted }
        return true
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    private fun toJson(s: SyncSettings): String = JSONObject().apply {
        put("url", s.webDav.url)
        put("username", s.webDav.username)
        put("password", s.webDav.password)
        put("directory", s.webDav.directory)
        put("syncPassword", s.syncPassword)
    }.toString()

    private fun parse(json: String): SyncSettings? {
        val o = JSONObject(json)
        val url = o.optString("url")
        if (url.isBlank()) return null
        return SyncSettings(
            WebDavConfig(url, o.optString("username"), o.optString("password"), o.optString("directory", WebDavConfig.DEFAULT_DIRECTORY)),
            o.optString("syncPassword"),
        )
    }
}
