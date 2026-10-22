package com.briqt.moke.ui

import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.Host

internal data class SessionGroupLabel(val key: String, val label: String)

internal fun sessionGroupLabel(
    groupBy: GroupBy,
    host: Host,
    targetKey: String,
    directoryPath: String? = null,
    directoryTargetKey: String? = null,
    directoryTargetLabel: String? = null,
): SessionGroupLabel = when (groupBy) {
    GroupBy.NONE -> SessionGroupLabel("", "")
    GroupBy.HOST -> SessionGroupLabel(groupKey("host", host.displayName), host.displayName)
    GroupBy.CONNECTION -> SessionGroupLabel(groupKey("connection", host.group), host.group)
    GroupBy.PROJECT -> {
        val path = directoryPath?.takeIf { it.isNotEmpty() }
        val target = if (path != null) directoryTargetKey ?: targetKey else targetKey
        val label = if (path != null) directoryTargetLabel ?: host.displayName else host.displayName
        SessionGroupLabel(groupKey("directory", target, path.orEmpty()),
            if (path == null) label else "$label $path")
    }
}

private fun groupKey(vararg parts: String): String =
    parts.joinToString("") { "${it.length}:$it" }

/** Reconcile a drag snapshot with live IDs without moving newly added or unrelated items. */
internal fun mergeSessionOrder(current: List<String>, requested: List<String>): List<String> {
    val currentIds = current.toSet()
    val valid = requested.filter { it in currentIds }.distinct()
    val moved = valid.toSet()
    val iterator = valid.iterator()
    return current.map { if (it in moved) iterator.next() else it }
}
