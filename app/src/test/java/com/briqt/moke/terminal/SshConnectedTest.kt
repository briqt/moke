package com.briqt.moke.terminal

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectedTest {
    private class Client(private val name: String, private val events: MutableList<String>) : SSHClient() {
        private val fakeSocket = object : Socket() {
            override fun close() { events.add("$name socket") }
        }
        override fun getSocket(): Socket = fakeSocket
        override fun disconnect() { events.add("$name disconnect") }
    }

    @Test fun `target and jump sockets are aborted before sshj disconnect and each owner closes once`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val c = SshConnector.Connected(Client("target", events), Client("jump", events))
        val done = CountDownLatch(1)
        Thread {
            c.abortSockets()
            c.close()
            c.close()
            c.abortSockets()
            done.countDown()
        }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("target socket", "jump socket", "target disconnect", "jump disconnect"), events)
    }

    @Test fun `concurrent connection close has only one disconnect owner`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val c = SshConnector.Connected(Client("target", events), Client("jump", events))
        val gate = CountDownLatch(1)
        val done = CountDownLatch(12)
        repeat(12) {
            Thread { gate.await(); c.close(); done.countDown() }.start()
        }
        gate.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("target socket", "jump socket", "target disconnect", "jump disconnect"), events)
    }

    @Test fun `configureClientHeartbeat starts keepAlive threads on connect and connectVia and terminates on close`() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { bannerServer ->
            val serverPeers = Collections.synchronizedList(mutableListOf<Socket>())
            val acceptor = Thread({
                repeat(2) {
                    val peer = runCatching { bannerServer.accept() }.getOrNull() ?: return@Thread
                    serverPeers.add(peer)
                    Thread({
                        runCatching {
                            peer.getOutputStream().write("SSH-2.0-OpenSSH_9.0\r\n".toByteArray(StandardCharsets.US_ASCII))
                            peer.getOutputStream().flush()
                            val buf = ByteArray(256)
                            while (peer.getInputStream().read(buf) >= 0) {
                                // Drain client identification / heartbeat bytes until closed.
                            }
                        }
                    }, "test-ssh-peer-$it").apply { isDaemon = true }.start()
                }
            }, "test-ssh-acceptor").apply { isDaemon = true }
            acceptor.start()

            open class NoKexClient : SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.HEARTBEAT }) {
                override fun doKex() {
                    // Skip full KEX in unit test so SSHClient.onConnect() proceeds directly to keepAlive startup.
                }
            }

            val jump = NoKexClient().also(::configureClientHeartbeat)
            val target = NoKexClient().also(::configureClientHeartbeat)
            assertEquals(SshConnector.HEARTBEAT_INTERVAL_SECONDS, jump.connection.keepAlive.keepAliveInterval)
            assertEquals(SshConnector.HEARTBEAT_INTERVAL_SECONDS, target.connection.keepAlive.keepAliveInterval)

            Socket("127.0.0.1", bannerServer.localPort).use { tunnelSocket ->
                val connected = SshConnector.Connected(target, jump)
                try {
                    jump.connect("127.0.0.1", bannerServer.localPort)
                    target.connectVia(tunnelSocket.getInputStream(), tunnelSocket.getOutputStream())

                    assertTrue("Jump keepAlive daemon must be alive after connect()", jump.connection.keepAlive.isAlive)
                    assertTrue("Target keepAlive daemon must be alive after connectVia()", target.connection.keepAlive.isAlive)
                } finally {
                    connected.close()
                    serverPeers.forEach { runCatching { it.close() } }
                }

                jump.connection.keepAlive.join(2_000)
                target.connection.keepAlive.join(2_000)
                assertFalse("Jump keepAlive thread must terminate on close()", jump.connection.keepAlive.isAlive)
                assertFalse("Target keepAlive thread must terminate on close()", target.connection.keepAlive.isAlive)
            }
        }
    }
}
