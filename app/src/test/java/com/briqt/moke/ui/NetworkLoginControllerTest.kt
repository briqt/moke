package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.data.StoreResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetworkLoginControllerTest {
    private val entry = NetworkEntry(id = "network", name = "Network", kind = NetworkKind.TAILSCALE)

    private class Backend : NetworkLoginBackend {
        class Gate { val release = CountDownLatch(1); var fail = false }
        val entered = LinkedBlockingQueue<Long>()
        val gates = ConcurrentHashMap<Long, Gate>()
        val holds = ConcurrentHashMap<String, Long>()
        val keys = ConcurrentHashMap<Long, String>()
        @Volatile var finishing = false
        override fun hold(entry: NetworkEntry, attempt: Long) { holds[entry.id] = attempt }
        override fun end(entry: NetworkEntry, attempt: Long) { holds.remove(entry.id, attempt) }
        override fun begin(entry: NetworkEntry, authKey: String, attempt: Long): String {
            val gate = gates.computeIfAbsent(attempt) { Gate() }
            keys[attempt] = authKey
            entered.put(attempt)
            if (finishing) gate.release.countDown()
            check(gate.release.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
            if (gate.fail) error("late backend failure")
            return "https://login.example/$attempt"
        }
        fun next(): Long = entered.poll(3, TimeUnit.SECONDS) ?: error("login never reached backend")
        fun release(attempt: Long, fail: Boolean = false) {
            gates.getValue(attempt).also { it.fail = fail; it.release.countDown() }
        }
        fun finish() { finishing = true; gates.values.forEach { it.release.countDown() } }
    }

    private class Fixture(save: suspend (NetworkEntry, Boolean) -> StoreResult<NetworkEntry>) {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.Unconfined)
        val backend = Backend()
        val controller = NetworkLoginController(scope, save, backend) { "Unable to log in" }
        suspend fun settle() { withTimeout(5_000) { root.children.toList().joinAll() } }
        fun close() { backend.finish(); scope.cancel() }
    }

    private class PreparingBackend : NetworkLoginBackend {
        data class Owner(val entry: NetworkEntry, val attempt: Long)
        class Gate(val owner: Owner) {
            val release = CountDownLatch(1)
            var result: StoreResult<NetworkEntry> = StoreResult.Saved(owner.entry)
            var failure: Exception? = null
        }
        val preparing = LinkedBlockingQueue<Gate>()
        val gates = ConcurrentHashMap<Long, Gate>()
        val holds = ConcurrentHashMap<String, Owner>()
        val began = LinkedBlockingQueue<Owner>()
        val ended = LinkedBlockingQueue<Owner>()
        @Volatile var finishing = false

        override fun hold(entry: NetworkEntry, attempt: Long) { holds[entry.id] = Owner(entry, attempt) }
        override fun end(entry: NetworkEntry, attempt: Long) {
            ended.put(Owner(entry, attempt))
            holds.computeIfPresent(entry.id) { _, current -> current.takeUnless { it.attempt == attempt } }
        }
        override suspend fun prepare(entry: NetworkEntry): StoreResult<NetworkEntry> {
            val owner = holds.getValue(entry.id)
            val gate = Gate(owner)
            gates[owner.attempt] = gate
            preparing.put(gate)
            if (finishing) gate.release.countDown()
            // Deliberately non-cooperative to expose late preparation results after cancellation.
            check(gate.release.await(5, TimeUnit.SECONDS)) { "preparation test gate timed out" }
            gate.failure?.let { throw it }
            return gate.result
        }
        override fun begin(entry: NetworkEntry, authKey: String, attempt: Long): String {
            began.put(Owner(entry, attempt))
            return "https://login.example/$attempt"
        }
        fun next(): Gate = preparing.poll(3, TimeUnit.SECONDS) ?: error("login never reached preparation")
        fun finish() { finishing = true; gates.values.forEach { it.release.countDown() } }
    }

    private class PreparationFixture(saved: NetworkEntry) {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.Unconfined)
        val backend = PreparingBackend()
        val saves = LinkedBlockingQueue<NetworkEntry>()
        val failures = LinkedBlockingQueue<Throwable>()
        val controller = NetworkLoginController(scope, { e, _ -> saves.put(e); StoreResult.Saved(saved) }, backend) {
            failures.put(it)
            "Unable to log in"
        }
        suspend fun settle() { withTimeout(5_000) { root.children.toList().joinAll() } }
        fun close() { backend.finish(); scope.cancel() }
    }

    @Test fun `failed persistence never starts or holds a login`() = runBlocking {
        val f = Fixture { _, _ -> StoreResult.Failed(StoreFailure.ENCRYPTION) }
        try {
            assertEquals(StoreResult.Failed(StoreFailure.ENCRYPTION), f.controller.login(entry, "", false))
            assertTrue(f.backend.entered.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun `cancel before blocking backend returns cannot restore browser or flow`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val attempt = f.backend.next()
            f.controller.cancel(entry)
            f.backend.release(attempt)
            f.settle()
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
            assertTrue(f.backend.holds.isEmpty())
        } finally { f.close() }
    }

    @Test fun `late failure after cancellation does not resurrect error state`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val attempt = f.backend.next()
            f.controller.cancel(entry)
            f.backend.release(attempt, fail = true)
            f.settle()
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `old task finally cannot release a newer hold or replace its flow`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val first = f.backend.next()
            val firstJob = f.root.children.single()
            f.controller.login(entry, "", true)
            val second = f.backend.next()
            f.backend.release(first, fail = true)
            withTimeout(5_000) { firstJob.join() }
            assertEquals(second, f.backend.holds[entry.id])
            assertEquals(second, f.controller.flows.value.getValue(entry.id).attempt)
            f.backend.release(second)
            f.settle()
            assertEquals(second, f.controller.url.value?.attempt)
        } finally { f.close() }
    }

    @Test fun `completed snapshot from old attempt cannot finish a new login`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val first = f.backend.next()
            f.controller.login(entry, "", true)
            val second = f.backend.next()
            f.controller.complete(entry, first)
            assertEquals(second, f.controller.flows.value.getValue(entry.id).attempt)
            assertEquals(second, f.backend.holds[entry.id])
            f.backend.release(first)
            f.backend.release(second)
            f.settle()
        } finally { f.close() }
    }

    @Test fun `pending browser event becomes unusable after cancel or delete`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            f.backend.release(f.backend.next())
            f.settle()
            val request = f.controller.url.value!!
            f.controller.cancel(entry)
            assertNull(f.controller.consume(request))
            f.controller.reopen(entry.id)
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `current browser event is consumed once and can be explicitly reopened`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            f.backend.release(f.backend.next())
            f.settle()
            val request = f.controller.url.value!!
            assertEquals(request.url, f.controller.consume(request))
            assertNull(f.controller.consume(request))
            f.controller.reopen(entry.id)
            assertEquals(request.url, f.controller.url.value?.url)
            f.controller.complete(entry, request.attempt)
            assertTrue(f.controller.flows.value.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
        } finally { f.close() }
    }

    @Test fun `separate view models never reuse a runtime login attempt identity`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        val otherRoot = SupervisorJob()
        val otherScope = CoroutineScope(otherRoot + Dispatchers.Unconfined)
        val other = NetworkLoginController(otherScope, { e, _ -> StoreResult.Saved(e) }, f.backend) { "Unable to log in" }
        try {
            f.controller.login(entry, "", false)
            val first = f.backend.next()
            other.login(entry, "", true)
            val second = f.backend.next()
            assertNotEquals("Attempt tokens must be unique across view-model lifetimes", first, second)
            f.scope.cancel()
            f.backend.release(first)
            withTimeout(5_000) { f.root.join() }
            assertEquals(second, f.backend.holds[entry.id])
            f.backend.release(second)
            withTimeout(5_000) { otherRoot.children.toList().joinAll() }
        } finally { f.close(); otherScope.cancel() }
    }

    @Test fun `scope cancellation releases its current hold and pending flow`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val attempt = f.backend.next()
            f.scope.cancel()
            f.backend.release(attempt)
            withTimeout(5_000) { f.root.join() }
            assertTrue("A cancelled owner left its hold installed", f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `disposed owner also releases a pending browser flow after its worker completed`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e) }
        try {
            f.controller.login(entry, "", false)
            val attempt = f.backend.next()
            f.backend.release(attempt)
            f.settle()
            assertNotNull(f.controller.url.value)
            f.scope.cancel()
            withTimeout(5_000) { f.root.join() }
            assertTrue(f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `cancel during blocked preparation never begins or resurrects the login`() = runBlocking {
        val f = PreparationFixture(entry)
        try {
            assertEquals(StoreResult.Saved(entry), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            assertEquals(gate.owner.attempt, f.controller.flows.value.getValue(entry.id).attempt)
            f.controller.cancel(entry)
            assertTrue(f.backend.holds.isEmpty())
            gate.release.countDown()
            f.settle()
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
            assertTrue(f.failures.isEmpty())
        } finally { f.close() }
    }

    @Test fun `scope cancellation during blocked preparation cannot begin registration`() = runBlocking {
        val f = PreparationFixture(entry)
        try {
            f.controller.login(entry, "", false)
            val gate = f.backend.next()
            f.scope.cancel()
            gate.release.countDown()
            withTimeout(5_000) { f.root.join() }
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
            assertTrue(f.failures.isEmpty())
        } finally { f.close() }
    }

    @Test fun `superseded preparation cannot begin or release the newer attempt hold`() = runBlocking {
        val f = PreparationFixture(entry)
        try {
            f.controller.login(entry, "", false)
            val first = f.backend.next()
            val firstJob = f.root.children.single()
            f.controller.login(entry, "", true)
            val second = f.backend.next()
            first.release.countDown()
            withTimeout(5_000) { firstJob.join() }
            assertTrue(f.backend.began.isEmpty())
            assertEquals(second.owner, f.backend.holds[entry.id])
            assertEquals(second.owner.attempt, f.controller.flows.value.getValue(entry.id).attempt)
            assertTrue(f.failures.isEmpty())
            second.result = StoreResult.Saved(entry.copy(revision = 9))
            second.release.countDown()
            f.settle()
            assertEquals(listOf(PreparingBackend.Owner(entry.copy(revision = 9), second.owner.attempt)), f.backend.began.toList())
            assertEquals(second.owner.attempt, f.controller.url.value?.attempt)
            f.controller.cancel(entry)
            assertTrue(f.backend.holds.isEmpty())
        } finally { f.close() }
    }

    @Test fun `preparation failure is a login error after a successful save`() = runBlocking {
        val saved = entry.copy(name = "Saved", revision = 4)
        val f = PreparationFixture(saved)
        try {
            assertEquals(StoreResult.Saved(saved), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            assertEquals(saved, gate.owner.entry)
            gate.result = StoreResult.Failed(StoreFailure.UNREADABLE)
            gate.release.countDown()
            f.settle()
            assertEquals(listOf(entry), f.saves.toList())
            val failure = f.failures.single() as NetworkLoginPreparationException
            assertEquals(StoreFailure.UNREADABLE, failure.reason)
            val flow = f.controller.flows.value.getValue(entry.id)
            assertEquals("Unable to log in", flow.error)
            assertFalse(flow.busy)
            assertEquals(gate.owner.attempt, flow.attempt)
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertNull(f.controller.url.value)
            assertTrue(f.backend.ended.all { it.entry == saved })
        } finally { f.close() }
    }

    @Test fun `a thrown preparation failure also stays in the login flow`() = runBlocking {
        val f = PreparationFixture(entry)
        try {
            assertEquals(StoreResult.Saved(entry), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            val failure = IllegalStateException("test preparation failure")
            gate.failure = failure
            gate.release.countDown()
            f.settle()
            // Coroutine stack-trace recovery may copy the exception across withContext.
            val reported = f.failures.single()
            assertEquals(failure.javaClass, reported.javaClass)
            assertEquals(failure.message, reported.message)
            assertEquals("Unable to log in", f.controller.flows.value.getValue(entry.id).error)
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `cancelled preparation is not reported as a login error`() = runBlocking {
        val f = PreparationFixture(entry)
        try {
            assertEquals(StoreResult.Saved(entry), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            gate.failure = kotlinx.coroutines.CancellationException("test preparation cancellation")
            gate.release.countDown()
            f.settle()
            assertTrue(f.failures.isEmpty())
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    private suspend fun rejectsPreparedTarget(change: (NetworkEntry) -> NetworkEntry) {
        val f = PreparationFixture(entry)
        try {
            assertEquals(StoreResult.Saved(entry), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            gate.result = StoreResult.Saved(change(entry.copy(revision = 9)))
            gate.release.countDown()
            f.settle()
            assertEquals(StoreFailure.CONFLICT, (f.failures.single() as NetworkLoginPreparationException).reason)
            assertEquals("Unable to log in", f.controller.flows.value.getValue(entry.id).error)
            assertTrue(f.backend.began.isEmpty())
            assertTrue(f.backend.holds.isEmpty())
            assertNull(f.controller.url.value)
            assertTrue(f.backend.ended.all { it.entry == entry })
        } finally { f.close() }
    }

    @Test fun `prepared id cannot silently redirect a saved login`() = runBlocking { rejectsPreparedTarget { it.copy(id = "other-network") } }
    @Test fun `prepared kind cannot silently redirect a saved login`() = runBlocking { rejectsPreparedTarget { it.copy(kind = NetworkKind.PROXY) } }
    @Test fun `prepared control URL cannot silently redirect a saved login`() = runBlocking { rejectsPreparedTarget { it.copy(controlUrl = "https://other-control.example") } }

    @Test fun `prepared revision reaches begin and complete releases that actual owner`() = runBlocking {
        val saved = entry.copy(revision = 4)
        val prepared = saved.copy(revision = 9)
        val f = PreparationFixture(saved)
        try {
            assertEquals(StoreResult.Saved(saved), f.controller.login(entry, "", false))
            val gate = f.backend.next()
            gate.result = StoreResult.Saved(prepared)
            gate.release.countDown()
            f.settle()
            assertEquals(listOf(PreparingBackend.Owner(prepared, gate.owner.attempt)), f.backend.began.toList())
            assertEquals(gate.owner.attempt, f.controller.url.value?.attempt)
            f.controller.complete(entry, gate.owner.attempt)
            assertTrue(f.backend.holds.isEmpty())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
            assertEquals(listOf(PreparingBackend.Owner(prepared, gate.owner.attempt)), f.backend.ended.toList())
        } finally { f.close() }
    }

    @Test fun `cancel uses the prepared owner instead of the stale editor revision`() = runBlocking {
        val prepared = entry.copy(revision = 9)
        val f = PreparationFixture(entry)
        try {
            f.controller.login(entry, "", false)
            val gate = f.backend.next()
            gate.result = StoreResult.Saved(prepared)
            gate.release.countDown()
            f.settle()
            f.controller.cancel(entry)
            assertTrue(f.backend.holds.isEmpty())
            assertEquals(listOf(PreparingBackend.Owner(prepared, gate.owner.attempt)), f.backend.ended.toList())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `scope disposal uses the prepared owner after registration worker completes`() = runBlocking {
        val prepared = entry.copy(revision = 9)
        val f = PreparationFixture(entry)
        try {
            f.controller.login(entry, "", false)
            val gate = f.backend.next()
            gate.result = StoreResult.Saved(prepared)
            gate.release.countDown()
            f.settle()
            f.scope.cancel()
            withTimeout(5_000) { f.root.join() }
            assertTrue(f.backend.holds.isEmpty())
            assertEquals(listOf(PreparingBackend.Owner(prepared, gate.owner.attempt)), f.backend.ended.toList())
            assertTrue(f.controller.flows.value.isEmpty())
            assertNull(f.controller.url.value)
        } finally { f.close() }
    }

    @Test fun `login uses the saved value and only removes surrounding auth key whitespace`() = runBlocking {
        val f = Fixture { e, _ -> StoreResult.Saved(e.copy(name = "Saved", revision = 9)) }
        try {
            val result = f.controller.login(entry, "  key-value\n", false) as StoreResult.Saved
            assertEquals(9, result.value.revision)
            val attempt = f.backend.next()
            assertEquals("key-value", f.backend.keys[attempt])
            f.backend.release(attempt)
            f.settle()
        } finally { f.close() }
    }
}
