package com.briqt.moke.terminal

import com.termux.terminal.TerminalScrollToken
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TerminalInputQueueTest {
    private class Sink(private val blockFirst: Boolean = true) : OutputStream() {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val closed = AtomicBoolean()
        private var writes = 0

        override fun write(value: Int) = error("queue must write a complete batch")

        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            events.add(String(bytes, offset, count, Charsets.UTF_8))
            if (++writes == 1 && blockFirst) {
                entered.countDown()
                // A real blocking stream need not react to Thread.interrupt(). Close/cancel must
                // still return without waiting for it; the transport owns the independent closer.
                awaitUninterruptibly(release)
            }
        }

        override fun flush() { flushed.countDown() }
        override fun close() { closed.set(true) }
    }

    @Test fun `cancel while a scroll write is blocked drops only queued old scroll and retains normal FIFO`() {
        val sink = Sink()
        val queue = TerminalInputQueue("scroll-cancel-test") { sink }
        val old = TerminalScrollToken()
        val current = TerminalScrollToken()
        val paste = "\u001b[200~a\n中\u001b[201~"
        val reply = "\u001b[12;34R"
        val wheel = "\u001b[<65;1;1M"
        try {
            assertTrue(queue.scroll("\u001b[A", old))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.scroll("\u001b[B", old))
            assertTrue(queue.text("x"))
            assertTrue(queue.execute { sink.events.add("resize") })
            assertTrue(queue.text(paste))
            assertTrue(queue.text(reply))
            assertTrue(queue.scroll("\u001b[A", old))
            assertTrue(queue.scroll(wheel, current))
            val canceled = CountDownLatch(1)
            Thread({ old.cancel(); canceled.countDown() }, "scroll-canceller").apply { isDaemon = true }.start()
            assertTrue("cancel must not wait for the blocked stream", canceled.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("\u001b[A"), sink.events.toList())
            sink.release.countDown()
            drain(queue)
            assertEquals(listOf("\u001b[A", "x", "resize", paste, reply, wheel), sink.events.toList())
            assertTrue(current.isValid)
            assertFalse(queue.scroll("\u001b[B", old))
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `scroll batches retain exact FIFO and opposite directions are not canceled or merged across input`() {
        val sink = Sink()
        val queue = TerminalInputQueue("scroll-order-test") { sink }
        val token = TerminalScrollToken()
        try {
            assertTrue(queue.text("blocked"))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            val mutable = byteArrayOf(0, 27, '['.code.toByte(), 'A'.code.toByte(), 0)
            assertTrue(queue.writeScroll(mutable, 1, 3, token))
            mutable.fill('z'.code.toByte()) // Admission must copy the caller's temporary encoder buffer.
            assertTrue(queue.scroll("\u001b[B", token))
            assertTrue(queue.text("key"))
            assertTrue(queue.scroll("\u001b[A", token))
            assertTrue(queue.execute { sink.events.add("resize") })
            assertTrue(queue.scroll("\u001b[B", token))
            sink.release.countDown()
            drain(queue)
            assertEquals(
                listOf("blocked", "\u001b[A", "\u001b[B", "key", "\u001b[A", "resize", "\u001b[B"),
                sink.events.toList(),
            )
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `default budget bounds pending scroll jobs and complete batch bytes but not ordinary input`() {
        val sink = Sink()
        val queue = TerminalInputQueue("scroll-budget-test") { sink }
        val token = TerminalScrollToken()
        val batch = "s".repeat(TerminalInputQueue.MAX_SCROLL_BATCH_BYTES)
        val batches = listOf(batch) + List(TerminalInputQueue.MAX_PENDING_SCROLL_JOBS - 1) { "s" }
        val normal = "p".repeat(TerminalInputQueue.MAX_PENDING_SCROLL_BYTES + 1)
        try {
            assertTrue(queue.text("blocked"))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            assertFalse(queue.scroll(batch + "s", token)) // Reject whole; do not send a truncated prefix.
            batches.forEach { assertTrue(queue.scroll(it, token)) }
            assertFalse(queue.scroll("s", token)) // Job limit reached well before the byte budget.
            assertTrue(queue.text(normal))
            assertTrue(queue.execute { sink.events.add("resize") })
            sink.release.countDown()
            drain(queue)
            assertEquals(
                listOf("blocked") + batches + listOf(normal, "resize"),
                sink.events.toList(),
            )
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `byte budget is independent and cancellation reclaims it without dropping other tokens or input`() {
        val sink = Sink()
        val queue = TerminalInputQueue(
            "scroll-byte-budget-test", maxScrollBatchBytes = 6, maxPendingScrollBytes = 8, maxPendingScrollJobs = 4,
        ) { sink }
        val old = TerminalScrollToken()
        val kept = TerminalScrollToken()
        val current = TerminalScrollToken()
        try {
            assertTrue(queue.text("blocked"))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.scroll("oldold", old))
            assertTrue(queue.text("key"))
            assertTrue(queue.scroll("ok", kept))
            assertFalse(queue.scroll("x", kept)) // Only two jobs, but all eight pending bytes are used.
            old.cancel()
            assertTrue(queue.scroll("newnew", current))
            assertFalse(queue.scroll("x", current))
            sink.release.countDown()
            drain(queue)
            assertEquals(listOf("blocked", "key", "ok", "newnew"), sink.events.toList())
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `repeated canceled gestures cannot fill the pending scroll budget behind blocked normal input`() {
        val sink = Sink()
        val queue = TerminalInputQueue(
            "scroll-reclaim-test", maxScrollBatchBytes = 8, maxPendingScrollBytes = 8, maxPendingScrollJobs = 1,
        ) { sink }
        try {
            assertTrue(queue.text("blocked"))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            repeat(100) {
                val token = TerminalScrollToken()
                assertTrue(queue.scroll("old", token))
                token.cancel()
            }
            assertTrue(queue.text("key"))
            assertTrue(queue.scroll("new", TerminalScrollToken()))
            sink.release.countDown()
            drain(queue)
            assertEquals(listOf("blocked", "key", "new"), sink.events.toList())
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `token is rechecked immediately before stream write rather than only when dequeued`() {
        val sink = Sink(blockFirst = false)
        val resolving = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val lookups = AtomicInteger()
        val queue = TerminalInputQueue("scroll-boundary-test") {
            if (lookups.incrementAndGet() == 1) {
                resolving.countDown()
                awaitUninterruptibly(releaseLookup)
            }
            sink
        }
        val token = TerminalScrollToken()
        try {
            assertTrue(queue.scroll("old", token))
            assertTrue(resolving.await(5, TimeUnit.SECONDS))
            token.cancel()
            assertTrue(queue.text("key")) // Stream lookup is outside the metadata lock too.
            releaseLookup.countDown()
            drain(queue)
            assertEquals(listOf("key"), sink.events.toList())
        } finally {
            releaseLookup.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `shutdown is nonblocking clears pending work and does not own or close the stream`() {
        val sink = Sink()
        val queue = TerminalInputQueue("scroll-shutdown-test") { sink }
        val token = TerminalScrollToken()
        val inFlight = "s".repeat(TerminalInputQueue.MAX_SCROLL_BATCH_BYTES)
        try {
            assertTrue(queue.scroll(inFlight, token))
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.scroll("queued", token))
            assertTrue(queue.text("queued key"))
            assertTrue(queue.execute { sink.events.add("queued resize") })
            val stopped = CountDownLatch(1)
            Thread({ queue.shutdownNow(); stopped.countDown() }, "input-stopper").apply { isDaemon = true }.start()
            assertTrue("shutdown must leave blocked I/O to the independent transport closer", stopped.await(5, TimeUnit.SECONDS))
            assertFalse(sink.closed.get())
            assertFalse(queue.text("late key"))
            assertFalse(queue.scroll("late", token))
            assertFalse(queue.execute { sink.events.add("late resize") })
            sink.release.countDown()
            assertTrue(sink.flushed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(inFlight), sink.events.toList())
            queue.shutdownNow() // Idempotent on EOF/explicit-close races.
        } finally {
            sink.release.countDown()
            queue.shutdownNow()
        }
    }

    @Test fun `a failed write or resize does not strand later reliable input`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stream = object : OutputStream() {
            override fun write(value: Int) = error("complete batch expected")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                val text = String(bytes, offset, count, Charsets.UTF_8)
                events.add(text)
                if (text == "fail") throw IOException("closed write")
            }
        }
        val queue = TerminalInputQueue("input-failure-test") { stream }
        try {
            assertTrue(queue.text("fail"))
            assertTrue(queue.execute { events.add("resize failed"); throw IOException("resize") })
            assertTrue(queue.text("next"))
            drain(queue)
            assertEquals(listOf("fail", "resize failed", "next"), events.toList())
        } finally {
            queue.shutdownNow()
        }
    }

    private fun TerminalInputQueue.text(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return write(bytes, 0, bytes.size)
    }

    private fun TerminalInputQueue.scroll(text: String, token: TerminalScrollToken): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return writeScroll(bytes, 0, bytes.size, token)
    }

    private fun drain(queue: TerminalInputQueue) {
        val done = CountDownLatch(1)
        assertTrue(queue.execute { done.countDown() })
        assertTrue("queued work did not finish", done.await(5, TimeUnit.SECONDS))
    }

    companion object {
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
