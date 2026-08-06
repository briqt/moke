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

/** GitHub Releases 里的一条发布（只取判定所需字段）。 */
data class ReleaseEntry(
    val tag: String,
    val url: String,
    val prerelease: Boolean,
    val draft: Boolean,
)

/** 从 GitHub Releases 查最新版并与当前版本比对。 */
object UpdateChecker {
    const val REPO_URL = "https://github.com/briqt/moke"
    private const val LATEST_API = "https://api.github.com/repos/briqt/moke/releases/latest"
    // 含预发布时必须用列表接口：/releases/latest 按 GitHub 定义只返回正式版。
    private const val LIST_API = "https://api.github.com/repos/briqt/moke/releases?per_page=20"

    suspend fun check(
        current: String,
        context: Context,
        includePrerelease: Boolean = false,
    ): UpdateStatus = withContext(Dispatchers.IO) {
        // 硬超时兜底：任何慢网络/卡住都在 12s 内收敛，spinner 不会永转。
        withTimeoutOrNull(12_000) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(if (includePrerelease) LIST_API else LATEST_API).openConnection() as HttpURLConnection).apply {
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
                val entries = if (includePrerelease) parseList(body) else listOf(parseOne(JSONObject(body)))
                val picked = pickLatest(entries, includePrerelease)
                    ?: return@withTimeoutOrNull UpdateStatus.Failed(context.getString(R.string.update_parse_failed))
                val latest = picked.tag.removePrefix("v").removePrefix("V")
                if (isNewer(latest, current)) UpdateStatus.Available(picked.tag, picked.url)
                else UpdateStatus.UpToDate(current)
            } catch (e: Throwable) {
                // 捕获 Throwable（含 Error），否则未捕获异常会让上层 spinner 永转。
                UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { conn?.disconnect() }
            }
        } ?: UpdateStatus.Failed(context.getString(R.string.update_timeout))
    }

    private fun parseOne(o: JSONObject) = ReleaseEntry(
        tag = o.optString("tag_name").ifBlank { o.optString("name") },
        url = o.optString("html_url").ifBlank { "$REPO_URL/releases" },
        prerelease = o.optBoolean("prerelease", false),
        draft = o.optBoolean("draft", false),
    )

    private fun parseList(body: String): List<ReleaseEntry> {
        val arr = org.json.JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { parseOne(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /**
     * 从若干发布里挑该提示哪一个。
     *
     * 不信任 GitHub 的返回顺序（按创建时间排，补发旧版本就会错位），一律按 SemVer 取最大者；
     * draft 永不参与；[includePrerelease] 为假时排除预发布。
     */
    fun pickLatest(entries: List<ReleaseEntry>, includePrerelease: Boolean): ReleaseEntry? {
        val usable = entries
            .filterNot { it.draft }
            .filter { includePrerelease || !it.prerelease }
            .filter { it.tag.isNotBlank() }
        val comparable = usable.mapNotNull { e ->
            SemVer.parse(e.tag.removePrefix("v").removePrefix("V"))?.let { e to it }
        }
        // 没有一个 tag 能解析（命名异常）时，退回第一条可用项，总比什么都不提示好。
        return comparable.maxByOrNull { it.second }?.first ?: usable.firstOrNull()
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
