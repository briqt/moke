package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// type: executable JVM launch-fallback regressions; no remote host or tmux server is created
class TmuxFallbackShellTest {
    private data class Result(val code: Int, val output: String, val shellStarted: Boolean)

    private fun run(restore: Boolean, brokenOutput: Boolean): Result {
        assumeTrue(File("/proc/sys/kernel/random/boot_id").isFile && File("/dev/full").exists())
        val root = Files.createTempDirectory("moke-fallback-").toFile().canonicalFile
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
        try {
            val marker = File(root, "shell-started")
            val shell = File(root, "shell").apply {
                writeText("#!/bin/sh\nprintf 'started\\n' >> \"\$MOKE_FALLBACK_MARKER\"\n")
                assertTrue(setExecutable(true))
            }
            val socket = File(root, "absent.sock").path
            val server = TmuxServerId(42, 1, socket, "01234567-89ab-cdef-0123-456789abcdef", 1, 1, 1)
            val command = if (restore) TmuxRemote.restore("missing", socketPath = socket)
                else TmuxRemote.open(TmuxSessionRef(server, "\$1", 1, "missing"))
            val builder = ProcessBuilder("/bin/sh", "-c", command).directory(root)
            builder.environment().apply {
                put("HOME", root.path)
                put("TMPDIR", root.path)
                put("TMUX_TMPDIR", root.path)
                put("SHELL", shell.path)
                put("MOKE_FALLBACK_MARKER", marker.path)
                put("BASH_ENV", "/dev/null")
                put("ENV", "/dev/null")
                put("LC_ALL", "C")
                remove("BASHOPTS")
                remove("SHELLOPTS")
            }
            if (brokenOutput) builder.redirectOutput(File("/dev/full"))
            val process = builder.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Private fallback fixture timed out")
            }
            val output = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
            return Result(process.exitValue(), output, marker.exists())
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test fun `failed status output never launches another shell for open or restore`() {
        for (restore in listOf(false, true)) {
            val actual = run(restore, brokenOutput = true)
            assertNotEquals(actual.output, 0, actual.code)
            assertFalse(actual.output, actual.shellStarted)
        }
    }

    @Test fun `writable status output preserves ordinary shell fallback for open and restore`() {
        for (restore in listOf(false, true)) {
            val actual = run(restore, brokenOutput = false)
            assertEquals(actual.output, 0, actual.code)
            assertTrue(actual.output, actual.shellStarted)
            assertTrue(actual.output, actual.output.contains("the following terminal is an ordinary shell"))
        }
    }
}
