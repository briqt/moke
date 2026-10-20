package com.briqt.moke.data

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A versioned, credential-free identity for the effective route, normally supplied by [Route.Ok]. */
object TmuxTargetStamp {
    private const val VERSION = "tmux-target-v1"

    fun of(host: Host, jump: Host?, network: NetworkEntry?): String {
        val fields = mutableListOf(VERSION, address(host.host), host.port.toString(), host.username)
        val jumpId = host.jumpHostId.takeIf { it.isNotBlank() }
        val actualJump = jump?.takeIf { it.id == jumpId }
        fields += jumpId.orEmpty()
        if (jumpId != null) {
            fields += if (actualJump == null) "missing-jump" else "jump"
            if (actualJump != null) {
                fields += listOf(address(actualJump.host), actualJump.port.toString(), actualJump.username)
            }
        }

        // A target's own network is inactive when a jump host supplies the first hop.
        val firstHop = if (jumpId == null) host else actualJump
        val networkId = firstHop?.networkId?.takeIf { it.isNotBlank() }
        fields += if (firstHop == null) "missing-first-hop" else "first-hop"
        fields += networkId.orEmpty()
        if (networkId != null) {
            val actualNetwork = network?.takeIf { it.id == networkId }
            fields += if (actualNetwork == null) "missing-network" else "network"
            if (actualNetwork != null) {
                fields += listOf(actualNetwork.kind.name, actualNetwork.revision.toString())
                fields += when (actualNetwork.kind) {
                    NetworkKind.PROXY -> listOf(
                        actualNetwork.proxyType.name,
                        address(actualNetwork.proxyHost),
                        actualNetwork.proxyPort.toString(),
                    )
                    NetworkKind.TAILSCALE -> listOf(controlUrl(actualNetwork.controlUrl))
                }
            }
        }

        // Length prefixes avoid delimiter collisions, including in user-controlled account names.
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "$VERSION:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun address(value: String): String {
        val trimmed = value.trim()
        val zone = trimmed.indexOf('%')
        // DNS / IPv6 hex is case-insensitive; an IPv6 interface scope need not be.
        return if (zone < 0) trimmed.lowercase(Locale.ROOT) else {
            trimmed.substring(0, zone).lowercase(Locale.ROOT) + trimmed.substring(zone)
        }
    }

    private fun controlUrl(value: String): String = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching value.trim()
        val host = uri.host?.let(::address) ?: return@runCatching value.trim()
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(uri.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
    }.getOrDefault(value.trim())
}
