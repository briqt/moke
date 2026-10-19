package com.briqt.moke.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the native process boundary is replaceable in JVM tests, not mosh's protocol. */
internal interface MoshProcessOps {
    fun waitFor(pid: Int): Int
    fun kill(pid: Int)
    fun closeFd(fd: Int)
}

/** A positive pid has one reaper before any descriptor wrapping or other fallible initialization. */
internal class MoshChildProcess private constructor(
    private val pid: Int,
    fd: Int,
    private val ops: MoshProcessOps,
    private val onForced: () -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private val lock = Any()
    private var rawFd = fd
    private var descriptor: AutoCloseable? = null
    private val reaping = AtomicBoolean()
    private val forced = AtomicBoolean()
    private val exit = CountDownLatch(1)
    @Volatile var exited = false
        private set
    val fd: Int get() = synchronized(lock) { rawFd }

    /** adoptFd is local and nonblocking. Serialize transfer against the waiter closing the raw fd. */
    fun <T : AutoCloseable> adopt(factory: (Int) -> T): T = synchronized(lock) {
        check(!exited && rawFd >= 0 && descriptor == null) { "mosh PTY already closed or adopted" }
        factory(rawFd).also { descriptor = it }
    }

    /** Keep a local ioctl from racing fd close/reuse; the action must not wait or perform network I/O. */
    fun withFd(action: (Int) -> Unit) = synchronized(lock) {
        if (!exited && rawFd >= 0) action(rawFd)
    }

    fun awaitExit(timeoutMs: Long): Boolean = exit.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun forceClose() {
        if (!forced.compareAndSet(false, true)) return
        synchronized(lock) {
            if (!exited) {
                // This callback only schedules remote cleanup; mark it before kill can wake the reaper.
                runCatching(onForced)
                runCatching { ops.kill(pid) }
            }
            closeFdLocked()
        }
    }

    private fun closeFdLocked() {
        if (rawFd < 0) return
        val fd = rawFd
        rawFd = -1
        val owner = descriptor
        descriptor = null
        if (owner != null) runCatching { owner.close() } else runCatching { ops.closeFd(fd) }
    }

    private fun reap() {
        if (!reaping.compareAndSet(false, true)) return
        val code = try { ops.waitFor(pid) } catch (_: Throwable) { 1 }
        synchronized(lock) {
            exited = true
            closeFdLocked()
        }
        exit.countDown()
        onExit(code)
    }

    companion object {
        fun start(
            pid: Int,
            fd: Int,
            ops: MoshProcessOps,
            onForced: () -> Unit,
            launch: (() -> Unit) -> Unit = { task -> Thread(task, "moke-mosh-waiter").start() },
            onExit: (Int) -> Unit,
        ): MoshChildProcess {
            if (pid <= 0) {
                if (fd >= 0) runCatching { ops.closeFd(fd) }
                error("mosh subprocess did not return a positive pid")
            }
            val child = MoshChildProcess(pid, fd, ops, onForced, onExit)
            try {
                launch { child.reap() }
            } catch (t: Throwable) {
                // The creator is already a worker: even a failed Thread.start cannot strand its child.
                child.forceClose()
                child.reap()
                throw t
            }
            return child
        }
    }
}

/** Bootstrap can return after close; ownership of that late remote pid is still taken exactly once. */
internal class MoshServerProcess(private val kill: (Int) -> Unit) {
    private val lock = Any()
    private var pid: Int? = null
    private var cleanupRequested = false
    private var disarmed = false

    fun register(value: Int?) {
        val orphan = synchronized(lock) {
            if (disarmed) null
            else if (cleanupRequested) value
            else { pid = value; null }
        }
        orphan?.let(kill)
    }

    fun cleanup() {
        val orphan = synchronized(lock) {
            cleanupRequested = true
            pid.also { pid = null }
        }
        orphan?.let(kill)
    }

    fun disarm() = synchronized(lock) { disarmed = true; pid = null }
}
