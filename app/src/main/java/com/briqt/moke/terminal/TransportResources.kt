package com.briqt.moke.terminal

import java.util.concurrent.atomic.AtomicBoolean

/** Short metadata lock only: late acquisitions close on their acquiring worker, never on the UI. */
internal class TransportResources {
    private val lock = Any()
    private val resources = mutableListOf<() -> Unit>()
    @Volatile var closed = false
        private set
    @Volatile var established = false
        private set
    @Volatile var started = false
        private set
    private val finished = AtomicBoolean()

    fun start(): Boolean = synchronized(lock) {
        if (started || closed) false else { started = true; true }
    }

    fun <T> own(value: T, close: (T) -> Unit): T {
        val release = once { close(value) }
        val accepted = synchronized(lock) {
            if (closed) false else { resources.add(release); true }
        }
        if (!accepted) {
            release()
            error("transport closed during creation")
        }
        return value
    }

    /** The callback is documented as nonblocking; serialize it against close, not against network I/O. */
    fun establish(callback: () -> Unit): Boolean = synchronized(lock) {
        if (closed || established) false else {
            established = true
            callback()
            true
        }
    }

    fun finish(callback: () -> Unit) {
        if (finished.compareAndSet(false, true)) callback()
    }

    /** Invalidate synchronously, perform the returned (idempotent) I/O on a background thread. */
    fun stop(): () -> Unit {
        val pending = synchronized(lock) {
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }
        return once { pending.forEach { it() } }
    }

    private fun once(action: () -> Unit): () -> Unit {
        val done = AtomicBoolean()
        return { if (done.compareAndSet(false, true)) { runCatching(action) } }
    }
}

/** These stages are also the failure-injection boundary: register before the first fallible operation. */
internal fun <C> connectSshOwned(
    target: C,
    createJump: (() -> C)?,
    connectJump: (C) -> Unit,
    authenticateJump: (C) -> Unit,
    connectTarget: (C?) -> Unit,
    authenticateTarget: (C) -> Unit,
    disconnect: (C) -> Unit,
): Pair<C, C?> {
    var jump: C? = null
    try {
        if (createJump != null) {
            jump = createJump()
            connectJump(jump)
            authenticateJump(jump)
        }
        connectTarget(jump)
        authenticateTarget(target)
        return target to jump
    } catch (t: Throwable) {
        runCatching { disconnect(target) }
        jump?.let { runCatching { disconnect(it) } }
        throw t
    }
}

internal fun <C, S, H, T> withSshChannel(
    resources: TransportResources,
    connect: () -> C,
    disconnect: (C) -> Unit,
    startSession: (C) -> S,
    closeSession: (S) -> Unit,
    allocatePty: (S) -> Unit,
    openChannel: (S) -> H,
    beforeSessionClose: (C) -> Unit = {},
    block: (C, S, H) -> T,
): T = try {
    val connection = resources.own(connect(), disconnect)
    val session = resources.own(startSession(connection)) {
        runCatching { beforeSessionClose(connection) }
        closeSession(it)
    }
    allocatePty(session)
    val channel = openChannel(session)
    block(connection, session, channel)
} finally {
    resources.stop().invoke()
}
