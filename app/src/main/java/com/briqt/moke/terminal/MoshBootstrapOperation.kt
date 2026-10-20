package com.briqt.moke.terminal

// type: bootstrap connection ownership
import java.util.concurrent.atomic.AtomicBoolean

/** One bootstrap, separate from both the data resources and independent tmux mutation owners. */
internal class MoshBootstrapOperation<C : AutoCloseable> {
    private class Owned<C : AutoCloseable>(val value: C) {
        private val closed = AtomicBoolean()
        fun close() { if (closed.compareAndSet(false, true)) value.close() }
    }

    private val lock = Any()
    private var connection: Owned<C>? = null
    private var started = false
    private var cancelled = false
    private var submitted = false

    /** Metadata only; the caller runs the returned socket/disconnect work on a background thread. */
    fun cancel(): () -> Unit {
        val pending = synchronized(lock) {
            cancelled = true
            // Once submitted, retain this bounded worker's authenticated connection through witness
            // capture. A late result still belongs to nonce/pidfd cleanup, not to a new data session.
            if (submitted) null else connection.also { connection = null }
        }
        return { pending?.close() }
    }

    private fun requireActive() = synchronized(lock) { check(!cancelled) { "bootstrap cancelled" } }

    /**
     * Linearization point shared with cancel(), NOT a check of the unrelated data-closed flag.
     * Close wins => no startup call. Submission wins => in flight/possibly executed, even if close
     * immediately follows. Never hold this metadata lock over SSH I/O or wait for it on the UI.
     */
    private fun commitStartup() = synchronized(lock) {
        check(!cancelled && !submitted) { "bootstrap cancelled or already submitted" }
        submitted = true
    }

    fun <P, S : AutoCloseable, R> run(
        connect: () -> C,
        prepare: (C) -> P,
        startSession: (C) -> S,
        startup: (S, P) -> R,
        witness: (C, R) -> Unit,
    ): R {
        synchronized(lock) {
            check(!cancelled && !started) { "bootstrap cancelled or already started" }
            started = true
        }
        val owned = Owned(connect())
        try {
            synchronized(lock) {
                check(!cancelled) { "bootstrap cancelled during connect" }
                connection = owned
            }
            requireActive()
            val prepared = prepare(owned.value)
            requireActive()
            return startSession(owned.value).use { session ->
                // No slow work between the atomic submission decision and entering startup().
                commitStartup()
                startup(session, prepared).also { witness(owned.value, it) }
            }
        } finally {
            synchronized(lock) { if (connection === owned) connection = null }
            owned.close()
        }
    }
}
