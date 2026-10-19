package com.briqt.moke.terminal

import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
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
}
