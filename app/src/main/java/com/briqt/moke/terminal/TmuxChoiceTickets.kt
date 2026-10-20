package com.briqt.moke.terminal

// type: tmux preference ordering
/** Tickets are issued when an action is accepted, never when its network response arrives. */
internal class TmuxChoiceTickets {
    private val lock = Any()
    private val latest = mutableMapOf<String, Long>()

    fun issue(hostId: String): Long = synchronized(lock) {
        val previous = latest[hostId] ?: 0L
        check(previous < Long.MAX_VALUE)
        (previous + 1).also { latest[hostId] = it }
    }

    /** Revoke only this exact affected choice, never a newer intent on another route. */
    fun invalidate(hostId: String, ticket: Long): Boolean = synchronized(lock) {
        if (ticket <= 0 || latest[hostId] != ticket) false else {
            issue(hostId)
            true
        }
    }

    fun isCurrent(hostId: String, ticket: Long): Boolean = synchronized(lock) {
        ticket > 0 && latest[hostId] == ticket
    }

    /** The local assignment is atomic with issuing a newer ticket; no blocking IO in [accept]. */
    fun claim(hostId: String, ticket: Long, accept: () -> Unit): Boolean = synchronized(lock) {
        if (ticket <= 0 || latest[hostId] != ticket) false else {
            accept()
            true
        }
    }
}
