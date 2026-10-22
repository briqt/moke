package com.briqt.moke.terminal

import com.termux.terminal.TerminalScrollToken
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * One FIFO for terminal bytes and resize work. Only scroll input has an admission budget; ordinary
 * input is never discarded to make room for scrolling. A scroll call is an atomic encoded batch:
 * oversize/full-budget submissions are rejected whole, never split inside an escape sequence.
 *
 * The metadata lock is never held while resolving/writing the stream or running resize work.
 * Cancellation is just a token change: it cannot wait for, or retract, the one in-flight write.
 * Stream ownership and transport close/quit handshakes remain outside this queue.
 */
internal class TerminalInputQueue(
    threadName: String,
    private val maxScrollBatchBytes: Int = MAX_SCROLL_BATCH_BYTES,
    private val maxPendingScrollBytes: Int = MAX_PENDING_SCROLL_BYTES,
    private val maxPendingScrollJobs: Int = MAX_PENDING_SCROLL_JOBS,
    private val outputStream: () -> OutputStream?,
) {
    init {
        require(maxScrollBatchBytes > 0 && maxPendingScrollBytes > 0 && maxPendingScrollJobs > 0)
    }

    private sealed interface Job {
        class Write(val bytes: ByteArray, val token: TerminalScrollToken? = null) : Job
        class Action(val run: () -> Unit) : Job
    }

    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    private var pendingScrollBytes = 0
    private var pendingScrollJobs = 0
    private var draining = false
    @Volatile private var stopped = false
    private val executor = Executors.newSingleThreadExecutor { Thread(it, threadName) }

    fun write(data: ByteArray, offset: Int, count: Int): Boolean {
        if (stopped) return false
        val bytes = data.copyOfRange(offset, offset + count)
        return synchronized(lock) { enqueueLocked(Job.Write(bytes)) }
    }

    /** Returns false for canceled, empty, oversize or over-budget scroll batches. */
    fun writeScroll(data: ByteArray, offset: Int, count: Int, token: TerminalScrollToken): Boolean {
        if (stopped || !token.isValid || count <= 0 || count > maxScrollBatchBytes) return false
        return synchronized(lock) {
            if (stopped || !token.isValid) return false
            // Reclaim canceled gestures even when a preceding stream write is still blocked.
            // The queue itself, unlike an executor full of canceled closures, stays scroll-bounded.
            discardCanceledScrollLocked()
            if (pendingScrollJobs >= maxPendingScrollJobs ||
                count > maxPendingScrollBytes - pendingScrollBytes
            ) return false
            enqueueLocked(Job.Write(data.copyOfRange(offset, offset + count), token))
        }
    }

    /** Resize/control work keeps its exact position relative to bytes; it is not scroll input. */
    fun execute(action: () -> Unit): Boolean = synchronized(lock) { enqueueLocked(Job.Action(action)) }

    /** Nonblocking stop. The transport's independent closer must release blocked stream I/O. */
    fun shutdownNow() {
        synchronized(lock) {
            stopped = true
            clearLocked()
        }
        executor.shutdownNow()
    }

    private fun enqueueLocked(job: Job): Boolean {
        if (stopped) return false
        pending.addLast(job)
        if (job is Job.Write && job.token != null) {
            pendingScrollJobs++
            pendingScrollBytes += job.bytes.size
        }
        if (!draining) {
            draining = true
            try {
                executor.execute(::drain)
            } catch (_: RejectedExecutionException) {
                stopped = true
                clearLocked()
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (stopped) return
                val next = pending.pollFirst() ?: run {
                    draining = false
                    return
                }
                if (next is Job.Write && next.token != null) {
                    pendingScrollJobs--
                    pendingScrollBytes -= next.bytes.size
                }
                next
            }
            // Like the previous transport executor, one failed write/resize must not strand later
            // input. Do not hold the metadata lock across any operation that can perform I/O.
            runCatching {
                when (job) {
                    is Job.Write -> {
                        val stream = if (stopped) null else outputStream()
                        // Last check before entering the blocking write, not merely at dequeue time.
                        if (stream != null && !stopped && (job.token == null || job.token.isValid)) {
                            stream.write(job.bytes)
                            stream.flush()
                        }
                    }
                    is Job.Action -> if (!stopped) job.run()
                }
            }
        }
    }

    private fun discardCanceledScrollLocked() {
        if (pendingScrollJobs == 0) return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is Job.Write && job.token != null && !job.token.isValid) {
                iterator.remove()
                pendingScrollJobs--
                pendingScrollBytes -= job.bytes.size
            }
        }
    }

    private fun clearLocked() {
        pending.clear()
        pendingScrollBytes = 0
        pendingScrollJobs = 0
    }

    companion object {
        // At most one additional <=1KiB batch may already be inside a stream write.
        internal const val MAX_SCROLL_BATCH_BYTES = 1024
        internal const val MAX_PENDING_SCROLL_BYTES = 8 * MAX_SCROLL_BATCH_BYTES
        internal const val MAX_PENDING_SCROLL_JOBS = 8
    }
}
