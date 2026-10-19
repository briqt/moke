package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import java.net.URI
import java.security.cert.CertificateFactory
import java.util.Base64

internal fun defaultPort(type: ProxyType): Int = when (type) {
    ProxyType.SOCKS5, ProxyType.SOCKS4 -> 1080
    ProxyType.HTTP -> 8080
    ProxyType.HTTPS -> 443
}

internal fun changedProxyPort(text: String, old: ProxyType, new: ProxyType): String =
    if (text == defaultPort(old).toString()) defaultPort(new).toString() else text

/** Local syntax checks only; saving a draft never performs DNS or connects to a server. */
internal object FormValidation {
    fun port(text: String, minimum: Int = 1): Int? {
        val value = text.trim()
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()?.takeIf { it in minimum..65535 }
    }

    fun host(text: String): Boolean = text.trim().let { value ->
        value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 253 &&
            value.none { it.isWhitespace() || it in "/\\@\u0000" }
    }

    fun proxyHost(text: String): Boolean = text.trim().let { value ->
        value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 253 &&
            value.none { it in " /\\@\u0000\r\n" }
    }

    fun controlUrl(text: String): Boolean {
        val value = text.trim()
        if (value.toByteArray(Charsets.UTF_8).size > 4096) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(":")
    }

    fun deviceName(text: String, default: String): Boolean = text.trim().ifEmpty { default }.let { value ->
        value.toByteArray(Charsets.UTF_8).size in 1..63 && value.none { it in "\u0000\r\n\t ./\\" }
    }

    fun authKey(text: String): Boolean = text.trim().let { value ->
        value.toByteArray(Charsets.UTF_8).size <= 4096 && value.none { it in "\u0000\r\n\t " }
    }

    fun proxyCredential(text: String, type: ProxyType, username: Boolean): Boolean =
        text.toByteArray(Charsets.UTF_8).size <= 255 && text.none { it in "\u0000\r\n" } &&
            !(username && type in listOf(ProxyType.HTTP, ProxyType.HTTPS) && ':' in text)

    fun extraCa(text: String): Boolean {
        if (text.isBlank()) return true
        val blocks = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").findAll(text).toList()
        if (blocks.isEmpty()) return false
        // Match Go's PEM acceptance without adding expiry, CA or trust-policy checks.
        return blocks.any { block ->
            runCatching {
                val der = Base64.getDecoder().decode(block.groupValues[1].filterNot(Char::isWhitespace))
                CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
            }.isSuccess
        }
    }

    fun duplicateName(name: String, id: String, kind: NetworkKind, entries: List<NetworkEntry>): Boolean =
        entries.any { it.id != id && it.kind == kind && it.name.trim().equals(name.trim(), ignoreCase = true) }

    fun tmuxName(text: String): Boolean = text.trim().let { value ->
        value.isNotEmpty() && value.none { it == '.' || it == ':' || it.isISOControl() }
    }
}
