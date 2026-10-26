package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

// type: visit-scoped tmux sheet presentation

/** The caller observes the actual SheetState, not composition or its target animation value. */
internal suspend fun reportTmuxSheetPresented(visible: Flow<Boolean>, onPresented: () -> Unit) {
    visible.first { it }
    onPresented()
}

internal enum class TmuxSheetMode { SELECT, MANAGE }

internal data class TmuxSheetRequest(
    val source: TmuxScope,
    val visit: Any,
    val serial: Long,
    val mode: TmuxSheetMode,
    val presented: Boolean = false,
) {
    /** Connection readiness, not a successful tmux snapshot, admits the existing checking sheet. */
    fun visibleFor(source: TmuxScope, visit: Any, dataReady: Boolean, alive: Boolean, foreground: Boolean): Boolean =
        this.source == source && this.visit === visit && dataReady && (alive || mode == TmuxSheetMode.MANAGE) &&
            (foreground || presented)
}

/** Main-thread owner of presentation only. Remote observations never create a request. */
internal class TmuxSheetController {
    private val _request = MutableStateFlow<TmuxSheetRequest?>(null)
    val request: StateFlow<TmuxSheetRequest?> = _request.asStateFlow()
    private val preparedSelections = mutableSetOf<TmuxScope>()
    private val temporarySources = mutableSetOf<TmuxScope>()
    private var source: TmuxScope? = null
    private var visit: Any? = null
    private var serial = 0L

    fun prepareSelection(source: TmuxScope) {
        preparedSelections += source
        temporarySources += source
    }

    /** Consume the opening result even if its navigation visit has already been abandoned. */
    fun opened(source: TmuxScope, visit: Any?) {
        val select = preparedSelections.remove(source)
        if (visit == null) {
            temporarySources -= source
            return
        }
        enter(source, visit)
        if (select) _request.value = TmuxSheetRequest(source, visit, ++serial, TmuxSheetMode.SELECT)
    }

    fun enter(source: TmuxScope, visit: Any) {
        if (isVisit(source, visit)) return
        clearVisit()
        // Merely coming back through the session list cannot replay an unconsumed opening result.
        if (preparedSelections.remove(source)) temporarySources -= source
        this.source = source
        this.visit = visit
    }

    fun leave(source: TmuxScope, visit: Any) {
        if (isVisit(source, visit)) clearVisit()
    }

    fun manage(source: TmuxScope, visit: Any) {
        if (!isVisit(source, visit)) return
        preparedSelections -= source
        val current = _request.value
        // Take over an automatic request in place, retaining its temporary-source ownership.
        _request.value = current?.copy(mode = TmuxSheetMode.MANAGE)
            ?: TmuxSheetRequest(source, visit, ++serial, TmuxSheetMode.MANAGE)
    }

    fun owns(request: TmuxSheetRequest): Boolean = _request.value?.let {
        it.serial == request.serial && it.source == request.source && it.visit === request.visit
    } == true

    fun presented(request: TmuxSheetRequest) {
        if (owns(request) && _request.value?.presented == false) _request.value = _request.value?.copy(presented = true)
    }

    /** Preserve an already-visible sheet/editor, but never open one on a background result. */
    fun background(source: TmuxScope, visit: Any) {
        if (!isVisit(source, visit)) return
        _request.value?.takeUnless { it.presented }?.let(::dismiss)
    }

    fun dismiss(request: TmuxSheetRequest) {
        if (!owns(request)) return
        _request.value = null
        temporarySources -= request.source
    }

    /** Only a still-owned successful choice may dispose its own freshly-created source. */
    fun finish(request: TmuxSheetRequest, destinationId: String): TmuxScope? {
        if (!owns(request)) return null
        _request.value = null
        val temporary = temporarySources.remove(request.source)
        return request.source.takeIf { temporary && destinationId != it.connectionId }
    }

    /** EOF withdraws the initial choice, not an explicit management sheet or its editor draft. */
    fun dataEnded(source: TmuxScope) {
        preparedSelections -= source
        val current = _request.value
        if (current?.source == source && current.mode == TmuxSheetMode.SELECT) _request.value = null
    }

    fun invalidate(source: TmuxScope) {
        if (this.source == source) clearVisit()
        preparedSelections -= source
        temporarySources -= source
    }

    fun retainSources(sources: Set<TmuxScope>) {
        if (source != null && source !in sources) clearVisit()
        preparedSelections.retainAll(sources)
        temporarySources.retainAll(sources)
    }

    private fun isVisit(source: TmuxScope, visit: Any): Boolean = this.source == source && this.visit === visit

    private fun clearVisit() {
        source?.let { temporarySources -= it }
        _request.value = null
        source = null
        visit = null
    }
}
