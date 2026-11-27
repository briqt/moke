package com.briqt.moke.terminal

import com.briqt.moke.data.Host
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PortForwardsTest {

    @Test
    fun `parse ports - separators, dedupe, order`() {
        val p = PortForwards.parsePorts("5173, 8080  3000，5173\n9229")
        assertEquals(listOf(5173, 8080, 3000, 9229), p.ports)
        assertEquals(emptyList<String>(), p.invalid)
    }

    @Test
    fun `parse ports - invalid tokens are reported, not dropped silently`() {
        val p = PortForwards.parsePorts("5173, abc, 0, 70000, 8080")
        assertEquals(listOf(5173, 8080), p.ports)
        assertEquals(listOf("abc", "0", "70000"), p.invalid)
        assertEquals(PortForwards.Parsed(emptyList(), emptyList()), PortForwards.parsePorts("  "))
    }

    @Test
    fun `parse local URLs without discarding explicit addresses`() {
        assertEquals(PortForwards.Target(8000, "127.0.0.1"), PortForwards.targetOf("http://127.0.0.1:8000/api?x=1"))
        assertEquals(PortForwards.Target(8000, "::1"), PortForwards.targetOf("http://[::1]:8000/"))
        assertEquals(PortForwards.Target(8000), PortForwards.targetOf("http://localhost:8000/"))
        assertEquals(PortForwards.Target(3000), PortForwards.targetOf("http://0.0.0.0:3000"))
        assertEquals(PortForwards.Target(80), PortForwards.targetOf("HTTP://LOCALHOST"))
        assertEquals(PortForwards.Target(443, "::1"), PortForwards.targetOf("https://[::1]?q"))
        assertEquals(PortForwards.Target(80), PortForwards.targetOf("http://localhost:/"))
        assertEquals(PortForwards.Target(443, "::1"), PortForwards.targetOf("https://[::1]:/"))
        assertEquals("[::1]:8000", PortForwards.Target(8000, "::1").address)
    }

    @Test
    fun `reject nonlocal and malformed targets without guessing a port`() {
        for (url in listOf(
            "https://example.com:8080/", "http://localhost.example.com/", "ftp://localhost:8000/",
            "http://localhost:99999/", "http://localhost:0/", "http://localhost:abc/", "http://[::1]::/",
            "http://localhost:+80/", "http://localhost:80@example.com/", "http://[::1]8000/", "http://[::1",
        )) {
            assertNull(url, PortForwards.targetOf(url))
        }
    }

    @Test
    fun `same port on different addresses keeps distinct listeners and stop is isolated`() {
        val transport = RecordingForwardClient()
        val forwards = PortForwards(transport)
        val v4 = PortForwards.Target(8000, "127.0.0.1")
        val v6 = PortForwards.Target(8000, "::1")
        val default = PortForwards.Target(8000)
        try {
            val local4 = forwards.start(v4)
            val local6 = forwards.start(v6)
            val localDefault = forwards.start(default.port)
            assertNotNull(local4)
            assertNotNull(local6)
            assertNotNull(localDefault)
            assertEquals(3, setOf(local4, local6, localDefault).size)
            assertEquals(local4, forwards.start(v4))
            assertEquals(local6, forwards.start(v6))
            assertEquals(localDefault, forwards.start(default))
            assertEquals(listOf(v4, v6, default), transport.opened.toList())
            assertEquals(listOf(v4, v6, default), forwards.entries.value.map { it.target })

            forwards.stop(v4)
            assertEquals(listOf(v6, default), forwards.entries.value.map { it.target })
            assertEquals(local6, forwards.start(v6))
            assertEquals(localDefault, forwards.start(default))
            assertEquals(2, transport.leases.get())
        } finally {
            forwards.stopAll()
        }
        assertEquals(0, transport.leases.get())
    }

    @Test
    fun `failed target and retry do not replace another target on the same port`() {
        val v4 = PortForwards.Target(8000, "127.0.0.1")
        val v6 = PortForwards.Target(8000, "::1")
        var failV4 = true
        val transport = RecordingForwardClient { if (failV4 && it == v4) throw IOException() }
        val forwards = PortForwards(transport)
        try {
            val local6 = forwards.start(v6)
            assertNotNull(local6)
            assertNull(forwards.start(v4))
            assertEquals(listOf(PortForwards.State.ACTIVE, PortForwards.State.FAILED), forwards.entries.value.map { it.state })
            assertEquals(local6, forwards.start(v6))

            failV4 = false
            val retry4 = forwards.start(v4)
            assertNotNull(retry4)
            assertNotEquals(local6, retry4)
            assertEquals(listOf(v6, v4), forwards.entries.value.map { it.target })
            assertEquals(listOf(v6, v4), transport.opened.toList())
        } finally {
            forwards.stopAll()
        }
        assertEquals(0, transport.leases.get())
    }

    @Test
    fun `concurrent starts of one target return the same live listener`() {
        val gate = CyclicBarrier(2)
        val transport = RecordingForwardClient { gate.await(5, TimeUnit.SECONDS) }
        val forwards = PortForwards(transport)
        val target = PortForwards.Target(8000, "::1")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val a = workers.submit<Int?> { forwards.start(target) }
            val b = workers.submit<Int?> { forwards.start(target) }
            val local = a.get(10, TimeUnit.SECONDS)
            assertNotNull(local)
            assertEquals(local, b.get(10, TimeUnit.SECONDS))
            assertEquals(local, forwards.entries.value.single().localPort)
            assertEquals(1, transport.leases.get())
        } finally {
            workers.shutdownNow()
            forwards.stopAll()
        }
        assertEquals(0, transport.leases.get())
    }

    @Test
    fun `lost SSH connection cannot leave a reusable listener`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val transport = RecordingForwardClient {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        val forwards = PortForwards(transport)
        val target = PortForwards.Target(8000, "::1")
        val worker = Executors.newSingleThreadExecutor()
        try {
            val stale = worker.submit<Int?> { forwards.start(target) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            transport.replaceConnection()
            resume.countDown()
            assertNull(stale.get(5, TimeUnit.SECONDS))
            assertEquals(PortForwards.State.FAILED, forwards.entries.value.single().state)
            assertEquals(0, transport.leases.get())

            val local = forwards.start(target)
            assertNotNull(local)
            transport.replaceConnection()
            val replacement = forwards.start(target)
            assertNotNull(replacement)
            assertNotEquals(local, replacement)
            assertEquals(replacement, forwards.entries.value.single().localPort)
            assertEquals(1, transport.leases.get())
        } finally {
            resume.countDown()
            worker.shutdownNow()
            forwards.stopAll()
        }
        assertEquals(0, transport.leases.get())
    }

    @Test
    fun `rewrite keeps scheme, path, query and fragment`() {
        assertEquals("http://127.0.0.1:41234/app?x=1#top", PortForwards.rewriteToLocal("http://localhost:5173/app?x=1#top", 41234))
        assertEquals("http://127.0.0.1:41234", PortForwards.rewriteToLocal("http://localhost:5173", 41234))
        assertEquals("http://127.0.0.1:41234/", PortForwards.rewriteToLocal("http://[::1]:8000/", 41234))
        assertEquals("https://127.0.0.1:41234/?q", PortForwards.rewriteToLocal("https://127.0.0.1:8443/?q", 41234))
    }

    @Test
    fun `host forwardPorts survives json round trip and defaults to empty for old data`() {
        val h = Host(host = "a", username = "u", forwardPorts = "5173, 8080")
        assertEquals("5173, 8080", Host.fromJson(h.toJson()).forwardPorts)
        val old = JSONObject().put("id", "x").put("host", "a").put("username", "u")
        assertEquals("", Host.fromJson(old).forwardPorts)
    }

    private class RecordingForwardClient(private val beforeOpen: (PortForwards.Target) -> Unit = {}) : ForwardCapable {
        val opened = CopyOnWriteArrayList<PortForwards.Target>()
        val leases = AtomicInteger()
        @Volatile private var client = TestClient()

        fun replaceConnection() {
            client.alive = false
            client = TestClient()
        }

        private inner class TestClient : SSHClient() {
            @Volatile var alive = true
            override fun isConnected(): Boolean = alive
            override fun isAuthenticated(): Boolean = alive

            override fun newLocalPortForwarder(params: Parameters, socket: ServerSocket): LocalPortForwarder {
                val target = PortForwards.Target(params.remotePort, params.remoteHost)
                beforeOpen(target)
                opened += target
                return object : LocalPortForwarder(connection, params, socket, LoggerFactory.DEFAULT) {
                    override fun listen() { socket.accept().close() }
                    override fun close() { socket.close() }
                }
            }
        }

        override fun acquireForwardClient(): SSHClient = client.also { leases.incrementAndGet() }
        override fun releaseForwardClient() { leases.decrementAndGet() }
    }
}
