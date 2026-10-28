package com.briqt.moke.terminal

// type: per-authentication request, cancellation, timeout and none-only regression tests
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.userauth.UserAuthImpl
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TailscaleSshAuthTest {
    private class Running(
        val requests: TailscaleSshAuthRequests,
        target: String = "user@host",
        initialBanner: String = "Check https://login.example.test/check?token=secret",
        timeoutMs: Long = 5_000,
        private val fail: Boolean = false,
        owner: TailscaleSshAuth.Owner? = null,
    ) : AutoCloseable {
        val banner = AtomicReference(initialBanner)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val aborts = AtomicInteger()
        val abortThread = AtomicReference<Thread?>()
        val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            try {
                requests.authenticate(
                    target = target,
                    readBanner = { banner.get() },
                    abort = { abortThread.set(Thread.currentThread()); aborts.incrementAndGet(); release.countDown() },
                    timeoutMs = timeoutMs,
                    pollMs = 5,
                    owner = owner,
                ) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test authentication did not complete" }
                    if (fail) throw IOException("Disconnect https://login.example.test/check?token=secret")
                }
            } catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }.apply { start() }

        fun finish() {
            release.countDown()
            assertTrue("authentication did not finish", done.await(2, TimeUnit.SECONDS))
        }

        override fun close() { release.countDown(); worker.join(2_000) }
    }

    private fun awaitRequest(requests: TailscaleSshAuthRequests, count: Int = 1): List<TailscaleSshAuth.Request> {
        repeat(400) {
            val pending = requests.pending.value
            if (pending.size == count) return pending
            Thread.sleep(5)
        }
        throw AssertionError("expected $count requests, found ${requests.pending.value.size}")
    }

    private fun assertNoObserver(id: String) {
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "moke-tssh-check-$id" })
    }

    @Test fun `no banner means no dialog and successful authentication clears its observer`() {
        val requests = TailscaleSshAuthRequests()
        Running(requests, initialBanner = "").use { attempt ->
            assertTrue(attempt.started.await(2, TimeUnit.SECONDS))
            assertTrue(requests.pending.value.isEmpty())
            attempt.banner.set("Server is checking the SSH policy")
            val request = awaitRequest(requests).single()
            attempt.finish()
            assertNull(attempt.failure.get())
            assertEquals(0, attempt.aborts.get())
            assertTrue(requests.pending.value.isEmpty())
            assertNoObserver(request.id)
        }
    }

    @Test fun `latest banner replaces only its own request without acknowledging authentication`() {
        val requests = TailscaleSshAuthRequests()
        Running(requests, target = "\u001b[31muser@host\u0000‮").use { attempt ->
            val initial = awaitRequest(requests).single()
            assertEquals("user@host", initial.target)
            attempt.banner.set("Denied by policy. No browser check available.")
            repeat(400) {
                if (requests.pending.value.singleOrNull()?.message == "Denied by policy. No browser check available.") return@repeat
                Thread.sleep(5)
            }
            val latest = requests.pending.value.single()
            assertEquals(initial.id, latest.id)
            assertEquals("Denied by policy. No browser check available.", latest.message)
            assertNull(latest.url)
            assertEquals(1L, attempt.done.count)
            attempt.finish()
            assertNull(attempt.failure.get())
        }
    }

    @Test fun `concurrent requests to the same host are independent and old cancel cannot affect a new attempt`() {
        val requests = TailscaleSshAuthRequests()
        Running(requests).use { first ->
            val old = awaitRequest(requests).single()
            Running(requests).use { second ->
                val both = awaitRequest(requests, 2)
                val current = both.single { it.id != old.id }
                requests.cancel(old.id)
                assertTrue(first.done.await(2, TimeUnit.SECONDS))
                assertEquals(TailscaleSshAuthFailureKind.CANCELLED, (first.failure.get() as TailscaleSshAuthFailure).kind)
                assertEquals(1, first.aborts.get())
                assertNotSame(Thread.currentThread(), first.abortThread.get())
                assertNoObserver(old.id)
                assertEquals(listOf(current), requests.pending.value)
                requests.cancel(old.id)
                assertEquals(0, second.aborts.get())
                assertEquals(1L, second.done.count)
                second.finish()
                assertNull(second.failure.get())
                assertTrue(requests.pending.value.isEmpty())
                assertNoObserver(current.id)
            }
        }
        Running(requests).use { fresh ->
            val newRequest = awaitRequest(requests).single()
            requests.cancel("stale-id")
            assertEquals(listOf(newRequest), requests.pending.value)
            fresh.finish()
            assertNull(fresh.failure.get())
        }
    }

    @Test fun `timeout closes the actual auth resource off caller thread and removes its request`() {
        val requests = TailscaleSshAuthRequests()
        Running(requests, timeoutMs = 250).use { attempt ->
            val request = awaitRequest(requests).single()
            assertTrue(attempt.done.await(2, TimeUnit.SECONDS))
            assertEquals(TailscaleSshAuthFailureKind.TIMED_OUT, (attempt.failure.get() as TailscaleSshAuthFailure).kind)
            assertEquals(1, attempt.aborts.get())
            assertNotSame(Thread.currentThread(), attempt.abortThread.get())
            assertTrue(requests.pending.value.isEmpty())
            assertNoObserver(request.id)
        }
    }

    @Test fun `cancel returns without socket I O and still interrupts the auth waiter if close fails`() {
        val requests = TailscaleSshAuthRequests()
        val entered = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val interruptedAfter = AtomicReference<Boolean>()
        val closeThread = AtomicReference<Thread?>()
        val worker = Thread {
            try {
                requests.authenticate(
                    "host", { "Check https://login.example.test/check" },
                    abort = {
                        closeThread.set(Thread.currentThread())
                        closing.countDown()
                        check(allowClose.await(2, TimeUnit.SECONDS))
                        throw IOException("socket already failed")
                    },
                    pollMs = 5,
                ) {
                    entered.countDown()
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) { result.set(t) }
            finally { interruptedAfter.set(Thread.currentThread().isInterrupted); done.countDown() }
        }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val request = awaitRequest(requests).single()
            requests.cancel(request.id)
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertNotSame(Thread.currentThread(), closeThread.get())
            assertEquals(1L, done.count)
            allowClose.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(TailscaleSshAuthFailureKind.CANCELLED, (result.get() as TailscaleSshAuthFailure).kind)
            assertEquals(false, interruptedAfter.get())
            assertTrue(requests.pending.value.isEmpty())
            assertNoObserver(request.id)
        } finally {
            allowClose.countDown()
            worker.interrupt()
            worker.join(2_000)
        }
    }

    @Test fun `failure clears pending and never retains an unsafe cause or token in details`() {
        val requests = TailscaleSshAuthRequests()
        Running(requests, initialBanner = "Denied. Visit https://login.example.test/check?token=secret", fail = true).use { attempt ->
            val request = awaitRequest(requests).single()
            attempt.finish()
            val failure = attempt.failure.get() as TailscaleSshAuthFailure
            assertEquals(TailscaleSshAuthFailureKind.REJECTED, failure.kind)
            assertEquals("Denied. Visit", failure.detail)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("secret"))
            assertTrue(requests.pending.value.isEmpty())
            assertNoObserver(request.id)
        }
    }

    @Test fun `immediate refusal reads its last banner even before the first observation`() {
        val requests = TailscaleSshAuthRequests()
        val failure = assertThrows(TailscaleSshAuthFailure::class.java) {
            requests.authenticate("host", { "Username is not permitted. https://login.example.test/token" }, {}) {
                throw IOException("https://login.example.test/token")
            }
        }
        assertEquals("Username is not permitted.", failure.detail)
        assertNull(failure.cause)
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test fun `sshj promise timeouts receive the timeout reason rather than generic denial`() {
        val requests = TailscaleSshAuthRequests()
        val failure = assertThrows(TailscaleSshAuthFailure::class.java) {
            requests.authenticate("host", { "" }, {}) { throw IOException(TimeoutException()) }
        }
        assertEquals(TailscaleSshAuthFailureKind.TIMED_OUT, failure.kind)
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test fun `external interruption is cancellation and still clears the observer`() {
        val requests = TailscaleSshAuthRequests()
        val result = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try {
                requests.authenticate("host", { "" }, {}) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted https://login.example.test/token")
                }
            } catch (t: Throwable) { result.set(t) }
            finally { done.countDown() }
        }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(TailscaleSshAuthFailureKind.CANCELLED, (result.get() as TailscaleSshAuthFailure).kind)
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test fun `cancelled owner cannot start authentication or publish a late banner`() {
        val requests = TailscaleSshAuthRequests()
        val owner = TailscaleSshAuth.Owner().also { it.cancel() }
        val calls = AtomicInteger()
        val failure = assertThrows(TailscaleSshAuthFailure::class.java) {
            requests.authenticate("host", { "Check https://login.example.test/check" }, {}, owner = owner) {
                calls.incrementAndGet()
            }
        }
        assertEquals(TailscaleSshAuthFailureKind.CANCELLED, failure.kind)
        assertEquals(0, calls.get())
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test fun `closing owner before its first banner aborts the pending authentication`() {
        val requests = TailscaleSshAuthRequests()
        val owner = TailscaleSshAuth.Owner()
        Running(requests, initialBanner = "", owner = owner).use { attempt ->
            assertTrue(attempt.started.await(2, TimeUnit.SECONDS))
            owner.cancel()
            attempt.banner.set("Late check https://login.example.test/check")
            assertTrue("closed owner kept waiting for authentication", attempt.done.await(2, TimeUnit.SECONDS))
            assertEquals(TailscaleSshAuthFailureKind.CANCELLED, (attempt.failure.get() as TailscaleSshAuthFailure).kind)
            assertEquals(1, attempt.aborts.get())
            assertNotSame(Thread.currentThread(), attempt.abortThread.get())
            assertTrue(requests.pending.value.isEmpty())
        }
    }

    @Test fun `owner cancellation removes its visible request immediately and is idempotent`() {
        val requests = TailscaleSshAuthRequests()
        val owner = TailscaleSshAuth.Owner()
        Running(requests, owner = owner).use { attempt ->
            val request = awaitRequest(requests).single()
            owner.cancel()
            assertTrue(requests.pending.value.isEmpty())
            owner.cancel()
            assertTrue(attempt.done.await(2, TimeUnit.SECONDS))
            assertEquals(1, attempt.aborts.get())
            assertNoObserver(request.id)
        }
    }

    @Test fun `one owner cancels all its checks without cancelling another owner at the same host`() {
        val requests = TailscaleSshAuthRequests()
        val owner = TailscaleSshAuth.Owner()
        Running(requests, owner = owner).use { first ->
            Running(requests, owner = owner).use { second ->
                val otherOwner = TailscaleSshAuth.Owner()
                Running(requests, owner = otherOwner).use { other ->
                    awaitRequest(requests, 3)
                    owner.cancel()
                    assertEquals(1, requests.pending.value.size)
                    assertTrue(first.done.await(2, TimeUnit.SECONDS))
                    assertTrue(second.done.await(2, TimeUnit.SECONDS))
                    assertEquals(1, first.aborts.get())
                    assertEquals(1, second.aborts.get())
                    assertEquals(0, other.aborts.get())
                    other.finish()
                    assertNull(other.failure.get())
                }
            }
        }
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test fun `closing a completed owner does not abort completed or replacement authentication`() {
        val requests = TailscaleSshAuthRequests()
        val owner = TailscaleSshAuth.Owner()
        Running(requests, owner = owner).use { completed ->
            awaitRequest(requests)
            completed.finish()
            Running(requests, owner = TailscaleSshAuth.Owner()).use { replacement ->
                val request = awaitRequest(requests).single()
                owner.cancel()
                assertEquals(0, completed.aborts.get())
                assertEquals(0, replacement.aborts.get())
                assertEquals(listOf(request), requests.pending.value)
                replacement.finish()
            }
        }
        assertTrue(requests.pending.value.isEmpty())
    }

    private class NoneClient(private val reject: Boolean) : SSHClient() {
        var calls = 0
        var methods = emptyList<String>()
        var user = ""
        var authTimeout = 0
        override fun auth(username: String, vararg methods: AuthMethod) {
            calls++
            user = username
            this.methods = methods.map { it.name }
            authTimeout = transport.timeoutMs
            if (reject) {
                (userAuth as UserAuthImpl).handle(Message.USERAUTH_BANNER, SSHPacket().putString("Denied https://login.example.test/token"))
                throw IOException("Server https://login.example.test/token")
            }
        }
    }

    @Test fun `Tailscale SSH supplies exactly none with no fallback and always restores the old timeout`() {
        for (reject in listOf(false, true)) {
            val client = NoneClient(reject)
            client.transport.timeoutMs = 12_345
            if (reject) {
                val failure = assertThrows(TailscaleSshAuthFailure::class.java) {
                    TailscaleSshAuth.authenticate(client, "alice", "host")
                }
                assertEquals(TailscaleSshAuthFailureKind.REJECTED, failure.kind)
                assertEquals("Denied", failure.detail)
            } else {
                TailscaleSshAuth.authenticate(client, "alice", "host")
            }
            assertEquals(1, client.calls)
            assertEquals("alice", client.user)
            assertEquals(listOf("none"), client.methods)
            assertEquals(300_000, client.authTimeout)
            assertEquals(12_345, client.transport.timeoutMs)
            assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        }
    }

    @Test fun `route validation rejects non-tailnet non22 mismatched network and second-hop none auth`() {
        val network = NetworkEntry(id = "tailnet", kind = NetworkKind.TAILSCALE)
        val tssh = Host(authType = AuthType.TAILSCALE_SSH, networkId = network.id)
        val ordinary = Host(authType = AuthType.PASSWORD)
        assertTrue(validTailscaleSshRoute(tssh, null, network))
        assertFalse(validTailscaleSshRoute(tssh, ordinary, network))
        assertFalse(validTailscaleSshRoute(tssh, null, null))
        assertFalse(validTailscaleSshRoute(tssh, null, network.copy(kind = NetworkKind.PROXY)))
        assertFalse(validTailscaleSshRoute(tssh.copy(port = 2222), null, network))
        assertFalse(validTailscaleSshRoute(tssh, null, network.copy(id = "other-tailnet")))
        assertTrue(validTailscaleSshRoute(ordinary, tssh, network))
        assertFalse(validTailscaleSshRoute(ordinary, tssh.copy(port = 2222), network))
        assertFalse(validTailscaleSshRoute(ordinary, tssh.copy(jumpHostId = "another-jump"), network))
        assertFalse(validTailscaleSshRoute(ordinary, tssh, network.copy(id = "other-tailnet")))
        assertFalse(validTailscaleSshRoute(ordinary, tssh, null))
        // Existing password/key routes retain exactly their old validation semantics.
        assertTrue(validTailscaleSshRoute(ordinary.copy(port = 2222), ordinary.copy(authType = AuthType.KEY), null))
    }
}
