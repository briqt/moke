package com.briqt.moke.data

sealed interface StoreResult<out T> {
    data class Saved<T>(val value: T) : StoreResult<T>
    data class Failed(val reason: StoreFailure) : StoreResult<Nothing>
}

enum class StoreFailure { UNREADABLE, ENCRYPTION, IO, MISSING, CONFLICT, DUPLICATE_NAME, TAILNET_LIMIT, INVALID }

const val MAX_TAILNET_ENTRIES = 8

internal data class ListEdit<T>(val items: List<T>, val saved: T)

/** Called inside the store transaction, never against a UI's stale list. */
internal fun prepareNetworkEdit(
    current: List<NetworkEntry>,
    entry: NetworkEntry,
    editing: Boolean,
): StoreResult<ListEdit<NetworkEntry>> {
    val old = current.firstOrNull { it.id == entry.id }
    if (editing && old == null) return StoreResult.Failed(StoreFailure.MISSING)
    if (!editing && old != null) return StoreResult.Failed(StoreFailure.CONFLICT)
    if (entry.id.isBlank() || entry.name.isBlank() || (old != null && old.kind != entry.kind)) {
        return StoreResult.Failed(StoreFailure.INVALID)
    }
    val name = entry.name.trim()
    if (current.any { it.id != entry.id && it.kind == entry.kind && it.name.trim().equals(name, ignoreCase = true) }) {
        return StoreResult.Failed(StoreFailure.DUPLICATE_NAME)
    }
    if (old == null && entry.kind == NetworkKind.TAILSCALE && current.count { it.kind == NetworkKind.TAILSCALE } >= MAX_TAILNET_ENTRIES) {
        return StoreResult.Failed(StoreFailure.TAILNET_LIMIT)
    }
    val changed = old != null && entry.kind == NetworkKind.PROXY && proxyConnectionChanged(old, entry)
    if (changed && old.revision == Int.MAX_VALUE) return StoreResult.Failed(StoreFailure.INVALID)
    val saved = entry.copy(name = name, revision = if (old == null) 1 else old.revision + if (changed) 1 else 0)
    val next = if (old == null) current + saved else current.map { if (it.id == saved.id) saved else it }
    return StoreResult.Saved(ListEdit(next, saved))
}

internal fun proxyConnectionChanged(a: NetworkEntry, b: NetworkEntry): Boolean =
    a.proxyType != b.proxyType || a.proxyHost != b.proxyHost || a.proxyPort != b.proxyPort ||
        a.username != b.username || a.password != b.password || a.extraCaPem != b.extraCaPem

/** Fields captured by a connection that must still match before it may remember a tmux session. */
internal fun sameTmuxTarget(a: Host, b: Host): Boolean =
    a.id == b.id && a.host == b.host && a.port == b.port && a.username == b.username &&
        a.jumpHostId == b.jumpHostId && (a.jumpHostId.isNotBlank() || a.networkId == b.networkId)

internal fun prepareHostEdit(current: List<Host>, entry: Host, editing: Boolean): StoreResult<ListEdit<Host>> {
    val old = current.firstOrNull { it.id == entry.id }
    if (editing && old == null) return StoreResult.Failed(StoreFailure.MISSING)
    if (!editing && old != null) return StoreResult.Failed(StoreFailure.CONFLICT)
    val memory = when {
        entry.persistence != SessionPersistence.TMUX -> null
        old == null -> entry
        old.persistence == SessionPersistence.TMUX && sameTmuxTarget(old, entry) -> old
        else -> null
    }
    val saved = entry.copy(
        lastConnectedAt = old?.lastConnectedAt ?: entry.lastConnectedAt,
        tmuxSessionName = memory?.tmuxSessionName.orEmpty(),
        tmuxTargetStamp = memory?.tmuxTargetStamp.orEmpty(),
    )
    return StoreResult.Saved(ListEdit(if (old == null) current + saved else current.map { if (it.id == saved.id) saved else it }, saved))
}
