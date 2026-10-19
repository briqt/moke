package com.briqt.moke.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled interleavings: no Android, Keystore, Go library, or timing sleeps. */
class NetworkRuntimeTest {
    private val a = NetworkRuntime.Network("a", "a", true, "original-name")
    private val b = NetworkRuntime.Network("b", "b", true, "other-name")

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "gate was not released" }
        }
        fun await() = assertTrue("operation did not reach gate", entered.await(2, TimeUnit.SECONDS))
    }

    private class Files : NetworkRuntime.IdentityFiles {
        val markers = ConcurrentHashMap.newKeySet<String>()
        val states = ConcurrentHashMap.newKeySet<String>()
        val deletes = AtomicInteger()
        val reads = AtomicInteger()
        var readGate: Gate? = null
        var writeGate: Gate? = null
        var onDelete: (String) -> Unit = {}
        var failNextDelete = false
        override fun hasLoggedIn(id: String): Boolean {
            reads.incrementAndGet()
            val result = id in markers
            readGate?.block()
            return result
        }
        override fun hasState(id: String) = id in states
        override fun markLoggedIn(id: String) { writeGate?.block(); markers.add(id) }
        override fun clearLoggedIn(id: String) { markers.remove(id) }
        override fun deleteState(id: String) {
            if (failNextDelete) { failNextDelete = false; throw java.io.IOException("delete failed") }
            onDelete(id)
            states.remove(id)
            deletes.incrementAndGet()
        }
    }

    private class Bridge : NetworkRuntime.Bridge {
        val engines = ConcurrentHashMap.newKeySet<String>()
        val starts = ConcurrentHashMap<String, String>()
        val controls = ConcurrentHashMap<String, String>()
        val counts = ConcurrentHashMap<String, Int>()
        val states = ConcurrentHashMap<String, NetworkRuntime.Status>()
        val initialized = AtomicInteger()
        val startCalls = AtomicInteger()
        var onStart: (NetworkRuntime.Network) -> Unit = {}
        var startGate: Gate? = null
        var statusGate: Gate? = null
        var loginGate: Gate? = null
        var stopGate: Gate? = null
        var busy = false
        var startFailure: String? = null
        override fun initialize() { initialized.incrementAndGet() }
        override fun start(network: NetworkRuntime.Network) {
            if (network.id == "a") startGate?.block()
            startFailure?.let { throw NetworkRuntime.Failure(it, "start failed") }
            check(engines.add(network.instanceId)) { "duplicate engine" }
            starts[network.instanceId] = network.configuration
            controls[network.instanceId] = network.controlUrl
            startCalls.incrementAndGet()
            onStart(network)
        }
        override fun stop(id: String) {
            if (id == "a") stopGate?.block()
            if (busy || (counts[id] ?: 0) > 0) throw NetworkRuntime.Failure("BUSY", "still in use")
            engines.remove(id)
        }
        override fun logout(id: String) = Unit
        override fun status(id: String): NetworkRuntime.Status {
            val snapshot = states[id] ?: running()
            if (id == "a") statusGate?.block()
            return snapshot
        }
        override fun leases(id: String) = counts[id] ?: 0
        override fun loginUrl(id: String): String {
            val control = controls[id]?.ifBlank { null } ?: "https://login.invalid"
            if (id == "a") loginGate?.block()
            return "$control/$id"
        }
        override fun sweepTickets() = Unit
    }

    private class Fixture {
        val bridge = Bridge()
        val files = Files()
        @Volatile var now = 0L
        val runtime = NetworkRuntime(bridge, files, { now }, { now += 200 })
        init { files.onDelete = { check(it !in bridge.engines) { "deleted a live engine's identity" } } }
    }

    private fun failure(code: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("expected $code, got $error", error is NetworkRuntime.Failure && error.code == code)
    }

    @Test fun `cancelled unfinished login switches to the new control before returning its browser URL`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example", configuration = "new-control-config")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.files.states.add(a.id)
        f.runtime.holdLogin(old, 1)
        assertEquals("https://old-control.example/a", f.runtime.beginLogin(old, false, 1))
        f.runtime.endLogin(old, 1)
        f.runtime.holdLogin(replacement, 2)
        assertEquals("https://new-control.example/a", f.runtime.beginLogin(replacement, false, 2))
        assertEquals("new-control-config", f.bridge.starts[a.id])
        assertEquals(1, f.files.deletes.get())
        assertFalse(f.runtime.isRunning(old))
        assertTrue(f.runtime.isRunning(replacement))
        f.now = 100_000
        f.runtime.endLogin(old, 1)
        f.runtime.reapIdle()
        assertTrue("old finally released the replacement login hold", f.runtime.isRunning(replacement))
    }

    @Test fun `same control login keeps the live node despite hostname changes`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://same-control.example")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        f.runtime.endLogin(old, 1)
        val renamed = old.copy(configuration = "new-hostname")
        f.runtime.holdLogin(renamed, 2)
        assertEquals("https://same-control.example/a", f.runtime.beginLogin(renamed, false, 2))
        assertEquals(1, f.bridge.startCalls.get())
        assertEquals("original-name", f.bridge.starts[a.id])
        assertEquals(0, f.files.deletes.get())
    }

    @Test fun `control change refuses busy old nodes without retiring identity or losing the new hold`() {
        for (busyAtStop in listOf(false, true)) {
            val f = Fixture()
            val old = a.copy(controlUrl = "https://old-control.example")
            val replacement = old.copy(controlUrl = "https://new-control.example")
            f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
            f.files.states.add(a.id)
            f.runtime.holdLogin(old, 1)
            f.runtime.beginLogin(old, false, 1)
            f.runtime.endLogin(old, 1)
            if (busyAtStop) f.bridge.busy = true else f.bridge.counts[a.id] = 1
            f.runtime.holdLogin(replacement, 2)
            failure("BUSY") { f.runtime.beginLogin(replacement, false, 2) }
            assertTrue(f.runtime.isRunning(old))
            assertFalse(f.runtime.isRunning(replacement))
            assertTrue(a.id in f.files.states)
            assertEquals(0, f.files.deletes.get())
            assertEquals("https://old-control.example", f.bridge.controls[a.id])
            f.bridge.busy = false
            f.bridge.counts[a.id] = 0
            f.runtime.endLogin(old, 1)
            assertEquals("https://new-control.example/a", f.runtime.beginLogin(replacement, false, 2))
            assertEquals(1, f.files.deletes.get())
        }
    }

    @Test fun `registered marker or ambiguous status prevents switching control even with an auth key`() {
        val unregistered = running().copy(state = "NeedsLogin", everLoggedIn = false)
        for ((status, marker) in listOf(
            running() to false, unregistered.copy(everLoggedIn = true) to false,
            unregistered.copy(state = "NeedsMachineAuth") to false,
            unregistered.copy(state = "Starting") to false,
            unregistered.copy(expired = true) to false, unregistered to true,
        )) {
            for (authKey in listOf(false, true)) {
                val f = Fixture()
                val old = a.copy(controlUrl = "https://old-control.example")
                val replacement = old.copy(controlUrl = "https://new-control.example")
                f.bridge.states[a.id] = status
                f.runtime.holdLogin(old, 1)
                f.runtime.beginLogin(old, false, 1)
                f.runtime.endLogin(old, 1)
                if (marker) f.files.markers.add(a.id)
                f.runtime.holdLogin(replacement, 2)
                failure("CONTROL_CHANGED") { f.runtime.beginLogin(replacement, authKey, 2) }
                assertTrue(f.runtime.isRunning(old))
                assertFalse(f.runtime.isRunning(replacement))
                assertEquals(0, f.files.deletes.get())
                assertEquals(marker, a.id in f.files.markers)
                assertEquals("https://old-control.example", f.bridge.controls[a.id])
            }
        }
    }

    @Test fun `metadata for another control never exposes or clears the old identity facts`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example")
        f.runtime.ensureStarted(old)
        assertTrue(f.runtime.hasLoggedIn(old))
        f.bridge.states[a.id] = running().copy(expired = true, leases = 2)
        f.runtime.status(old)
        assertEquals(true, f.runtime.loggedInSnapshot(old))
        assertTrue(f.runtime.isKnownExpired(old))
        assertEquals(2, f.runtime.leases(old))
        val reads = f.files.reads.get()
        assertEquals(false, f.runtime.loggedInSnapshot(replacement))
        assertFalse(f.runtime.hasLoggedIn(replacement))
        assertFalse(f.runtime.isKnownExpired(replacement))
        assertEquals(0, f.runtime.leases(replacement))
        assertEquals(0, f.runtime.refreshLeases(replacement))
        assertFalse(f.runtime.isRunning(replacement))
        assertNull(f.runtime.status(replacement))
        assertEquals(reads, f.files.reads.get())
        assertTrue(a.id in f.files.markers)
        assertTrue(f.runtime.hasLoggedIn(old))
        assertTrue(f.runtime.isKnownExpired(old))
        failure("CONTROL_CHANGED") { f.runtime.withStarted(replacement) { error("wrong control dialed") } }
        assertTrue(f.runtime.isRunning(old))
    }

    @Test fun `control binding survives idle stop so late old callers cannot restart the old server`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example", configuration = "new-control-config")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        f.runtime.endLogin(old, 1)
        f.runtime.holdLogin(replacement, 2)
        f.runtime.beginLogin(replacement, false, 2)
        f.runtime.endLogin(replacement, 2)
        f.files.states.add(a.id)
        f.now = 100_000
        f.runtime.reapIdle()
        assertFalse(f.runtime.isRunning(replacement))
        val calls = f.bridge.startCalls.get()
        failure("CONTROL_CHANGED") { f.runtime.withStarted(old) { error("old dial revived") } }
        f.runtime.holdLogin(old, 3)
        failure("CONTROL_CHANGED") { f.runtime.beginLogin(old, false, 3) }
        assertEquals(calls, f.bridge.startCalls.get())
        assertEquals(1, f.files.deletes.get())
        assertTrue(a.id in f.files.states)
        f.runtime.holdLogin(replacement, 4)
        assertEquals("https://new-control.example/a", f.runtime.beginLogin(replacement, false, 4))
    }

    @Test fun `cancelled control switch retires only old files and preserves replacement hold`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example")
        val newest = old.copy(controlUrl = "https://newest-control.example")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.files.states.add(a.id)
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        f.runtime.endLogin(old, 1)
        f.runtime.holdLogin(replacement, 2)
        val gate = Gate().also { f.bridge.stopGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val switching = pool.submit { failure("CANCELLED") { f.runtime.beginLogin(replacement, false, 2) } }
            gate.await()
            f.runtime.endLogin(replacement, 2)
            f.runtime.holdLogin(newest, 3)
            gate.release.countDown()
            switching.get(2, TimeUnit.SECONDS)
            assertEquals(0, f.files.deletes.get())
            assertTrue(a.id in f.files.states)
            f.bridge.stopGate = null
            assertEquals("https://newest-control.example/a", f.runtime.beginLogin(newest, false, 3))
            assertEquals(1, f.files.deletes.get())
            f.files.states.add(a.id)
            f.runtime.endLogin(replacement, 2)
            f.now = 100_000
            f.runtime.reapIdle()
            assertTrue(f.runtime.isRunning(newest))
            assertTrue(a.id in f.files.states)
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `queued dial cannot borrow the generation created by a control switch`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        f.runtime.endLogin(old, 1)
        f.runtime.holdLogin(replacement, 2)
        val gate = Gate().also { f.bridge.stopGate = it }
        f.bridge.onStart = { if (it.controlUrl == replacement.controlUrl) f.bridge.states[a.id] = running() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val switch = pool.submit<String> { f.runtime.beginLogin(replacement, false, 2) }
            gate.await()
            val thread = AtomicReference<Thread>()
            val actions = AtomicInteger()
            val queued = pool.submit {
                thread.set(Thread.currentThread())
                failure("CANCELLED") { f.runtime.withStarted(replacement) { actions.incrementAndGet() } }
            }
            @Suppress("UNCHECKED_CAST")
            val locks = NetworkRuntime::class.java.getDeclaredField("gates").apply { isAccessible = true }.get(f.runtime) as Map<String, ReentrantLock>
            val lock = locks.getValue(a.id)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((thread.get() == null || !lock.hasQueuedThread(thread.get())) && System.nanoTime() < deadline) Thread.yield()
            assertTrue("old generation request was not queued at IO gate", thread.get() != null && lock.hasQueuedThread(thread.get()))
            gate.release.countDown()
            assertEquals("https://new-control.example/a", switch.get(2, TimeUnit.SECONDS))
            queued.get(2, TimeUnit.SECONDS)
            assertEquals(0, actions.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `old status and delayed deletion cannot mutate the replacement control identity`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example")
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        val gate = Gate().also { f.bridge.statusGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val oldStatus = pool.submit<NetworkRuntime.Status?> { f.runtime.status(old) }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(old)
            f.runtime.holdLogin(replacement, 2)
            gate.release.countDown()
            assertNull(oldStatus.get(2, TimeUnit.SECONDS))
            f.bridge.statusGate = null
            f.runtime.beginLogin(replacement, false, 2)
            f.runtime.status(replacement)
            f.files.states.add(a.id)
            assertNull(f.runtime.status(old))
            assertTrue(f.runtime.forget(old, deletion))
            assertTrue(f.runtime.isRunning(replacement))
            assertTrue(f.runtime.hasLoggedIn(replacement))
            assertTrue(a.id in f.files.states)
            assertEquals(1, f.files.deletes.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `legacy login for a different control cannot overwrite the explicit replacement hold`() {
        val f = Fixture()
        val old = a.copy(controlUrl = "https://old-control.example")
        val replacement = old.copy(controlUrl = "https://new-control.example")
        f.bridge.states[a.id] = running().copy(state = "NeedsLogin", everLoggedIn = false)
        f.runtime.holdLogin(old, 1)
        f.runtime.beginLogin(old, false, 1)
        f.runtime.endLogin(old, 1)
        f.runtime.holdLogin(replacement, 2)
        f.runtime.beginLogin(replacement, false, 2)
        failure("CONTROL_CHANGED") { f.runtime.beginLogin(old, false) }
        assertEquals("https://new-control.example/a", f.runtime.beginLogin(replacement, false, 2))
        f.now = 100_000
        f.runtime.reapIdle()
        assertTrue(f.runtime.isRunning(replacement))
    }

    @Test fun `an untracked already started tailnet cannot be adopted under a new control binding`() {
        val f = Fixture()
        f.bridge.startFailure = "ALREADY_STARTED"
        val network = a.copy(controlUrl = "https://new-control.example")
        f.runtime.holdLogin(network, 1)
        failure("CONTROL_CHANGED") { f.runtime.beginLogin(network, false, 1) }
        assertFalse(f.runtime.isRunning(network))
    }

    @Test fun `blocked start does not own metadata or another network gate`() {
        val f = Fixture()
        val gate = Gate().also { f.bridge.startGate = it }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = pool.submit { f.runtime.ensureStarted(a) }
            gate.await()
            pool.submit {
                assertFalse(f.runtime.isRunning(a))
                assertFalse(f.runtime.isKnownExpired(a))
                assertEquals(0, f.runtime.leases(a))
                f.runtime.holdLogin(a, 1)
                f.runtime.endLogin(a, 1)
                f.runtime.ensureStarted(b)
                assertTrue(f.runtime.isRunning(b))
            }.get(2, TimeUnit.SECONDS)
            gate.release.countDown()
            start.get(2, TimeUnit.SECONDS)
            assertEquals(1, f.bridge.initialized.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `startup and lease acquisition are one idle stop critical section`() {
        val f = Fixture()
        val acquired = Gate()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val dial = pool.submit {
                f.runtime.withStarted(a) {
                    acquired.block()
                    f.bridge.counts[a.instanceId] = 1
                }
            }
            acquired.await()
            f.now = 100_000
            f.runtime.reapIdle()
            assertTrue(a.instanceId in f.bridge.engines)
            acquired.release.countDown()
            dial.get(2, TimeUnit.SECONDS)
            f.runtime.reapIdle()
            assertEquals(1, f.runtime.leases(a))
            assertTrue(f.runtime.isRunning(a))
        } finally { acquired.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `busy forget refuses registration until old node stops and never deletes replacement on idle`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        f.runtime.status(a)
        assertTrue(f.files.markers.contains(a.id))
        f.bridge.busy = true
        assertTrue(f.runtime.forget(a))
        assertFalse(f.runtime.hasLoggedIn(a))
        assertNull(f.runtime.status(a))
        assertEquals(0, f.files.deletes.get())
        f.runtime.holdLogin(a, 2)
        failure("BUSY") { f.runtime.beginLogin(a, freshIdentity = false, attempt = 2) }
        assertEquals(0, f.files.deletes.get())
        f.bridge.busy = false
        f.runtime.beginLogin(a, freshIdentity = false, attempt = 2)
        f.runtime.status(a)
        f.runtime.endLogin(a, 2)
        assertTrue(f.runtime.hasLoggedIn(a))
        assertEquals(1, f.files.deletes.get())
        f.now = 100_000
        f.runtime.reapIdle()
        assertFalse(f.runtime.isRunning(a))
        assertTrue(f.files.markers.contains(a.id))
        assertEquals(1, f.files.deletes.get())
    }

    @Test fun `forget invalidates an in flight Running snapshot before waiting for Go`() {
        val f = Fixture()
        f.runtime.holdLogin(a, 1)
        f.runtime.beginLogin(a, false, 1)
        val gate = Gate().also { f.bridge.statusGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val status = pool.submit<NetworkRuntime.Status?> { f.runtime.status(a) }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            assertEquals(false, f.runtime.loggedInSnapshot(a))
            gate.release.countDown()
            assertNull(status.get(2, TimeUnit.SECONDS))
            // The old snapshot must not create a marker even before forget's IO phase gets the gate.
            assertFalse(f.files.markers.contains(a.id))
            assertFalse(f.runtime.hasLoggedIn(a))
            f.runtime.forget(a, deletion)
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `late marker write cannot recreate marker after forget`() {
        val f = Fixture()
        f.runtime.holdLogin(a, 1)
        f.runtime.beginLogin(a, false, 1)
        val gate = Gate().also { f.files.writeGate = it }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val status = pool.submit { f.runtime.status(a) }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            gate.release.countDown()
            status.get(2, TimeUnit.SECONDS)
            assertFalse(f.files.markers.contains(a.id))
            assertEquals(false, f.runtime.loggedInSnapshot(a))
            f.runtime.forget(a, deletion)
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `initial marker state is unknown and late read cannot resurrect forgotten identity`() {
        val f = Fixture()
        f.files.markers.add(a.id)
        assertNull(f.runtime.loggedInSnapshot(a))
        val gate = Gate().also { f.files.readGate = it }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<Boolean> { f.runtime.hasLoggedIn(a) }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            val forget = pool.submit { f.runtime.forget(a, deletion) }
            gate.release.countDown()
            assertFalse(read.get(2, TimeUnit.SECONDS))
            forget.get(2, TimeUnit.SECONDS)
            assertEquals(false, f.runtime.loggedInSnapshot(a))
            assertEquals(0, f.bridge.initialized.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `stale login return and old finally cannot release replacement hold`() {
        val f = Fixture()
        f.runtime.holdLogin(a, 10)
        val gate = Gate().also { f.bridge.loginGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit { failure("CANCELLED") { f.runtime.beginLogin(a, false, 10) } }
            gate.await()
            f.runtime.endLogin(a, 10)
            f.runtime.holdLogin(a, 11)
            f.runtime.endLogin(a, 10)
            gate.release.countDown()
            old.get(2, TimeUnit.SECONDS)
            f.bridge.loginGate = null
            f.runtime.beginLogin(a, false, 11)
            f.now = 100_000
            f.runtime.reapIdle()
            assertTrue(f.runtime.isRunning(a))
            f.runtime.endLogin(a, 11)
            f.now += 100_000
            f.runtime.reapIdle()
            assertFalse(f.runtime.isRunning(a))
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `cancelled queued login cannot reinstall its hold or start Go`() {
        val f = Fixture()
        f.runtime.holdLogin(a, 1)
        f.runtime.endLogin(a, 1)
        failure("CANCELLED") { f.runtime.beginLogin(a, false, 1) }
        assertEquals(0, f.bridge.initialized.get())
        assertFalse(f.runtime.isRunning(a))
    }

    @Test fun `name change does not restart live node and is used at next node startup`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        val renamed = a.copy(configuration = "saved-name")
        f.runtime.ensureStarted(renamed)
        assertEquals("original-name", f.bridge.starts[a.id])
        f.now = 100_000
        f.runtime.reapIdle()
        f.runtime.ensureStarted(renamed)
        assertEquals("saved-name", f.bridge.starts[a.id])
    }

    @Test fun `idle stop blocked on A does not block snapshots or starting B`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        val gate = Gate().also { f.bridge.stopGate = it }
        f.now = 100_000
        val pool = Executors.newFixedThreadPool(2)
        try {
            val stop = pool.submit { f.runtime.reapIdle() }
            gate.await()
            pool.submit {
                f.runtime.endLogin(a, 123)
                assertEquals(0, f.runtime.leases(a))
                f.runtime.ensureStarted(b)
            }.get(2, TimeUnit.SECONDS)
            gate.release.countDown()
            stop.get(2, TimeUnit.SECONDS)
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `a delayed forget body cannot remove the identity created by a newer login`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        val deletion = f.runtime.invalidateIdentity(a)
        f.runtime.holdLogin(a, 2)
        f.runtime.beginLogin(a, false, 2)
        f.runtime.status(a)
        assertEquals(1, f.files.deletes.get())
        assertTrue(f.runtime.forget(a, deletion))
        assertTrue(f.runtime.isRunning(a))
        assertTrue(f.runtime.hasLoggedIn(a))
        assertEquals(1, f.files.deletes.get())
    }

    @Test fun `late lease from an invalidated operation has exactly one discard owner`() {
        val f = Fixture()
        val gate = Gate()
        val discarded = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val operation = pool.submit {
                failure("CANCELLED") {
                    f.runtime.withStarted(a, discard = { _: String ->
                        f.bridge.counts[a.id] = 0
                        discarded.incrementAndGet()
                    }) {
                        gate.block()
                        f.bridge.counts[a.id] = 1
                        "ticket"
                    }
                }
            }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            gate.release.countDown()
            operation.get(2, TimeUnit.SECONDS)
            f.runtime.forget(a, deletion)
            assertEquals(1, discarded.get())
            assertFalse(f.runtime.isRunning(a))
            assertEquals(1, f.files.deletes.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `forget during startup tracks and stops the late engine before deleting its files`() {
        val f = Fixture()
        val gate = Gate().also { f.bridge.startGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val start = pool.submit { failure("CANCELLED") { f.runtime.ensureStarted(a) } }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            gate.release.countDown()
            start.get(2, TimeUnit.SECONDS)
            f.runtime.forget(a, deletion)
            assertFalse(a.id in f.bridge.engines)
            assertEquals(1, f.files.deletes.get())
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `old expired snapshot cannot mark a forgotten or replacement identity expired`() {
        val f = Fixture()
        f.runtime.holdLogin(a, 1)
        f.runtime.beginLogin(a, false, 1)
        f.bridge.states[a.id] = running().copy(expired = true)
        val gate = Gate().also { f.bridge.statusGate = it }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val status = pool.submit<NetworkRuntime.Status?> { f.runtime.status(a) }
            gate.await()
            val deletion = f.runtime.invalidateIdentity(a)
            gate.release.countDown()
            assertNull(status.get(2, TimeUnit.SECONDS))
            assertFalse(f.runtime.isKnownExpired(a))
            f.runtime.forget(a, deletion)
            f.bridge.statusGate = null
            f.bridge.states[a.id] = running()
            f.runtime.holdLogin(a, 2)
            f.runtime.beginLogin(a, false, 2)
            f.runtime.status(a)
            assertFalse(f.runtime.isKnownExpired(a))
            assertTrue(f.runtime.hasLoggedIn(a))
        } finally { gate.release.countDown(); pool.shutdownNow() }
    }

    @Test fun `auth key registration cannot erase identity of a busy live engine`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        f.bridge.busy = true
        f.runtime.holdLogin(a, 1)
        failure("BUSY") { f.runtime.beginLogin(a, true, 1) }
        assertEquals(0, f.files.deletes.get())
        assertTrue(f.files.markers.contains(a.id))
        assertTrue(f.runtime.isRunning(a))
    }

    @Test fun `marker cache reads once and unused metadata does not initialize Go`() {
        val f = Fixture()
        f.files.markers.add(a.id)
        assertNull(f.runtime.loggedInSnapshot(a))
        assertTrue(f.runtime.hasLoggedIn(a))
        assertEquals(true, f.runtime.loggedInSnapshot(a))
        assertTrue(f.runtime.hasLoggedIn(a))
        assertEquals(1, f.files.reads.get())
        assertEquals(0, f.bridge.initialized.get())
        assertTrue(f.runtime.forget(b.copy(tailnet = false)))
        assertEquals(0, f.bridge.initialized.get())
    }

    @Test fun `background lease refresh with no leases does not perpetually postpone idle stop`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        f.now = 50_000
        assertEquals(0, f.runtime.refreshLeases(a))
        f.now = 100_000
        f.runtime.reapIdle()
        assertFalse(f.runtime.isRunning(a))
    }

    @Test fun `asynchronous maintenance never queues B behind a slow A stop`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        f.runtime.ensureStarted(b)
        val gate = Gate().also { f.bridge.stopGate = it }
        f.now = 100_000
        val pool = Executors.newFixedThreadPool(2)
        try {
            val bDone = CountDownLatch(1)
            f.runtime.reapIdle { task -> pool.submit { task(); if (!f.runtime.isRunning(b)) bDone.countDown() } }
            gate.await()
            assertTrue(bDone.await(2, TimeUnit.SECONDS))
            assertFalse(f.runtime.isRunning(b))
        } finally { gate.release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `failed deferred deletion is retried without restarting Go or losing retirement`() {
        val f = Fixture()
        f.runtime.ensureStarted(a)
        f.bridge.busy = true
        f.runtime.forget(a)
        f.bridge.busy = false
        f.files.failNextDelete = true
        f.now = 100_000
        f.runtime.reapIdle()
        assertFalse(f.runtime.isRunning(a))
        assertEquals(0, f.files.deletes.get())
        f.runtime.reapIdle()
        assertEquals(1, f.files.deletes.get())
        assertEquals(1, f.bridge.initialized.get())
    }

    @Test fun `auth key registration must not accept already started as successful key application`() {
        val f = Fixture()
        f.bridge.startFailure = "ALREADY_STARTED"
        f.runtime.holdLogin(a, 1)
        failure("BUSY") { f.runtime.beginLogin(a, true, 1) }
    }

    @Test fun `generated device name is always within the Go UTF8 byte limit`() {
        val models = listOf("", "型".repeat(100), "Ü".repeat(100), "Model / Name\\42\u0000\r\n\t", "a".repeat(200), "😀")
        for (model in models) {
            val name = defaultDeviceNameForModel(model)
            assertTrue(name.startsWith("moke"))
            assertTrue(name.toByteArray(Charsets.UTF_8).size in 1..63)
            assertFalse(name.any { it in "\u0000\r\n\t ./\\" })
            assertFalse(name.endsWith('-'))
            assertFalse(name.any { it.isSurrogate() })
        }
        assertEquals("moke-" + "型".repeat(19), defaultDeviceNameForModel("型".repeat(100)))
        assertEquals("moke-model-name-42", defaultDeviceNameForModel("Model / Name\\42"))
    }

    companion object {
        private fun running() = NetworkRuntime.Status("Running", emptyList(), "", "", false, true, 0)
    }
}
