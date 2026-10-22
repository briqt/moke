package com.briqt.moke.ui

/** Only user values belong in a draft; automatic values are resolved for display each time. */
internal data class SessionTitleDraft(val prefix: String, val title: String) {
    fun preview(automaticPrefix: String, automaticTitle: String): String = listOf(
        prefix.trim().ifEmpty { automaticPrefix.trim() },
        title.trim().ifEmpty { automaticTitle.trim() },
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}
