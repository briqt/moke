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

/** 一次解码的完整结论：拿到的 JSON，以及「存量密文根本解不开」这个必须区分出来的状态。 */
internal data class DecodedHosts(val json: String, val unreadable: Boolean)

/**
 * 存储字节 → 解码结论的**纯逻辑**（[decrypt] 注入，便于 JVM 单测；线上就是 Keystore 那把）。
 *
 * 抽出来是因为这里出过一个静默毁数据的 bug：解密失败被 `getOrDefault("[]")` 吞掉，和「空列表」
 * 完全无法区分。这个分类必须有测试守着。
 */
internal fun decodeHosts(raw: String?, decrypt: (String) -> String): DecodedHosts = when {
    raw.isNullOrBlank() -> DecodedHosts("[]", false)
    // 旧版明文（以 '[' 开头），下次 save 自动加密。
    raw.trimStart().startsWith("[") -> DecodedHosts(raw, false)
    else -> runCatching { decrypt(raw) }
        .fold({ DecodedHosts(it, false) }, { DecodedHosts("[]", true) })
}

/** 主机列表持久化（DataStore Preferences，单个 JSON 字符串键；凭据经 Keystore 加密）。 */
class HostStore(private val context: Context) {

    private val key = stringPreferencesKey("hosts_json")

    private val decoded: Flow<DecodedHosts> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    val hosts: Flow<List<Host>> = decoded.map { parse(it.json) }

    /**
     * 存量密文解不开（Keystore 密钥已失效）。
     *
     * 典型场景：整机备份恢复到新设备——DataStore 文件跟着备份过去了，Keystore 里的密钥没有。
     * 此前把这种情况和「空列表」混为一谈：界面显示一台主机都没有，而接下来任何一次写入
     * （连一次、拖一下排序）都会把密文覆盖成空数组，原数据**永久丢失**。现在它是一个显式状态，
     * UI 据此给出说明，[save] 据此拒绝写入。
     */
    val unreadable: Flow<Boolean> = decoded.map { it.unreadable }

    private fun decode(raw: String?): DecodedHosts = decodeHosts(raw, CredentialCrypto::decrypt)

    /**
     * 写回主机列表。两条硬规则：
     *
     * 1. 存量密文解不开时**绝不写**——那一刻内存里的列表必然是空的，写下去就是把用户的连接抹掉。
     * 2. 加密失败时**绝不退回明文**——凭据明文落盘是安全事故，宁可这次改动不生效。
     *
     * @return 是否真的落盘；false = 被上面两条规则挡下。
     */
    suspend fun save(list: List<Host>): Boolean {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val encrypted = runCatching { CredentialCrypto.encrypt(arr.toString()) }.getOrNull() ?: return false
        var written = false
        context.dataStore.edit { prefs ->
            // 在同一个事务里重新判定，避免「读到空 → 用户操作 → 写空」这条竞态。
            if (decode(prefs[key]).unreadable) return@edit
            prefs[key] = encrypted
            written = true
        }
        return written
    }

    suspend fun upsert(host: Host, current: List<Host>): Boolean {
        val idx = current.indexOfFirst { it.id == host.id }
        val next = current.toMutableList()
        if (idx >= 0) next[idx] = host else next.add(host)
        return save(next)
    }

    suspend fun delete(host: Host, current: List<Host>): Boolean =
        save(current.filterNot { it.id == host.id })

    private fun parse(json: String): List<Host> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { Host.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
