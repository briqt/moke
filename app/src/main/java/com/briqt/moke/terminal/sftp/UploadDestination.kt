package com.briqt.moke.terminal.sftp

import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.data.Route
import java.util.EnumSet
import net.schmizz.sshj.sftp.OpenMode

/** Non-secret destination identity captured by an upload's check, not the current visible page. */
@ConsistentCopyVisibility
data class UploadDestination private constructor(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val jumpHost: String?,
    private val jumpPort: Int?,
    private val jumpUsername: String?,
    private val networkId: String?,
    private val networkKind: NetworkKind?,
    private val networkRevision: Int?,
    private val proxyHost: String?,
    private val proxyPort: Int?,
    private val proxyType: ProxyType?,
    private val controlUrl: String?,
) {
    companion object {
        fun from(host: Host, jumpHost: Host?, network: NetworkEntry?): UploadDestination = UploadDestination(
            host.host, host.port, host.username, jumpHost?.host, jumpHost?.port, jumpHost?.username,
            network?.id, network?.kind, network?.revision,
            network?.takeIf { it.kind == NetworkKind.PROXY }?.proxyHost,
            network?.takeIf { it.kind == NetworkKind.PROXY }?.proxyPort,
            network?.takeIf { it.kind == NetworkKind.PROXY }?.proxyType,
            network?.takeIf { it.kind == NetworkKind.TAILSCALE }?.controlUrl,
        )
    }
    fun matches(route: Route.Ok): Boolean = this == from(route.host, route.jump, route.network)
}

internal fun uploadDestinationMatches(destination: UploadDestination?, route: Route.Ok): Boolean =
    destination?.matches(route) == true

/** Upload metadata is a basename, never an absolute/relative path supplied by a document provider. */
fun validUploadName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

internal fun uploadOpenModes(offset: Long, allowOverwrite: Boolean): EnumSet<OpenMode> = when {
    offset > 0 -> EnumSet.of(OpenMode.WRITE)
    allowOverwrite -> EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
    else -> EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
}
