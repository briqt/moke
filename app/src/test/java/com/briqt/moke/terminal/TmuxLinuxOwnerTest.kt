package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// type: opt-in Linux production owner/probe regression with two private PTYs and pinned tmux
class TmuxLinuxOwnerTest {
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun executable(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }

    @Test fun `two live owners uniquely bind their own clients and corrupted witnesses never attach`() {
        assumeTrue(java.lang.Boolean.getBoolean("moke.test.localTmux"))
        assumeTrue(File("/proc/sys/kernel/random/boot_id").isFile && executable("script") != null && executable("tmux") != null)
        val root = Files.createTempDirectory("moke-linux-owner-").toFile().canonicalFile
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
        val uid = (Files.getAttribute(root.toPath(), "unix:uid") as Number).toLong()
        val socket = File(root, "tmux-$uid/default").path
        val bin = File(root, "bin").apply { mkdir() }
        val realTmux = executable("tmux")!!.canonicalPath
        File(bin, "tmux").apply {
            writeText("""
                #!/bin/sh
                found=0; previous=
                for argument do
                    if [ "§previous" = -S ]; then [ "§argument" = ${q(socket)} ] || exit 99; found=1; fi
                    previous=§argument
                done
                [ "§found" -eq 1 ] || exit 99
                exec ${q(realTmux)} -f /dev/null "§@"
            """.trimIndent().replace('§', '$') + "\n")
            setExecutable(true)
        }
        fun builder(vararg arguments: String): ProcessBuilder = ProcessBuilder(arguments.toList()).apply {
            redirectErrorStream(true)
            environment().apply {
                remove("TMUX"); remove("TMUX_PANE")
                put("PATH", bin.path + File.pathSeparator + System.getenv("PATH"))
                put("TMUX_TMPDIR", root.path); put("TMPDIR", root.path); put("HOME", root.path)
                put("XDG_CONFIG_HOME", root.path); put("SHELL", "/bin/sh")
                put("ENV", "/dev/null"); put("BASH_ENV", "/dev/null"); put("HISTFILE", "/dev/null")
                put("LC_ALL", "C"); put("TERM", "xterm-256color")
            }
        }
        fun execute(command: String): String {
            val process = builder("/bin/sh", "-c", command).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("private owner command timeout") }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            return output
        }
        val assetRoot = listOf(File("src/main/assets/tmux"), File("app/src/main/assets/tmux")).first { it.isDirectory }
        val scripts = TmuxScripts(File(assetRoot, "observer.sh").readText(), File(assetRoot, "probe.sh").readText())
        val nonces = listOf("a".repeat(32), "b".repeat(32))
        val owners = mutableListOf<Process>()
        var request = 0
        fun observe(nonce: String): TmuxObservation {
            val id = "owner_${++request}"
            val output = execute(TmuxRemote.observe(scripts, id, nonce, true, socket))
            return TmuxRemote.parseObservation(output, id).also { assertNotNull(output, it) }!!
        }
        try {
            val createdOutput = execute(TmuxRemote.create("owner-regression", socket))
            val target = TmuxRemote.parseOperation(createdOutput)?.created
            assertNotNull(createdOutput, target)
            val observations = nonces.mapIndexed { index, nonce ->
                val payload = "tmux -N -u -S ${q(socket)} attach-session -t ${q(target!!.id)}"
                val command = TmuxRemote.launch(scripts, nonce, payload)
                owners += builder(executable("script")!!.path, "-q", "-f", "-c", command, "/dev/null")
                    .redirectOutput(File(root, "pty-$index")).start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var observed = observe(nonce)
                while ((observed.owner == null || observed.clients.none { it.identity.tty == observed.owner.tty }) && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                    observed = observe(nonce)
                }
                assertTrue(observed.toString(), TmuxCoordinatorRules.verified(observed, nonce, true, socket))
                assertNotNull(TmuxCoordinatorRules.binding(TmuxScope("local", "owner-$index"), observed))
                observed
            }
            assertNotEquals(observations[0].owner!!.tty, observations[1].owner!!.tty)
            assertNotEquals(observations[0].owner!!.pid, observations[1].owner!!.pid)
            val final = observe(nonces[0])
            assertEquals(2, final.clients.size)
            val wrongPlatform = execute(TmuxRemote.observe(scripts, "wrong_platform", nonces[0], true, socket,
                target!!.server.copy(startedTicks = -target.server.startedTicks)))
            assertEquals(wrongPlatform, TmuxProbeKind.UNKNOWN, TmuxRemote.parseObservation(wrongPlatform, "wrong_platform")?.kind)
            val record = File(root, ".moke-tty-${nonces[0]}/record")
            val original = record.readText()
            val fields = original.trimEnd('\n').split('\n')
            assertEquals(6, fields.size)
            val corruptions = listOf(
                fields.toMutableList().apply { this[0] = "c".repeat(32) },
                fields.toMutableList().apply { this[2] = (uid + 1).toString() },
                fields.toMutableList().apply { this[3] = observations[1].owner!!.tty },
                fields.toMutableList().apply {
                    val pair = this[4].split(' ')
                    this[4] = pair[0] + " " + (pair[1].toLong() + 1)
                },
            )
            for (bad in corruptions) {
                record.writeText(bad.joinToString("\n") + "\n")
                val unknown = observe(nonces[0])
                assertEquals(TmuxProbeKind.UNKNOWN, unknown.kind)
                assertNull(unknown.server)
                record.writeText(original)
            }
            assertEquals(TmuxProbeKind.READY, observe(nonces[0]).kind)
            // Exercise the complete nested guard as actually sent, including its argv size,
            // repeated live owner proof, frozen server/session and complete operation ACK.
            val rename = TmuxRemote.guardPeer(scripts, nonces[0], true, final.peerBootId,
                TmuxRemote.rename(target!!, "owner-renamed"))
            val renamed = execute(rename)
            assertEquals(renamed, TmuxOperationCode.OK, TmuxRemote.parseOperation(renamed)?.code)
            assertEquals("owner-renamed", observe(nonces[0]).sessions.single().name)
        } finally {
            execute("tmux -N -u -S ${q(socket)} kill-server >/dev/null 2>&1 || :")
            owners.forEach { process ->
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
            assertTrue(root.deleteRecursively())
        }
    }
}
