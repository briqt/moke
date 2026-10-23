package com.briqt.moke.terminal

import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class TerminalSizeStateTest {
    private val portrait = TerminalWindowSize(60, 47, 18, 40)
    private val landscape = TerminalWindowSize(127, 14, 18, 40)
    private val keyboard = TerminalWindowSize(60, 25, 18, 40)

    private class Queue {
        val jobs = ArrayDeque<() -> Unit>()
        fun enqueue(action: () -> Unit): Boolean { jobs.addLast(action); return true }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().invoke() }
    }

    @Test fun `initial geometry is available to PTY allocation`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        assertEquals(portrait, state.current())
        assertTrue(queue.jobs.isEmpty())
    }

    @Test fun `bootstrap updates replace the initial geometry without writing before ready`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        state.update(keyboard)
        state.update(landscape)
        assertEquals(landscape, state.current())
        assertTrue(queue.jobs.isEmpty())
        val applied = mutableListOf<TerminalWindowSize>()
        state.attach { applied += it }
        assertTrue(applied.isEmpty())
        queue.drain()
        assertEquals(listOf(landscape), applied)
    }

    @Test fun `an update before initialization is not overwritten by captured startup dimensions`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.update(landscape)
        state.initialize(portrait)
        assertEquals(landscape, state.current())
    }

    @Test fun `resize between PTY allocation and ready is replayed to the new channel`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        val allocated = state.current()
        state.update(landscape)
        val applied = mutableListOf<TerminalWindowSize>()
        state.attach { applied += it }
        queue.drain()
        assertEquals(portrait, allocated)
        assertEquals(listOf(landscape), applied)
    }

    @Test fun `ready updates preserve exact ordering relative to terminal input`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        val events = mutableListOf<String>()
        state.initialize(portrait)
        state.attach { events += "${it.columns}x${it.rows}" }
        state.update(keyboard)
        queue.enqueue { events += "input" }
        state.update(landscape)
        queue.drain()
        assertEquals(listOf("60x47", "60x25", "input", "127x14"), events)
    }

    @Test fun `pixel geometry is retained as well as rows and columns`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        val next = TerminalWindowSize(60, 47, 21, 45)
        state.update(next)
        val applied = mutableListOf<TerminalWindowSize>()
        state.attach { applied += it }
        queue.drain()
        assertEquals(listOf(next), applied)
    }

    @Test fun `ready publication cannot enqueue an old size after a concurrent newer update`() {
        val jobs = ArrayDeque<() -> Unit>()
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val updated = CountDownLatch(1)
        val state = TerminalSizeState { action ->
            if (publishing.count > 0) {
                publishing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            jobs.addLast(action)
            true
        }
        state.initialize(portrait)
        val applied = mutableListOf<TerminalWindowSize>()
        val attaching = Thread { state.attach { applied += it } }
        attaching.start()
        try {
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val updating = Thread { state.update(landscape); updated.countDown() }
            updating.start()
            release.countDown()
            assertTrue(updated.await(5, TimeUnit.SECONDS))
            updating.join(5000)
            attaching.join(5000)
            assertFalse(attaching.isAlive)
            while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
            assertEquals(listOf(portrait, landscape), applied)
        } finally {
            release.countDown()
            attaching.join(5000)
        }
    }

    @Test fun `blocked resize IO does not hold the geometry metadata lock`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        val applying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val updated = CountDownLatch(1)
        state.attach { applying.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val worker = Thread(queue.jobs.removeFirst())
        worker.start()
        try {
            assertTrue(applying.await(5, TimeUnit.SECONDS))
            val updating = Thread { state.update(landscape); updated.countDown() }
            updating.start()
            assertTrue(updated.await(5, TimeUnit.SECONDS))
            updating.join(5000)
            assertEquals(landscape, state.current())
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }

    @Test fun `real input queue preserves resize byte FIFO and stops without retracting in flight IO`() {
        val events = ConcurrentLinkedQueue<String>()
        val fifoFinished = CountDownLatch(1)
        val resizeEntered = CountDownLatch(1)
        val releaseResize = CountDownLatch(1)
        val resizeFinished = CountDownLatch(1)
        val shutdownReturned = CountDownLatch(1)
        val shutdownFailure = AtomicReference<Throwable>()
        val worker = AtomicReference<Thread>()
        val blockingSize = TerminalWindowSize(90, 30, 18, 40)
        val stream = object : OutputStream() {
            override fun write(value: Int) { events.add("byte:${value.toChar()}") }
        }
        val queue = TerminalInputQueue("terminal-size-integration-test") { stream }
        val state = TerminalSizeState(queue::execute)
        val stopping = Thread({
            try {
                queue.shutdownNow()
            } catch (failure: Throwable) {
                shutdownFailure.set(failure)
            } finally {
                shutdownReturned.countDown()
            }
        }, "terminal-size-stop-test")
        try {
            state.initialize(portrait)
            state.update(landscape)
            assertEquals(landscape, state.current())
            assertTrue("Bootstrap updates must not perform IO before attach", events.isEmpty())
            state.attach { size ->
                events.add("resize:${size.columns}x${size.rows}")
                if (size == blockingSize) {
                    worker.set(Thread.currentThread())
                    resizeEntered.countDown()
                    // Model network IO which does not unblock just because shutdown interrupts it.
                    // Once this resize has entered IO, shutdown cannot retract it.
                    var interrupted = false
                    try {
                        while (true) {
                            try {
                                releaseResize.await()
                                break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        events.add("resize-finished")
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                        resizeFinished.countDown()
                    }
                }
            }
            assertTrue(queue.write(byteArrayOf('A'.code.toByte()), 0, 1))
            state.update(keyboard)
            assertTrue(queue.write(byteArrayOf('B'.code.toByte()), 0, 1))
            assertTrue(queue.execute { fifoFinished.countDown() })
            assertTrue("Resize and byte FIFO did not drain", fifoFinished.await(5, TimeUnit.SECONDS))
            val beforeBlocking = listOf("resize:127x14", "byte:A", "resize:60x25", "byte:B")
            assertEquals(beforeBlocking, events.toList())

            state.update(blockingSize)
            assertTrue("The blocking resize did not enter IO", resizeEntered.await(5, TimeUnit.SECONDS))
            state.update(portrait)
            assertTrue(queue.write(byteArrayOf('C'.code.toByte()), 0, 1))
            stopping.start()
            assertTrue("Shutdown must return before in-flight IO is released", shutdownReturned.await(5, TimeUnit.SECONDS))
            assertNull(shutdownFailure.get())
            assertEquals("Shutdown cannot retract already-entered IO", 1L, resizeFinished.count)

            releaseResize.countDown()
            assertTrue("The in-flight resize did not finish", resizeFinished.await(5, TimeUnit.SECONDS))
            val inputWorker = worker.get()
            inputWorker.join(5000)
            assertFalse("The stopped input worker must exit", inputWorker.isAlive)
            assertEquals(
                "Pending resize and ordinary bytes must not run after shutdown",
                beforeBlocking + listOf("resize:90x30", "resize-finished"),
                events.toList(),
            )
        } finally {
            releaseResize.countDown()
            queue.shutdownNow()
            stopping.join(5000)
            worker.get()?.join(5000)
        }
    }

    @Test fun `the one channel cannot be replaced by a second attachment`() {
        val queue = Queue()
        val state = TerminalSizeState(queue::enqueue)
        state.initialize(portrait)
        val applied = mutableListOf<TerminalWindowSize>()
        state.attach { applied += it }
        assertThrows(IllegalStateException::class.java) { state.attach { error("replaced") } }
        queue.drain()
        assertEquals(listOf(portrait), applied)
    }
}
