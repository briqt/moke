package com.briqt.moke.terminal

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// type: connection-local directory metadata

/** Display/grouping context only. This must never select an SSH, SFTP or tmux operation target. */
data class SessionDirectory(val path: String, val targetKey: String, val targetLabel: String)

internal const val SESSION_PATH_LIMIT = 4096
internal const val SESSION_TITLE_LIMIT = 4096

internal fun safeSessionText(value: String, limit: Int): Boolean =
    value.length <= limit && value.none { it.isISOControl() || it == ' ' || it == ' ' } &&
        validSurrogates(value)

private fun validSurrogates(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) {
            if (i == value.length || !value[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}

/** A report's host is a claim by the terminal application, not authenticated remote identity. */
internal data class OscDirectoryReport(val host: String?, val path: String) {
    companion object {
        private const val URI_LIMIT = 16_384

        fun parse(raw: String): OscDirectoryReport? {
            if (!safeSessionText(raw, URI_LIMIT)) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (!uri.scheme.equals("file", ignoreCase = true) || uri.isOpaque ||
                uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) return null
            val authority = uri.rawAuthority
            // Reject malformed authorities, empty ports, userinfo and encoded DNS aliases.
            if (!authority.isNullOrEmpty() && (uri.host == null || authority != uri.host)) return null
            val rawPath = uri.rawPath ?: return null
            val path = decodePath(rawPath) ?: return null
            if (!path.startsWith('/') || !safeSessionText(path, SESSION_PATH_LIMIT)) return null
            return OscDirectoryReport(uri.host?.let(::directoryAddress), path)
        }

        private fun decodePath(raw: String): String? {
            val bytes = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '%') {
                    if (i + 2 >= raw.length) return null
                    val high = raw[i + 1].digitToIntOrNull(16) ?: return null
                    val low = raw[i + 2].digitToIntOrNull(16) ?: return null
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    val start = i
                    while (i < raw.length && raw[i] != '%') i++
                    bytes.write(raw.substring(start, i).toByteArray(Charsets.UTF_8))
                }
            }
            return runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            }.getOrNull()
        }
    }
}

/** Canonical spelling only: no DNS, address resolution, alias expansion or physical-host guesses. */
internal fun directoryAddress(address: String): String {
    val plain = address.trim().removeSurrounding("[", "]")
    val zone = plain.indexOf('%')
    return if (zone < 0) plain.lowercase(Locale.ROOT) else
        plain.substring(0, zone).lowercase(Locale.ROOT) + plain.substring(zone).replaceFirst("%25", "%")
}

internal class SessionDirectoryTarget(
    private val scope: TmuxScope,
    address: String,
    private val label: String,
) {
    private val address = directoryAddress(address)
    private fun key(vararg context: String): String =
        (listOf("session-directory-v1", scope.targetStamp, scope.networkEpoch.toString()) + context)
            .joinToString("") { "${it.length}:$it" }

    fun authenticated(path: String): SessionDirectory = SessionDirectory(path, key("target"), label)

    fun reported(report: OscDirectoryReport): SessionDirectory? {
        val host = report.host ?: return null
        // An unqualified/local report does not identify which machine owns the path.
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return null
        // Matching reports can share display groups; a group key never grants target authority.
        // Foreign host claims remain in their own context, with no DNS or alias guessing.
        return if (host == address) SessionDirectory(report.path, key("target"), label)
        else SessionDirectory(report.path, key("report", host), host)
    }
}

/** Small lifecycle holder shared by callbacks and observation; it never starts an observation. */
internal class SessionMetadata(private val scope: TmuxScope, private val target: SessionDirectoryTarget) {
    private val lock = Any()
    private val state = MutableStateFlow<SessionDirectory?>(null)
    val directory: StateFlow<SessionDirectory?> = state.asStateFlow()
    private var ended = false
    private var targetChanged = false
    private var tmux = TmuxUiState()
    private var osc: SessionDirectory? = null
    private var context: DirectoryContext? = null

    // Names and read generations are metadata, not a change of the observed object.
    private data class DirectoryContext(
        val owner: TmuxOwnerProof?, val server: TmuxServerId?, val sessionId: String?,
        val sessionCreated: Long?, val client: TmuxClientId?, val paneId: String?,
    )

    fun update(observed: TmuxUiState, alive: Boolean) = synchronized(lock) {
        if (!alive) { endLocked(); return@synchronized }
        if (ended) return@synchronized
        targetChanged = targetChanged || observed.targetChanged
        val verified = observed.snapshot?.let { it.verified && it.scope == scope } == true
        val binding = observed.currentBinding?.takeIf { it.scope == scope && verified }
        val pane = observed.currentPane?.takeIf { it.binding.scope == scope }
        val owner = binding?.owner ?: (observed.presence as? TmuxPresence.Shell)?.owner?.takeIf { verified }
        val next = owner?.let { DirectoryContext(it, binding?.server, binding?.session?.id,
            binding?.session?.created, binding?.client, pane?.pane?.id) }
        if (context != null && context != next) osc = null
        context = next
        tmux = observed
        publish()
    }

    fun onDirectory(rawUri: String) = synchronized(lock) {
        if (ended || targetChanged) return@synchronized
        osc = OscDirectoryReport.parse(rawUri)?.let(target::reported)
        publish()
    }

    fun end() = synchronized(lock) { endLocked() }

    private fun endLocked() {
        ended = true
        osc = null
        state.value = null
    }

    private fun publish() {
        state.value = when {
            ended || targetChanged -> null
            tmux.currentBinding != null -> tmux.currentPane?.takeIf { it.binding.scope == scope }
                ?.pane?.path?.let(target::authenticated)
            else -> osc
        }
    }
}
