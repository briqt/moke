package com.briqt.moke.ui

import com.briqt.moke.terminal.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.cancellation.CancellationException

class TmuxPresentationTest {
    private val scope = TmuxScope("target-a", "connection-a", 4)
    private val server = TmuxServerId(101, 1234, "/private/socket", "boot-a", 456, 7, 8)
    private val owner = TmuxOwnerProof("nonce", 55, 1000, 44, "/dev/pts/4")
    private val client = TmuxClientId(201, 1200, owner.tty)
    private val a = TmuxSession("\$0", "same-prefix-alpha", 1, 3, 100)
    private val b = TmuxSession("\$1", "same-prefix-beta", 2, 1, 101)
    private fun binding(row: TmuxSession) = TmuxBinding(scope, owner, client, row.ref(server))
    private fun ready(row: TmuxSession = a, generation: Long = 1): TmuxUiState {
        val snapshot = TmuxSnapshot(scope, generation,
            TmuxObservation(TmuxProbeKind.READY, owner, server, listOf(a, b)), verified = true)
        return TmuxUiState(TmuxPhase.READY, listOf(a, b), snapshot = snapshot,
            presence = TmuxPresence.Attached(binding(row)))
    }

    @Test fun `only the current binding marks a row current after A to B`() {
        val state = ready(b)
        assertFalse(isCurrentTmuxRow(state, a))
        assertTrue(isCurrentTmuxRow(state, b))
        assertEquals(b.ref(server), currentTmuxSelection(state)?.session)
        assertEquals(client, currentTmuxSelection(state)?.expectedClient)
    }

    @Test fun `same name or dollar zero never proves current across scope or server`() {
        val state = ready()
        val snapshot = state.snapshot!!
        assertFalse(isCurrentTmuxRow(state.copy(snapshot = snapshot.copy(scope = scope.copy(connectionId = "new"))), a))
        assertFalse(isCurrentTmuxRow(state.copy(snapshot = snapshot.copy(scope = scope.copy(targetStamp = "other"))), a))
        assertFalse(isCurrentTmuxRow(state.copy(snapshot = snapshot.copy(scope = scope.copy(networkEpoch = 5))), a))
        assertFalse(isCurrentTmuxRow(state.copy(snapshot = snapshot.copy(observation = snapshot.observation.copy(
            server = server.copy(startedTicks = 999)))), a))
        assertFalse(isCurrentTmuxRow(state, a.copy(created = 999)))
    }

    @Test fun `unknown current leaves witnessed other selections manageable`() {
        val state = ready().copy(presence = TmuxPresence.Unknown())
        assertTrue(state.canManage)
        assertNotNull(state.selectionFor(a))
        assertNull(state.selectionFor(a)?.expectedClient)
        assertNull(currentTmuxSelection(state))
        assertFalse(isCurrentTmuxRow(state, a))
        val close = freezeSessionCloseTarget(state, a.name, "host")
        assertTrue(close.unknown)
        assertNull(close.selection)
    }

    @Test fun `unverified unwitnessed and ended states never produce a close target`() {
        val state = ready()
        val snapshot = state.snapshot!!
        val invalid = listOf(
            state.copy(snapshot = null),
            state.copy(snapshot = snapshot.copy(verified = false)),
            state.copy(snapshot = snapshot.copy(observation = snapshot.observation.copy(server = server.copy(bootId = "")))),
            state.copy(presence = TmuxPresence.Ended(binding(a))),
        )
        invalid.forEach { assertNull(currentTmuxSelection(it)) }
    }

    @Test fun `operation busy and a refresh failure disable management without erasing a proved identity`() {
        val state = ready()
        for (paused in listOf(state.copy(busy = true), state.copy(phase = TmuxPhase.ERROR))) {
            assertFalse(paused.canManage)
            assertEquals(a.ref(server), currentTmuxSelection(paused)?.session)
            assertEquals(client, currentTmuxSelection(paused)?.expectedClient)
            assertFalse(freezeSessionCloseTarget(paused, "custom", "host").unknown)
        }
    }

    @Test fun `close uses full real name not the custom title and freezes client scope generation`() {
        val state = ready()
        val frozen = freezeSessionCloseTarget(state, "custom title not a tmux name", "target-a")
        assertEquals(a.name, frozen.selection?.session?.name)
        assertEquals(client, frozen.selection?.expectedClient)
        assertEquals(scope, frozen.selection?.scope)
        assertEquals(1L, frozen.selection?.generation)
        assertEquals(2, frozen.otherClients)
        val switched = ready(b, 2)
        assertEquals(b.name, currentTmuxSelection(switched)?.session?.name)
        assertEquals(a.name, frozen.selection?.session?.name)
        assertEquals(client, frozen.selection?.expectedClient)
        assertEquals(1L, frozen.selection?.generation)
    }

    @Test fun `rename changes displayed remote name but cannot silently retarget an open confirmation`() {
        val state = ready()
        val frozen = freezeSessionCloseTarget(state, "title", "target-a")
        val renamed = a.copy(name = "renamed-'Unicode-会话-尾部")
        val rows = listOf(renamed, b)
        val next = state.copy(sessions = rows, presence = TmuxPresence.Attached(binding(renamed)),
            snapshot = state.snapshot!!.copy(observation = state.snapshot.observation.copy(sessions = rows)))
        assertEquals(renamed.name, currentTmuxSelection(next)?.session?.name)
        assertEquals(a.name, frozen.selection?.session?.name)
        assertEquals(renamed.name, freezeSessionCloseTarget(next, "stale display title", "target-a").selection?.session?.name)
    }

    @Test fun `a stale binding name does not become permission to end a different display snapshot`() {
        val state = ready()
        val renamed = a.copy(name = "new-name")
        val rows = listOf(renamed, b)
        val next = state.copy(sessions = rows,
            snapshot = state.snapshot!!.copy(observation = state.snapshot.observation.copy(sessions = rows)))
        assertTrue(isCurrentTmuxRow(next, renamed))
        assertNull(currentTmuxSelection(next))
        assertNull(freezeSessionCloseTarget(next, "title", "host").selection)
    }

    @Test fun `tmux choices remain mandatory with confirm close off while plain shells respect it`() {
        val attached = ready()
        assertTrue(requiresSessionCloseChoice(attached, false))
        assertTrue(requiresSessionCloseChoice(attached.copy(presence = TmuxPresence.Unknown()), false))
        assertTrue(requiresSessionCloseChoice(TmuxUiState(), false))
        val plain = attached.copy(presence = TmuxPresence.Shell(owner))
        assertFalse(requiresSessionCloseChoice(plain, false))
        assertTrue(requiresSessionCloseChoice(plain, true))
        assertTrue(requiresSessionCloseChoice(plain.copy(lastBinding = binding(a)), false))
    }

    private fun unobservedSshState(): TmuxUiState {
        val state = ready()
        // Ordinary persistence=NONE SSH has no observer; discovery may verify the list but has no owner.
        return state.copy(presence = TmuxPresence.Unknown(),
            snapshot = state.snapshot!!.copy(observation = state.snapshot.observation.copy(owner = null)))
    }

    @Test fun `ordinary SSH owner absent unknown respects disabled close confirmation without claiming tmux uncertainty`() {
        val state = unobservedSshState()
        assertNull(state.snapshot!!.observation.owner)
        assertTrue(state.canManage) // Explicitly selected, witnessed remote rows remain usable.
        assertFalse(requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = false))
        val target = freezeSessionCloseTarget(state, "ordinary SSH", "host", observeCurrent = false)
        assertFalse(target.unknown)
        assertNull(target.selection)
    }

    @Test fun `ordinary SSH pending and unknown use plain confirmation only when the setting requests it`() {
        for (state in listOf(TmuxUiState(), unobservedSshState())) {
            assertFalse(requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = false))
            assertTrue(requiresSessionCloseChoice(state, confirmClose = true, observeCurrent = false))
            assertFalse(freezeSessionCloseTarget(state, "ordinary SSH", "host", observeCurrent = false).unknown)
        }
    }

    @Test fun `real transport persistence and runtime intent context select observation without inventing a binding`() {
        val intents = listOf(TmuxIntent.None, TmuxIntent.Restore("remembered"), TmuxIntent.Open(a.ref(server)))
        for (useMosh in listOf(false, true)) for (persistenceTmux in listOf(false, true)) for (intent in intents) {
            val observed = observesCurrentTmux(useMosh, persistenceTmux, intent)
            val requested = useMosh || persistenceTmux || intent != TmuxIntent.None
            assertEquals(requested, observed)
            val state = unobservedSshState()
            assertEquals(requested, requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = observed))
            val target = freezeSessionCloseTarget(state, "title", "host", observeCurrent = observed)
            assertEquals(requested, target.unknown)
            assertNull(target.selection) // Intent and configuration must never prove a current client.
        }
    }

    @Test fun `plain unobserved SSH close does not surface unrelated tmux coordinator messages`() {
        val state = unobservedSshState().copy(message = "Current session unconfirmed.")
        val plain = freezeSessionCloseTarget(state, "ordinary SSH", "host", observeCurrent = false)
        assertNull(sessionClosePresentationState(state, plain, observeCurrent = false).message)
        assertEquals("Current session unconfirmed.", state.message) // Display filtering never mutates the live state.
        val requested = freezeSessionCloseTarget(state, "observed terminal", "host", observeCurrent = true)
        assertEquals(state.message, sessionClosePresentationState(state, requested, observeCurrent = true).message)
        val historic = freezeSessionCloseTarget(state.copy(lastBinding = binding(a)), "past tmux", "host", observeCurrent = false)
        assertEquals(state.message, sessionClosePresentationState(state, historic, observeCurrent = false).message)
    }

    @Test fun `ordinary SSH only polls tmux while its panel or an actually unknown close choice is open`() {
        val plainState = unobservedSshState()
        val plainClose = freezeSessionCloseTarget(plainState, "ordinary SSH", "host", observeCurrent = false)
        assertFalse(shouldPollTmux(observeCurrent = false, panelOpen = false, closeTarget = null))
        assertFalse(shouldPollTmux(observeCurrent = false, panelOpen = false, closeTarget = plainClose))
        assertTrue(shouldPollTmux(observeCurrent = false, panelOpen = true, closeTarget = null))
        val historicalClose = freezeSessionCloseTarget(plainState.copy(lastBinding = binding(a)), "past tmux", "host", observeCurrent = false)
        assertTrue(shouldPollTmux(observeCurrent = false, panelOpen = false, closeTarget = historicalClose))
        assertTrue(shouldPollTmux(observeCurrent = true, panelOpen = false, closeTarget = null))
        assertFalse(shouldPollTmux(observeCurrent = false, panelOpen = false, closeTarget = null))
    }

    @Test fun `requested but unresolved observation keeps only disconnect and refresh regardless of the setting`() {
        for (state in listOf(TmuxUiState(), unobservedSshState())) {
            assertTrue(requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = true))
            val target = freezeSessionCloseTarget(state, "terminal", "host", observeCurrent = true)
            assertTrue(target.unknown)
            assertNull(target.selection)
        }
    }

    @Test fun `actual bindings and tmux history cannot be hidden by an unobserved SSH context`() {
        val attached = ready()
        assertTrue(requiresSessionCloseChoice(attached, confirmClose = false, observeCurrent = false))
        assertEquals(a.ref(server), freezeSessionCloseTarget(attached, "title", "host", observeCurrent = false).selection?.session)
        val historical = listOf(
            unobservedSshState().copy(lastBinding = binding(a)),
            attached.copy(presence = TmuxPresence.Shell(owner), lastBinding = binding(a)),
            attached.copy(presence = TmuxPresence.Ended(binding(a))),
        )
        historical.forEach { state ->
            assertTrue(requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = false))
            val target = freezeSessionCloseTarget(state, "title", "host", observeCurrent = false)
            assertTrue(target.unknown)
            assertNull(target.selection)
        }
    }

    @Test fun `confirmed remote ended missing restore and changed target always require a choice without observation`() {
        val plain = unobservedSshState().copy(presence = TmuxPresence.Shell(owner))
        val ended = plain.copy(presence = TmuxPresence.Ended(remoteEnded = true))
        for (state in listOf(ended, plain.copy(restoreMissing = true), plain.copy(targetChanged = true))) {
            assertTrue(requiresSessionCloseChoice(state, confirmClose = false, observeCurrent = false))
        }
        assertFalse(freezeSessionCloseTarget(ended, "title", "host", observeCurrent = false).unknown)
        assertTrue(freezeSessionCloseTarget(plain.copy(targetChanged = true), "title", "host", observeCurrent = false).unknown)
    }

    @Test fun `ordinary connection failure after ended observation is failed not a remote session ended claim`() {
        val failed = unobservedSshState().copy(presence = TmuxPresence.Ended())
        assertEquals(TerminalEndNotice.CONNECTION_FAILED, terminalEndNotice(failed, alive = false, connectFailed = true))
        assertEquals(TerminalEndNotice.CONNECTION_ENDED, terminalEndNotice(failed, alive = false, connectFailed = false))
    }

    @Test fun `first picker pending or verified list never creates a false terminal end notice or current selection`() {
        val pending = TmuxUiState()
        assertNull(terminalEndNotice(pending, alive = true, connectFailed = false))
        assertTrue(requiresSessionCloseChoice(pending, confirmClose = false, observeCurrent = true))
        val listed = ready().copy(presence = TmuxPresence.Pending)
        assertNull(terminalEndNotice(listed, alive = true, connectFailed = false))
        assertNull(currentTmuxSelection(listed))
        assertNotNull(listed.selectionFor(a))
        assertTrue(listed.canManage)
    }

    @Test fun `restore missing surfaces ended even while fallback shell remains alive and never offers reconnect`() {
        val state = ready().copy(presence = TmuxPresence.Shell(owner), restoreMissing = true)
        val notice = terminalEndNotice(state, alive = true, connectFailed = false)
        assertEquals(TerminalEndNotice.REMOTE_ENDED, notice)
        assertFalse(canReconnectFromNotice(notice!!))
        assertFalse(freezeSessionCloseTarget(state, "title", "host").unknown)
    }

    @Test fun `target change surfaces even while transport is alive and overrides the old restore notice`() {
        val state = ready().copy(targetChanged = true, restoreMissing = true)
        val notice = terminalEndNotice(state, alive = true, connectFailed = true)
        assertEquals(TerminalEndNotice.TARGET_CHANGED, notice)
        assertFalse(canReconnectFromNotice(notice!!))
    }

    @Test fun `a witnessed remote end never becomes ordinary reconnect after EOF`() {
        val state = ready().copy(presence = TmuxPresence.Ended(binding(a), remoteEnded = true), lastBinding = binding(a))
        for (alive in listOf(true, false)) {
            assertEquals(TerminalEndNotice.REMOTE_ENDED, terminalEndNotice(state, alive, connectFailed = true))
        }
        assertNull(freezeSessionCloseTarget(state, "title", "host").selection)
        assertFalse(freezeSessionCloseTarget(state, "title", "host").unknown)
    }

    @Test fun `connection interruption does not claim the remote session ended or work recovered`() {
        val state = ready().copy(presence = TmuxPresence.Unknown(), lastBinding = binding(a))
        assertNull(terminalEndNotice(state, alive = true, connectFailed = false))
        val notice = terminalEndNotice(state, alive = false, connectFailed = false)
        assertEquals(TerminalEndNotice.CONNECTION_ENDED, notice)
        assertTrue(canReconnectFromNotice(notice!!))
        assertEquals(TerminalEndNotice.CONNECTION_FAILED, terminalEndNotice(state, alive = false, connectFailed = true))
    }

    @Test fun `successful current detach reveals a status bar even before the data transport exits`() {
        val state = ready().copy(presence = TmuxPresence.Ended(binding(a), remoteEnded = false))
        assertEquals(TerminalEndNotice.LEFT_TMUX, terminalEndNotice(state, alive = true, connectFailed = false))
        assertTrue(canReconnectFromNotice(TerminalEndNotice.LEFT_TMUX))
        assertTrue(shouldRevealTerminalAfterTmuxAction(true, state))
        assertFalse(shouldRevealTerminalAfterTmuxAction(false, state))
    }

    @Test fun `only a successful action ending this local source dismisses the management list`() {
        val attached = ready()
        assertFalse(shouldRevealTerminalAfterTmuxAction(true, attached))
        assertFalse(shouldRevealTerminalAfterTmuxAction(false, attached.copy(restoreMissing = true)))
        assertTrue(shouldRevealTerminalAfterTmuxAction(true, attached.copy(restoreMissing = true)))
        assertFalse(shouldRevealTerminalAfterTmuxAction(true, attached.copy(presence = TmuxPresence.Unknown())))
    }

    @Test fun `fresh connection permit only enables explicit new creation from an ended source`() {
        val ended = ready().copy(presence = TmuxPresence.Ended(binding(a), remoteEnded = true))
        assertFalse(ended.canCreate)
        assertFalse(canCreateTmuxFromEndNotice(ended))
        val permitted = ended.copy(canStartNewConnection = true)
        assertTrue(canCreateTmuxFromEndNotice(permitted))
        assertFalse(permitted.canCreate)
        assertTrue(permitted.canManage) // A live fallback can still manage other witnessed rows.
        assertNull(currentTmuxSelection(permitted))
        assertFalse(canCreateTmuxFromEndNotice(permitted.copy(targetChanged = true)))
        assertFalse(canCreateTmuxFromEndNotice(permitted.copy(busy = true)))
        val unknown = TmuxUiState(phase = TmuxPhase.ERROR, presence = TmuxPresence.Unknown(), canStartNewConnection = true)
        assertFalse(canCreateTmuxFromEndNotice(unknown))
        assertTrue(canCreateTmuxFromEndNotice(unknown.copy(restoreMissing = true)))
    }

    @Test fun `explicit new retry after missing target keeps the fresh-source permit distinct from old transport creation`() {
        val missing = ready().copy(presence = TmuxPresence.Ended(binding(a), remoteEnded = true),
            restoreMissing = true, canStartNewConnection = true, message = "Remote session ended")
        assertFalse(missing.canCreate)
        assertTrue(missing.canManage)
        assertTrue(canCreateTmuxFromEndNotice(missing))
        assertNull(currentTmuxSelection(missing))
        for (denied in listOf(missing.copy(canStartNewConnection = false), missing.copy(busy = true),
            missing.copy(targetChanged = true))) {
            assertFalse(canCreateTmuxFromEndNotice(denied))
        }
        val unverified = missing.copy(presence = TmuxPresence.Unknown(), restoreMissing = false, snapshot = null)
        assertTrue(unverified.canStartNewConnection)
        assertFalse(canCreateTmuxFromEndNotice(unverified))
    }

    private fun panelSource(): String {
        val override = System.getProperty("moke.tmuxPanelSource")?.let { java.io.File(it) }
        val file = override ?: listOf(java.io.File("src/main/java/com/briqt/moke/ui/TmuxPanel.kt"),
            java.io.File("app/src/main/java/com/briqt/moke/ui/TmuxPanel.kt")).first { it.isFile }
        return file.readText()
    }

    @Test fun `actual sheet new action uses the explicit fresh-source gate`() {
        val footer = panelSource().substringAfter("Column(Modifier.fillMaxWidth().padding(bottom = 8.dp))")
            .substringBefore("if (onPlainShell != null)")
        val gate = Regex("enabled\\s*=\\s*canCreateTmuxFromEndNotice\\(state\\)\\s*&&\\s*!opening")
        assertTrue("Sheet New must stay reachable after a missing-target precheck", gate.containsMatchIn(footer))
    }

    @Test fun `actual ordinary new dialog retries with the same explicit gate and rename stays on canManage`() {
        val allowed = panelSource().substringAfter("val allowed = when {").substringBefore("\n    }")
        assertTrue(Regex("originalName\\s*!=\\s*null\\s*->\\s*state\\.canManage").containsMatchIn(allowed))
        assertTrue("The ordinary New Dialog must allow a fresh-source retry, not only the end-notice dialog",
            Regex("else\\s*->\\s*canCreateTmuxFromEndNotice\\(state\\)").containsMatchIn(allowed))
        assertTrue(Regex("fromEndNotice\\s*->\\s*canCreateTmuxFromEndNotice\\(state\\)").containsMatchIn(allowed))
    }

    @Test fun `no server still allows strict create through the verified live state gate`() {
        val base = ready()
        val state = base.copy(sessions = emptyList(), presence = TmuxPresence.Shell(owner),
            snapshot = base.snapshot!!.copy(observation = TmuxObservation(TmuxProbeKind.NO_SERVER)))
        assertTrue(state.canCreate)
        assertTrue(canCreateTmuxFromEndNotice(state))
        assertFalse(state.canManage)
        assertNull(currentTmuxSelection(state))
    }

    private class ActionRun {
        var completed = false
        var error: Throwable? = null
    }

    private fun startAction(block: suspend () -> Unit): ActionRun = ActionRun().also { run ->
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                run.completed = true
                run.error = result.exceptionOrNull()
            }
        })
    }

    @Test fun `false and exceptional callbacks never fire the dismiss callback`() {
        for (throws in listOf(false, true)) {
            var dismissed = false
            var failed = false
            val run = startAction {
                completeTmuxUiAction(
                    action = { if (throws) error("result unavailable") else false },
                    onSuccess = { dismissed = true },
                    onFailure = { failed = true },
                )
            }
            assertTrue(run.completed)
            assertNull(run.error)
            assertTrue(failed)
            assertFalse(dismissed)
        }
    }

    @Test fun `no navigation occurs before async result and only true dismisses the choice`() {
        var reply: Continuation<Boolean>? = null
        var dismissed = 0
        var failures = 0
        val run = startAction {
            completeTmuxUiAction({ suspendCoroutine { reply = it } }, { dismissed++ }, { failures++ })
        }
        assertFalse(run.completed)
        assertEquals(0, dismissed)
        assertEquals(0, failures)
        reply!!.resume(false)
        assertTrue(run.completed)
        assertNull(run.error)
        assertEquals(0, dismissed)
        assertEquals(1, failures)
        val confirmed = startAction { completeTmuxUiAction({ true }, { dismissed++ }, { failures++ }) }
        assertTrue(confirmed.completed)
        assertEquals(1, dismissed)
        assertEquals(1, failures)
    }

    @Test fun `cancellation neither fakes success nor replaces it with a failure reply`() {
        var callbacks = 0
        val cancelled = CancellationException("closed UI")
        val run = startAction {
            completeTmuxUiAction({ throw cancelled }, { callbacks++ }, { callbacks++ })
        }
        assertTrue(run.completed)
        assertSame(cancelled, run.error)
        assertEquals(0, callbacks)
    }

    @Test fun `target label brackets IPv6 without changing the authenticated endpoint`() {
        assertEquals("SSH · user@host", tmuxTargetLabel("SSH", "user", "host", 22))
        assertEquals("MOSH · user@[2001:db8::1]:2200 · via jump", tmuxTargetLabel("MOSH", "user", "2001:db8::1", 2200, "via jump"))
        assertEquals("SSH · [::1]:2200", tmuxTargetLabel("SSH", "", "[::1]", 2200))
    }
}
