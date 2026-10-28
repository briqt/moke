package com.briqt.moke.terminal

// type: Android authentication ownership and prompt regression tests
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.sftp.SftpSession
import com.briqt.moke.ui.MokeActivityFixture
import com.briqt.moke.ui.FilesController
import com.briqt.moke.ui.FilesWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.userauth.UserAuthImpl
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real owners, connector and prompt flow with controlled sshj authentication; no remote service. */
@RunWith(AndroidJUnit4::class)
class AuthenticationOwnerTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val host = Host(host = "owner-check.example.test", username = "tester", authType = AuthType.TAILSCALE_SSH)

    private class WaitingClient(private val holdClose: Boolean) : SSHClient() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closes = AtomicInteger()
        val closeThread = AtomicReference<Thread?>()
        var methods = emptyList<String>()
        private val resource = object : Socket() {
            override fun close() {
                closes.incrementAndGet()
                closeThread.set(Thread.currentThread())
                closing.countDown()
                if (holdClose) check(allowClose.await(5, TimeUnit.SECONDS))
                release.countDown()
            }
        }
        override fun getSocket(): Socket = resource
        override fun auth(username: String, vararg methods: AuthMethod) {
            this.methods = methods.map { it.name }
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "controlled authentication timed out" }
            if (closes.get() > 0) throw IOException("controlled socket closed")
        }
        fun banner() {
            (userAuth as UserAuthImpl).handle(Message.USERAUTH_BANNER,
                SSHPacket().putString("Check https://login.example.test/check"))
        }
    }

    private inner class Running(owner: TailscaleSshAuth.Owner, holdClose: Boolean = false) : AutoCloseable {
        val client = WaitingClient(holdClose).apply { transport.timeoutMs = 12_345 }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            try { SshConnector(context).authenticate(client, host, owner) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }.apply { start() }

        fun entered() = assertTrue(client.entered.await(3, TimeUnit.SECONDS))
        fun cancelled() {
            assertTrue("authentication did not stop", done.await(3, TimeUnit.SECONDS))
            val error = failure.get()
            assertTrue("expected safe cancellation, got $error", error is TailscaleSshAuthException)
            assertEquals(TailscaleSshAuthFailureKind.CANCELLED, (error as TailscaleSshAuthException).kind)
            assertEquals(listOf("none"), client.methods)
            assertNotSame(Looper.getMainLooper().thread, client.closeThread.get())
        }
        fun finish() {
            client.release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
        }
        override fun close() {
            client.allowClose.countDown()
            client.release.countDown()
            worker.join(3_000)
            assertFalse("authentication worker survived cleanup", worker.isAlive)
            runCatching { client.close() }
        }
    }

    @Test fun closingSshOwnerBeforeBannerPreventsLatePrompt() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val transport = SshTransport(host, context)
        try {
            Running(transport.authenticationOwner).use { running ->
                running.entered()
                inst.runOnMainSync { transport.close() }
                running.client.banner()
                running.cancelled()
                assertTrue(TailscaleSshAuth.pending.value.isEmpty())
            }
        } finally { transport.close() }
    }

    @Test fun closingFileBrowserDoesNotCancelIndependentConnectionAtSameHost() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val browser = SftpSession(host, null, context)
        val transfer = SftpSession(host, null, context)
        try {
            Running(browser.authenticationOwner).use { browsing ->
                browsing.entered()
                browsing.client.banner()
                val old = awaitRequests(1).single()
                Running(transfer.authenticationOwner).use { independent ->
                    independent.entered()
                    independent.client.banner()
                    awaitRequests(2)
                    browser.close()
                    browsing.cancelled()
                    val remaining = awaitRequests(1).single()
                    assertNotEquals(old.id, remaining.id)
                    assertEquals(0, independent.client.closes.get())
                    independent.finish()
                }
            }
        } finally { browser.close(); transfer.close() }
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
    }

    @Test fun closingMoshOwnerCancelsBothPendingChecks() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val transport = MoshTransport(host, context)
        try {
            Running(transport.authenticationOwner).use { bootstrap ->
                bootstrap.entered()
                bootstrap.client.banner()
                Running(transport.authenticationOwner).use { control ->
                    control.entered()
                    control.client.banner()
                    awaitRequests(2)
                    inst.runOnMainSync { transport.close() }
                    bootstrap.cancelled()
                    control.cancelled()
                }
            }
        } finally { transport.close() }
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
    }

    @Test fun closingOwnerDismissesRealDialogWithoutWaitingForSocketClose() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val transport = SshTransport(host, context)
        try {
            MokeActivityFixture.open().use { fixture ->
                Running(transport.authenticationOwner, holdClose = true).use { running ->
                    running.entered()
                    running.client.banner()
                    awaitRequests(1)
                    await("visible authentication target") { hasTarget() }
                    fixture.onActivity { transport.close() }
                    assertTrue(TailscaleSshAuth.pending.value.isEmpty())
                    assertTrue(running.client.closing.await(3, TimeUnit.SECONDS))
                    assertEquals(1L, running.done.count)
                    await("dismissed authentication target") { !hasTarget() }
                    running.client.allowClose.countDown()
                    running.cancelled()
                }
            }
        } finally { transport.close() }
    }

    @Test fun closingPageCancelsPromptBeforeQueuedIoCleanupCanRun() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val pool = Executors.newSingleThreadExecutor()
        val dispatcher = pool.asCoroutineDispatcher()
        val job = SupervisorJob()
        val controller = FilesController(context, CoroutineScope(job + dispatcher))
        val browser = SftpSession(host, null, context)
        val field = FilesController::class.java.getDeclaredField("work").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val work = field.get(controller) as FilesWork<SftpSession>
        work.attach(browser)
        val occupied = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        pool.execute { occupied.countDown(); releaseIo.await(10, TimeUnit.SECONDS) }
        try {
            assertTrue(occupied.await(3, TimeUnit.SECONDS))
            Running(browser.authenticationOwner).use { running ->
                running.entered()
                running.client.banner()
                awaitRequests(1)
                inst.runOnMainSync { controller.close() }
                assertTrue("page retirement waited for IO cleanup", TailscaleSshAuth.pending.value.isEmpty())
                running.cancelled()
            }
        } finally {
            releaseIo.countDown()
            controller.close()
            browser.close()
            job.cancel()
            dispatcher.close()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun successfulAuthenticationRetiresOwnerBindingAndRestoresTimeout() {
        assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        val owner = TailscaleSshAuth.Owner()
        Running(owner).use { running ->
            running.entered()
            running.client.banner()
            awaitRequests(1)
            running.finish()
            owner.cancel()
            assertEquals(0, running.client.closes.get())
            assertEquals(listOf("none"), running.client.methods)
            assertEquals(12_345, running.client.transport.timeoutMs)
            assertTrue(TailscaleSshAuth.pending.value.isEmpty())
        }
    }

    private fun awaitRequests(count: Int): List<TailscaleSshAuth.Request> {
        await("$count authentication requests") { TailscaleSshAuth.pending.value.size == count }
        return TailscaleSshAuth.pending.value
    }

    private fun hasTarget(): Boolean {
        val root = inst.uiAutomation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString()?.contains(host.host) == true) return true
            for (i in 0 until node.childCount) if (node.getChild(i)?.let(::visit) == true) return true
            return false
        }
        return visit(root)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for $label")
    }
}
