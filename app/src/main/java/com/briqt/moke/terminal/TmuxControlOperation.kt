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

internal fun supportsTmuxObserver(client: SSHClient): Boolean = runCatching {
    val output = execTmuxChannel(client, TmuxRemote.capabilityCommand()) ?: return@runCatching false
    isTmuxObserverSupported(output)
}.getOrDefault(false)

internal fun isTmuxObserverSupported(output: String): Boolean {
    val lines = output.replace("\r\n", "\n").split('\n')
    // The final element is not a complete line. Do not accept a truncated record or a conflict.
    val records = lines.dropLast(1).filter { it.startsWith("__MOKE_TMUX_V1__|") }
    return records == listOf("__MOKE_TMUX_V1__|CAPABILITY|SUPPORTED") &&
        !lines.last().startsWith("__MOKE_TMUX_V1__|")
}

internal data class TmuxStartup(val command: String?, val nonce: String? = null)

/** Ordinary SSH, including Windows and host-configured commands, never gets a POSIX wrapper. */
internal fun sshTmuxStartup(
    startupCommand: String?,
    effectiveStartup: String?,
    observerNonce: String?,
    scripts: TmuxScripts?,
    observeDefaultShell: Boolean = false,
    capability: () -> Boolean,
): TmuxStartup = tmuxStartup(
    effectiveStartup, observerNonce.takeIf { observeDefaultShell || !startupCommand.isNullOrBlank() }, scripts, capability,
)

/** Mosh also uses the default-shell carrier. Failure must preserve the original nullable command. */
internal fun tmuxStartup(
    effectiveStartup: String?,
    observerNonce: String?,
    scripts: TmuxScripts?,
    capability: () -> Boolean,
): TmuxStartup {
    if (observerNonce == null || scripts == null) return TmuxStartup(effectiveStartup)
    return runCatching {
        if (capability()) TmuxStartup(TmuxRemote.launch(scripts, observerNonce, effectiveStartup), observerNonce)
        else TmuxStartup(effectiveStartup)
    }.getOrElse { TmuxStartup(effectiveStartup) }
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
