package com.briqt.moke.terminal

// type: bootstrap cancellation and startup submission regression tests
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MoshBootstrapOperationTest {
    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun `close before connect starts makes no connection and sends no startup`() {
        val operation = MoshBootstrapOperation<Resource>()
        operation.cancel().invoke()
        var connects = 0
        var startups = 0
        assertThrows(IllegalStateException::class.java) {
            operation.run(
                connect = { connects++; Resource() }, prepare = { Unit }, startSession = { Resource() },
                startup = { _, _ -> startups++ }, witness = { _, _ -> },
            )
        }
        assertEquals(0, connects)
        assertEquals(0, startups)
    }

    private fun cancelledAt(stage: String) {
        val operation = MoshBootstrapOperation<Resource>()
        val connection = Resource()
        val session = Resource()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val startups = AtomicInteger()
        val sessions = AtomicInteger()
        fun pause(name: String) {
            if (name == stage) {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS)) { "test gate timed out" }
            }
        }
        Thread {
            try {
                operation.run(
                    connect = { pause("connect"); connection },
                    prepare = { pause("capability"); Unit },
                    startSession = { sessions.incrementAndGet(); pause("startSession"); session },
                    startup = { _, _ -> startups.incrementAndGet() },
                    witness = { _, _ -> error("cancelled bootstrap cannot have a result") },
                )
            } catch (t: Throwable) { failure.set(t) }
            finally { completed.countDown() }
        }.start()
        assertTrue("$stage gate not entered", entered.await(3, TimeUnit.SECONDS))
        // cancel itself performs only metadata work; even socket closing is a returned background action.
        val cleanup = operation.cancel()
        assertEquals(0, connection.closes.get())
        resume.countDown()
        assertTrue("$stage did not stop", completed.await(3, TimeUnit.SECONDS))
        cleanup()
        operation.cancel().invoke()
        assertTrue("$stage cancellation missing: ${failure.get()}", failure.get() is IllegalStateException)
        assertEquals("$stage sent an old startup after close", 0, startups.get())
        assertEquals(1, connection.closes.get())
        assertEquals(if (stage == "startSession") 1 else 0, session.closes.get())
        if (stage != "startSession") assertEquals(0, sessions.get())
    }

    @Test fun `close while connect is paused rejects and releases the late connection`() = cancelledAt("connect")
    @Test fun `close while capability is paused cannot submit old open or takeover startup`() = cancelledAt("capability")
    @Test fun `close while startSession is paused releases late session without startup exec`() = cancelledAt("startSession")

    @Test fun `startup committed before close retains bootstrap owner through same connection witness`() {
        val operation = MoshBootstrapOperation<Resource>()
        val connection = Resource()
        val session = Resource()
        val submitted = CountDownLatch(1)
        val resultReady = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val witnesses = AtomicInteger()
        Thread {
            try {
                assertEquals("reply", operation.run(
                    connect = { connection }, prepare = { "startup" }, startSession = { session },
                    startup = { _, command ->
                        assertEquals("startup", command)
                        submitted.countDown()
                        check(resultReady.await(3, TimeUnit.SECONDS))
                        "reply"
                    },
                    witness = { same, result ->
                        assertSame(connection, same)
                        assertEquals("reply", result)
                        assertEquals(0, connection.closes.get())
                        witnesses.incrementAndGet()
                    },
                ))
            } catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }.start()
        assertTrue(submitted.await(3, TimeUnit.SECONDS))
        operation.cancel().invoke()
        assertEquals(0, connection.closes.get())
        resultReady.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(1, witnesses.get())
        assertEquals(1, session.closes.get())
        assertEquals(1, connection.closes.get())
    }

    @Test fun `every acquired bootstrap resource closes once on stage failures including unknown startup result`() {
        for (stage in listOf("prepare", "startSession", "startup", "witness")) {
            val operation = MoshBootstrapOperation<Resource>()
            val connection = Resource()
            val session = Resource()
            fun fail(at: String) { if (at == stage) error(at) }
            assertThrows(IllegalStateException::class.java) {
                operation.run(
                    connect = { connection }, prepare = { fail("prepare"); Unit },
                    startSession = { fail("startSession"); session },
                    startup = { _, _ -> fail("startup") }, witness = { _, _ -> fail("witness") },
                )
            }
            operation.cancel().invoke()
            assertEquals(stage, 1, connection.closes.get())
            assertEquals(stage, if (stage in listOf("startup", "witness")) 1 else 0, session.closes.get())
        }
    }
}
