package com.briqt.moke.terminal.sftp

import com.briqt.moke.data.Host
import com.briqt.moke.data.Route
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferManagerTest {

    private val host = Host(id = "h1", label = "prod", host = "server.example", port = 22, username = "user")
    private val okRoute = Route.Ok(host, null, null)

    private class MemoryStore : TransferManager.BackingStore {
        val saves = Collections.synchronizedList(mutableListOf<List<TransferTask>>())
        override suspend fun load(): List<TransferTask> = emptyList()
        override suspend fun save(list: List<TransferTask>) {
            saves.add(list)
        }
    }

    private object TestStrings : TransferManager.Strings {
        override fun interrupted(): String = "interrupted"
        override fun hostGone(): String = "host_gone"
        override fun routeRefusal(route: Route): String = "refused"
        override fun destinationUnverified(): String = "unverified"
        override fun destinationChanged(): String = "changed"
        override fun remoteMissing(): String = "missing"
        override fun localUnwritable(): String = "unwritable"
        override fun overwriteRequired(): String = "overwrite"
        override fun localUnreadable(): String = "unreadable"
        override fun localSeekFailed(): String = "seek"
        override fun createFailed(): String = "create"
        override fun describeFailure(t: Throwable, notice: String?): String = t.message ?: "failed"
    }

    @Test
    fun `interruptActive pauses running upload and download preserving exact done offset and upload resume permission`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore()
            val enteredRunning = CompletableDeferred<Unit>()
            val resourceAborted = CountDownLatch(1)
            val attemptCount = AtomicInteger(0)

            val manager = TransferManager(
                appContext = null,
                scope = scope,
                backingStore = store,
                routeResolver = { okRoute },
                strings = TestStrings,
                customExecutor = { task, _, _, callbacks ->
                    val runNumber = attemptCount.incrementAndGet()
                    if (runNumber == 1) {
                        val closed = AtomicBoolean(false)
                        val fakeSession = AutoCloseable {
                            if (closed.compareAndSet(false, true)) {
                                resourceAborted.countDown()
                            }
                        }
                        callbacks.attachCloseable(fakeSession) { it.close() }
                        enteredRunning.complete(Unit)
                        assertTrue("Active session must be aborted promptly", resourceAborted.await(2, TimeUnit.SECONDS))
                        assertTrue(callbacks.isAborted)
                        // Return exact unthrottled chunk boundary (64 KiB) even though UI progress was never pushed.
                        65_536L to null
                    } else {
                        // Second attempt (after retry) must inherit preserved done=65536 and upload permission.
                        assertEquals(65_536L, task.done)
                        task.total to null
                    }
                },
            )

            val uploadTask = TransferTask(
                id = "up-1",
                hostId = host.id,
                hostLabel = host.displayName,
                direction = TransferDirection.UPLOAD,
                remotePath = "/srv/archive.tar",
                name = "archive.tar",
                total = 262_144L,
                done = 0L,
            )
            manager.enqueueTasksForTest(
                listOf(uploadTask),
                uploadDestination = UploadDestination.from(host, null, null),
            )

            withTimeout(2_000) { enteredRunning.await() }
            manager.interruptActive()

            assertTrue("Session closeable must be aborted by interruptActive()", resourceAborted.await(2, TimeUnit.SECONDS))
            val interrupted = withTimeout(2_000) {
                manager.tasks.first { list ->
                    list.singleOrNull()?.let { it.state == TransferState.FAILED && it.done == 65_536L } == true
                }.single()
            }
            assertEquals("interrupted", interrupted.error)

            // Retry must keep upload destination permission and complete cleanly from offset 65536.
            manager.retry(uploadTask.id)
            val finished = withTimeout(2_000) {
                manager.tasks.first { list -> list.singleOrNull()?.state == TransferState.DONE }.single()
            }
            assertEquals(262_144L, finished.done)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `interruptActive while QUEUED task is resolving route prevents resurrection to RUNNING`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val inRouteLookup = CompletableDeferred<Unit>()
            val releaseRouteLookup = CompletableDeferred<Unit>()
            val executorCalled = AtomicBoolean(false)

            val manager = TransferManager(
                appContext = null,
                scope = scope,
                backingStore = MemoryStore(),
                routeResolver = {
                    inRouteLookup.complete(Unit)
                    releaseRouteLookup.await()
                    okRoute
                },
                strings = TestStrings,
                customExecutor = { _, _, _, _ ->
                    executorCalled.set(true)
                    100L to null
                },
            )

            val task = TransferTask(
                id = "dl-queued",
                hostId = host.id,
                hostLabel = host.displayName,
                direction = TransferDirection.DOWNLOAD,
                remotePath = "/srv/data.bin",
                name = "data.bin",
                total = 100L,
            )
            manager.enqueueTasksForTest(listOf(task))

            withTimeout(2_000) { inRouteLookup.await() }
            manager.interruptActive()
            assertEquals(TransferState.FAILED, manager.tasks.value.single().state)
            assertEquals("interrupted", manager.tasks.value.single().error)

            // Unblock route resolution: runOne must NOT resurrect the interrupted task to RUNNING or run executor.
            releaseRouteLookup.complete(Unit)
            Thread.sleep(100)
            val after = manager.tasks.value.single()
            assertEquals(TransferState.FAILED, after.state)
            assertEquals("interrupted", after.error)
            assertTrue("Interrupted QUEUED task must never enter executor", !executorCalled.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `immediate retry while interrupted attempt is still unwinding is never clobbered by old attempt`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val attempt1Running = CompletableDeferred<Unit>()
            val releaseAttempt1Unwind = CompletableDeferred<Unit>()
            val attempt1FinishedUnwind = CompletableDeferred<Unit>()
            val attempts = AtomicInteger(0)

            val manager = TransferManager(
                appContext = null,
                scope = scope,
                backingStore = MemoryStore(),
                routeResolver = { okRoute },
                strings = TestStrings,
                customExecutor = { task, _, _, callbacks ->
                    if (attempts.incrementAndGet() == 1) {
                        attempt1Running.complete(Unit)
                        // Hold attempt #1 in unwinding until AFTER interruptActive() AND retry() have both run!
                        releaseAttempt1Unwind.await()
                        callbacks.reportProgress(1_500_000L)
                        callbacks.updateTask { it.copy(error = "stale-overwrite") }
                        attempt1FinishedUnwind.complete(Unit)
                        throw IOException("aborted socket read")
                    } else {
                        task.total to null
                    }
                },
            )

            val task = TransferTask(
                id = "dl-race",
                hostId = host.id,
                hostLabel = host.displayName,
                direction = TransferDirection.DOWNLOAD,
                remotePath = "/srv/large.iso",
                name = "large.iso",
                total = 4_000_000L,
            )
            manager.enqueueTasksForTest(listOf(task))

            withTimeout(2_000) { attempt1Running.await() }
            manager.interruptActive()
            assertEquals(TransferState.FAILED, manager.tasks.value.single().state)

            // User immediately taps Resume while attempt #1 is still stuck unwinding!
            manager.retry(task.id)
            assertEquals(TransferState.QUEUED, manager.tasks.value.single().state)

            // Now let attempt #1 throw its late IOException and emit late progress/updates.
            releaseAttempt1Unwind.complete(Unit)
            withTimeout(2_000) { attempt1FinishedUnwind.await() }

            // Attempt #2 must still run cleanly to DONE without being reverted to FAILED by attempt #1.
            val completed = withTimeout(2_000) {
                manager.tasks.first { list -> list.singleOrNull()?.state == TransferState.DONE }.single()
            }
            assertEquals(4_000_000L, completed.done)
            assertEquals("", completed.error)
            assertEquals(2, attempts.get())
        } finally {
            scope.cancel()
        }
    }
}
