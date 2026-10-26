package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxCoordinator
import com.briqt.moke.terminal.TmuxIntent
import com.briqt.moke.terminal.TmuxObservation
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxPresence
import com.briqt.moke.terminal.TmuxProbeKind
import com.briqt.moke.terminal.TmuxScope
import com.briqt.moke.terminal.TmuxScripts
import com.briqt.moke.terminal.TmuxServerId
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.terminal.TmuxSnapshot
import com.briqt.moke.terminal.TmuxUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

// type: visit-scoped tmux presentation JVM regression tests
class TmuxSheetControllerTest {
    private val source = TmuxScope("target-a", "connection-a", 1)
    private val other = TmuxScope("target-b", "connection-b", 1)
    private val visit = Any()

    private fun automatic(controller: TmuxSheetController = TmuxSheetController()): Pair<TmuxSheetController, TmuxSheetRequest> {
        controller.prepareSelection(source)
        controller.opened(source, visit)
        return controller to controller.request.value!!
    }

    private fun TmuxSheetRequest.visible(
        ready: Boolean = true,
        alive: Boolean = true,
        foreground: Boolean = true,
        source: TmuxScope = this@TmuxSheetControllerTest.source,
        visit: Any = this@TmuxSheetControllerTest.visit,
    ) = visibleFor(source, visit, ready, alive, foreground)

    @Test fun preparedConnectionPresentsCheckingImmediatelyAtDataReady() {
        val controller = TmuxSheetController()
        controller.prepareSelection(source)
        assertNull(controller.request.value)
        controller.opened(source, visit)
        val request = controller.request.value!!
        assertEquals(TmuxSheetMode.SELECT, request.mode)
        assertFalse(request.visible(ready = false)) // Includes connection and necessary TOFU.
        assertTrue(request.visible())
        assertFalse(request.visible(alive = false))
    }

    @Test fun readinessDoesNotWaitForFirstObservationAndResultsUpdateInPlace() = runBlocking {
        val (controller, request) = automatic()
        val result = CompletableDeferred<String?>()
        val state = MutableStateFlow(TmuxUiState())
        var ready = false
        var output: suspend () -> String? = { result.await() }
        val coordinator = TmuxCoordinator(source, TmuxIntent.None, TmuxScripts("observer", "probe"),
            object : TmuxCoordinator.Bridge {
                override suspend fun observe(command: String): String? = output()
                override suspend fun operation(command: String): String? = error("not an action")
            }, { null }, false, { ready }, { true }, state, { it.name },
            observationParser = { _, _ -> TmuxObservation(TmuxProbeKind.NO_SERVER,
                peerBootId = "12345678-1234-1234-1234-123456789abc", defaultSocketPath = "/tmp/tmux-1000/default") })
        assertFalse(request.visible(ready = ready))
        ready = true
        val read = async(start = CoroutineStart.UNDISPATCHED) { coordinator.refresh() }
        assertEquals(TmuxPhase.CHECKING, state.value.phase)
        assertNull(state.value.snapshot)
        assertTrue(state.value.refreshing)
        assertTrue(request.visible(ready = ready))
        controller.presented(request)
        val shown = controller.request.value!!
        result.complete(null)
        read.await()
        assertEquals(TmuxPhase.ERROR, state.value.phase)
        assertNull(state.value.snapshot)
        assertEquals(shown, controller.request.value)
        assertTrue(shown.visible())
        // A second real read is valid, but neither success nor failure chooses presentation.
        output = { "response" }
        coordinator.refresh()
        assertTrue(state.value.snapshot?.verified == true)
        assertEquals(shown, controller.request.value)
        controller.dismiss(shown)
        coordinator.refresh()
        assertNull(controller.request.value)
    }

    @Test fun manualTakeoverBeforeReadyUsesOneRequestAndRetainsSourceOwnership() {
        val (controller, automatic) = automatic()
        controller.manage(source, visit)
        val manual = controller.request.value!!
        assertEquals(TmuxSheetMode.MANAGE, manual.mode)
        assertEquals(automatic.serial, manual.serial)
        assertFalse(manual.visible(ready = false))
        assertTrue(manual.visible())
        controller.opened(source, visit) // A repeated opening result cannot replay SELECT.
        assertEquals(manual, controller.request.value)
        assertEquals(source, controller.finish(manual, other.connectionId))
        assertNull(controller.request.value)
    }

    @Test fun manualTakeoverAfterPresentationKeepsTheSamePresentation() {
        val (controller, automatic) = automatic()
        controller.presented(automatic)
        controller.manage(source, visit)
        val manual = controller.request.value!!
        assertTrue(manual.presented)
        assertEquals(automatic.serial, manual.serial)
        controller.manage(source, visit)
        assertEquals(manual, controller.request.value)
    }

    @Test fun dismissalBeforeAndAfterReadinessCannotBeReplayed() {
        for (ready in listOf(false, true)) {
            val (controller, request) = automatic()
            assertEquals(ready, request.visible(ready = ready))
            controller.dismiss(request)
            controller.enter(source, visit)
            controller.opened(source, visit)
            assertNull(controller.request.value)
            assertNull(controller.finish(request, other.connectionId))
            controller.manage(source, visit)
            assertNull(controller.finish(controller.request.value!!, other.connectionId))
        }
    }

    @Test fun leavingBeforeOrAfterReadyAndReturningNeverReplays() {
        for (ready in listOf(false, true)) {
            val (controller, request) = automatic()
            assertEquals(ready, request.visible(ready = ready))
            controller.leave(source, visit)
            assertNull(controller.request.value)
            val returning = Any()
            controller.enter(source, returning)
            assertNull(controller.request.value)
            assertFalse(request.visible(visit = returning))
            assertNull(controller.finish(request, other.connectionId))
            controller.manage(source, returning)
            assertNull(controller.finish(controller.request.value!!, other.connectionId))
        }
    }

    @Test fun switchingSessionsRejectsLateResultsAndOldDisposal() {
        val (controller, old) = automatic()
        val otherVisit = Any()
        controller.enter(other, otherVisit)
        controller.manage(other, otherVisit)
        val current = controller.request.value!!
        controller.leave(source, visit)
        controller.dismiss(old)
        controller.presented(old)
        assertNull(controller.finish(old, other.connectionId))
        assertEquals(current, controller.request.value)
        assertFalse(old.visible(source = other, visit = otherVisit))
        controller.enter(source, Any())
        assertNull(controller.request.value)
    }

    @Test fun equalButDistinctVisitTokensAreDifferentAccessesForTheSameConnection() {
        data class Visit(val id: Int)
        val first = Visit(1)
        val second = Visit(1)
        val controller = TmuxSheetController()
        controller.prepareSelection(source)
        controller.opened(source, first)
        val old = controller.request.value!!
        controller.enter(source, second)
        controller.manage(source, second)
        val current = controller.request.value!!
        assertNotEquals(old.serial, current.serial)
        assertFalse(old.visible(visit = second))
        controller.leave(source, first)
        controller.dismiss(old)
        assertNull(controller.finish(old, other.connectionId))
        assertEquals(current, controller.request.value)
        assertNull(controller.finish(current, other.connectionId))
    }

    @Test fun connectionScopeCannotBeSubstitutedByAnEqualIdFromAnotherTargetOrEpoch() {
        val (controller, request) = automatic()
        for (replacement in listOf(source.copy(targetStamp = "edited"), source.copy(networkEpoch = 2))) {
            assertFalse(request.visible(source = replacement))
            controller.manage(replacement, visit)
            assertEquals(request, controller.request.value)
            controller.invalidate(replacement)
            assertEquals(request, controller.request.value)
        }
    }

    @Test fun lateBackgroundOpeningDoesNotStealCurrentVisitOrReplayOnReturn() {
        val controller = TmuxSheetController()
        controller.prepareSelection(source)
        controller.enter(other, visit)
        controller.manage(other, visit)
        val current = controller.request.value!!
        controller.opened(source, null)
        assertEquals(current, controller.request.value)
        val returning = Any()
        controller.enter(source, returning)
        assertNull(controller.request.value)
        controller.manage(source, returning)
        assertNull(controller.finish(controller.request.value!!, other.connectionId))
    }

    @Test fun unconsumedOpeningCannotReplayThroughTheSessionList() {
        val controller = TmuxSheetController()
        controller.prepareSelection(source)
        controller.enter(source, visit)
        assertNull(controller.request.value)
        controller.opened(source, visit)
        assertNull(controller.request.value)
        controller.manage(source, visit)
        assertNull(controller.finish(controller.request.value!!, other.connectionId))
    }

    @Test fun backgroundReadinessCannotOpenAPendingSheetAndForegroundDoesNotReplayIt() {
        val (controller, request) = automatic()
        assertFalse(request.visible(foreground = false))
        controller.background(source, visit)
        assertNull(controller.request.value)
        controller.enter(source, visit)
        assertNull(controller.request.value)
        controller.presented(request)
        assertNull(controller.request.value)
    }

    @Test fun backgroundKeepsAnAlreadyVisibleSheetAndItsEditorAlive() {
        val (controller, request) = automatic()
        controller.presented(request)
        val shown = controller.request.value!!
        controller.background(source, visit)
        assertEquals(shown, controller.request.value)
        assertTrue(shown.visible(foreground = false))
        controller.leave(source, visit)
        assertNull(controller.request.value)
    }

    @Test fun presentationReceiptWaitsForActualVisibilityAndCannotReviveBackgroundRequest() = runBlocking {
        val (controller, request) = automatic()
        val visible = MutableStateFlow(false)
        var receipts = 0
        val report = async(start = CoroutineStart.UNDISPATCHED) {
            reportTmuxSheetPresented(visible) { receipts++; controller.presented(request) }
        }
        // Composition/readiness and even an animation target do not establish actual visibility.
        assertTrue(request.visible())
        assertFalse(controller.request.value!!.presented)
        assertEquals(0, receipts)
        controller.background(source, visit)
        assertNull(controller.request.value)
        visible.value = true // A late actual-visibility receipt still has to pass owns().
        report.await()
        assertEquals(1, receipts)
        assertNull(controller.request.value)
        controller.enter(source, visit)
        assertNull(controller.request.value)
    }

    @Test fun cancellingVisibilityObservationBeforeSheetShowsProducesNoReceipt() = runBlocking {
        val (controller, request) = automatic()
        val visible = MutableStateFlow(false)
        var receipts = 0
        val report = async(start = CoroutineStart.UNDISPATCHED) {
            reportTmuxSheetPresented(visible) { receipts++; controller.presented(request) }
        }
        report.cancel()
        report.join()
        visible.value = true
        assertEquals(0, receipts)
        assertFalse(controller.request.value!!.presented)
        controller.background(source, visit)
        assertNull(controller.request.value)
    }

    @Test fun oldVisibilityReceiptCannotMarkANewAccessToTheSameConnectionPresented() = runBlocking {
        val (controller, old) = automatic()
        val visible = MutableStateFlow(false)
        val report = async(start = CoroutineStart.UNDISPATCHED) {
            reportTmuxSheetPresented(visible) { controller.presented(old) }
        }
        controller.leave(source, visit)
        val returning = Any()
        controller.enter(source, returning)
        controller.manage(source, returning)
        val current = controller.request.value!!
        visible.value = true
        report.await()
        assertSame(current, controller.request.value)
        assertFalse(current.presented)
        controller.background(source, returning)
        assertNull(controller.request.value)
    }

    @Test fun oldVisibilityReceiptCannotUndoAnExplicitDismissal() = runBlocking {
        val (controller, request) = automatic()
        val visible = MutableStateFlow(false)
        val report = async(start = CoroutineStart.UNDISPATCHED) {
            reportTmuxSheetPresented(visible) { controller.presented(request) }
        }
        controller.dismiss(request)
        visible.value = true
        report.await()
        assertNull(controller.request.value)
    }

    @Test fun naturalEndPreservesVisibleManagementEditorOwnerInForegroundAndBackground() = runBlocking {
        for (background in listOf(false, true)) {
            val controller = TmuxSheetController()
            controller.enter(source, visit)
            controller.manage(source, visit)
            val request = controller.request.value!!
            reportTmuxSheetPresented(MutableStateFlow(true)) { controller.presented(request) }
            // The exact request instance owns the still-composed sheet and its in-progress rename
            // draft. Natural EOF must not replace/remove that editor owner, even in the background.
            val editorOwner = controller.request.value!!
            val server = TmuxServerId(90, 100, "/tmp/tmux-1000/default",
                "12345678-1234-1234-1234-123456789abc", 200, 7, 800)
            val row = TmuxSession("\$0", "before-rename", 1, 0, 1000)
            val observation = TmuxObservation(TmuxProbeKind.READY, server = server, sessions = listOf(row),
                peerBootId = server.bootId, defaultSocketPath = server.socketPath)
            val state = MutableStateFlow(TmuxUiState(phase = TmuxPhase.READY, sessions = listOf(row),
                snapshot = TmuxSnapshot(source, 1, observation, verified = true), presence = TmuxPresence.Unknown()))
            val coordinator = TmuxCoordinator(source, TmuxIntent.None, TmuxScripts("observer", "probe"),
                object : TmuxCoordinator.Bridge {
                    override suspend fun observe(command: String): String? = error("not observing")
                    override suspend fun operation(command: String): String? = error("not mutating")
                }, { null }, false, { true }, { false }, state, { it.name })
            assertTrue(state.value.canManage)
            assertTrue(state.value.canCreate)
            if (background) controller.background(source, visit)
            coordinator.dataEnded()
            controller.dataEnded(source)
            assertSame(editorOwner, controller.request.value)
            assertTrue(editorOwner.visible(alive = false, foreground = !background))
            assertFalse(state.value.canManage)
            assertFalse(state.value.canCreate)
            assertTrue(state.value.presence is TmuxPresence.Ended)
            // Repeated EOF/store/status emissions are not a user dismissal either.
            controller.dataEnded(source)
            assertSame(editorOwner, controller.request.value)
            controller.invalidate(source)
            assertNull(controller.request.value)
        }
    }

    @Test fun dataEndRevokesPreparedAndSelectingRequestsButNotManagement() {
        val prepared = TmuxSheetController()
        prepared.prepareSelection(source)
        prepared.dataEnded(source)
        prepared.opened(source, visit)
        assertNull(prepared.request.value)
        for (presented in listOf(false, true)) {
            val (controller, request) = automatic()
            if (presented) controller.presented(request)
            controller.dataEnded(source)
            assertNull(controller.request.value)
            controller.presented(request)
            assertNull(controller.request.value)
        }
        val controller = TmuxSheetController()
        controller.enter(source, visit)
        controller.manage(source, visit)
        val manual = controller.request.value!!
        controller.dataEnded(source)
        assertSame(manual, controller.request.value)
    }

    @Test fun reconnectOrCloseInvalidatesPendingAndShownRequests() {
        for (presented in listOf(false, true)) {
            val (controller, request) = automatic()
            if (presented) controller.presented(request)
            controller.invalidate(source)
            controller.opened(source, visit)
            assertNull(controller.request.value)
            assertNull(controller.finish(request, other.connectionId))
            controller.prepareSelection(other)
            val nextVisit = Any()
            controller.opened(other, nextVisit)
            val current = controller.request.value!!
            controller.invalidate(source)
            controller.dismiss(request)
            assertEquals(current, controller.request.value)
        }
    }

    @Test fun connectionFailureRemovesCheckingOverlayAndDoesNotBlockRecovery() {
        val (controller, request) = automatic()
        controller.dataEnded(source)
        assertNull(controller.request.value)
        assertNull(controller.finish(request, other.connectionId))
        controller.manage(source, visit)
        val manual = controller.request.value!!
        assertFalse(manual.visible(ready = false, alive = false))
        // A previously-established ended source is still explicitly manageable, as before.
        assertTrue(manual.visible(ready = true, alive = false))
    }

    @Test fun removedSourcesLoseBothPresentationAndTemporaryOwnership() {
        val (controller, request) = automatic()
        controller.retainSources(setOf(other))
        assertNull(controller.request.value)
        assertNull(controller.finish(request, other.connectionId))
        controller.enter(source, visit)
        controller.manage(source, visit)
        assertNull(controller.finish(controller.request.value!!, other.connectionId))
    }

    @Test fun temporarySourceIsDisposedOnlyByItsOwnedSuccessfulChoice() {
        val (controller, request) = automatic()
        assertTrue(controller.owns(request)) // Failure leaves the request/editor untouched.
        assertEquals(source, controller.finish(request, other.connectionId))
        assertNull(controller.finish(request, other.connectionId))
        assertNull(controller.request.value)
    }

    @Test fun selectingTheSourceItselfAndOrdinaryManagementNeverDisposeIt() {
        val (controller, request) = automatic()
        assertNull(controller.finish(request, source.connectionId))
        controller.manage(source, visit)
        assertNull(controller.finish(controller.request.value!!, other.connectionId))
    }

    @Test fun staleCompletionCannotDisposeAnotherPickersTemporarySource() {
        val (controller, old) = automatic()
        controller.prepareSelection(other)
        val nextVisit = Any()
        controller.opened(other, nextVisit)
        val current = controller.request.value!!
        assertNull(controller.finish(old, other.connectionId))
        controller.dismiss(old)
        assertEquals(current, controller.request.value)
        assertEquals(other, controller.finish(current, "third-connection"))
    }
}
