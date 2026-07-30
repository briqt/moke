package com.briqt.moke.update

import android.content.Context
import com.briqt.moke.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.math.BigInteger
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

    suspend fun check(current: String, context: Context): UpdateStatus = withContext(Dispatchers.IO) {
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
                if (code == 404) return@withTimeoutOrNull UpdateStatus.Failed(context.getString(R.string.update_none))
                if (code !in 200..299) return@withTimeoutOrNull UpdateStatus.Failed("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name").ifBlank { o.optString("name") }
                val url = o.optString("html_url").ifBlank { "https://github.com/briqt/moke/releases/latest" }
                if (tag.isBlank()) return@withTimeoutOrNull UpdateStatus.Failed(context.getString(R.string.update_parse_failed))
                val latest = tag.removePrefix("v").removePrefix("V")
                if (isNewer(latest, current)) UpdateStatus.Available(tag, url)
                else UpdateStatus.UpToDate(current)
            } catch (e: Throwable) {
                // 捕获 Throwable（含 Error），否则未捕获异常会让上层 spinner 永转。
                UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { conn?.disconnect() }
            }
        } ?: UpdateStatus.Failed(context.getString(R.string.update_timeout))
    }

    /** SemVer 优先级比较：a > b 返回 true；正式版高于相同核心版本的预发布版。 */
    fun isNewer(a: String, b: String): Boolean {
        val left = SemVer.parse(a) ?: return false
        val right = SemVer.parse(b) ?: return false
        return left.compareTo(right) > 0
    }

    private data class SemVer(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
        val prerelease: List<String>?,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

            if (prerelease == null && other.prerelease == null) return 0
            if (prerelease == null) return 1
            if (other.prerelease == null) return -1

            for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
                val left = prerelease[i]
                val right = other.prerelease[i]
                val leftNumber = left.toBigIntegerOrNull()
                val rightNumber = right.toBigIntegerOrNull()
                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return prerelease.size.compareTo(other.prerelease.size)
        }

        companion object {
            private val pattern = Regex(
                """^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?(?:\+[0-9A-Za-z.-]+)?$"""
            )

            fun parse(value: String): SemVer? {
                val match = pattern.matchEntire(value.trim()) ?: return null
                return SemVer(
                    major = match.groupValues[1].toBigInteger(),
                    minor = match.groupValues[2].toBigInteger(),
                    patch = match.groupValues[3].toBigInteger(),
                    prerelease = match.groupValues[4]
                        .takeIf { it.isNotEmpty() }
                        ?.split('.'),
                )
            }
        }
    }
}
