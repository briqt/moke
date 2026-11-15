package com.briqt.moke.sync

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 远端备份文件名：`moke_<yyyyMMdd-HHmmss>[-n]_<设备名>_<平台>_<版本>.mokesync`。
 *
 * 时间排第一：任何 WebDAV 网页或文件管理器按名称排序就等于按时间排序；列表只靠解析文件名展示，
 * 不必下载解密。文件名里的这几项是明文（服务商看得到），配置内容全部在加密正文里。
 */
object BackupName {
    const val EXTENSION = ".mokesync"
    const val PLATFORM_ANDROID = "android"
    private const val PREFIX = "moke"
    private const val MAX_DEVICE = 32
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val timePart = Regex("""(\d{8}-\d{6})(?:-(\d{1,3}))?""")

    /** [suffix] > 1 时追加在秒之后，只在同一秒内重名时使用。 */
    fun build(time: LocalDateTime, device: String, version: String, platform: String = PLATFORM_ANDROID, suffix: Int = 1): String {
        val stamp = timeFormat.format(time) + if (suffix > 1) "-$suffix" else ""
        return listOf(PREFIX, stamp, sanitize(device), sanitize(platform), sanitize(version)).joinToString("_") + EXTENSION
    }

    /** 去掉分隔符、路径字符和控制字符；空值记为 unknown。 */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim().map { c ->
            when {
                c == '_' || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' ||
                    c == '<' || c == '>' || c == '|' || c == '%' || c.isWhitespace() || c.isISOControl() -> '-'
                else -> c
            }
        }.joinToString("").trim('-', '.')
        val bounded = if (cleaned.codePointCount(0, cleaned.length) > MAX_DEVICE) {
            cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_DEVICE))
        } else cleaned
        return bounded.ifEmpty { "unknown" }
    }

    fun isBackup(name: String): Boolean = name.endsWith(EXTENSION) && name.length > EXTENSION.length

    /** 解析不出约定格式时返回 null；调用方仍可按原文件名展示。 */
    fun parse(name: String): Parsed? {
        if (!isBackup(name)) return null
        val parts = name.removeSuffix(EXTENSION).split('_')
        if (parts.size != 5 || parts[0] != PREFIX) return null
        val m = timePart.matchEntire(parts[1]) ?: return null
        val time = runCatching { LocalDateTime.parse(m.groupValues[1], timeFormat) }.getOrNull() ?: return null
        if (parts[2].isEmpty() || parts[3].isEmpty() || parts[4].isEmpty()) return null
        return Parsed(time, parts[2], parts[3], parts[4])
    }

    data class Parsed(val time: LocalDateTime, val device: String, val platform: String, val version: String)
}
