package com.briqt.moke.terminal

import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient

/** A mutation owns its SSH connection; closing the data channel cannot discard its reply. */
internal fun execTmuxOwned(
    connector: SshConnector,
    host: Host,
    jumpHost: Host?,
    network: NetworkEntry?,
    command: String,
    dialHost: String? = null,
): String? = connector.connect(host, jumpHost, network, dialHost = dialHost).use { connected ->
    execTmuxChannel(connected.client, command)
}

/** No PTY, no retry, and no success from partial output, nonzero status or a missing close. */
internal fun execTmuxChannel(client: SSHClient, command: String): String? = client.startSession().use { session ->
    session.exec(command).use { child ->
        child.join(10, TimeUnit.SECONDS)
        if (child.isOpen || child.exitStatus != 0) null else readTmuxResult(child.inputStream)
    }
}

internal fun supportsTmuxObserver(client: SSHClient): TmuxObserverCapability = runCatching {
    val output = execTmuxChannel(client, TmuxRemote.capabilityCommand())
        ?: return@runCatching TmuxObserverCapability.Failure
    parseTmuxObserverCapability(output)
}.getOrDefault(TmuxObserverCapability.Failure)

internal fun parseTmuxObserverCapability(output: String): TmuxObserverCapability {
    if (output.length > 262_144) return TmuxObserverCapability.Failure
    val normalized = output.replace("\r\n", "\n")
    if (normalized.any { it.isISOControl() && it != '\n' }) return TmuxObserverCapability.Failure
    val lines = normalized.split('\n')
    // The final element is not a complete line. Truncation/conflict is Failure, not Unsupported.
    val records = lines.dropLast(1).filter { it.startsWith("__MOKE_TMUX_V1__|") }
    if (records.size != 1 || lines.last().startsWith("__MOKE_TMUX_V1__|")) return TmuxObserverCapability.Failure
    return when (records.single()) {
        "__MOKE_TMUX_V1__|CAPABILITY|SUPPORTED" -> TmuxObserverCapability.Supported
        "__MOKE_TMUX_V1__|CAPABILITY|UNSUPPORTED" -> TmuxObserverCapability.Unsupported
        else -> TmuxObserverCapability.Failure
    }
}

internal data class TmuxStartup(
    val command: String?,
    val nonce: String? = null,
    val capability: TmuxObserverCapability? = null,
)

/** Ordinary SSH, including Windows and host-configured commands, never gets a POSIX wrapper. */
internal fun sshTmuxStartup(
    startupCommand: String?,
    effectiveStartup: String?,
    observerNonce: String?,
    scripts: TmuxScripts?,
    observeDefaultShell: Boolean = false,
    capability: () -> TmuxObserverCapability,
): TmuxStartup = tmuxStartup(
    effectiveStartup, observerNonce.takeIf { observeDefaultShell || !startupCommand.isNullOrBlank() }, scripts, capability,
)

/** Mosh also uses the default-shell carrier. Failure must preserve the original nullable command. */
internal fun tmuxStartup(
    effectiveStartup: String?,
    observerNonce: String?,
    scripts: TmuxScripts?,
    capability: () -> TmuxObserverCapability,
): TmuxStartup {
    if (observerNonce == null || scripts == null) return TmuxStartup(effectiveStartup)
    return runCatching {
        val result = capability()
        if (result == TmuxObserverCapability.Supported)
            TmuxStartup(TmuxRemote.launch(scripts, observerNonce, effectiveStartup), observerNonce, result)
        else TmuxStartup(effectiveStartup, capability = result)
    }.getOrElse { TmuxStartup(effectiveStartup, capability = TmuxObserverCapability.Failure) }
}

internal fun readTmuxResult(input: InputStream, limit: Int = 262_144): String? {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) return null
        output.write(buffer, 0, count)
    }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }.getOrNull()
}
