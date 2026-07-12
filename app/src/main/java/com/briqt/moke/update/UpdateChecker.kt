package com.briqt.moke.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检查更新的结果状态。 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val current: String) : UpdateStatus
    data class Available(val latest: String, val url: String) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/** 从 GitHub Releases 查最新版并与当前版本比对。 */
object UpdateChecker {
    private const val LATEST_API = "https://api.github.com/repos/briqt/moke/releases/latest"

    suspend fun check(current: String): UpdateStatus = withContext(Dispatchers.IO) {
        // 硬超时兜底：任何慢网络/卡住都在 12s 内收敛，spinner 不会永转。
        withTimeoutOrNull(12_000) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "moke")
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                // 404 = 仓库私有或尚无 Release（公开 REST API 不可见）——给出可读提示而非裸 HTTP 码。
                if (code == 404) return@withTimeoutOrNull UpdateStatus.Failed("暂无公开发布")
                if (code !in 200..299) return@withTimeoutOrNull UpdateStatus.Failed("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name").ifBlank { o.optString("name") }
                val url = o.optString("html_url").ifBlank { "https://github.com/briqt/moke/releases/latest" }
                if (tag.isBlank()) return@withTimeoutOrNull UpdateStatus.Failed("无法解析版本")
                val latest = tag.removePrefix("v").removePrefix("V")
                if (isNewer(latest, current)) UpdateStatus.Available(tag, url)
                else UpdateStatus.UpToDate(current)
            } catch (e: Throwable) {
                // 捕获 Throwable（含 Error），否则未捕获异常会让上层 spinner 永转。
                UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { conn?.disconnect() }
            }
        } ?: UpdateStatus.Failed("超时")
    }

    /** 语义化版本数字段比较：a > b 返回 true。非数字段忽略。 */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
