package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxCoordinator
import com.briqt.moke.terminal.TmuxIntent
import com.briqt.moke.terminal.TmuxObservation
import com.briqt.moke.terminal.TmuxProbeKind
import com.briqt.moke.terminal.TmuxScope
import com.briqt.moke.terminal.TmuxScripts
import com.briqt.moke.terminal.TmuxUiState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

// type: sheet-local tmux refresh feedback regression tests
class TmuxRefreshFeedbackTest {
    private fun withScope(test: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            test(scope)
        } finally {
            scope.cancel()
        }
    }

    @Test fun pendingExplicitRequestShowsFeedbackImmediatelyAndClearsOnCompletion() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = Job()
        feedback.refresh { request }
        assertTrue(feedback.refreshing.value)
        assertTrue(request.isActive)
        request.complete()
        assertFalse(feedback.refreshing.value)
    }

    @Test fun alreadyCompletedAndCancelledRequestsCannotLatchFeedbackOrBlockTheNextRefresh() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        for (request in listOf(Job().apply { complete() }, Job().apply { cancel() })) {
            feedback.refresh { request }
            assertFalse(feedback.refreshing.value)
        }
        val next = Job()
        feedback.refresh { next }
        assertTrue(feedback.refreshing.value)
        next.complete()
        assertFalse(feedback.refreshing.value)
    }

    @Test fun duplicateClicksDoNotStartAnotherRequestWhileTheExplicitOneIsPending() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = Job()
        var calls = 0
        repeat(3) { feedback.refresh { calls++; request } }
        assertEquals(1, calls)
        assertTrue(feedback.refreshing.value)
        request.complete()
        feedback.refresh { calls++; Job().apply { complete() } }
        assertEquals(2, calls)
        assertFalse(feedback.refreshing.value)
    }

    @Test fun cancellingTheRequestClearsFeedbackWithoutCancellingTheUiScope() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = Job()
        feedback.refresh { request }
        request.cancel()
        assertFalse(feedback.refreshing.value)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
        feedback.refresh { Job().apply { complete() } }
        assertFalse(feedback.refreshing.value)
    }

    @Test fun aFailedRequestClearsFeedbackWithoutTurningTheReadIntoAnActionFailure() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = CompletableDeferred<Unit>()
        feedback.refresh { request }
        assertTrue(feedback.refreshing.value)
        request.completeExceptionally(IllegalStateException("Controlled read failure"))
        assertFalse(feedback.refreshing.value)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
    }

    @Test fun disposingTheSheetCancelsOnlyItsWaiterAndRejectsStaleClicks() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = Job()
        feedback.refresh { request }
        feedback.dispose()
        assertFalse(feedback.refreshing.value)
        assertTrue("Dismissing a sheet must not cancel its view-model read", request.isActive)
        feedback.refresh { error("A disposed sheet cannot request another read") }
        request.complete()
        assertFalse(feedback.refreshing.value)
    }

    @Test fun cancellingTheUiOwnerDoesNotCancelTheViewModelRequest() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val request = Job()
        feedback.refresh { request }
        scope.cancel()
        assertFalse(feedback.refreshing.value)
        assertTrue(request.isActive)
        request.complete()
    }

    @Test fun aCancelledUiScopeNeverStartsAnExplicitRequest() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        scope.cancel()
        feedback.refresh { error("Cancelled UI must not start a request") }
        assertFalse(feedback.refreshing.value)
    }

    @Test fun anOldSheetsLateCompletionCannotClearANewSheetsFeedback() = withScope { scope ->
        val old = TmuxRefreshFeedback(scope)
        val oldRequest = Job()
        old.refresh { oldRequest }
        old.dispose()
        val current = TmuxRefreshFeedback(scope)
        val currentRequest = Job()
        current.refresh { currentRequest }
        oldRequest.complete()
        assertFalse(old.refreshing.value)
        assertTrue(current.refreshing.value)
        currentRequest.complete()
        assertFalse(current.refreshing.value)
    }

    @Test fun coalescingWithAnExistingReadCompletesWithoutWaitingForAnotherRefreshingEdge() = withScope { scope ->
        val feedback = TmuxRefreshFeedback(scope)
        val state = MutableStateFlow(TmuxUiState())
        var reply = CompletableDeferred<String?>()
        var reads = 0
        val coordinator = TmuxCoordinator(
            TmuxScope("target", "connection", 1), TmuxIntent.None, TmuxScripts("observer", "probe"),
            object : TmuxCoordinator.Bridge {
                override suspend fun observe(command: String): String? { reads++; return reply.await() }
                override suspend fun operation(command: String): String? = error("Not a mutation test")
            },
            { null }, false, { true }, { true }, state, { it.name },
            observationParser = { _, _ -> TmuxObservation(TmuxProbeKind.NO_SERVER,
                peerBootId = "12345678-1234-1234-1234-123456789abc", defaultSocketPath = "/tmp/tmux-1000/default") },
        )
        val background = scope.launch { coordinator.refresh() }
        assertTrue(state.value.refreshing)
        assertFalse(feedback.refreshing.value)
        feedback.refresh { scope.launch { coordinator.refresh() } }
        // tryLock coalesces the explicit request: its Job is complete, not a new pending read.
        assertFalse(feedback.refreshing.value)
        assertTrue(state.value.refreshing)
        assertEquals(1, reads)
        reply.complete("response")
        assertTrue(background.isCompleted)
        assertFalse(state.value.refreshing)

        reply = CompletableDeferred()
        feedback.refresh { scope.launch { coordinator.refresh() } }
        assertTrue(state.value.refreshing)
        assertTrue(feedback.refreshing.value)
        reply.complete("response")
        assertFalse(feedback.refreshing.value)

        // A later polling read cannot restart the previous user request's feedback.
        reply = CompletableDeferred()
        val nextPoll = scope.launch { coordinator.refresh() }
        assertTrue(state.value.refreshing)
        assertFalse(feedback.refreshing.value)
        reply.complete("response")
        assertTrue(nextPoll.isCompleted)
        assertEquals(3, reads)
        assertFalse(feedback.refreshing.value)
    }

    @Test fun terminalEntryKeepsItsCountAndDoesNotSubscribeToReadActivity() {
        val source = source("TerminalScreen.kt")
        val topBar = source.substringAfter("private fun TerminalTopBar(")
        assertFalse(topBar.contains("tmuxQuerying"))
        assertFalse(topBar.contains("refreshing"))
        assertFalse(topBar.contains("CircularProgressIndicator"))
        assertTrue(topBar.contains("IconButton(onClick = onOpenTmux)"))
        assertTrue(topBar.contains("if (tmuxCount > 0)"))
        assertTrue(topBar.contains("Text(tmuxCount.toString())"))
        assertTrue(source.contains("tmuxCount = tmuxState.sessions.size"))
        // The existing ordinary-SSH policy and five-second loop must not be used to hide animation.
        assertTrue(source.contains("dataReady && shouldPollTmux(observeCurrent, showTmux, closeTarget)"))
        assertTrue(source.contains("refreshTmux()\n                delay(5_000)"))
    }

    @Test fun bothSheetModesUseOneRequestOwnedProjectionWithoutRekeyingTheEditors() {
        val source = source("MokeApp.kt").substringAfter("sheetRequest?.let { request ->")
            .substringBefore("is Screen.Appearance")
        assertTrue(source.contains("remember(request.serial) { TmuxRefreshFeedback(navigationScope) }"))
        assertTrue(source.contains("onDispose { refreshFeedback.dispose() }"))
        assertTrue(source.contains("tmuxState.copy(refreshing = explicitlyRefreshing)"))
        assertTrue(source.contains("val refresh = { refreshFeedback.refresh { vm.refreshTmux(ts) } }"))
        assertEquals(2, Regex("state = sheetTmuxState").findAll(source).count())
        assertEquals(2, Regex("onRefresh = refresh,").findAll(source).count())
        assertFalse("A refresh must not recreate the sheet or its name draft", source.contains("key("))
        assertFalse(source.contains("busy ="))
        assertTrue(source.contains("onPresented = onPresented"))
        assertTrue(source.contains("vm.dismissTmuxSheet(request)"))
    }

    @Test fun sharedSheetKeepsInitialCheckingAndShowsExplicitReadFeedbackOnlyAtTheRefreshButton() {
        val source = source("TmuxPanel.kt")
        val header = source.substringAfter("val refreshDescription =").substringBefore("val targetDescription =")
        assertTrue(header.contains("if (state.refreshing) stringResource(R.string.tmux_checking)"))
        assertTrue(header.contains("contentDescription = refreshDescription"))
        assertTrue(header.contains("stateDescription = refreshStatus"))
        assertTrue(header.contains("enabled = !state.busy && !opening && !state.refreshing"))
        assertTrue(header.contains("minWidth = 48.dp, minHeight = 48.dp"))
        assertTrue(header.contains("CircularProgressIndicator("))
        assertTrue(header.contains("Icon(Icons.Filled.Refresh, contentDescription = null)"))
        assertTrue(source.contains("TmuxPhase.IDLE, TmuxPhase.CHECKING -> item(\"checking\")"))
        assertTrue(source.contains("if (opening || state.busy)"))
        assertTrue(source.contains("TextButton(onClick = onRefresh, enabled = !state.busy && !opening && !state.refreshing"))
        assertTrue(source.contains("val enabled = !opening && state.canManage && selected != null"))
        assertTrue(source.contains("items(state.sessions, key = { \"\${it.id}:\${it.created}\" })"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/java/com/briqt/moke/ui", name),
        File("app/src/main/java/com/briqt/moke/ui", name),
    ).first { it.isFile }.readText()
}
