package com.briqt.moke.terminal

import java.net.URI

/** Only [url] may retain a check token; neither display text nor failure text contains URLs. */
internal data class TailscaleSshBanner(val message: String, val url: String?)

internal object TailscaleSshAuthText {
    private const val MAX_INPUT = 16_384
    private const val MAX_MESSAGE = 2_000
    private const val MAX_TARGET = 200
    private const val MAX_URL = 4_096
    private val escapes = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val whitespace = Regex("\\s+")
    private val https = Regex("(?i)(?<![a-z0-9+./:_@%\\-])https://[^\\s<>\"']+")
    private val links = Regex("(?i)\\b[a-z][a-z0-9+.-]*:[^\\s<>\"']+|//[^\\s<>\"']+")

    fun banner(raw: String): TailscaleSshBanner {
        val bounded = raw.take(MAX_INPUT)
        // Do not interpret a truncated token as a complete authentication link.
        val url = if (raw.length > MAX_INPUT) null else https.findAll(bounded)
            .map { it.value.trimEnd('.', ',', ';', ')', '}') }
            .firstOrNull(::isSafeUrl)
        val clean = clean(bounded)
        val message = whitespace.replace(links.replace(clean, ""), " ").trim().take(MAX_MESSAGE)
        return TailscaleSshBanner(message, url)
    }

    fun target(raw: String): String = whitespace.replace(clean(raw.take(MAX_INPUT)), " ").trim().take(MAX_TARGET)

    internal fun isSafeUrl(raw: String): Boolean {
        if (raw.length !in 1..MAX_URL || raw.any(::unsafeCharacter) || raw.any(Char::isWhitespace) || '\\' in raw) return false
        val uri = try { URI(raw) } catch (_: Exception) { return false }
        if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0
        ) return false
        // Browsers decode percent escapes; reject encoded controls and ambiguous backslashes too.
        return listOfNotNull(uri.authority, uri.path, uri.query, uri.fragment).none { part ->
            part.any(::unsafeCharacter) || '\\' in part
        }
    }

    private fun clean(raw: String): String = buildString {
        for (char in escapes.replace(raw, "")) {
            when {
                char.isWhitespace() -> append(' ')
                !unsafeCharacter(char) -> append(char)
            }
        }
    }

    private fun unsafeCharacter(char: Char): Boolean =
        Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt()
}
