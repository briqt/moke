package com.briqt.moke.ui

import java.io.Closeable
import com.briqt.moke.terminal.sftp.validUploadName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun validDirectoryName(raw: String): Boolean = raw.trim().let { name ->
    name.isNotEmpty() && name !in setOf(".", "..") && name.none { it in "/\u0000\r\n" }
}
internal fun validRemotePath(raw: String): Boolean = raw.trim().let { path ->
    path.isNotEmpty() && path.none { it in "\u0000\r\n" }
}

internal class InvalidUploadNames : IllegalArgumentException()
private class ObsoleteFilesOperation : CancellationException("obsolete files operation")

/** Page ownership and latest-operation publication; no Android or SFTP implementation required. */
internal class FilesWork<C : Closeable> {
    internal class Page<C : Closeable>(val connection: C) { val io = Mutex() }
    internal class Ticket<C : Closeable>(val page: Page<C>, val operation: Long)
    private val lock = Any()
    private var page: Page<C>? = null
    private var operation = 0L

    fun attach(connection: C): Ticket<C> = synchronized(lock) {
        check(page == null)
        val next = Page(connection)
        page = next
        Ticket(next, ++operation)
    }
    fun begin(): Ticket<C>? = synchronized(lock) { page?.let { Ticket(it, ++operation) } }
    fun snapshot(): Ticket<C>? = synchronized(lock) { page?.let { Ticket(it, operation) } }
    fun isAttached(): Boolean = synchronized(lock) { page != null }
    fun detachPage(): Page<C>? = synchronized(lock) { operation++; page.also { page = null } }
    fun detach(): C? = detachPage()?.connection
    suspend fun closeAndDrain(retired: Page<C>) {
        runCatching { retired.connection.close() }
        retired.io.withLock { runCatching { retired.connection.close() } }
    }
    fun current(ticket: Ticket<C>): Boolean = synchronized(lock) { page === ticket.page && operation == ticket.operation }
    private fun owns(ticket: Ticket<C>): Boolean = synchronized(lock) { page === ticket.page }

    /** null is no authorization; an empty list is returned only after a complete successful check. */
    suspend fun inspectNames(ticket: Ticket<C>, names: List<String>, exists: (C, String) -> Boolean): List<String>? {
        if (!current(ticket)) return null
        val captured = names.toList()
        if (captured.isEmpty() || captured.any { !validUploadName(it) }) throw InvalidUploadNames()
        return try {
            execute(ticket) { connection -> captured.filter { name -> exists(connection, name) } }
                .takeIf { current(ticket) }
        } catch (_: ObsoleteFilesOperation) { null }
    }

    /** Keep state writes atomic with invalidation, but never run IO in this callback. */
    fun commit(ticket: Ticket<C>, update: () -> Unit): Boolean = synchronized(lock) {
        if (!current(ticket)) false else { update(); true }
    }

    /** sshj does not support concurrent SFTP operations or cancellation of every blocking call. */
    suspend fun <T> execute(ticket: Ticket<C>, action: (C) -> T): T = ticket.page.io.withLock {
        currentCoroutineContext().ensureActive()
        if (!current(ticket)) throw ObsoleteFilesOperation()
        try {
            action(ticket.page.connection).also { currentCoroutineContext().ensureActive() }
        } finally {
            // close may have happened before a blocking connect installed its late resources. Close
            // again after it returns; this is the old page's connection, never its replacement.
            if (!owns(ticket)) runCatching { ticket.page.connection.close() }
        }
    }
}
