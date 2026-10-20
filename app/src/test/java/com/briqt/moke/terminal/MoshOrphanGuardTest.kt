package com.briqt.moke.terminal

// type: remote process identity safety tests
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MoshOrphanGuardTest {
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val identity = MoshServerIdentity(123, 1000, "01234567-89ab-cdef-0123-456789abcdef", 4567, nonce)
    private val record = "__MOKE_MOSH_INSTANCE_V1__|123|1000|${identity.bootId}|4567|$nonce\n"

    @Test fun `argv witness preserves the exact default or command bootstrap carrier`() {
        val base = MoshBootstrap.serverCommand()
        for (startup in listOf(null, "", "printf 'hello world'; exec bash -l")) {
            val original = MoshBootstrap.serverCommand(startupCommand = startup)
            val marked = MoshOrphanGuard.serverCommand(startup, nonce)
            assertEquals(base + " -l MOKE_MOSH_INSTANCE=$nonce" + original.removePrefix(base), marked)
            assertEquals(original, marked.replace(" -l MOKE_MOSH_INSTANCE=$nonce", ""))
            if (startup.isNullOrBlank()) assertFalse(marked.contains(" -- "))
            else assertTrue(marked.indexOf("MOKE_MOSH_INSTANCE=") < marked.indexOf(" -- "))
        }
    }

    @Test fun `capture only accepts one complete record for the requested pid and independent nonce`() {
        assertEquals(identity, MoshOrphanGuard.parseCapture(record, 123, nonce))
        assertEquals(identity, MoshOrphanGuard.parseCapture("notice\r\n$record", 123, nonce))
        for (output in listOf(
            "", record.trimEnd(), record + record, record.replace("|123|", "|124|"),
            record.replace("|4567|", "|0|"), record.replace("|1000|", "|-1|"),
            record.replace(nonce, "a".repeat(32)), record.replace(identity.bootId, "invalid"),
        )) assertNull(output, MoshOrphanGuard.parseCapture(output, 123, nonce))
    }

    @Test fun `capture and cleanup use isolated python with no numeric signal or environ access`() {
        for (command in listOf(MoshOrphanGuard.captureCommand(123, nonce), MoshOrphanGuard.cleanupCommand(identity))) {
            assertTrue(command.startsWith("python3 -I -c "))
            assertTrue(command.contains("pidfd_open"))
            assertTrue(command.contains("pidfd_send_signal"))
            assertFalse(command.contains("os.kill("))
            assertFalse(command.contains("kill 123"))
            assertFalse(command.contains("/environ"))
            assertFalse(command.contains("MOSH_KEY"))
            assertFalse(command.contains("pkill"))
        }
    }

    @Test fun `cleanup opens a handle before rechecking all guards and signalling the handle`() {
        val command = MoshOrphanGuard.cleanupCommand(identity)
        val open = command.indexOf("fd = os.pidfd_open(pid, 0)")
        val witness = command.indexOf("actual = witness()")
        val compare = command.indexOf("actual == expected and witness() == expected")
        val signal = command.indexOf("signal.pidfd_send_signal(fd, signal.SIGTERM, None, 0)")
        assertTrue(open in 0 until witness && witness < compare && compare < signal)
        for (guard in listOf("os.getuid()", "os.geteuid()", "st_uid", "boot_id", "mosh-server", "MOKE_MOSH_INSTANCE=", "ticks")) {
            assertTrue(guard, command.contains(guard))
        }
        assertTrue(command.contains("os.close(fd)"))
    }

    @Test fun `invalid identities cannot become cleanup commands`() {
        assertThrows(IllegalArgumentException::class.java) { identity.copy(pid = 1) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(uid = -1) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(startedTicks = 0) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(bootId = "'; kill 1") }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(nonce = "'; kill 1") }
    }

    /** Executes the PRODUCTION Python body, with every /proc read and signal replaced in memory. */
    private fun fakeCleanup(scenario: String, capture: Boolean = false): String {
        val command = if (capture) MoshOrphanGuard.captureCommand(identity.pid, nonce) else MoshOrphanGuard.cleanupCommand(identity)
        val prefix = "python3 -I -c '"
        val end = command.indexOf("' '123' '$nonce'")
        check(command.startsWith(prefix) && end > prefix.length)
        val body = command.substring(prefix.length, end).replace("'\\''", "'")
        val harness = """
            import builtins, io, os, signal, sys, types
            sys.excepthook = sys.__excepthook__
            body, scenario = sys.argv[1:3]
            calls = []
            reads = 0
            def opened(pid, flags):
                assert pid == 123 and flags == 0
                calls.append('open')
                if scenario == 'pidfd-fails':
                    raise OSError('unsupported kernel')
                return 91
            def sent(fd, sig, info, flags):
                assert fd == 91 and info is None and flags == 0
                calls.append('signal')
                # A dead handle cannot follow a reused numeric PID, including after witness checks.
                if scenario == 'reused-after-check':
                    raise ProcessLookupError('original handle is dead')
                if sig == 0:
                    calls.append('probe')
                    return
                assert sig == signal.SIGTERM
                calls.append('term-original')
            def stat(path):
                assert path == '/proc/123'
                return types.SimpleNamespace(st_uid=1001 if scenario == 'uid' else 1000)
            def read(path, mode='r', **kw):
                global reads
                if path == '/proc/sys/kernel/random/boot_id':
                    return io.StringIO('11234567-89ab-cdef-0123-456789abcdef' if scenario == 'boot' else '01234567-89ab-cdef-0123-456789abcdef')
                if path == '/proc/123/comm':
                    return io.BytesIO(b'tmux: server\n' if scenario == 'tmux' else b'mosh-server\n')
                if path == '/proc/123/cmdline':
                    marker = b'MOKE_MOSH_INSTANCE=' + (b'a' * 32 if scenario == 'nonce' else b'0123456789abcdef0123456789abcdef')
                    return io.BytesIO(b'mosh-server\0new\0-l\0' + marker + b'\0')
                assert path == '/proc/123/stat'
                reads += 1
                ticks = b'9999' if scenario == 'birth' or (scenario == 'reused-during-check' and reads > 1) else b'4567'
                return io.BytesIO(b'123 (mosh-server) S ' + b'0 ' * 18 + ticks + b'\n')
            def forbidden_numeric_kill(*args, **kw):
                raise AssertionError('numeric kill is forbidden')
            os.kill = forbidden_numeric_kill
            os.getuid = lambda: 1000
            os.geteuid = lambda: 1001 if scenario == 'euid' else 1000
            os.stat = stat
            os.pidfd_open = opened
            os.close = lambda fd: calls.append('close') if fd == 91 else None
            signal.pidfd_send_signal = sent
            builtins.open = read
            if scenario == 'no-pidfd':
                del os.pidfd_open
            if scenario == 'no-signal':
                del signal.pidfd_send_signal
            sys.argv = ['guard', '123', '0123456789abcdef0123456789abcdef', '1000', '01234567-89ab-cdef-0123-456789abcdef', '4567']
            try:
                exec(compile(body, '<production-orphan-guard>', 'exec'))
            except SystemExit as e:
                assert e.code == 0
            print(','.join(calls))
        """.trimIndent()
        val process = runCatching { ProcessBuilder("python3", "-I", "-c", harness, body, scenario).redirectErrorStream(true).start() }.getOrNull()
        assumeTrue("Optional local Python3 is needed for in-memory production-body safety execution", process != null)
        process!!
        try {
            assertTrue("guard exceeded deadline", process.waitFor(5, TimeUnit.SECONDS))
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            assertEquals(result, 0, process.exitValue())
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun `production capture emits only verified metadata and never a terminating signal`() {
        val output = fakeCleanup("same", capture = true)
        assertEquals(identity, MoshOrphanGuard.parseCapture(output, identity.pid, nonce))
        assertTrue(output.endsWith("open,signal,probe,close"))
        assertFalse(output.contains("term-original"))
    }

    @Test fun `production capture rejects an already reused PID with another argv nonce`() {
        assertEquals("open,close", fakeCleanup("nonce", capture = true))
    }

    @Test fun `production guard signals only the exact original handle`() {
        assertEquals("open,signal,term-original,close", fakeCleanup("same"))
    }

    @Test fun `production guard skips unsupported pidfd rather than falling back to numeric kill`() {
        assertEquals("", fakeCleanup("no-pidfd"))
        assertEquals("", fakeCleanup("no-signal"))
        assertEquals("open", fakeCleanup("pidfd-fails"))
    }

    @Test fun `production guard refuses changed uid boot birth nonce and tmux processes`() {
        for (scenario in listOf("uid", "euid", "boot", "birth", "nonce", "tmux", "reused-during-check")) {
            assertEquals(scenario, "open,close", fakeCleanup(scenario))
        }
    }

    @Test fun `production guard cannot follow numeric PID reuse after final validation`() {
        assertEquals("open,signal,close", fakeCleanup("reused-after-check"))
    }
}
