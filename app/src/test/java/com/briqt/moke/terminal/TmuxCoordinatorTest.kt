package com.briqt.moke.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// type: tmux coordinator JVM regression tests
class TmuxCoordinatorTest {
    private val scope = TmuxScope("target-a", "connection-a", 4)
    private val nonce = "a".repeat(32)
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val server = TmuxServerId(90, 100, "/tmp/tmux-1000/default", boot, 200, 7, 800)
    private val first = TmuxSession("\$0", "first", 1, 1, 1000)
    private val second = TmuxSession("\$1", "second", 1, 1, 1001)
    private val owner = TmuxOwnerProof(nonce, 70, 1000, 150, "/dev/pts/2")
    private val client = TmuxClient(TmuxClientId(80, 900, owner.tty), first.id)
    private val otherClient = TmuxClient(TmuxClientId(81, 901, "/dev/pts/3"), second.id)
    private val p = "__MOKE_TMUX_V1__|"

    private fun observed(
        kind: TmuxProbeKind = TmuxProbeKind.READY,
        owner: TmuxOwnerProof? = this.owner,
        sessions: List<TmuxSession> = listOf(first, second),
        clients: List<TmuxClient> = listOf(client, otherClient),
        server: TmuxServerId = this.server,
    ) = TmuxObservation(kind, owner, server.takeIf { kind == TmuxProbeKind.READY },
        sessions.takeIf { kind == TmuxProbeKind.READY }.orEmpty(),
        clients.takeIf { kind == TmuxProbeKind.READY }.orEmpty(),
        peerBootId = boot, defaultSocketPath = server.socketPath)

    private fun serverRow(s: TmuxServerId) =
        "${s.pid}|${s.started}|${s.startedTicks}|${s.socketDevice}|${s.socketInode}|${s.bootId}|${s.socketPath}"

    private fun frame(o: TmuxObservation, request: String): String = buildString {
        append("${p}BEGIN|$request\n")
        o.peerBootId?.let { append("${p}PEER|$it\n") }
        o.owner?.let { append("${p}OWNER|${it.nonce}|${it.pid}|${it.uid}|${it.startedTicks}|${it.tty}\n") }
        o.defaultSocketPath?.let { append("${p}DEFAULT_SOCKET|$it\n") }
        o.server?.let { append("${p}SERVER|${serverRow(it)}\n") }
        o.sessions.forEach { append("${p}SESSION|${it.id}|${it.created}|${it.windows}|${it.clients}|${it.name}\n") }
        o.clients.forEach {
            val pane = it.pane
            if (pane == null) append("${p}CLIENT|${it.identity.pid}|${it.identity.created}|${it.sessionId}|${it.identity.tty}\n")
            else {
                fun encode(value: String) = value.replace("%", "%25").replace("|", "%7C")
                append("${p}CLIENT_PANE|${it.identity.pid}|${it.identity.created}|${it.sessionId}|${it.sessionCreated}|${it.identity.tty}|${pane.id}|${encode(pane.title)}|${encode(pane.path.orEmpty())}\n")
            }
        }
        append("${p}KIND|${o.kind}\n${p}END|$request|0\n")
    }

    private fun ack(code: TmuxOperationCode = TmuxOperationCode.OK, created: TmuxSessionRef? = null): String = buildString {
        append("${p}OP_BEGIN\n")
        created?.let {
            append("${p}SERVER|${serverRow(it.server)}\n")
            append("${p}CREATED|${it.id}|${it.created}|1|0|${it.name}\n")
        }
        append("${p}RESULT|$code\n${p}OP_END|0\n")
    }

    private inner class FakeBridge : TmuxCoordinator.Bridge {
        var observation = observed()
        var output: ((String) -> String?)? = null
        var commandObservation: ((String, String) -> TmuxObservation)? = null
        var operationOutput: String? = ack()
        var beforeObserve: suspend () -> Unit = {}
        var beforeOperation: suspend () -> Unit = {}
        val observations = mutableListOf<String>()
        val operations = mutableListOf<String>()
        override suspend fun observe(command: String): String? {
            observations += command
            beforeObserve()
            val request = Regex("__MOKE_TMUX_V1__\\|BEGIN\\|([A-Za-z0-9_-]+)").find(command)!!.groupValues[1]
            return if (output != null) output!!.invoke(request) else
                frame(commandObservation?.invoke(command, request) ?: observation, request)
        }
        override suspend fun operation(command: String): String? {
            operations += command
            beforeOperation()
            return operationOutput
        }
    }

    private inner class Fixture(
        intent: TmuxIntent = TmuxIntent.None,
        required: Boolean = true,
        injectObservation: Boolean = false,
        initial: TmuxUiState = TmuxUiState(),
        source: TmuxScope = scope,
        proof: TmuxOwnerProof = owner,
    ) {
        var ready = true
        var alive = true
        var currentNonce: String? = proof.nonce
        var now = 10L
        val bridge = FakeBridge().apply { observation = observed(owner = proof) }
        val state = MutableStateFlow(initial)
        val coordinator = TmuxCoordinator(source, intent, TmuxScripts("observer", "probe"), bridge,
            { currentNonce }, required, { ready }, { alive }, state, { it.name }, { now },
            if (injectObservation) { _, _ -> bridge.observation } else TmuxRemote::parseObservation)
        suspend fun select(session: TmuxSession = first): TmuxSelection {
            coordinator.refresh()
            return state.value.selectionFor(session)!!
        }
        fun ended(): TmuxPresence.Ended = state.value.presence as TmuxPresence.Ended
    }

    @Test fun queryActivityTracksFirstAndSubsequentReadsNotSessionCount() = runBlocking {
        val f = Fixture()
        for (firstRead in listOf(true, false)) {
            val release = CompletableDeferred<Unit>()
            f.bridge.beforeObserve = { release.await() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
            assertTrue(f.state.value.refreshing)
            assertFalse(f.state.value.busy)
            assertEquals(if (firstRead) TmuxPhase.CHECKING else TmuxPhase.READY, f.state.value.phase)
            val count = f.bridge.observations.size
            f.coordinator.refresh() // An overlapping timer/manual request coalesces.
            assertEquals(count, f.bridge.observations.size)
            assertTrue(f.state.value.refreshing)
            release.complete(Unit)
            read.await()
            assertFalse(f.state.value.refreshing)
            assertEquals(2, f.state.value.sessions.size)
        }
    }

    @Test fun queryActivityEndsBeforeIndependentOperationAcknowledgement() = runBlocking {
        val f = Fixture()
        val readRelease = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { readRelease.await() }
        f.bridge.beforeOperation = { operationEntered.complete(Unit); operationRelease.await() }
        val created = first.copy(id = "\$9", name = "created", created = 1010).ref(server)
        f.bridge.operationOutput = ack(created = created)
        val create = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.create(created.name) }
        assertTrue(f.state.value.busy)
        assertTrue(f.state.value.refreshing)
        readRelease.complete(Unit)
        // The operation entry confirms that the fresh read has finished, without timing sleeps.
        withTimeout(2_000) { operationEntered.await() }
        assertTrue(f.state.value.busy)
        assertFalse(f.state.value.refreshing)
        operationRelease.complete(Unit)
        assertEquals(created, create.await())
        assertFalse(f.state.value.busy)
        assertFalse(f.state.value.refreshing)
    }

    @Test fun failedAndCancelledQueriesClearActivityWithoutInventingUnsupported() = runBlocking {
        val f = Fixture()
        f.bridge.output = { null }
        f.coordinator.refresh()
        assertEquals(TmuxPhase.ERROR, f.state.value.phase)
        assertFalse(f.state.value.refreshing)
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        assertTrue(f.state.value.refreshing)
        read.cancel()
        read.join()
        assertFalse(f.state.value.refreshing)
        assertEquals(TmuxPhase.ERROR, f.state.value.phase)
    }

    @Test fun endingOrClosingDuringQueryCannotLeavePermanentCheckingOrLateActivity() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.bridge.beforeObserve = { release.await() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
            assertTrue(f.state.value.refreshing)
            if (close) f.coordinator.close() else f.coordinator.dataEnded()
            assertFalse(f.state.value.refreshing)
            assertEquals(TmuxPhase.ERROR, f.state.value.phase)
            assertTrue(f.state.value.presence is TmuxPresence.Ended)
            release.complete(Unit)
            read.await()
            assertFalse(f.state.value.refreshing)
            assertNull(f.state.value.snapshot)
            assertEquals(TmuxPhase.ERROR, f.state.value.phase)
        }
    }

    @Test fun waitingForDataIsNotAnActualQuery() = runBlocking {
        val f = Fixture()
        f.ready = false
        f.coordinator.refresh()
        assertFalse(f.state.value.refreshing)
        assertTrue(f.bridge.observations.isEmpty())
    }

    @Test fun startupWaitsForDataAndKeepsRestoreIntent() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.ready = false
        f.coordinator.refresh()
        assertTrue(f.bridge.observations.isEmpty())
        assertEquals(TmuxPresence.Pending, f.state.value.presence)
        assertFalse(f.state.value.restoreMissing)
        assertEquals(TmuxIntent.Restore("first"), f.coordinator.reconnectIntent())
        f.ready = true
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
    }

    @Test fun requiredOwnerMissingDuringBootstrapCannotTrustEmptyList() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.currentNonce = null
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER, owner = null)
        f.coordinator.refresh()
        assertNull(f.state.value.currentBinding)
        assertFalse(f.state.value.snapshot?.verified == true)
        assertFalse(f.state.value.restoreMissing)
        f.currentNonce = nonce
        f.bridge.observation = observed()
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentBinding)
    }

    @Test fun verifiedPlainOwnerWithoutServerIsShellAndLaterAttachRecovers() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.coordinator.refresh()
        assertTrue(f.state.value.canCreate)
        assertEquals(TmuxPresence.Shell(owner), f.state.value.presence)
        assertFalse(f.state.value.restoreMissing)
        f.bridge.observation = observed()
        f.coordinator.refresh()
        assertEquals(client.identity, f.state.value.currentBinding?.client)
    }

    @Test fun wrongOrAbsentRequiredOwnerIsNeverShellOrVerified() = runBlocking {
        for (proof in listOf(null, owner.copy(nonce = "b".repeat(32)))) {
            val f = Fixture()
            f.bridge.observation = observed(owner = proof)
            f.coordinator.refresh()
            assertFalse(f.state.value.snapshot?.verified == true)
            assertFalse(f.state.value.canManage)
            assertFalse(f.state.value.presence is TmuxPresence.Shell)
        }
    }

    @Test fun optionalSshOwnerAllowsServerListButNotCurrentGuess() = runBlocking {
        val f = Fixture(required = false)
        f.currentNonce = null
        f.bridge.observation = observed(owner = null, sessions = listOf(first.copy(clients = 9)),
            clients = listOf(client))
        f.coordinator.refresh()
        assertTrue(f.state.value.canManage)
        assertNull(f.state.value.currentBinding)
        assertTrue(f.state.value.presence is TmuxPresence.Unknown)
    }

    @Test fun otherPeoplesClientCountsDoNotIdentifyCurrentAndNextReadRecovers() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(sessions = listOf(first.copy(clients = 100), second), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertNull(f.state.value.currentBinding)
        assertEquals(TmuxPresence.Shell(owner), f.state.value.presence)
        f.bridge.observation = observed()
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
    }

    @Test fun ambiguousOrMissingClientSessionDoesNotBindEvenWithInjectedFrames() = runBlocking {
        val f = Fixture(injectObservation = true)
        for (clients in listOf(listOf(client, otherClient.copy(identity = otherClient.identity.copy(tty = owner.tty))),
            listOf(client.copy(sessionId = "\$9")))) {
            f.bridge.observation = observed(clients = clients)
            f.coordinator.refresh()
            assertNull(f.state.value.currentBinding)
        }
        f.bridge.observation = observed()
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
    }

    @Test fun manualSwitchAndRenameFollowActualClientNotNameOrZeroId() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.coordinator.refresh()
        val changed = second.copy(name = "renamed")
        f.bridge.observation = observed(sessions = listOf(first, changed), clients = listOf(client.copy(sessionId = second.id)))
        f.coordinator.refresh()
        assertEquals(changed.ref(server), f.state.value.currentBinding?.session)
        assertEquals(changed.ref(server), f.state.value.lastBinding?.session)
        assertEquals(TmuxIntent.Open(changed.ref(server)), f.coordinator.reconnectIntent())
    }

    @Test fun unknownAndTruncatedFramesKeepCacheUnverifiedThenRecover() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.output = { request -> frame(observed(), request).substringBefore("${p}END") }
        f.coordinator.refresh()
        assertEquals(listOf(first, second), f.state.value.sessions)
        assertFalse(f.state.value.snapshot!!.verified)
        assertFalse(f.state.value.canManage)
        assertEquals(selection.session, f.state.value.lastBinding?.session)
        f.bridge.output = null
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentBinding)
    }

    @Test fun requiredOwnerChangeWhileReadInFlightCannotBindOldOwner() = runBlocking {
        val f = Fixture()
        f.bridge.beforeObserve = { f.currentNonce = "b".repeat(32) }
        f.coordinator.refresh()
        assertNull(f.state.value.currentBinding)
        assertFalse(f.state.value.snapshot?.verified == true)
    }

    @Test fun verifiedServerMustAgreeWithPeerAndStrongIdentity() = runBlocking {
        val f = Fixture(injectObservation = true)
        for (o in listOf(observed().copy(peerBootId = "87654321-1234-1234-1234-123456789abc"),
            observed(server = server.copy(socketInode = 0)))) {
            f.bridge.observation = o
            f.coordinator.refresh()
            assertFalse(f.state.value.snapshot?.verified == true)
            assertNull(f.state.value.currentBinding)
        }
    }

    @Test fun missingRestoreAndOpenAreEndedWithoutImplicitCreate() = runBlocking {
        for (intent in listOf(TmuxIntent.Restore("absent"), TmuxIntent.Open(first.ref(server)))) {
            val f = Fixture(intent)
            f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
            f.coordinator.refresh()
            assertTrue(f.state.value.restoreMissing)
            assertTrue(f.ended().remoteEnded)
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun readySnapshotConfirmsMissingFrozenOpenNotSameNameReplacement() = runBlocking {
        val f = Fixture(TmuxIntent.Open(first.ref(server)))
        f.bridge.observation = observed(sessions = listOf(first.copy(id = "\$8")), clients = emptyList())
        f.coordinator.refresh()
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun notInstalledIsNotRemoteEndedAndDoesNotClearRestore() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.bridge.observation = observed(TmuxProbeKind.NOT_INSTALLED)
        f.coordinator.refresh()
        assertEquals(TmuxPhase.NOT_INSTALLED, f.state.value.phase)
        assertFalse(f.state.value.presence is TmuxPresence.Ended)
        assertFalse(f.state.value.restoreMissing)
        assertEquals(TmuxIntent.Restore("first"), f.coordinator.reconnectIntent())
    }

    @Test fun harmlessNewerGenerationAndNameChangeDoNotExpireConfirmation() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.now += 600_000_000_000L
        f.bridge.observation = observed(sessions = listOf(first.copy(name = "new-name"), second))
        repeat(4) { f.coordinator.refresh() }
        assertTrue(f.coordinator.validateSelection(selection, requireCurrent = true))
        assertTrue(f.coordinator.rename(selection, "newer"))
        assertEquals("newer", f.state.value.currentBinding?.session?.name)
        assertEquals(1, f.bridge.operations.size)
    }

    @Test fun scopeEpochAndFutureGenerationAreRejectedBeforeRemoteIo() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val count = f.bridge.observations.size
        for (s in listOf(selection.copy(scope = scope.copy(connectionId = "other")),
            selection.copy(scope = scope.copy(targetStamp = "other")),
            selection.copy(scope = scope.copy(networkEpoch = scope.networkEpoch + 1)),
            selection.copy(generation = selection.generation + 1))) {
            assertFalse(f.coordinator.validateSelection(s))
            assertFalse(f.coordinator.kill(s))
        }
        assertEquals(count, f.bridge.observations.size)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun changedServerIdOrBirthRejectsFrozenSelection() = runBlocking {
        for (change in 0..2) {
            val f = Fixture()
            val selection = f.select()
            f.bridge.observation = when (change) {
                0 -> observed(server = server.copy(socketInode = 999))
                1 -> observed(sessions = listOf(first.copy(id = "\$7"), second), clients = listOf(client.copy(sessionId = "\$7")))
                else -> observed(sessions = listOf(first.copy(created = 9999), second))
            }
            assertFalse(f.coordinator.kill(selection))
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun confirmationDoesNotFollowClientSwitchToKillAnotherObject() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.observation = observed(clients = listOf(client.copy(sessionId = second.id)))
        assertFalse(f.coordinator.kill(selection))
        assertTrue(f.bridge.operations.isEmpty())
        assertEquals(second.ref(server), f.state.value.currentBinding?.session)
    }

    @Test fun currentClientReplacementRejectsConfirmation() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.observation = observed(clients = listOf(client.copy(identity = client.identity.copy(created = 999))))
        assertFalse(f.coordinator.detach(selection))
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun requireCurrentRejectsOtherRowButNormalRowValidationCanManageIt() = runBlocking {
        val f = Fixture()
        val selection = f.select(second)
        assertFalse(f.coordinator.validateSelection(selection, requireCurrent = true))
        assertTrue(f.coordinator.validateSelection(selection))
    }

    @Test fun renameOtherRowDoesNotMakeOtherClientCurrent() = runBlocking {
        val f = Fixture()
        val selection = f.select(second)
        assertTrue(f.coordinator.rename(selection, "renamed"))
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
        assertEquals(first.ref(server), f.state.value.lastBinding?.session)
        assertEquals("renamed", f.state.value.sessions.single { it.id == second.id }.name)
        assertTrue(f.bridge.operations.single().contains(server.bootId))
        assertTrue(f.bridge.operations.single().contains("moke_expected_ino=${server.socketInode}"))
    }

    @Test fun nullAckIsUnknownNeverReplayedAndNeverSuccessful() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.operationOutput = null
        assertFalse(f.coordinator.kill(selection))
        assertEquals(1, f.bridge.operations.size)
        assertEquals(TmuxProblem.RESULT_UNKNOWN.name, f.state.value.message)
        assertFalse((f.state.value.presence as? TmuxPresence.Ended)?.remoteEnded == true)
        f.coordinator.refresh()
        assertEquals(1, f.bridge.operations.size)
    }

    @Test fun nonOkOperationCodesNeverReportSuccess() = runBlocking {
        for (code in TmuxOperationCode.entries.filter { it != TmuxOperationCode.OK }) {
            val f = Fixture()
            val selection = f.select()
            f.bridge.operationOutput = ack(code)
            assertFalse(f.coordinator.rename(selection, "new"))
            assertEquals(1, f.bridge.operations.size)
            assertNotEquals("new", f.state.value.lastBinding?.session?.name)
        }
    }

    @Test fun currentKillAckAfterDataEofStillConfirmsRemoteEndAndRemovesExactEntry() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.beforeOperation = { f.alive = false; f.coordinator.dataEnded() }
        assertTrue(f.coordinator.kill(selection))
        assertTrue(f.ended().remoteEnded)
        assertFalse(f.state.value.snapshot!!.verified)
        assertEquals(listOf(second), f.state.value.sessions)
        val reads = f.bridge.observations.size
        f.coordinator.refresh()
        assertEquals(reads, f.bridge.observations.size)
        assertTrue(f.ended().remoteEnded)
    }

    @Test fun currentDetachAckEndsSourceButKeepsRemoteSessionEntry() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        assertTrue(f.coordinator.detach(selection))
        assertFalse(f.ended().remoteEnded)
        assertFalse(f.state.value.restoreMissing)
        assertTrue(f.state.value.sessions.any { it.id == first.id })
        assertFalse(f.state.value.canManage)
        assertEquals(TmuxIntent.Open(first.ref(server)), f.coordinator.reconnectIntent())
        assertNotEquals(TmuxProblem.REMOTE_ENDED.name, f.state.value.message)
    }

    @Test fun nullAckAfterEofIsNotProofOfSuccessfulKill() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.operationOutput = null
        f.bridge.beforeOperation = { f.coordinator.dataEnded() }
        assertFalse(f.coordinator.kill(selection))
        assertFalse(f.ended().remoteEnded)
        assertEquals(TmuxProblem.RESULT_UNKNOWN.name, f.state.value.message)
    }

    @Test fun dataEndedDuringReadCannotResurrectAttached() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        f.coordinator.dataEnded()
        release.complete(Unit)
        refresh.await()
        assertTrue(f.state.value.presence is TmuxPresence.Ended)
        assertFalse(f.state.value.snapshot?.verified == true)
    }

    @Test fun closeDuringOperationInvalidatesEvenValidLateAck() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeOperation = { entered.complete(Unit); release.await() }
        val action = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.kill(selection) }
        withTimeout(2000) { entered.await() }
        f.coordinator.close()
        val closed = f.state.value
        release.complete(Unit)
        assertFalse(action.await())
        assertEquals(closed, f.state.value)
        assertFalse((f.state.value.presence as? TmuxPresence.Ended)?.remoteEnded == true)
    }

    @Test fun closeDuringReadRejectsLateObservationAndQueuedActions() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.kill(selection) }
        f.coordinator.close()
        val closed = f.state.value
        release.complete(Unit)
        read.await()
        assertFalse(queued.await())
        assertEquals(closed, f.state.value)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun repeatedTimerRefreshesCoalesceRatherThanQueue() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        (1..30).map { async { f.coordinator.refresh() } }.awaitAll()
        assertEquals(1, f.bridge.observations.size)
        release.complete(Unit)
        read.await()
        assertEquals(1, f.bridge.observations.size)
        assertNotNull(f.state.value.currentBinding)
    }

    @Test fun createRequiresFreshVerifiedEmptyTargetAndReturnsRefWithoutPretendingAttach() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        val created = first.ref(server)
        f.bridge.operationOutput = ack(created = created)
        assertEquals(created, f.coordinator.create(first.name))
        assertEquals(1, f.bridge.observations.size)
        assertEquals(1, f.bridge.operations.size)
        assertNull(f.state.value.currentBinding)
        assertEquals(TmuxIntent.None, f.coordinator.reconnectIntent())
        assertTrue(f.bridge.operations.single().contains(server.socketPath))
    }

    @Test fun existsAndMissingCreateAckDoNotRetryOrInventCreatedRef() = runBlocking {
        for (result in listOf(ack(TmuxOperationCode.EXISTS), ack(), null)) {
            val f = Fixture()
            f.bridge.operationOutput = result
            assertNull(f.coordinator.create("wanted"))
            assertEquals(1, f.bridge.operations.size)
            assertNotEquals("wanted", f.state.value.currentBinding?.session?.name)
            if (result == ack(TmuxOperationCode.EXISTS)) assertEquals(TmuxProblem.NAME_EXISTS.name, f.state.value.message)
        }
    }

    @Test fun createDoesNotTrustWrongPeerOrSocketInCreatedAck() = runBlocking {
        val f = Fixture()
        f.bridge.operationOutput = ack(created = first.ref(server.copy(socketInode = 999)))
        assertNull(f.coordinator.create(first.name))
        assertEquals(1, f.bridge.operations.size)
        assertEquals(TmuxProblem.RESULT_UNKNOWN.name, f.state.value.message)
    }

    @Test fun unknownObservationCannotCreateAndNeverTriggersImplicitMutation() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.bridge.observation = observed(TmuxProbeKind.UNKNOWN)
        f.coordinator.refresh()
        assertNull(f.coordinator.create("new"))
        assertTrue(f.bridge.operations.isEmpty())
        assertFalse(f.state.value.restoreMissing)
    }

    @Test fun mainRouteFlagsSurviveReadsWhileVerifiedBindingClearsStaleMissing() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.state.value = f.state.value.copy(targetChanged = true, canStartNewConnection = true, restoreMissing = true)
        assertTrue(f.coordinator.rename(selection, "renamed"))
        assertTrue(f.state.value.targetChanged)
        assertTrue(f.state.value.canStartNewConnection)
        assertFalse(f.state.value.restoreMissing)
        assertEquals(scope, f.state.value.snapshot?.scope)
        assertNull(f.coordinator.create("new"))
        assertEquals(1, f.bridge.operations.size)
    }

    @Test fun createdAckCannotStandInForKillAck() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.operationOutput = ack(created = first.ref(server))
        assertFalse(f.coordinator.kill(selection))
        assertFalse((f.state.value.presence as? TmuxPresence.Ended)?.remoteEnded == true)
        assertEquals(1, f.bridge.operations.size)
        assertEquals(TmuxProblem.RESULT_UNKNOWN.name, f.state.value.message)
    }

    @Test fun foreignScopeCacheAndBindingCannotLeakIntoNewConnection() = runBlocking {
        val foreignScope = scope.copy(networkEpoch = scope.networkEpoch - 1)
        val foreignBinding = TmuxBinding(foreignScope, owner, client.identity, first.ref(server))
        val f = Fixture(initial = TmuxUiState(phase = TmuxPhase.READY, sessions = listOf(first),
            snapshot = TmuxSnapshot(foreignScope, 100, observed(), verified = true),
            presence = TmuxPresence.Attached(foreignBinding), lastBinding = foreignBinding,
            targetChanged = true, canStartNewConnection = true))
        f.ready = false
        f.coordinator.refresh()
        assertTrue(f.state.value.sessions.isEmpty())
        assertNull(f.state.value.snapshot)
        assertNull(f.state.value.lastBinding)
        assertNull(f.state.value.currentBinding)
        assertEquals(TmuxIntent.None, f.coordinator.reconnectIntent())
        assertTrue(f.state.value.targetChanged)
        assertTrue(f.state.value.canStartNewConnection)
    }

    @Test fun injectedDuplicateNamesOrInconsistentClientRowsCannotVerify() = runBlocking {
        val f = Fixture(injectObservation = true)
        for (bad in listOf(
            observed(sessions = listOf(first, second.copy(name = first.name))),
            observed(clients = listOf(client.copy(sessionId = "\$99"))),
            observed(clients = listOf(client, client.copy(sessionId = second.id))),
            observed(sessions = listOf(first.copy(name = ""), second)),
        )) {
            f.bridge.observation = bad
            f.coordinator.refresh()
            assertFalse(f.state.value.snapshot?.verified == true)
            assertFalse(f.state.value.canManage)
        }
    }

    @Test fun nullObserveAndWrongRequestRetainUnverifiedCacheWithoutClearingIntent() = runBlocking {
        val f = Fixture(TmuxIntent.Restore("first"))
        f.select()
        for (response in listOf<(String) -> String?>({ null }, { frame(observed(), "wrong-request") })) {
            f.bridge.output = response
            f.coordinator.refresh()
            assertEquals(listOf(first, second), f.state.value.sessions)
            assertFalse(f.state.value.snapshot!!.verified)
            assertNull(f.state.value.currentBinding)
            assertEquals(first.ref(server), f.state.value.lastBinding?.session)
            assertFalse(f.state.value.restoreMissing)
        }
        f.bridge.output = null
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentBinding)
    }

    @Test fun openAndRestoreObserveTheirExplicitSocketRatherThanSshDefault() = runBlocking {
        val custom = server.copy(socketPath = "/tmp/private-target/socket")
        for (intent in listOf(TmuxIntent.Open(first.ref(custom)), TmuxIntent.Restore(first.name, custom.socketPath))) {
            val f = Fixture(intent)
            f.bridge.observation = observed(server = custom)
            f.coordinator.refresh()
            assertEquals(first.ref(custom), f.state.value.currentBinding?.session)
            assertTrue(f.bridge.observations.single().contains(custom.socketPath))
        }
    }

    @Test fun firstVerifiedSocketStaysPinnedEvenWithoutOwnerAndCannotFollowDefaultDrift() = runBlocking {
        val f = Fixture(required = false)
        f.currentNonce = null
        f.bridge.observation = observed(owner = null)
        f.coordinator.refresh()
        f.bridge.observation = observed(owner = null, server = server.copy(socketPath = "/tmp/other/default"))
        f.coordinator.refresh()
        assertTrue(f.bridge.observations.last().contains(server.socketPath))
        assertFalse(f.state.value.snapshot!!.verified)
        assertFalse(f.state.value.restoreMissing)
        assertNull(f.state.value.currentBinding)
    }

    @Test fun detachAckAfterEofKeepsFrozenReconnectAndNeverMarksRestoreMissing() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.beforeOperation = { f.alive = false; f.coordinator.dataEnded() }
        assertTrue(f.coordinator.detach(selection))
        assertFalse(f.ended().remoteEnded)
        assertFalse(f.state.value.restoreMissing)
        assertEquals(TmuxIntent.Open(first.ref(server)), f.coordinator.reconnectIntent())
        assertFalse(f.state.value.snapshot!!.verified)
        f.coordinator.dataEnded()
        assertFalse(f.ended().remoteEnded)
    }

    @Test fun killingOtherExactRowDoesNotEndSourceOrChangeCurrentClient() = runBlocking {
        val f = Fixture()
        val selection = f.select(second)
        assertTrue(f.coordinator.kill(selection))
        assertEquals(listOf(first), f.state.value.sessions)
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
        assertEquals(client.identity, f.state.value.currentBinding?.client)
        assertFalse(f.state.value.restoreMissing)
        assertEquals(TmuxIntent.Open(first.ref(server)), f.coordinator.reconnectIntent())
    }

    @Test fun explicitActionWaitsForReadButTimerTicksDoNotQueueBehindAction() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        val action = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.rename(selection, "new") }
        repeat(10) { f.coordinator.refresh() }
        assertTrue(f.bridge.operations.isEmpty())
        release.complete(Unit)
        read.await()
        assertTrue(action.await())
        assertEquals(3, f.bridge.observations.size)
        assertEquals(1, f.bridge.operations.size)
    }

    @Test fun openCannotDeclareOldSessionGoneWhenDnsNowReachesAnotherPeer() = runBlocking {
        val changedBoot = "87654321-1234-1234-1234-123456789abc"
        for (kind in listOf(TmuxProbeKind.READY, TmuxProbeKind.NO_SERVER)) {
            val f = Fixture(TmuxIntent.Open(first.ref(server)))
            f.bridge.observation = observed(kind, server = server.copy(bootId = changedBoot)).copy(peerBootId = changedBoot)
            f.coordinator.refresh()
            assertEquals(TmuxPresence.Unknown(TmuxUnknownReason.TARGET_CHANGED), f.state.value.presence)
            assertTrue(f.state.value.targetChanged)
            assertFalse(f.state.value.snapshot?.verified == true)
            assertTrue(f.state.value.sessions.isEmpty())
            assertFalse(f.state.value.restoreMissing)
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun provenOldPeerCacheCannotBeReplacedBySameNameAndIdOnNewPeer() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val changedBoot = "87654321-1234-1234-1234-123456789abc"
        f.bridge.observation = observed(server = server.copy(bootId = changedBoot),
            sessions = listOf(first.copy(name = "new-peer"), second)).copy(peerBootId = changedBoot)
        f.coordinator.refresh()
        assertEquals(TmuxPresence.Unknown(TmuxUnknownReason.TARGET_CHANGED), f.state.value.presence)
        assertFalse(f.state.value.snapshot!!.verified)
        assertEquals(first.ref(server), f.state.value.lastBinding?.session)
        assertEquals(listOf(first, second), f.state.value.sessions)
        assertFalse(f.state.value.restoreMissing)
        assertFalse(f.coordinator.kill(selection))
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun failedReadTicketIsNotAnIssuedFutureSelectionGeneration() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.bridge.output = { null }
        f.coordinator.refresh()
        val count = f.bridge.observations.size
        f.bridge.output = null
        assertFalse(f.coordinator.validateSelection(selection.copy(generation = selection.generation + 1)))
        assertEquals(count, f.bridge.observations.size)
        assertTrue(f.coordinator.validateSelection(selection))
    }

    @Test fun unmatchedClientDoesNotBlockExactLastBindingMetadataRefresh() = runBlocking {
        val f = Fixture()
        f.select()
        val renamed = first.copy(name = "manual-rename")
        f.bridge.observation = observed(sessions = listOf(renamed, second), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertNull(f.state.value.currentBinding)
        assertEquals(renamed.ref(server), f.state.value.lastBinding?.session)
        assertEquals(TmuxIntent.Open(renamed.ref(server)), f.coordinator.reconnectIntent())
        assertFalse(f.state.value.restoreMissing)
    }

    @Test fun eofBeforeActionPreflightDoesNotSendControlMutation() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        f.alive = false
        assertFalse(f.coordinator.kill(selection))
        assertEquals(1, f.bridge.observations.size)
        assertTrue(f.bridge.operations.isEmpty())
        assertTrue(f.state.value.presence is TmuxPresence.Ended)
        assertFalse(f.ended().remoteEnded)
    }

    @Test fun submittedControlOperationWaitsForIndependentAckAfterEof() = runBlocking {
        val f = Fixture()
        val selection = f.select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeOperation = { entered.complete(Unit); release.await() }
        val action = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.kill(selection) }
        withTimeout(2000) { entered.await() }
        f.alive = false
        f.coordinator.dataEnded()
        assertFalse(action.isCompleted)
        assertFalse(f.ended().remoteEnded)
        release.complete(Unit)
        assertTrue(action.await())
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertFalse(f.state.value.snapshot!!.verified)
    }

    @Test fun anotherClientsRenameAckCannotBeRolledBackByOurSuspendedOldFrame() = runBlocking {
        val actor = Fixture()
        val receiverScope = scope.copy(connectionId = "connection-b")
        val receiverOwner = owner.copy(nonce = "b".repeat(32), pid = 71, startedTicks = 151, tty = otherClient.identity.tty)
        val receiver = Fixture(source = receiverScope, proof = receiverOwner)
        val shared = observed(clients = listOf(client, otherClient.copy(sessionId = first.id)))
        actor.bridge.observation = shared
        receiver.bridge.observation = shared.copy(owner = receiverOwner)
        val selection = actor.select()
        receiver.select()
        val currentClient = receiver.state.value.currentBinding!!.client
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFrame = receiver.bridge.observation
        receiver.bridge.output = { request -> frame(oldFrame, request) }
        receiver.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { receiver.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        assertTrue(actor.coordinator.rename(selection, "beta"))
        receiver.coordinator.renamed(selection.session, "beta")
        assertEquals("beta", receiver.state.value.currentBinding?.session?.name)
        val publishedGeneration = receiver.state.value.snapshot!!.generation
        release.complete(Unit)
        read.await()
        assertEquals("beta", receiver.state.value.sessions.single { it.id == first.id }.name)
        assertEquals("beta", receiver.state.value.currentBinding?.session?.name)
        assertEquals("beta", receiver.state.value.lastBinding?.session?.name)
        assertEquals(publishedGeneration, receiver.state.value.snapshot!!.generation)
        assertEquals(currentClient, receiver.state.value.currentBinding!!.client)
        assertEquals(TmuxIntent.Open(first.copy(name = "beta").ref(server)), receiver.coordinator.reconnectIntent())
        assertEquals(1, actor.bridge.operations.size)
        assertTrue(receiver.bridge.operations.isEmpty())
        receiver.bridge.output = null
        receiver.bridge.beforeObserve = {}
        receiver.bridge.observation = oldFrame.copy(sessions = listOf(first.copy(name = "beta"), second))
        receiver.coordinator.refresh()
        assertEquals("beta", receiver.state.value.currentBinding?.session?.name)
    }

    @Test fun propagatedNoncurrentRenameChangesOnlyTheExactRowAndNotCurrentClient() = runBlocking {
        val f = Fixture()
        f.select()
        val current = f.state.value.currentBinding
        val generation = f.state.value.snapshot!!.generation
        f.state.value = f.state.value.copy(targetChanged = true, canStartNewConnection = true)
        f.coordinator.renamed(second.ref(server), "beta")
        assertEquals(current, f.state.value.currentBinding)
        assertEquals(current, f.state.value.lastBinding)
        assertEquals(first, f.state.value.sessions.single { it.id == first.id })
        assertEquals("beta", f.state.value.sessions.single { it.id == second.id }.name)
        assertEquals(listOf(client, otherClient), f.state.value.snapshot!!.observation.clients)
        assertTrue(f.state.value.snapshot!!.generation > generation)
        assertTrue(f.state.value.snapshot!!.verified)
        assertTrue(f.state.value.targetChanged)
        assertTrue(f.state.value.canStartNewConnection)
    }

    @Test fun propagatedRenameCannotMatchByNameOrByIdOnAnotherServerOrBirth() = runBlocking {
        val f = Fixture()
        f.select()
        val initial = f.state.value
        for (target in listOf(first.ref(server.copy(socketInode = 999)), first.copy(id = "\$8").ref(server),
            first.copy(created = 999).ref(server), first.ref(server.copy(startedTicks = 0)))) {
            f.coordinator.renamed(target, "beta")
            assertEquals(initial, f.state.value)
        }
        f.coordinator.renamed(first.ref(server), "invalid.name")
        assertEquals(initial, f.state.value)
    }

    @Test fun propagatedRenamePreservesNormalEndedAndItsMessageWithoutReverification() = runBlocking {
        val f = Fixture()
        f.select()
        f.coordinator.dataEnded()
        val ended = f.state.value
        f.coordinator.renamed(first.ref(server), "beta")
        assertTrue(f.state.value.presence is TmuxPresence.Ended)
        assertFalse(f.ended().remoteEnded)
        assertEquals(ended.message, f.state.value.message)
        assertEquals(ended.restoreMissing, f.state.value.restoreMissing)
        assertFalse(f.state.value.snapshot!!.verified)
        assertEquals("beta", f.ended().lastBinding!!.session.name)
        assertEquals("beta", f.state.value.lastBinding!!.session.name)
        assertEquals(TmuxIntent.Open(first.copy(name = "beta").ref(server)), f.coordinator.reconnectIntent())
    }

    @Test fun propagatedRenameCannotReviveClosedCoordinatorOrItsInFlightRead() = runBlocking {
        val f = Fixture()
        f.select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        f.coordinator.close()
        val closed = f.state.value
        f.coordinator.renamed(first.ref(server), "beta")
        release.complete(Unit)
        read.await()
        assertEquals(closed, f.state.value)
        assertTrue(f.state.value.presence is TmuxPresence.Ended)
    }

    @Test fun propagatedRenameDoesNotInventProofFromIntentOrAnotherScopeCache() = runBlocking {
        val f = Fixture(TmuxIntent.Open(first.ref(server)))
        val pending = f.state.value
        f.coordinator.renamed(first.ref(server), "beta")
        assertEquals(pending, f.state.value)
        f.select()
        f.state.value = f.state.value.copy(snapshot = f.state.value.snapshot!!.copy(scope = scope.copy(networkEpoch = 99)),
            presence = TmuxPresence.Pending, lastBinding = null)
        val foreign = f.state.value
        f.coordinator.renamed(first.ref(server), "beta")
        assertEquals(foreign, f.state.value)
    }

    @Test fun propagatedRenameKeepsUnknownPresenceAndUnverifiedCachedIdentity() = runBlocking {
        val f = Fixture()
        f.select()
        f.bridge.output = { null }
        f.coordinator.refresh()
        val before = f.state.value
        f.coordinator.renamed(first.ref(server), "beta")
        assertEquals(before.presence, f.state.value.presence)
        assertEquals(before.message, f.state.value.message)
        assertFalse(f.state.value.snapshot!!.verified)
        assertEquals("beta", f.state.value.lastBinding?.session?.name)
        assertEquals("beta", f.state.value.sessions.single { it.id == first.id }.name)
    }

    @Test fun plainIntentWithVerifiedOwnerAndZeroClientsIsAReachableShell() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(sessions = listOf(first.copy(clients = 0), second.copy(clients = 0)), clients = emptyList())
        f.coordinator.refresh()
        assertEquals(TmuxPresence.Shell(owner), f.state.value.presence)
        assertNull(f.state.value.currentBinding)
        assertNull(f.state.value.lastBinding)
        assertTrue(f.state.value.snapshot!!.verified)
        assertEquals(TmuxPhase.READY, f.state.value.phase)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun waitingFirstRestoreOrOpenWithNoMatchingClientRemainsPendingUntilActualAttach() = runBlocking {
        for (intent in listOf(TmuxIntent.Restore(first.name), TmuxIntent.Open(first.ref(server)))) {
            val f = Fixture(intent)
            f.bridge.observation = observed(clients = listOf(otherClient))
            f.coordinator.refresh()
            assertEquals(TmuxPresence.Pending, f.state.value.presence)
            assertNull(f.state.value.lastBinding)
            assertFalse(f.state.value.restoreMissing)
            f.bridge.observation = observed()
            f.coordinator.refresh()
            assertEquals(first.ref(server), f.state.value.currentBinding?.session)
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun manualDetachToShellPreservesVerifiedLastBindingAndFrozenReconnect() = runBlocking {
        for (intent in listOf(TmuxIntent.None, TmuxIntent.Restore(first.name), TmuxIntent.Open(first.ref(server)))) {
            val f = Fixture(intent)
            f.select()
            val renamed = first.copy(name = "detached-name", clients = 0)
            f.bridge.observation = observed(sessions = listOf(renamed, second), clients = listOf(otherClient))
            f.coordinator.refresh()
            assertEquals(TmuxPresence.Shell(owner), f.state.value.presence)
            assertNull(f.state.value.currentBinding)
            assertEquals(renamed.ref(server), f.state.value.lastBinding?.session)
            assertEquals(TmuxIntent.Open(renamed.ref(server)), f.coordinator.reconnectIntent())
            assertTrue(f.state.value.snapshot!!.verified)
            assertFalse(f.state.value.restoreMissing)
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun shellCannotAppearBeforeDataReadyOrRequiredOwnerNonce() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.ready = false
        f.coordinator.refresh()
        assertEquals(TmuxPresence.Pending, f.state.value.presence)
        f.ready = true
        f.currentNonce = null
        f.coordinator.refresh()
        assertEquals(TmuxPresence.Unknown(TmuxUnknownReason.RECONNECT_REQUIRED), f.state.value.presence)
        assertEquals(TmuxProblem.OWNER_UNAVAILABLE.name, f.state.value.message)
        assertTrue(f.bridge.observations.isEmpty())
        f.currentNonce = nonce
        f.coordinator.refresh()
        assertEquals(TmuxPresence.Shell(owner), f.state.value.presence)
    }

    @Test fun ownerlessSshReadyAndNoServerRemainUnknownNotShell() = runBlocking {
        for (kind in listOf(TmuxProbeKind.READY, TmuxProbeKind.NO_SERVER)) {
            val f = Fixture(required = false)
            f.currentNonce = null
            f.bridge.observation = observed(kind, owner = null, clients = emptyList())
            f.coordinator.refresh()
            assertTrue(f.state.value.presence is TmuxPresence.Unknown)
            assertNull(f.state.value.currentBinding)
            assertTrue(f.state.value.snapshot!!.verified)
        }
    }

    @Test fun unknownUnsupportedPendingAndAmbiguousEvidenceDoNotImplyShell() = runBlocking {
        val f = Fixture()
        for (kind in listOf(TmuxProbeKind.UNKNOWN, TmuxProbeKind.ERROR, TmuxProbeKind.NOT_INSTALLED, TmuxProbeKind.PENDING)) {
            f.bridge.observation = observed(kind, owner = owner.takeUnless { kind == TmuxProbeKind.PENDING })
            f.coordinator.refresh()
            assertFalse(f.state.value.presence is TmuxPresence.Shell)
            assertNull(f.state.value.currentBinding)
            assertFalse(f.state.value.snapshot?.verified == true)
        }
        f.bridge.observation = observed(clients = listOf(client, client.copy(identity = client.identity.copy(pid = 999))))
        f.coordinator.refresh()
        assertFalse(f.state.value.presence is TmuxPresence.Shell)
        assertNull(f.state.value.currentBinding)
        assertFalse(f.state.value.snapshot?.verified == true)
    }

    @Test fun missingPreviouslyBoundTargetEndsRatherThanFallingBackToShell() = runBlocking {
        for (intent in listOf(TmuxIntent.None, TmuxIntent.Restore(first.name), TmuxIntent.Open(first.ref(server)))) {
            val f = Fixture(intent)
            f.select()
            f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
            f.coordinator.refresh()
            assertTrue(f.state.value.presence is TmuxPresence.Ended)
            assertTrue(f.ended().remoteEnded)
            assertTrue(f.state.value.restoreMissing)
            assertTrue(f.bridge.operations.isEmpty())
        }
    }

    @Test fun previouslyBoundLiveServerWithUnavailableSocketIsUnknownAndRecoversWithoutEnding() = runBlocking {
        val f = Fixture()
        f.select()
        assertNotNull(f.state.value.currentBinding)
        f.bridge.commandObservation = { command, request ->
            val withPreviousBirth = TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                server.socketPath, previousServer = server)
            // Simulate the Remote contract: only a supplied previous birth can distinguish
            // a still-live server behind an unavailable control socket from genuine absence.
            if (command == withPreviousBirth) observed(TmuxProbeKind.UNKNOWN) else observed(TmuxProbeKind.NO_SERVER)
        }
        f.coordinator.refresh()
        assertTrue(f.state.value.presence is TmuxPresence.Unknown)
        assertFalse(f.state.value.restoreMissing)
        assertFalse(f.state.value.snapshot!!.verified)
        assertEquals(first.ref(server), f.state.value.lastBinding?.session)
        assertTrue(f.bridge.operations.isEmpty())
        f.bridge.commandObservation = null
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
        assertFalse(f.state.value.restoreMissing)
        assertTrue(f.state.value.snapshot!!.verified)
    }

    @Test fun provenPreviousServerBirthGoneStillConfirmsRemoteEnd() = runBlocking {
        val f = Fixture()
        f.select()
        f.bridge.commandObservation = { command, request ->
            assertEquals(TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                server.socketPath, previousServer = server), command)
            observed(TmuxProbeKind.NO_SERVER)
        }
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.state.value.snapshot!!.verified)
        assertFalse(f.state.value.canManage)
        assertFalse(f.state.value.canCreate)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun explicitOpenSuppliesPreviousBirthBeforeAnyLocalAttachmentWitness() = runBlocking {
        val f = Fixture(TmuxIntent.Open(first.ref(server)))
        f.bridge.commandObservation = { command, request ->
            assertEquals(TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                server.socketPath, previousServer = server), command)
            observed(TmuxProbeKind.UNKNOWN)
        }
        f.coordinator.refresh()
        assertTrue(f.state.value.presence is TmuxPresence.Unknown)
        assertFalse(f.state.value.restoreMissing)
        assertNull(f.state.value.lastBinding)
        f.bridge.commandObservation = null
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
    }

    @Test fun ordinaryShellListAndFirstRestoreListDoNotInventPreviousCurrentServer() = runBlocking {
        for (intent in listOf(TmuxIntent.None, TmuxIntent.Restore(first.name))) {
            val f = Fixture(intent)
            f.bridge.observation = observed(clients = listOf(otherClient))
            f.coordinator.refresh()
            assertNull(f.state.value.lastBinding)
            assertTrue(f.state.value.snapshot!!.verified)
            f.bridge.commandObservation = { command, request ->
                assertEquals(TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                    server.socketPath, previousServer = null), command)
                f.bridge.observation
            }
            f.coordinator.refresh()
            assertNull(f.state.value.lastBinding)
            assertFalse(f.state.value.restoreMissing)
            assertEquals(if (intent == TmuxIntent.None) TmuxPresence.Shell(owner) else TmuxPresence.Pending, f.state.value.presence)
        }
    }

    @Test fun provenSameScopeLastBindingTakesPrecedenceOverInitialOpenServer() = runBlocking {
        val actualServer = server.copy(pid = 91, started = 101, startedTicks = 201, socketInode = 801)
        val binding = TmuxBinding(scope, owner, client.identity, first.ref(actualServer))
        val observation = observed(server = actualServer)
        val f = Fixture(TmuxIntent.Open(first.ref(server)), initial = TmuxUiState(phase = TmuxPhase.READY,
            sessions = observation.sessions, snapshot = TmuxSnapshot(scope, 4, observation, true),
            presence = TmuxPresence.Attached(binding), lastBinding = binding))
        f.bridge.commandObservation = { command, request ->
            assertEquals(TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                actualServer.socketPath, previousServer = actualServer), command)
            observation
        }
        f.coordinator.refresh()
        assertEquals(actualServer, f.state.value.currentBinding?.server)
    }

    @Test fun foreignScopeLastBindingCannotLeakPreviousBirthIntoNewSource() = runBlocking {
        val foreign = scope.copy(networkEpoch = scope.networkEpoch - 1)
        val binding = TmuxBinding(foreign, owner, client.identity, first.ref(server))
        val f = Fixture(initial = TmuxUiState(phase = TmuxPhase.READY,
            snapshot = TmuxSnapshot(foreign, 4, observed(), true),
            presence = TmuxPresence.Attached(binding), lastBinding = binding))
        f.bridge.commandObservation = { command, request ->
            assertEquals(TmuxRemote.observe(TmuxScripts("observer", "probe"), request, nonce, true,
                socketPath = null, previousServer = null), command)
            observed()
        }
        f.coordinator.refresh()
        assertEquals(scope, f.state.value.currentBinding?.scope)
    }

    @Test fun missingRestoreOnLiveFallbackCanObserveManualAttachmentAndClearMissing() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.alive)
        val generation = f.state.value.snapshot!!.generation
        val manual = second.copy(name = "manual-new-target")
        val manualClient = TmuxClient(TmuxClientId(82, 902, owner.tty), manual.id)
        f.bridge.observation = observed(sessions = listOf(manual), clients = listOf(manualClient))
        f.coordinator.refresh()
        assertEquals(manual.ref(server), f.state.value.currentBinding?.session)
        assertEquals(manualClient.identity, f.state.value.currentBinding?.client)
        assertEquals(manual.ref(server), f.state.value.lastBinding?.session)
        assertFalse(f.state.value.restoreMissing)
        assertTrue(f.state.value.snapshot!!.verified)
        assertTrue(f.state.value.snapshot!!.generation > generation)
        assertEquals(TmuxIntent.Open(manual.ref(server)), f.coordinator.reconnectIntent())
        f.coordinator.refresh()
        assertEquals(manual.ref(server), f.state.value.currentBinding?.session)
        assertFalse(f.state.value.restoreMissing)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun oldSelectionCannotFollowSameNameManualObjectAfterLiveFallbackRecovery() = runBlocking {
        val f = Fixture(TmuxIntent.Open(first.ref(server)))
        val old = f.select()
        f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        val replacement = second.copy(name = first.name)
        val replacementClient = TmuxClient(TmuxClientId(82, 902, owner.tty), replacement.id)
        f.bridge.observation = observed(sessions = listOf(replacement), clients = listOf(replacementClient))
        f.coordinator.refresh()
        assertEquals(replacement.ref(server), f.state.value.currentBinding?.session)
        assertFalse(f.state.value.restoreMissing)
        assertFalse(f.coordinator.validateSelection(old))
        assertFalse(f.coordinator.validateSelection(old.copy(expectedClient = null)))
        assertFalse(f.coordinator.kill(old))
        assertFalse(f.coordinator.rename(old.copy(expectedClient = null), "must-not-rename-replacement"))
        assertEquals(replacement.ref(server), f.state.value.currentBinding?.session)
        assertEquals(TmuxIntent.Open(replacement.ref(server)), f.coordinator.reconnectIntent())
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun aliveMissingFallbackRequiresReadyDataAndMatchingOwnerBeforeRecovering() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.coordinator.refresh()
        val reads = f.bridge.observations.size
        f.bridge.observation = observed()
        f.ready = false
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        f.ready = true
        f.currentNonce = null
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertEquals(reads, f.bridge.observations.size)
        f.currentNonce = nonce
        f.bridge.observation = observed(owner = owner.copy(nonce = "b".repeat(32)))
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertFalse(f.state.value.snapshot!!.verified)
        f.bridge.observation = observed()
        f.coordinator.refresh()
        assertEquals(first.ref(server), f.state.value.currentBinding?.session)
        assertFalse(f.state.value.restoreMissing)
    }

    @Test fun missingFallbackCannotReviveAfterNativeEofOrCloseEvenIfAliveProviderReturnsTrue() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture(TmuxIntent.Restore(first.name))
            f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
            f.coordinator.refresh()
            assertTrue(f.ended().remoteEnded)
            if (close) f.coordinator.close() else f.coordinator.dataEnded()
            val ended = f.state.value
            val reads = f.bridge.observations.size
            f.bridge.observation = observed()
            f.coordinator.refresh()
            assertEquals(reads, f.bridge.observations.size)
            assertEquals(ended, f.state.value)
            assertTrue(f.state.value.restoreMissing)
            assertNull(f.state.value.currentBinding)
        }
    }

    @Test fun nativeEofAndCloseInvalidateAnInFlightFallbackRecoveryFrame() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture(TmuxIntent.Restore(first.name))
            f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
            f.coordinator.refresh()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.bridge.observation = observed()
            f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
            withTimeout(2000) { entered.await() }
            if (close) f.coordinator.close() else f.coordinator.dataEnded()
            val ended = f.state.value
            release.complete(Unit)
            read.await()
            assertEquals(ended, f.state.value)
            assertTrue(f.state.value.restoreMissing)
            assertNull(f.state.value.currentBinding)
        }
    }

    @Test fun confirmedCurrentKillOrDetachCannotReviveAsLiveFallback() = runBlocking {
        for (kill in listOf(false, true)) {
            val f = Fixture()
            val selection = f.select()
            val other = f.state.value.selectionFor(second)!!
            assertTrue(if (kill) f.coordinator.kill(selection) else f.coordinator.detach(selection))
            val ended = f.state.value
            val reads = f.bridge.observations.size
            f.bridge.observation = observed(clients = listOf(client.copy(sessionId = second.id)))
            f.coordinator.refresh()
            assertFalse(f.state.value.canManage)
            assertFalse(f.state.value.snapshot!!.verified)
            assertFalse(f.coordinator.validateSelection(other))
            assertFalse(f.coordinator.rename(other, "after-confirmed-end"))
            assertFalse(f.coordinator.detach(other))
            assertFalse(f.coordinator.kill(other))
            assertNull(f.coordinator.create("after-confirmed-end"))
            assertEquals(1, f.bridge.operations.size)
            assertEquals(reads, f.bridge.observations.size)
            assertEquals(ended, f.state.value)
            assertNull(f.state.value.currentBinding)
        }
    }

    @Test fun createPreflightDiscoveringMissingTargetCannotCreateOnTheOldSource() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.bridge.operationOutput = ack(created = second.copy(name = "manual-replacement").ref(server))
        assertNull(f.coordinator.create("manual-replacement"))
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertFalse(f.state.value.canCreate)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun missingTargetOnLiveFallbackKeepsVerifiedOtherRowsManageableWithoutCurrent() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.state.value.snapshot!!.verified)
        assertTrue(f.state.value.canManage)
        assertFalse(f.state.value.canCreate)
        assertNull(f.state.value.currentBinding)
        assertNull(f.state.value.lastBinding)
        val selected = f.state.value.selectionFor(second)!!
        assertTrue(f.coordinator.validateSelection(selected))
        assertFalse(f.coordinator.validateSelection(selected, requireCurrent = true))
        assertTrue(f.coordinator.validateSelection(selected))
        assertTrue(f.state.value.restoreMissing)
        assertEquals(TmuxIntent.Restore(first.name), f.coordinator.reconnectIntent())
        val reads = f.bridge.observations.size
        assertNull(f.coordinator.create("must-use-fresh-source"))
        assertEquals(reads, f.bridge.observations.size)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun liveFallbackCanRenameDetachOrKillExplicitOtherRowWithoutClaimingItAsCurrent() = runBlocking {
        for (action in listOf("rename", "detach", "kill")) {
            val f = Fixture(TmuxIntent.Restore(first.name))
            f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
            f.coordinator.refresh()
            assertTrue(f.state.value.canManage)
            val selected = f.state.value.selectionFor(second)!!
            val ok = when (action) {
                "rename" -> f.coordinator.rename(selected, "renamed-other")
                "detach" -> f.coordinator.detach(selected)
                else -> f.coordinator.kill(selected)
            }
            assertTrue(ok)
            assertTrue(f.ended().remoteEnded)
            assertTrue(f.state.value.restoreMissing)
            assertNull(f.state.value.currentBinding)
            assertNull(f.state.value.lastBinding)
            assertEquals(TmuxProblem.REMOTE_ENDED.name, f.state.value.message)
            assertEquals(TmuxIntent.Restore(first.name), f.coordinator.reconnectIntent())
            assertEquals(1, f.bridge.operations.size)
            assertTrue(f.bridge.operations.single().contains("moke_expected_ino=${server.socketInode}"))
            if (action == "rename") assertEquals("renamed-other", f.state.value.sessions.single().name)
            if (action == "kill") assertTrue(f.state.value.sessions.isEmpty())
            val operations = f.bridge.operations.size
            assertNull(f.coordinator.create("not-on-ended-source"))
            assertEquals(operations, f.bridge.operations.size)
        }
    }

    @Test fun missingCurrentFrozenRefIsRejectedWhileOtherFrozenRefCanStillBeManaged() = runBlocking {
        val f = Fixture(TmuxIntent.Open(first.ref(server)))
        val missingSelection = f.select()
        val otherSelection = f.state.value.selectionFor(second)!!
        f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertTrue(f.state.value.canManage)
        assertNull(f.state.value.currentBinding)
        assertEquals(first.ref(server), f.state.value.lastBinding?.session)
        assertFalse(f.coordinator.validateSelection(missingSelection))
        assertFalse(f.coordinator.validateSelection(missingSelection.copy(expectedClient = null)))
        assertFalse(f.coordinator.kill(missingSelection))
        assertFalse(f.coordinator.validateSelection(otherSelection, requireCurrent = true))
        assertTrue(f.coordinator.validateSelection(otherSelection))
        assertTrue(f.coordinator.rename(otherSelection, "other-renamed"))
        assertEquals(1, f.bridge.operations.size)
        assertNull(f.state.value.currentBinding)
        assertEquals(first.ref(server), f.state.value.lastBinding?.session)
        assertTrue(f.state.value.restoreMissing)
    }

    @Test fun missingFallbackOtherRowsStillNeedFreshOwnerAndListProof() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
        f.coordinator.refresh()
        val selected = f.state.value.selectionFor(second)!!
        f.bridge.output = { null }
        assertFalse(f.coordinator.rename(selected, "not-confirmed"))
        assertTrue(f.bridge.operations.isEmpty())
        assertFalse(f.state.value.snapshot!!.verified)
        assertFalse(f.state.value.canManage)
        assertTrue(f.state.value.restoreMissing)
        f.bridge.output = null
        f.bridge.observation = observed(owner = null, sessions = listOf(second), clients = listOf(otherClient))
        assertFalse(f.coordinator.detach(selected))
        assertTrue(f.bridge.operations.isEmpty())
        assertFalse(f.state.value.canManage)
        assertTrue(f.state.value.restoreMissing)
    }

    @Test fun nativeEofAndCloseDisableOtherRowsEvenWhenOriginalEndWasOnlyLogical() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture(TmuxIntent.Restore(first.name))
            f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
            f.coordinator.refresh()
            assertTrue(f.state.value.canManage)
            val selected = f.state.value.selectionFor(second)!!
            if (close) f.coordinator.close() else f.coordinator.dataEnded()
            val reads = f.bridge.observations.size
            assertFalse(f.state.value.canManage)
            assertFalse(f.state.value.snapshot!!.verified)
            assertFalse(f.coordinator.validateSelection(selected))
            assertFalse(f.coordinator.rename(selected, "after-data-end"))
            assertFalse(f.coordinator.detach(selected))
            assertFalse(f.coordinator.kill(selected))
            assertNull(f.coordinator.create("after-data-end"))
            assertEquals(reads, f.bridge.observations.size)
            assertTrue(f.bridge.operations.isEmpty())
            assertTrue(f.state.value.restoreMissing)
        }
    }

    @Test fun renamingAnotherRowToOldIntentNameDoesNotClearMissingOrBindByName() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(sessions = listOf(second), clients = listOf(otherClient))
        f.coordinator.refresh()
        val selected = f.state.value.selectionFor(second)!!
        assertTrue(f.coordinator.rename(selected, first.name))
        f.bridge.observation = observed(sessions = listOf(second.copy(name = first.name)), clients = listOf(otherClient))
        f.coordinator.refresh()
        assertTrue(f.ended().remoteEnded)
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.state.value.canManage)
        assertNull(f.state.value.currentBinding)
        assertNull(f.state.value.lastBinding)
        assertEquals(TmuxProblem.REMOTE_ENDED.name, f.state.value.message)
        assertEquals(1, f.bridge.operations.size)
        assertFalse(f.state.value.canCreate)
        val sameNameOther = f.state.value.selectionFor(second.copy(name = first.name))!!
        assertTrue(f.coordinator.validateSelection(sameNameOther))
        assertFalse(f.coordinator.validateSelection(sameNameOther, requireCurrent = true))
        val reads = f.bridge.observations.size
        assertNull(f.coordinator.create("not-before-real-attach"))
        assertEquals(reads, f.bridge.observations.size)
        assertEquals(1, f.bridge.operations.size)
        val actualClient = TmuxClient(TmuxClientId(82, 902, owner.tty), second.id)
        f.bridge.observation = observed(sessions = listOf(second.copy(name = first.name)), clients = listOf(actualClient))
        f.coordinator.refresh()
        assertEquals(second.copy(name = first.name).ref(server), f.state.value.currentBinding?.session)
        assertEquals(actualClient.identity, f.state.value.currentBinding?.client)
        assertFalse(f.state.value.restoreMissing)
        assertNull(f.state.value.message)
        assertTrue(f.state.value.canCreate)
        assertEquals(TmuxIntent.Open(second.copy(name = first.name).ref(server)), f.coordinator.reconnectIntent())
    }

    @Test fun restoreMissingDisablesUiCreateEvenIfPresentationIsInconsistent() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.coordinator.refresh()
        assertTrue(f.state.value.restoreMissing)
        assertTrue(f.state.value.snapshot!!.verified)
        for (presence in listOf(TmuxPresence.Pending, TmuxPresence.Shell(owner), TmuxPresence.Unknown())) {
            f.state.value = f.state.value.copy(presence = presence)
            assertFalse(f.state.value.canCreate)
        }
    }

    @Test fun restoreMissingCreateEntryRejectsInconsistentPresentationBeforeAnyControlRead() = runBlocking {
        val f = Fixture(TmuxIntent.Restore(first.name))
        f.bridge.observation = observed(TmuxProbeKind.NO_SERVER)
        f.coordinator.refresh()
        f.state.value = f.state.value.copy(presence = TmuxPresence.Pending)
        val before = f.state.value
        val reads = f.bridge.observations.size
        assertNull(f.coordinator.create("must-use-new-source"))
        assertEquals(reads, f.bridge.observations.size)
        assertEquals(before, f.state.value)
        assertTrue(f.bridge.operations.isEmpty())
    }

    @Test fun restoreMissingAppearingAtFinalCreateAuthorizationStillBlocksOperation() = runBlocking {
        val bridge = FakeBridge().apply {
            observation = observed(TmuxProbeKind.NO_SERVER)
            operationOutput = ack(created = second.copy(name = "must-not-create").ref(server))
        }
        val state = MutableStateFlow(TmuxUiState())
        var nonceReads = 0
        val coordinator = TmuxCoordinator(scope, TmuxIntent.None, TmuxScripts("observer", "probe"), bridge,
            ownerNonce = {
                if (++nonceReads == 3) {
                    state.value = state.value.copy(restoreMissing = true, presence = TmuxPresence.Pending,
                        message = TmuxProblem.REMOTE_ENDED.name)
                }
                nonce
            }, requireOwner = true, dataReady = { true }, dataAlive = { true }, state = state,
            message = { it.name }, clockNanos = { 10L })
        assertNull(coordinator.create("must-not-create"))
        assertEquals(3, nonceReads)
        assertTrue(state.value.restoreMissing)
        assertFalse(state.value.canCreate)
        assertTrue(bridge.operations.isEmpty())
    }

    @Test fun jointMetadataFollowsOnlyOwnedClientAndPublishesPaneTitleAndPathTogether() = runBlocking {
        val f = Fixture()
        val ours = client.copy(sessionCreated = first.created, pane = TmuxPane("%1", "default-host", "/one|first"))
        val theirs = otherClient.copy(sessionCreated = second.created, pane = TmuxPane("%2", "other task", "/two"))
        f.bridge.observation = observed(clients = listOf(theirs, ours))
        f.coordinator.refresh()
        val firstPane = f.state.value.currentPane!!
        assertEquals(ours.pane, firstPane.pane)
        assertEquals(client.identity, firstPane.binding.client)
        assertEquals(first.ref(server), firstPane.binding.session)
        assertEquals(f.state.value.snapshot!!.generation, firstPane.generation)
        f.bridge.observation = observed(clients = listOf(theirs,
            ours.copy(pane = TmuxPane("%3", "pane three", "/three"))))
        f.coordinator.refresh()
        assertEquals(TmuxPane("%3", "pane three", "/three"), f.state.value.currentPane!!.pane)
        assertTrue(f.state.value.currentPane!!.generation > firstPane.generation)
        // A session switch is a new client context, even if the new title/path happen to match.
        f.bridge.observation = observed(clients = listOf(ours.copy(sessionId = second.id,
            sessionCreated = second.created, pane = TmuxPane("%4", "new session", "/four"))))
        f.coordinator.refresh()
        assertEquals(second.ref(server), f.state.value.currentPane!!.binding.session)
        assertEquals(TmuxPane("%4", "new session", "/four"), f.state.value.currentPane!!.pane)
    }

    @Test fun paneBytesNeedMatchingOwnerServerClientSessionBirthAndScopeEvenWithParserSeam() = runBlocking {
        val ours = client.copy(sessionCreated = first.created, pane = TmuxPane("%1", "title", "/cwd"))
        val good = observed(clients = listOf(ours))
        for (invalid in listOf(
            good.copy(owner = owner.copy(nonce = "b".repeat(32))),
            good.copy(server = server.copy(startedTicks = 0)),
            good.copy(clients = listOf(ours.copy(sessionCreated = first.created + 1))),
            good.copy(clients = listOf(ours.copy(identity = client.identity.copy(tty = "not-a-tty")))),
            good.copy(clients = listOf(ours.copy(sessionCreated = null))),
            good.copy(clients = listOf(ours.copy(pane = TmuxPane("%1", "bad\u001b", "/cwd")))),
            good.copy(clients = listOf(ours.copy(pane = TmuxPane("%1", "title", "relative")))),
        )) {
            val f = Fixture(injectObservation = true)
            f.bridge.observation = invalid
            f.coordinator.refresh()
            assertNull(f.state.value.currentPane)
            assertFalse(f.state.value.snapshot?.verified == true)
        }
        val optional = Fixture(required = false)
        optional.currentNonce = null
        optional.bridge.observation = good.copy(owner = null)
        optional.coordinator.refresh()
        assertTrue(optional.state.value.snapshot!!.verified)
        assertNull(optional.state.value.currentPane)
        val f = Fixture()
        f.bridge.observation = good
        f.coordinator.refresh()
        val current = f.state.value.currentBinding!!
        assertNull(f.state.value.copy(presence = TmuxPresence.Attached(current.copy(client = current.client.copy(created = 1000)))).currentPane)
        assertNull(f.state.value.copy(presence = TmuxPresence.Attached(current.copy(scope = scope.copy(networkEpoch = 99)))).currentPane)
        assertNull(f.state.value.copy(targetChanged = true).currentPane)
    }

    @Test fun unrelatedClientsUnsafePaneTextCannotDisableVerifiedCurrentBindingOrExplicitManagement() = runBlocking {
        for (pane in listOf(TmuxPane("%2", "bad title", "/other"), TmuxPane("%2", "bad title", "/other"),
            TmuxPane("%2", "other", "/bad path"), TmuxPane("%2", "other", "/bad path"))) {
            val f = Fixture()
            val ours = client.copy(sessionCreated = first.created, pane = TmuxPane("%1", "current", "/current"))
            f.bridge.observation = observed(clients = listOf(ours, otherClient.copy(sessionCreated = second.created, pane = pane)))
            f.coordinator.refresh()
            assertEquals(TmuxPhase.READY, f.state.value.phase)
            assertTrue(f.state.value.snapshot!!.verified)
            assertTrue(f.state.value.canManage)
            assertEquals(first.ref(server), f.state.value.currentBinding!!.session)
            assertEquals(ours.pane, f.state.value.currentPane!!.pane)
            val unrelated = f.state.value.snapshot!!.observation.clients.single { it.identity == otherClient.identity }
            assertEquals(second.created, unrelated.sessionCreated)
            assertNull(unrelated.pane)
            val selection = f.state.value.selectionFor(second)!!
            assertTrue(f.coordinator.rename(selection, "renamed-other"))
            assertEquals(1, f.bridge.operations.size)
            assertEquals(first.ref(server), f.state.value.currentBinding!!.session)
            assertEquals("renamed-other", f.state.value.sessions.single { it.id == second.id }.name)
            assertTrue(f.state.value.canManage)
        }
    }

    @Test fun ownedClientsUnsafeOptionalPaneWithdrawsMetadataButKeepsStrongBindingManageable() = runBlocking {
        val f = Fixture()
        val ours = client.copy(sessionCreated = first.created, pane = TmuxPane("%1", "good", "/current"))
        f.bridge.observation = observed(clients = listOf(ours, otherClient))
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentPane)
        f.bridge.observation = observed(clients = listOf(ours.copy(pane = TmuxPane("%1", "bad title", "/current")), otherClient))
        f.coordinator.refresh()
        assertTrue(f.state.value.snapshot!!.verified)
        assertTrue(f.state.value.canManage)
        assertEquals(first.ref(server), f.state.value.currentBinding!!.session)
        assertEquals(client.identity, f.state.value.currentBinding!!.client)
        assertNull(f.state.value.currentPane)
        assertTrue(f.coordinator.rename(f.state.value.selectionFor(first)!!, "renamed-current"))
        assertEquals("renamed-current", f.state.value.currentBinding!!.session.name)
        assertEquals(1, f.bridge.operations.size)
    }

    @Test fun failedOrOwnerChangedRefreshWithdrawsCurrentPaneInsteadOfReusingCachedBytes() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(clients = listOf(client.copy(sessionCreated = first.created,
            pane = TmuxPane("%1", "title", "/cwd"))))
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentPane)
        f.bridge.output = { null }
        f.coordinator.refresh()
        assertNull(f.state.value.currentPane)
        f.bridge.output = null
        f.coordinator.refresh()
        assertNotNull(f.state.value.currentPane)
        f.bridge.beforeObserve = { f.currentNonce = "b".repeat(32) }
        f.coordinator.refresh()
        assertNull(f.state.value.currentPane)
    }

    @Test fun EOFAndCloseRejectLateJointPaneMetadataAndNeverResurrectOldLocation() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture()
            f.bridge.observation = observed(clients = listOf(client.copy(sessionCreated = first.created,
                pane = TmuxPane("%1", "title", "/cwd"))))
            f.coordinator.refresh()
            assertNotNull(f.state.value.currentPane)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
            withTimeout(2000) { entered.await() }
            if (close) f.coordinator.close() else f.coordinator.dataEnded()
            assertNull(f.state.value.currentPane)
            val ended = f.state.value
            release.complete(Unit)
            read.await()
            assertEquals(ended, f.state.value)
            assertNull(f.state.value.currentPane)
        }
    }

    @Test fun mergedRenameGenerationDiscardsPaneSampleAndRejectsSuspendedOlderPaneFrame() = runBlocking {
        val f = Fixture()
        f.bridge.observation = observed(clients = listOf(client.copy(sessionCreated = first.created,
            pane = TmuxPane("%1", "old pane title", "/old"))))
        f.coordinator.refresh()
        val oldGeneration = f.state.value.currentPane!!.generation
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bridge.beforeObserve = { entered.complete(Unit); release.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.refresh() }
        withTimeout(2000) { entered.await() }
        f.coordinator.renamed(first.ref(server), "renamed")
        assertTrue(f.state.value.snapshot!!.generation > oldGeneration)
        assertNull(f.state.value.currentPane)
        release.complete(Unit)
        read.await()
        assertNull(f.state.value.currentPane)
        assertEquals("renamed", f.state.value.currentBinding!!.session.name)
        f.bridge.beforeObserve = {}
        f.bridge.observation = observed(sessions = listOf(first.copy(name = "renamed"), second),
            clients = listOf(client.copy(sessionCreated = first.created, pane = TmuxPane("%2", "new", "/new"))))
        f.coordinator.refresh()
        assertEquals(TmuxPane("%2", "new", "/new"), f.state.value.currentPane!!.pane)
    }

    @Test fun eofOnDifferentThreadCannotPublishAnAlreadyParsedAttachedFrame() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val state = MutableStateFlow(TmuxUiState())
        val bridge = FakeBridge()
        val coordinator = TmuxCoordinator(scope, TmuxIntent.None, TmuxScripts("observer", "probe"), bridge,
            { nonce }, true, { true }, { true }, state, { it.name }, { 1L }, { output, request ->
                val parsed = TmuxRemote.parseObservation(output, request)
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                parsed
            })
        val reader = Thread {
            try { runBlocking { coordinator.refresh() } } catch (t: Throwable) { failure.set(t) }
        }
        reader.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.dataEnded()
        } finally {
            release.countDown()
            reader.join(3000)
        }
        assertFalse(reader.isAlive)
        failure.get()?.let { throw it }
        assertTrue(state.value.presence is TmuxPresence.Ended)
        assertFalse(state.value.snapshot?.verified == true)
    }
}
