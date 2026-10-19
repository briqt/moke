package com.briqt.moke.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TransportResourcesTest {
    private class Handle(private val leases: AtomicInteger) {
        var closes = 0
        init { leases.incrementAndGet() }
        fun close() { closes++; leases.decrementAndGet() }
    }

    @Test fun `duplicate start is rejected and closing before start acquires nothing`() {
        val running = TransportResources()
        assertTrue(running.start())
        assertFalse(running.start())
        running.stop().invoke()
        assertFalse(running.start())
        assertTrue(running.started) // Its already-running worker/waiter still owns completion.
        val cancelled = TransportResources()
        cancelled.stop().invoke()
        assertFalse(cancelled.start())
        assertFalse(cancelled.started)
    }

    @Test fun `jump connect and authentication failures close both clients once`() {
        for (failure in listOf("jump connect", "jump auth", "target connect", "target auth")) {
            val leases = AtomicInteger()
            val target = Handle(leases)
            val jump = Handle(leases)
            assertThrows(IllegalStateException::class.java) {
                connectSshOwned(
                    target = target,
                    createJump = { jump },
                    connectJump = { if (failure == "jump connect") error(failure) },
                    authenticateJump = { if (failure == "jump auth") error(failure) },
                    connectTarget = { if (failure == "target connect") error(failure) },
                    authenticateTarget = { if (failure == "target auth") error(failure) },
                    disconnect = Handle::close,
                )
            }
            assertEquals(failure, 1, target.closes)
            assertEquals(failure, 1, jump.closes)
            assertEquals(failure, 0, leases.get())
        }
    }

    @Test fun `session PTY shell and exec failures and normal EOF have the same final owner`() {
        for (failure in listOf("session", "pty", "shell", "exec", "eof")) {
            val leases = AtomicInteger()
            val connection = Handle(leases)
            var session: Handle? = null
            val lifetime = TransportResources()
            val finishes = AtomicInteger()
            val outcome = runCatching {
                withSshChannel(
                    lifetime,
                    connect = { connection },
                    disconnect = Handle::close,
                    startSession = { if (failure == "session") error(failure); Handle(leases).also { session = it } },
                    closeSession = Handle::close,
                    allocatePty = { if (failure == "pty") error(failure) },
                    openChannel = { if (failure == "shell" || failure == "exec") error(failure); Unit },
                ) { _, _, _ ->
                    assertTrue(lifetime.establish {})
                    // Immediate EOF: no explicit UI close is needed to stop heartbeats or release leases.
                }
            }
            if (failure == "eof") assertTrue(outcome.isSuccess)
            else assertEquals(failure, outcome.exceptionOrNull()?.message)
            // Assert BEFORE any explicit close: otherwise this test hides the original EOF leak.
            assertTrue(failure, lifetime.closed)
            assertEquals(failure, 1, connection.closes)
            assertEquals(failure, if (failure == "session") null else 1, session?.closes)
            assertEquals(failure, 0, leases.get())
            lifetime.finish { finishes.incrementAndGet() }
            lifetime.finish { finishes.incrementAndGet() }
            lifetime.stop().invoke()
            assertEquals(failure, 1, finishes.get())
            assertEquals(failure, 1, connection.closes)
        }
    }

    @Test fun `normal EOF cleans resources and stops future latency probes before finish`() {
        val lifetime = TransportResources()
        val leases = AtomicInteger()
        val connection = Handle(leases)
        val session = Handle(leases)
        var finished = 0
        withSshChannel(
            lifetime, connect = { connection }, disconnect = Handle::close,
            startSession = { session }, closeSession = Handle::close,
            allocatePty = {}, openChannel = {},
        ) { _, _, _ -> assertTrue(lifetime.establish {}) }
        lifetime.finish {
            assertTrue(lifetime.closed) // RTT and login workers use this same terminal condition.
            assertEquals(0, leases.get())
            finished++
        }
        assertEquals(1, finished)
        assertEquals(1, connection.closes)
        assertEquals(1, session.closes)
    }

    @Test fun `socket abort precedes channel cleanup and unblocks an in flight write`() {
        val lifetime = TransportResources()
        val blockedWrite = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val events = mutableListOf<String>()
        val writer = Thread { blockedWrite.await(); writerDone.countDown() }.apply { isDaemon = true }
        writer.start()
        withSshChannel(
            lifetime, connect = { Unit }, disconnect = { events.add("disconnect") },
            startSession = { Unit },
            closeSession = { assertTrue(writerDone.await(2, TimeUnit.SECONDS)); events.add("session") },
            allocatePty = {}, openChannel = {},
            beforeSessionClose = { events.add("socket"); blockedWrite.countDown() },
        ) { _, _, _ -> }
        assertTrue(writerDone.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("socket", "session", "disconnect"), events)
    }

    @Test fun `close during connect or session creation rejects and closes late handles`() {
        for (stage in listOf("connect", "session", "channel")) {
            val lifetime = TransportResources()
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val done = CountDownLatch(1)
            val leases = AtomicInteger()
            val handles = mutableListOf<Handle>()
            val established = AtomicInteger()
            fun gate(at: String) {
                if (stage == at) { entered.countDown(); assertTrue(proceed.await(2, TimeUnit.SECONDS)) }
            }
            Thread {
                try {
                    runCatching {
                        withSshChannel(
                            lifetime,
                            connect = { gate("connect"); Handle(leases).also { handles.add(it) } },
                            disconnect = Handle::close,
                            startSession = { gate("session"); Handle(leases).also { handles.add(it) } },
                            closeSession = Handle::close,
                            allocatePty = {},
                            openChannel = { gate("channel") },
                        ) { _, _, _ -> lifetime.establish { established.incrementAndGet() } }
                    }
                } finally { done.countDown() }
            }.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            lifetime.stop().invoke()
            proceed.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(stage, 0, established.get())
            assertEquals(stage, 0, leases.get())
            assertTrue(handles.all { it.closes == 1 })
        }
    }

    @Test fun `stop detaches cleanup without waiting for a blocked writer and establishment is once`() {
        val lifetime = TransportResources()
        val leases = AtomicInteger()
        val handle = lifetime.own(Handle(leases), Handle::close)
        var established = 0
        assertTrue(lifetime.establish { established++ })
        assertFalse(lifetime.establish { established++ })
        val cleanup = lifetime.stop() // UI only changes state; the independent closer runs the returned work.
        assertTrue(lifetime.closed)
        assertEquals(0, handle.closes)
        cleanup()
        cleanup()
        assertFalse(lifetime.establish { established++ })
        assertEquals(1, established)
        assertEquals(1, handle.closes)
        assertEquals(0, leases.get())
    }
}
