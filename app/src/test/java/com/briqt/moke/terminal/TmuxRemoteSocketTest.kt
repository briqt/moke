package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// type: executable pinned socket path regression tests
class TmuxRemoteSocketTest {
    private val scripts = TmuxScripts("", "")
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private inner class Host(realTmux: Boolean = false) : AutoCloseable {
        init {
            assumeTrue(File("/bin/sh").canExecute() && File("/proc/sys/kernel/random/boot_id").isFile)
        }
        val base = Files.createTempDirectory(File("/tmp").toPath(), "moke-sock-").toFile().canonicalFile
        private val bin = File(base, "bin").apply { mkdir() }
        private val trace = File(base, "tmux-calls")
        val uid = (Files.getAttribute(base.toPath(), "unix:uid") as Number).toLong()
        val parent = File(base, "tmux-$uid")
        val socket = File(parent, "default").path
        private var request = 0

        init {
            mode(base, "rwx------")
            val executable = if (realTmux) {
                System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "tmux") }
                    .first { it.isFile && it.canExecute() }.canonicalPath
            } else null
            File(bin, "tmux").apply {
                writeText("#!/bin/sh\n" + if (executable == null) {
                    """
                        printf '%s\n' "${'$'}*" >> ${q(trace.path)}
                        [ "${'$'}1" = -N ] && [ "${'$'}2" = -u ] && [ "${'$'}3" = -S ] && [ "${'$'}5" = display-message ] || exit 9
                        printf 'error connecting to %s (No such file or directory)\n' "${'$'}4" >&2
                        exit 1
                    """.trimIndent()
                } else {
                    // The real generated commands run intact; only config and socket isolation
                    // are enforced at the executable boundary. Never use the user's server.
                    """
                        found=0; previous=
                        for argument do
                            if [ "${'$'}previous" = -S ]; then
                                [ "${'$'}argument" = ${q(socket)} ] || exit 9
                                found=1
                            fi
                            previous=${'$'}argument
                        done
                        [ "${'$'}found" -eq 1 ] || exit 9
                        exec ${q(executable)} -f /dev/null "${'$'}@"
                    """.trimIndent()
                })
                assertTrue(setExecutable(true))
            }
        }

        fun execute(command: String, tmpBase: File = base): String {
            val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
            builder.environment().apply {
                remove("TMUX"); remove("TMUX_PANE")
                put("PATH", bin.path + File.pathSeparator + System.getenv("PATH"))
                put("TMUX_TMPDIR", tmpBase.path); put("TMPDIR", base.path)
                put("HOME", base.path); put("XDG_CONFIG_HOME", base.path)
                put("HISTFILE", "/dev/null"); put("LESSHISTFILE", "/dev/null")
                put("BASH_ENV", "/dev/null"); put("ENV", "/dev/null")
                put("LC_ALL", "C"); put("TERM", "xterm-256color"); put("SHELL", "/bin/sh")
            }
            val process = builder.start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("socket command timeout", completed)
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            return output
        }

        fun observe(path: String? = null, tmpBase: File = base, previous: TmuxServerId? = null): TmuxObservation {
            val id = "socket_${++request}"
            val output = execute(TmuxRemote.observe(scripts, id, null, false, path, previous), tmpBase)
            return TmuxRemote.parseObservation(output, id).also { assertNotNull(output, it) }!!
        }

        fun calls(): List<String> = trace.takeIf { it.exists() }?.readLines().orEmpty()

        fun tree(): Set<String> = base.walkTopDown().filter { it != trace }
            .map { it.relativeTo(base).path }.toSet()

        override fun close() {
            assertTrue("remove only our private test directory", base.deleteRecursively())
        }
    }

    private fun mode(file: File, permissions: String) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    @Test fun `absent default parent stays no server after its observed socket is pinned`() {
        Host().use { host ->
            val first = host.observe()
            assertEquals(TmuxProbeKind.NO_SERVER, first.kind)
            assertEquals(host.socket, first.defaultSocketPath)
            assertFalse(host.parent.exists())
            repeat(2) {
                val pinned = host.observe(first.defaultSocketPath)
                assertEquals(TmuxProbeKind.NO_SERVER, pinned.kind)
                assertEquals(first.defaultSocketPath, pinned.defaultSocketPath)
                assertFalse("read-only probes must not create the missing parent", host.parent.exists())
            }
            assertEquals(3, host.calls().size)
        }
    }

    @Test fun `canonical explicit default pins do not rediscover a changed environment`() {
        Host().use { host ->
            val other = File(host.base, "other-base").apply { mkdir() }
            mode(other, "rwx------")
            val pinned = host.observe(host.socket, tmpBase = other)
            assertEquals(TmuxProbeKind.NO_SERVER, pinned.kind)
            assertEquals(host.socket, pinned.defaultSocketPath)
            assertFalse(host.parent.exists())
            val changed = host.observe(tmpBase = other)
            assertEquals(TmuxProbeKind.NO_SERVER, changed.kind)
            assertEquals(File(other, "tmux-${host.uid}/default").path, changed.defaultSocketPath)
            assertNotEquals(pinned.defaultSocketPath, changed.defaultSocketPath)
            assertFalse(File(other, "tmux-${host.uid}").exists())
        }
    }

    private fun rejected(host: Host, path: String) {
        val calls = host.calls()
        assertEquals(path, TmuxProbeKind.UNKNOWN, host.observe(path).kind)
        val output = host.execute(TmuxRemote.create("must-not-create", path))
        assertEquals(output, TmuxOperationCode.UNKNOWN, TmuxRemote.parseOperation(output)?.code)
        assertEquals("unsafe paths must stop before invoking tmux", calls, host.calls())
    }

    @Test fun `arbitrary custom missing parents and foreign default layouts are not absence proof`() {
        Host().use { host ->
            val before = host.tree()
            for (path in listOf(
                File(host.base, "custom/socket").path,
                File(host.base, "tmux-${host.uid}/custom").path,
                File(host.base, "tmux-${host.uid + 1}/default").path,
                File(host.base, "missing-base/tmux-${host.uid}/default").path,
            )) rejected(host, path)
            assertEquals("unknown probes and creates must have no filesystem side effects", before, host.tree())
            assertFalse(host.parent.exists())
        }
    }

    @Test fun `unsafe canonical base is unknown for both initial and pinned default without writes`() {
        Host().use { host ->
            mode(host.base, "rwxrwxrwx")
            val before = host.tree()
            assertEquals(TmuxProbeKind.UNKNOWN, host.observe().kind)
            rejected(host, host.socket)
            assertEquals(before, host.tree())
            assertFalse(host.parent.exists())
        }
    }

    @Test fun `symlink bases and symlink ancestors cannot authorize missing pinned parents`() {
        Host().use { host ->
            val real = File(host.base, "real").apply { mkdir() }
            val nested = File(real, "nested").apply { mkdir() }
            mode(real, "rwx------"); mode(nested, "rwx------")
            val alias = File(host.base, "alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            try {
                rejected(host, File(alias, "tmux-${host.uid}/default").path)
                rejected(host, File(alias, "nested/tmux-${host.uid}/default").path)
                assertFalse(File(real, "tmux-${host.uid}").exists())
                assertFalse(File(nested, "tmux-${host.uid}").exists())
            } finally {
                Files.delete(alias.toPath())
            }
        }
    }

    @Test fun `empty private default parent stays no server but replacement permissions fail closed`() {
        for (permissions in listOf("rwxr-xr-x", "rwxrwxrwx")) Host().use { host ->
            assertTrue(host.parent.mkdir())
            mode(host.parent, "rwx------")
            assertEquals(TmuxProbeKind.NO_SERVER, host.observe().kind)
            assertEquals(TmuxProbeKind.NO_SERVER, host.observe(host.socket).kind)
            val original = File(host.base, "original-parent")
            assertTrue(host.parent.renameTo(original))
            assertTrue(host.parent.mkdir())
            mode(host.parent, permissions)
            rejected(host, host.socket)
            assertEquals(TmuxProbeKind.UNKNOWN, host.observe().kind)
            assertTrue(original.isDirectory)
            assertTrue(host.parent.listFiles()!!.isEmpty())
        }
    }

    @Test fun `replaced symlink or regular file parents are unknown and remain untouched`() {
        for (replacement in listOf("symlink", "file", "dangling")) Host().use { host ->
            assertEquals(TmuxProbeKind.NO_SERVER, host.observe().kind)
            val target = File(host.base, "replacement")
            when (replacement) {
                "symlink" -> {
                    assertTrue(target.mkdir()); mode(target, "rwx------")
                    Files.createSymbolicLink(host.parent.toPath(), target.toPath())
                }
                "dangling" -> Files.createSymbolicLink(host.parent.toPath(), target.toPath())
                else -> host.parent.writeText("preserve parent bytes")
            }
            try {
                rejected(host, host.socket)
                assertEquals(TmuxProbeKind.UNKNOWN, host.observe().kind)
                if (replacement == "file") assertEquals("preserve parent bytes", host.parent.readText())
                else assertTrue(Files.isSymbolicLink(host.parent.toPath()))
                assertFalse(File(target, "default").exists())
            } finally {
                if (Files.isSymbolicLink(host.parent.toPath())) Files.delete(host.parent.toPath())
            }
        }
    }

    @Test fun `dangling socket symlink does not become a no server result or get replaced`() {
        Host().use { host ->
            assertTrue(host.parent.mkdir()); mode(host.parent, "rwx------")
            val socket = File(host.socket).toPath()
            val missing = File(host.base, "missing-target").toPath()
            Files.createSymbolicLink(socket, missing)
            try {
                rejected(host, host.socket)
                assertEquals(TmuxProbeKind.UNKNOWN, host.observe().kind)
                assertEquals(missing, Files.readSymbolicLink(socket))
            } finally {
                Files.delete(socket)
            }
        }
    }

    @Test fun `existing private custom parent remains supported without treating it as default`() {
        Host().use { host ->
            val parent = File(host.base, "custom").apply { mkdir() }
            mode(parent, "rwx------")
            val socket = File(parent, "socket").path
            val observed = host.observe(socket)
            assertEquals(TmuxProbeKind.NO_SERVER, observed.kind)
            assertEquals(socket, observed.defaultSocketPath)
            val output = host.execute(TmuxRemote.create("custom", socket))
            // The boundary deliberately rejects mutation, but the verified existing custom
            // parent must permit reaching the single explicit create, never default discovery.
            assertEquals(output, TmuxOperationCode.FAILED, TmuxRemote.parseOperation(output)?.code)
            assertEquals(1, host.calls().count { it.contains("new-session -d -P") })
            assertFalse(host.parent.exists())
            assertTrue(parent.listFiles()!!.isEmpty())
        }
    }

    /** Explicit opt-in, as in TmuxScrollSetupTest; every tmux invocation pins our own socket. */
    @Test fun `owned local tmux creates from pinned absence and ended references do not recreate it`() {
        assumeTrue(java.lang.Boolean.getBoolean("moke.test.localTmux"))
        Host(realTmux = true).use { host ->
            try {
                val first = host.observe()
                assertEquals(TmuxProbeKind.NO_SERVER, first.kind)
                assertEquals(host.socket, first.defaultSocketPath)
                val pinned = host.observe(first.defaultSocketPath)
                assertEquals(TmuxProbeKind.NO_SERVER, pinned.kind)
                assertFalse(host.parent.exists())
                val createdOutput = host.execute(TmuxRemote.create("socket-regression", pinned.defaultSocketPath))
                val created = TmuxRemote.parseOperation(createdOutput)
                assertEquals(createdOutput, TmuxOperationCode.OK, created?.code)
                val target = created!!.created!!
                val ready = host.observe(host.socket)
                assertEquals(TmuxProbeKind.READY, ready.kind)
                assertEquals(target.server, ready.server)
                assertEquals(target, ready.sessions.single().ref(ready.server!!))
                val moved = File(host.base, "moved-parent")
                assertTrue(host.parent.renameTo(moved))
                try {
                    // A now-missing default parent is not proof that a witnessed live server
                    // ended. The previous-birth guard must still withhold NO_SERVER.
                    assertEquals(TmuxProbeKind.UNKNOWN, host.observe(host.socket, previous = target.server).kind)
                    assertFalse(host.parent.exists())
                } finally {
                    assertTrue(moved.renameTo(host.parent))
                }
                assertEquals(TmuxOperationCode.OK, TmuxRemote.parseOperation(host.execute(TmuxRemote.kill(target)))?.code)
                val ended = host.observe(host.socket, previous = target.server)
                assertEquals(TmuxProbeKind.NO_SERVER, ended.kind)
                val repeatedKill = TmuxRemote.parseOperation(host.execute(TmuxRemote.kill(target)))
                assertTrue(repeatedKill?.code in setOf(TmuxOperationCode.STALE, TmuxOperationCode.UNKNOWN))
                assertEquals(TmuxProbeKind.NO_SERVER, host.observe(host.socket).kind)
            } finally {
                host.execute("tmux -N -S ${q(host.socket)} kill-server >/dev/null 2>&1 || :")
            }
        }
    }
}
