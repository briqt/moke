package com.briqt.moke.terminal

import org.junit.Assert.*
import org.junit.Test

// type: pure directory parsing and lifecycle tests
class SessionMetadataTest {
    private val scope = TmuxScope("route-account-network", "connection-a", 3)
    private val host = "server.example"
    private val label = "Work connection"
    private val target = SessionDirectoryTarget(scope, host, label)
    private val server = TmuxServerId(20, 10, "/tmp/tmux-1000/default", "12345678-1234-1234-1234-123456789abc", 15, 4, 7)
    private val owner = TmuxOwnerProof("a".repeat(32), 30, 1000, 16, "/dev/pts/4")
    private val client = TmuxClientId(40, 17, owner.tty)
    private val session = TmuxSession("\$0", "work", 1, 1, 18)

    private fun attached(pane: TmuxPane = TmuxPane("%1", "title", "/tmux"), generation: Long = 1): TmuxUiState {
        val binding = TmuxBinding(scope, owner, client, session.ref(server))
        val observation = TmuxObservation(TmuxProbeKind.READY, owner, server, listOf(session),
            listOf(TmuxClient(client, session.id, session.created, pane)), peerBootId = server.bootId)
        return TmuxUiState(phase = TmuxPhase.READY, snapshot = TmuxSnapshot(scope, generation, observation, true),
            presence = TmuxPresence.Attached(binding), lastBinding = binding)
    }

    @Test fun `strict file URI decodes Unicode space percent pipe and plus exactly once`() {
        val report = OscDirectoryReport.parse("file://SERVER.example/home/%E4%B8%AD%E6%96%87%20%25%7C+a%252F")!!
        assertEquals(host, report.host)
        assertEquals("/home/中文 %|+a%2F", report.path)
        assertEquals("/中文", OscDirectoryReport.parse("file://$host/中文")!!.path)
        assertEquals("/", OscDirectoryReport.parse("file://$host/")!!.path)
        assertEquals("/a/../b//", OscDirectoryReport.parse("file://$host/a/../b//")!!.path)
    }

    @Test fun `malformed encoding UTF8 unsupported URI parts controls and excessive paths are rejected`() {
        val invalid = listOf(
            "", "/home/a", "ssh://$host/home", "file:relative", "file://$host", "file://$host/has space",
            "file://user@$host/home", "file://$host:22/home", "file://$host:/home", "file://$host/home?x",
            "file://$host/home#x", "file://$host/home?", "file://$host/home#", "file://%73erver.example/home",
            "file://$host/%", "file://$host/%1", "file://$host/%xy", "file://$host/%C0%AF", "file://$host/%FF",
            "file://$host/%E4%B8", "file://$host/%ED%A0%80", "file://$host/%F4%90%80%80", "file://$host/%80",
            "file://$host/%00", "file://$host/%1B", "file://$host/%7F", "file://$host/%C2%85", "file://$host/%0A",
            "file://$host/line\nnext", "file://$host/line\tother", "file://$host/%E2%80%A8", "file://$host/\uD800",
            "file://$host/" + "x".repeat(SESSION_PATH_LIMIT), "file://$host/" + "%41".repeat(6000),
        )
        invalid.forEach { assertNull(it, OscDirectoryReport.parse(it)) }
        assertNotNull(OscDirectoryReport.parse("file://$host/" + "x".repeat(SESSION_PATH_LIMIT - 1)))
    }

    @Test fun `empty host and localhost never guess outer physical machine`() {
        for (raw in listOf("file:///home/a", "file:/home/a", "file://localhost/home/a", "file://LOCALHOST/home/a", "file://test.localhost/home/a")) {
            assertNull(raw, target.reported(OscDirectoryReport.parse(raw)!!))
        }
    }

    @Test fun `IPv6 remains a reported target and never resolves aliases`() {
        val report = OscDirectoryReport.parse("file://[2001:DB8::2]/home/a")!!
        assertEquals("2001:db8::2", report.host)
        assertEquals("2001:db8::2", target.reported(report)!!.targetLabel)
        val ipv6 = SessionDirectoryTarget(scope, "[2001:db8::2]", label).reported(report)!!
        assertEquals(label, ipv6.targetLabel)
        val scoped = OscDirectoryReport.parse("file://[fe80::2%25eth0]/home/a")!!
        assertEquals("fe80::2%eth0", scoped.host)
    }

    @Test fun `group identity excludes connection and display label but includes route account network and report`() {
        val raw = OscDirectoryReport.parse("file://$host/project")!!
        val report = target.reported(raw)!!
        val copy = SessionDirectoryTarget(scope.copy(connectionId = "connection-b"), host, "Another label").reported(raw)!!
        assertEquals(report.targetKey, copy.targetKey)
        assertEquals(label, report.targetLabel)
        assertEquals(target.authenticated(raw.path).targetKey, report.targetKey)
        for (otherScope in listOf(scope.copy(targetStamp = "another-account-or-route"), scope.copy(networkEpoch = 4))) {
            assertNotEquals(report.targetKey, SessionDirectoryTarget(otherScope, host, label).reported(raw)!!.targetKey)
        }
        val nested = target.reported(raw.copy(host = "nested.example"))!!
        assertEquals("nested.example", nested.targetLabel)
        assertNotEquals(report.targetKey, nested.targetKey)
        val alias = target.reported(raw.copy(host = "server-alias.example"))!!
        assertEquals("server-alias.example", alias.targetLabel)
        assertNotEquals(report.targetKey, alias.targetKey)
        assertEquals(target.authenticated("/project").targetKey, target.authenticated("/another").targetKey)
    }

    @Test fun `latest invalid or unassignable report withdraws old path`() {
        val metadata = SessionMetadata(scope, target)
        metadata.onDirectory("file://$host/first")
        assertEquals("/first", metadata.directory.value!!.path)
        metadata.onDirectory("file://nested.example/second")
        assertEquals("nested.example", metadata.directory.value!!.targetLabel)
        assertEquals("/second", metadata.directory.value!!.path)
        metadata.onDirectory("file://localhost/unknown")
        assertNull(metadata.directory.value)
        metadata.onDirectory("file://$host/third")
        metadata.onDirectory("file://$host/%FF")
        assertNull(metadata.directory.value)
    }

    @Test fun `tmux owns joint current pane directory and does not borrow OSC for unavailable cwd`() {
        val metadata = SessionMetadata(scope, target)
        metadata.onDirectory("file://nested.example/osc")
        metadata.update(attached(), true)
        assertEquals(target.authenticated("/tmux"), metadata.directory.value)
        metadata.onDirectory("file://nested.example/not-tmux")
        assertEquals("/tmux", metadata.directory.value!!.path)
        metadata.update(attached(TmuxPane("%2", "next", null), 2), true)
        assertNull(metadata.directory.value)
        metadata.update(attached(TmuxPane("%2", "next", "/new"), 3), true)
        assertEquals("/new", metadata.directory.value!!.path)
    }

    @Test fun `normal generations do not clear same pane location but invalidation and binding changes do`() {
        val metadata = SessionMetadata(scope, target)
        val first = attached()
        metadata.update(first, true)
        val path = metadata.directory.value
        metadata.update(attached(generation = 2), true)
        assertEquals(path, metadata.directory.value)
        val unknown = first.copy(snapshot = first.snapshot!!.copy(verified = false), presence = TmuxPresence.Unknown())
        metadata.update(unknown, true)
        assertNull(metadata.directory.value)
        metadata.update(first, true)
        assertNotNull(metadata.directory.value)
        metadata.update(first.copy(presence = TmuxPresence.Shell(owner)), true)
        assertNull(metadata.directory.value)
        metadata.onDirectory("file://$host/shell")
        assertEquals("/shell", metadata.directory.value!!.path)
        metadata.update(first.copy(presence = TmuxPresence.Shell(owner.copy(startedTicks = 999))), true)
        assertNull(metadata.directory.value)
    }

    @Test fun `merged generation cannot inherit pane bytes and foreign scope cannot supply a path`() {
        val first = attached()
        val metadata = SessionMetadata(scope, target)
        metadata.update(first, true)
        val merged = first.copy(snapshot = first.snapshot!!.copy(generation = 2,
            observation = first.snapshot.observation.copy(clients = first.snapshot.observation.clients.map { it.copy(pane = null) })))
        metadata.update(merged, true)
        assertNull(metadata.directory.value)
        val foreign = first.currentBinding!!.copy(scope = scope.copy(networkEpoch = 9))
        metadata.update(first.copy(presence = TmuxPresence.Attached(foreign)), true)
        assertNull(metadata.directory.value)
    }

    @Test fun `EOF close and target changes cannot accept late reports or old observations`() {
        for (end in 0..2) {
            val metadata = SessionMetadata(scope, target)
            metadata.update(attached(), true)
            assertNotNull(metadata.directory.value)
            when (end) {
                0 -> metadata.end()
                1 -> metadata.update(attached(), false)
                else -> metadata.update(attached().copy(targetChanged = true), true)
            }
            metadata.onDirectory("file://$host/late")
            metadata.update(attached(generation = 99), true)
            assertNull(metadata.directory.value)
        }
    }

    @Test fun `new connection starts without any report or inherited pane`() {
        val old = SessionMetadata(scope, target)
        old.onDirectory("file://$host/old")
        val nextScope = scope.copy(connectionId = "new-connection")
        val fresh = SessionMetadata(nextScope, SessionDirectoryTarget(nextScope, host, label))
        assertNull(fresh.directory.value)
        fresh.update(attached(), true)
        assertNull(fresh.directory.value)
        assertEquals("/old", old.directory.value!!.path)
    }
}
