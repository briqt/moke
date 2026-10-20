package com.briqt.moke.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MoshChildProcessTest {
    private class Native : MoshProcessOps {
        val waits = AtomicInteger()
        val kills = AtomicInteger()
        val rawCloses = AtomicInteger()
        val waitEntered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        override fun waitFor(pid: Int): Int {
            assertEquals(42, pid)
            waits.incrementAndGet()
            waitEntered.countDown()
            assertTrue(exit.await(2, TimeUnit.SECONDS))
            return 0
        }
        override fun kill(pid: Int) { assertEquals(42, pid); kills.incrementAndGet(); exit.countDown() }
        override fun closeFd(fd: Int) { assertEquals(7, fd); rawCloses.incrementAndGet() }
    }

    @Test fun `close after native create before descriptor adoption still waits and finishes once`() {
        val native = Native()
        val lifetime = TransportResources()
        val finishes = AtomicInteger()
        val orphans = AtomicInteger()
        val leases = AtomicInteger(1)
        lifetime.own(Unit) { leases.decrementAndGet() }
        lifetime.stop().invoke() // createSubprocess was in flight when close won.
        val done = CountDownLatch(1)
        val child = MoshChildProcess.start(42, 7, native, { orphans.incrementAndGet() }) {
            lifetime.finish { finishes.incrementAndGet() }
            done.countDown()
        }
        assertThrows(IllegalStateException::class.java) { lifetime.own(child) { it.forceClose() } }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        child.forceClose()
        assertEquals(1, native.waits.get())
        assertEquals(1, native.kills.get())
        assertEquals(1, native.rawCloses.get())
        assertEquals(1, orphans.get())
        assertEquals(1, finishes.get())
        assertEquals(0, leases.get())
    }

    @Test fun `descriptor wrapping or stream initialization failures cannot bypass waiter`() {
        for (failure in listOf("adopt", "output", "input", "established")) {
            val native = Native()
            val wrapperCloses = AtomicInteger()
            val finishes = AtomicInteger()
            val orphans = AtomicInteger()
            val leases = AtomicInteger(1)
            val lifetime = TransportResources()
            lifetime.own(Unit) { leases.decrementAndGet() }
            val done = CountDownLatch(1)
            val child = MoshChildProcess.start(42, 7, native, { orphans.incrementAndGet() }) {
                lifetime.stop().invoke()
                lifetime.finish { finishes.incrementAndGet() }
                done.countDown()
            }
            lifetime.own(child) { it.forceClose() }
            assertTrue(native.waitEntered.await(2, TimeUnit.SECONDS))
            fun inject(stage: String) { if (failure == stage) error(stage) }
            assertThrows(IllegalStateException::class.java) {
                try {
                    child.adopt { fd ->
                        assertEquals(7, fd)
                        inject("adopt")
                        AutoCloseable { wrapperCloses.incrementAndGet() }
                    }
                    inject("output")
                    lifetime.establish { inject("established") }
                    inject("input")
                } finally { lifetime.stop().invoke() }
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            child.forceClose()
            lifetime.finish { finishes.incrementAndGet() }
            assertEquals(failure, 1, native.waits.get())
            assertEquals(failure, 1, native.kills.get())
            assertEquals(failure, if (failure == "adopt") 1 else 0, native.rawCloses.get())
            assertEquals(failure, if (failure == "adopt") 0 else 1, wrapperCloses.get())
            assertEquals(failure, 1, finishes.get())
            assertEquals(failure, 1, orphans.get())
            assertEquals(failure, 0, leases.get())
        }
    }

    @Test fun `normal exit owns fd cleanup and late force close cannot kill a reaped pid`() {
        val native = Native()
        val closes = AtomicInteger()
        val orphans = AtomicInteger()
        val done = CountDownLatch(1)
        val child = MoshChildProcess.start(42, 7, native, { orphans.incrementAndGet() }) { done.countDown() }
        child.adopt { AutoCloseable { closes.incrementAndGet() } }
        native.exit.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        child.forceClose()
        assertEquals(1, native.waits.get())
        assertEquals(0, native.kills.get())
        assertEquals(1, closes.get())
        assertEquals(0, native.rawCloses.get())
        assertEquals(0, orphans.get())
        assertEquals(-1, child.fd)
    }

    @Test fun `waiter exiting before adoption closes raw fd and refuses reuse`() {
        val native = Native()
        val done = CountDownLatch(1)
        native.exit.countDown()
        val child = MoshChildProcess.start(42, 7, native, {}) { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { child.adopt { error("must not adopt a reused fd") } }
        assertEquals(1, native.waits.get())
        assertEquals(1, native.rawCloses.get())
        assertEquals(0, native.kills.get())
    }

    @Test fun `waiter launch failure kills closes and reaps on the creating worker`() {
        val native = Native()
        val finishes = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            MoshChildProcess.start(42, 7, native, {}, launch = { error("thread failed") }) { finishes.incrementAndGet() }
        }
        assertEquals(1, native.waits.get())
        assertEquals(1, native.kills.get())
        assertEquals(1, native.rawCloses.get())
        assertEquals(1, finishes.get())
    }

    @Test fun `native wait error is reported as failure rather than a successful exit`() {
        val native = Native()
        val done = CountDownLatch(1)
        var exitCode: Int? = null
        val ops = object : MoshProcessOps {
            override fun waitFor(pid: Int): Int { native.waits.incrementAndGet(); error("ECHILD") }
            override fun kill(pid: Int) = native.kill(pid)
            override fun closeFd(fd: Int) = native.closeFd(fd)
        }
        val child = MoshChildProcess.start(42, 7, ops, {}) { exitCode = it; done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        child.forceClose()
        assertEquals(1, exitCode)
        assertEquals(1, native.waits.get())
        assertEquals(1, native.rawCloses.get())
        assertEquals(0, native.kills.get())
    }

    @Test fun `concurrent forced close releases a child and its remote orphan once`() {
        val native = Native()
        val orphans = AtomicInteger()
        val finished = CountDownLatch(1)
        val child = MoshChildProcess.start(42, 7, native, { orphans.incrementAndGet() }) { finished.countDown() }
        val gate = CountDownLatch(1)
        val closers = CountDownLatch(12)
        repeat(12) { Thread { gate.await(); child.forceClose(); closers.countDown() }.start() }
        gate.countDown()
        assertTrue(closers.await(2, TimeUnit.SECONDS))
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, native.waits.get())
        assertEquals(1, native.kills.get())
        assertEquals(1, native.rawCloses.get())
        assertEquals(1, orphans.get())
    }

    @Test fun `orphan cleanup requested before bootstrap result also takes the late server once`() {
        val kills = mutableListOf<MoshServerIdentity>()
        fun identity(pid: Int) = MoshServerIdentity(
            pid, 1000, "01234567-89ab-cdef-0123-456789abcdef", 4567, "0123456789abcdef0123456789abcdef",
        )
        val first = identity(123)
        val orphan = MoshServerProcess { kills.add(it) }
        orphan.cleanup()
        orphan.register(first)
        orphan.register(first) // A duplicate late registration is not a second cleanup authority.
        orphan.cleanup()
        assertEquals(listOf(first), kills)
        val normal = MoshServerProcess { kills.add(it) }
        normal.register(identity(456))
        normal.disarm()
        normal.cleanup()
        assertEquals(listOf(first), kills)
    }
}
