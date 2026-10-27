package com.briqt.moke.net

import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import java.util.Locale
import org.json.JSONObject

/** A visible peer, not a promise that its SSH service or a particular account is accessible. */
data class TailnetDevice(
    val id: String,
    val hostname: String,
    val dnsName: String,
    val ips: List<String>,
    val os: String,
    val online: Boolean,
    val tailscaleSsh: Boolean,
) {
    val address: String get() = dnsName.trimEnd('.').ifBlank { ips.firstOrNull().orEmpty() }
    val name: String get() = hostname.ifBlank { dnsName.trimEnd('.').substringBefore('.') }.ifBlank { address }
}

internal fun parseTailnetDevices(json: String): List<TailnetDevice> {
    val peers = JSONObject(json).getJSONArray("peers")
    return (0 until peers.length()).map { index ->
        val peer = peers.getJSONObject(index)
        val ips = peer.getJSONArray("ips")
        TailnetDevice(
            id = peer.getString("id"), hostname = peer.optString("hostname"), dnsName = peer.optString("dnsName").trimEnd('.'),
            ips = (0 until ips.length()).map { ips.getString(it) }, os = peer.optString("os"),
            online = peer.optBoolean("online"), tailscaleSsh = peer.optBoolean("tailscaleSsh"),
        )
    }.filter { it.id.isNotEmpty() && it.address.isNotBlank() }.distinctBy { it.id }
        .sortedWith(compareBy<TailnetDevice>({ !it.online }, { it.name.lowercase(Locale.ROOT) }, { it.address }, { it.id }))
}

internal fun filterTailnetDevices(devices: List<TailnetDevice>, query: String): List<TailnetDevice> {
    val needle = query.trim()
    if (needle.isEmpty()) return devices
    return devices.filter { device ->
        listOf(device.hostname, device.dnsName, device.os).any { it.contains(needle, ignoreCase = true) } ||
            device.ips.any { it.contains(needle, ignoreCase = true) }
    }
}

/** Selecting a peer changes the draft only; credentials and the system username are never inferred. */
internal fun selectTailnetDevice(
    draft: Host,
    networkId: String,
    device: TailnetDevice,
    newHost: Boolean,
    automaticLabel: String?,
    authenticationChosen: Boolean,
): Host {
    require(device.address.isNotBlank())
    val suggestSsh = newHost && !authenticationChosen && draft.password.isEmpty() && draft.privateKeyPem.isBlank() && device.tailscaleSsh
    return draft.copy(
        networkId = networkId,
        host = device.address,
        label = if (draft.label.isBlank() || draft.label == automaticLabel) device.name else draft.label,
        port = if (suggestSsh) 22 else draft.port,
        authType = if (suggestSsh) AuthType.TAILSCALE_SSH else draft.authType,
    )
}
