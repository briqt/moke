package com.briqt.moke.terminal

internal data class TerminalWindowSize(
    val columns: Int,
    val rows: Int,
    val cellWidthPixels: Int,
    val cellHeightPixels: Int,
)

/** Retain bootstrap resizes, then hand them to the same FIFO as terminal input. */
internal class TerminalSizeState(private val enqueue: (() -> Unit) -> Boolean) {
    private val lock = Any()
    private var latest: TerminalWindowSize? = null
    private var apply: ((TerminalWindowSize) -> Unit)? = null

    fun initialize(initial: TerminalWindowSize) = synchronized(lock) {
        if (latest == null) latest = initial
    }

    fun current(): TerminalWindowSize = synchronized(lock) { checkNotNull(latest) }

    fun update(size: TerminalWindowSize) {
        synchronized(lock) {
            latest = size
            apply?.let { resize -> enqueue { resize(size) } }
        }
    }

    fun attach(resize: (TerminalWindowSize) -> Unit) {
        synchronized(lock) {
            check(apply == null)
            val size = checkNotNull(latest)
            apply = resize
            // Queue while holding only the metadata lock so a newer update cannot be overtaken.
            enqueue { resize(size) }
        }
    }
}
