package com.briqt.moke.ui

import java.io.Closeable
import com.briqt.moke.data.Host
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FilesWorkTest {
    private class Connection : Closeable {
        val closes = AtomicInteger()
        @Volatile var live = false
        override fun close() { live = false; closes.incrementAndGet() }
    }

    @Test fun `old page success or failure cannot publish into reopened page`() {
        val work = FilesWork<Connection>()
        val first = work.attach(Connection())
        work.detach()?.close()
        val next = work.attach(Connection())
        var screen = "new path and entries"
        assertFalse(work.commit(first) { screen = "late old error" })
        assertFalse(work.commit(first) { screen = "late old directory" })
        assertTrue(work.commit(next) { screen = "current path and entries" })
        assertEquals("current path and entries", screen)
    }

    @Test fun `new navigation invalidates old publication and serializes SFTP IO`() = runBlocking {
        val work = FilesWork<Connection>()
        val old = work.attach(Connection())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit {
                runBlocking { work.execute(old) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val newer = checkNotNull(work.begin())
            val calls = AtomicInteger()
            val next = launch(start = CoroutineStart.UNDISPATCHED) { work.execute(newer) { calls.incrementAndGet() } }
            assertEquals(0, calls.get()) // Mutex must suspend this operation before touching the SFTP client.
            var state = "existing directory"
            assertFalse(work.commit(old) { state = "late failure" })
            assertEquals("existing directory", state)
            release.countDown()
            running.get(2, TimeUnit.SECONDS)
            next.join()
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `close during blocked connect also closes resources installed after cancellation`() {
        val work = FilesWork<Connection>()
        val connection = Connection()
        val old = work.attach(connection)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit {
                runBlocking { work.execute(old) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    it.live = true // Native/SSH connect returned after its first close.
                } }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work.detach()?.close()
            val replacement = Connection()
            work.attach(replacement)
            release.countDown()
            running.get(2, TimeUnit.SECONDS)
            assertFalse(connection.live)
            assertEquals(2, connection.closes.get())
            assertEquals(0, replacement.closes.get())
            assertFalse(work.commit(old) { error("late publication") })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `recovery waits for late blocking IO and does not close a replacement page`() = runBlocking {
        val work = FilesWork<Connection>()
        val connection = Connection()
        val old = work.attach(connection)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val blocked = pool.submit { runBlocking { work.execute(old) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                it.live = true
            } } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val retired = checkNotNull(work.detachPage())
            val pause = launch(start = CoroutineStart.UNDISPATCHED) { work.closeAndDrain(retired) }
            assertFalse(pause.isCompleted)
            val replacement = Connection()
            work.attach(replacement)
            release.countDown()
            blocked.get(2, TimeUnit.SECONDS)
            pause.join()
            assertFalse(connection.live)
            assertEquals(0, replacement.closes.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `prompt validation retains ordinary remote names and path syntax`() {
        listOf("folder", "folder name", "目录", "a\\b").forEach { assertTrue(validDirectoryName(it)) }
        listOf("", " ", ".", "..", "a/b", "a\nb", "a\u0000b").forEach { assertFalse(validDirectoryName(it)) }
        listOf("/var/log", "~/dir", "../dir", "a b").forEach { assertTrue(validRemotePath(it)) }
        listOf("", " ", "a\nb", "a\u0000b").forEach { assertFalse(validRemotePath(it)) }
    }

    @Test fun `upload inspection never authorizes absence when stat fails`() = runBlocking {
        val work = FilesWork<Connection>()
        val ticket = work.attach(Connection())
        val denied = java.io.IOException("permission denied")
        val error = runCatching { work.inspectNames(ticket, listOf("file")) { _, _ -> throw denied } }.exceptionOrNull()
        assertSame(denied, error)
        assertEquals(emptyList<String>(), work.inspectNames(ticket, listOf("file")) { _, _ -> false })
    }

    @Test fun `changing directory during stat returns no upload authorization not an empty clash list`() {
        val work = FilesWork<Connection>()
        val original = work.attach(Connection())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val inspected = pool.submit<List<String>?> { runBlocking {
                work.inspectNames(original, listOf("file")) { _, _ ->
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); false
                }
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work.begin() // A new directory operation supersedes the original target, even on the same connection.
            release.countDown()
            assertNull(inspected.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `new host and paused page invalidate upload checks without inspecting the new connection`() = runBlocking {
        val work = FilesWork<Connection>()
        val first = work.attach(Connection())
        work.detach()?.close()
        assertNull(work.snapshot())
        val replacement = Connection()
        work.attach(replacement) // Resume is only attachment: no connection IO or old operation replay.
        assertFalse(replacement.live)
        assertEquals(0, replacement.closes.get())
        assertNull(work.inspectNames(first, listOf("file")) { _, _ -> error("old upload touched replacement host") })
    }

    @Test fun `upload metadata is a basename and is never silently trimmed`() = runBlocking {
        val work = FilesWork<Connection>()
        val ticket = work.attach(Connection())
        listOf("", ".", "..", "/absolute", "a/b", "a\u0000b").forEach { name ->
            val error = runCatching { work.inspectNames(ticket, listOf(name)) { _, _ -> error("invalid basename reached stat") } }.exceptionOrNull()
            assertTrue("invalid name: $name", error is IllegalArgumentException)
        }
        val seen = mutableListOf<String>()
        val names = listOf(" name ", "a\\b", "a\nb", "目录")
        assertEquals(names, work.inspectNames(ticket, names) { _, name -> seen += name; true })
        assertEquals(names, seen)
    }

    @Test fun `upload capability is absent on paused loading failed or unknown-directory pages`() {
        val active = FilesUiState(host = Host(id = "h"), path = "/original", browsingActive = true)
        assertTrue(active.canUpload)
        assertFalse(active.copy(browsingActive = false).canUpload)
        assertFalse(active.copy(loading = true).canUpload)
        assertFalse(active.copy(path = "").canUpload)
        assertFalse(active.copy(error = "refused").canUpload)
        assertEquals("/original", active.copy(browsingActive = false).path)
    }

    @Test fun `queued obsolete operation never reaches the connection`() = runBlocking {
        val work = FilesWork<Connection>()
        val old = work.attach(Connection())
        val latest = checkNotNull(work.begin())
        val failure = runCatching { work.execute(old) { error("obsolete operation ran") } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(work.current(latest))
    }
}
