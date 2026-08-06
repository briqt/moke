package com.briqt.moke.terminal.sftp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray

private val Context.transferDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_transfers")

/**
 * 传输任务表持久化。
 *
 * 单独一个 DataStore 文件：任务表写得比设置频繁得多（每完成/失败一次就写），
 * 与设置混在一起会让设置文件反复重写，也让"清设置"这种调试动作误伤传输记录。
 * 只存元数据（路径、URI、断点），**不存任何凭据**——凭据仍只在 HostStore 里。
 */
class TransferStore(private val context: Context) {

    private val key = stringPreferencesKey("tasks_json")

    suspend fun load(): List<TransferTask> = runCatching {
        val raw = context.transferDataStore.data.first()[key] ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { TransferTask.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    suspend fun save(list: List<TransferTask>) {
        // 已完成的只留最近若干条：这是"最近传了什么"的回执，不是历史归档。
        val trimmed = list.filter { it.active || it.state == TransferState.FAILED } +
            list.filter { !it.active && it.state != TransferState.FAILED }
                .sortedByDescending { it.createdAt }
                .take(KEEP_FINISHED)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        context.transferDataStore.edit { it[key] = arr.toString() }
    }

    companion object {
        const val KEEP_FINISHED = 20
    }
}
