package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// type: executable Darwin shell protocol tests using private native/BSD-stat fakes on Linux
// These tests do not establish that osascript or the native APIs run on macOS.
class TmuxDarwinShellTest {
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun mode(file: File, permissions: String) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }
    private fun bits(file: File, permissions: String) {
        Files.setAttribute(file.toPath(), "unix:mode", permissions.toInt(8))
    }

    private inner class Host : AutoCloseable {
        init { assumeTrue(File("/proc/sys/kernel/random/boot_id").isFile) }
        val root = Files.createTempDirectory("moke-darwin-shell-").toFile().canonicalFile
        val base = File(root, "base").apply { mkdir(); mode(this, "rwx------") }
        val alias = File(root, "alias")
        val uid = (Files.getAttribute(base.toPath(), "unix:uid") as Number).toLong()
        val recordDir = File(base, ".moke-tty-$nonce")
        val record = File(recordDir, "record")
        val ownerRow = "OWNER|200|$uid|-1700000000123456|/dev/ttys000|1234|$boot"
        private val stat = File(root, "bsd-stat").apply {
            writeText("""
                #!/bin/sh
                # BSD %Lp is only the low rwx bits, unlike GNU %a. Preserve this real
                # distinction so sticky/setuid/setgid cannot silently disappear in tests.
                hex=§(/usr/bin/stat -c %f -- "§3") || exit 1
                full=§(printf '%o' "§((0x§hex))")
                low=§(printf '%o' "§((0x§hex & 0777))")
                special=§(printf '%o' "§(((0x§hex >> 9) & 7))")
                format=§2
                for key in %Op %p %Lp %Mp %Sp %z; do
                    case "§key" in
                        %Op|%p) replacement=§full;;
                        %Lp) replacement=§low;;
                        %Mp) replacement=§special;;
                        %Sp) replacement=%A;;
                        %z) replacement=%s;;
                    esac
                    while :; do
                        case "§format" in
                            *"§key"*) prefix=§{format%%"§key"*}; suffix=§{format#*"§key"}
                                format="§prefix§replacement§suffix";;
                            *) break;;
                        esac
                    done
                done
                exec /usr/bin/stat -c "§format" -- "§3"
            """.trimIndent().replace('§', '$') + "\n")
            setExecutable(true)
        }
        private val native = File(root, "native-bridge").apply {
            writeText("""
                #!/bin/sh
                # Check the entire osascript invocation contract, including official stdin '-'.
                [ "§#" -ge 4 ] && [ "§1" = -l ] && [ "§2" = JavaScript ] && [ "§3" = - ] || exit 97
                cat >/dev/null
                case "§4" in
                    capability) printf '%s\n' "§{CAPABILITY:-SUPPORTED}";;
                    boot) printf 'BOOT|%s\n' "§BOOT";;
                    proc)
                        case "§SCENARIO" in absent) printf 'ABSENT\n';; permission) printf 'FAILURE\n';;
                            *) printf 'PROC|%s|%s|%s|%s\n' "§5" "§OWNER_UID" "§BIRTH" "§BOOT";; esac;;
                    owner)
                        case "§SCENARIO" in
                            record)
                                cp "§RECORD_DIR/record" "§RECORD_DIR/replacement"
                                chmod 600 "§RECORD_DIR/replacement"
                                mv "§RECORD_DIR/replacement" "§RECORD_DIR/record";;
                            directory)
                                mv "§RECORD_DIR" "§RECORD_DIR.old"
                                mkdir -m 700 "§RECORD_DIR"
                                cp "§RECORD_DIR.old/record" "§RECORD_DIR/record"
                                chmod 600 "§RECORD_DIR/record";;
                        esac
                        printf 'OWNER|%s|%s|%s|/dev/ttys000|1234|%s\n' "§5" "§OWNER_UID" "§BIRTH" "§BOOT";;
                    *) exit 9;;
                esac
            """.trimIndent().replace('§', '$') + "\n")
            setExecutable(true)
        }
        private val bin = File(root, "bin").apply {
            mkdir()
            File(this, "uname").apply { writeText("#!/bin/sh\nprintf 'Darwin\\n'\n"); setExecutable(true) }
            File(this, "tmux").apply {
                writeText("""
                    #!/bin/sh
                    [ "§1" = -N ] && [ "§2" = -u ] && [ "§3" = -S ] &&
                        [ "§4" = "§EXPECTED_SOCKET" ] && [ "§5" = display-message ] || exit 98
                    printf 'error connecting to %s (No such file or directory)\n' "§4" >&2
                    exit 1
                """.trimIndent().replace('§', '$') + "\n")
                setExecutable(true)
            }
        }
        private fun adapt(command: String) = command.replace("/usr/bin/stat", stat.path)
            .replace("/usr/bin/osascript", native.path)
        fun production(command: String, socketBase: File? = null): Pair<Int, String> =
            execute(adapt(command), socketBase = socketBase)

        val core = "moke_uint() { case \"\$1\" in ''|*[!0-9]*) return 1;; esac; }\n" +
            "moke_restore_locale() { :; }\n" + TmuxDarwin.core
                .replace("/usr/bin/stat", stat.path).replace("/usr/bin/osascript", native.path)

        fun publish(value: String = ownerRow + "\n") {
            assertTrue(recordDir.mkdir()); mode(recordDir, "rwx------")
            record.writeText(value); mode(record, "rw-------")
        }

        fun execute(body: String, scenario: String = "good", birth: String = "-1700000000123456", tmp: File = base,
                    socketBase: File? = null, vararg args: String): Pair<Int, String> {
            val builder = ProcessBuilder(listOf("/bin/sh", "-c", core + "\n" + body, "moke-test") + args)
                .redirectErrorStream(true)
            builder.environment().apply {
                put("TMPDIR", tmp.path)
                put("TMUX_TMPDIR", socketBase?.path ?: alias.takeIf { Files.isSymbolicLink(it.toPath()) }?.path ?: base.path)
                put("PATH", bin.path + File.pathSeparator + System.getenv("PATH"))
                put("EXPECTED_SOCKET", File(socketBase ?: base, "tmux-$uid/default").path)
                put("OWNER_UID", uid.toString()); put("BOOT", boot); put("BIRTH", birth)
                put("RECORD_DIR", recordDir.path); put("SCENARIO", scenario)
                put("BASH_ENV", "/dev/null"); put("ENV", "/dev/null"); put("LC_ALL", "C")
            }
            val process = builder.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("private fake bridge timed out") }
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        }
        fun probe(scenario: String = "good", birth: String = "-1700000000123456", tmp: File = base) =
            execute(TmuxDarwin.probe, scenario, birth, tmp, args = arrayOf(nonce)).let { (code, output) -> assertEquals(output, 0, code); output }

        override fun close() {
            if (Files.isSymbolicLink(alias.toPath())) Files.delete(alias.toPath())
            assertTrue(root.deleteRecursively())
        }
    }

    @Test fun `BSD stat fake reproduces full type mode low rwx and special bits separately`() {
        Host().use { host ->
            val shared = File(host.root, "shared").apply { mkdir() }
            bits(shared, "1777")
            val format = "%p|%Op|%Lp|%Mp|%Sp"
            fun stat(file: File) = host.execute("moke_stat ${q(format)} ${q(file.path)}").second
            assertEquals("41777|41777|777|1|drwxrwxrwt\n", stat(shared))
            bits(shared, "777")
            assertEquals("40777|40777|777|0|drwxrwxrwx\n", stat(shared))
            assertEquals("40700|40700|700|0|drwx------\n", stat(host.base))
            host.publish()
            assertEquals("100600|100600|600|0|-rw-------\n", stat(host.record))
            bits(host.record, "4600")
            assertEquals("104600|104600|600|4|-rwS------\n", stat(host.record))
        }
    }

    @Test fun `complete production capability accepts sticky shared base but rejects writable nonsticky base`() {
        Host().use { host ->
            val shared = File(host.root, "shared").apply { mkdir() }
            bits(shared, "1777")
            val (safeCode, safe) = host.production(TmuxRemote.capabilityCommand(), socketBase = shared)
            assertEquals(safe, 0, safeCode)
            assertEquals(safe, TmuxObserverCapability.Supported, parseTmuxObserverCapability(safe))
            assertFalse(File(shared, "tmux-${host.uid}").exists())
            bits(shared, "777")
            val (unsafeCode, unsafe) = host.production(TmuxRemote.capabilityCommand(), socketBase = shared)
            assertEquals(unsafe, 0, unsafeCode)
            assertEquals(unsafe, TmuxObserverCapability.Failure, parseTmuxObserverCapability(unsafe))
            assertFalse(File(shared, "tmux-${host.uid}").exists())
        }
    }

    @Test fun `owner base exact 700 includes special bits rather than only low rwx bits`() {
        for (permissions in listOf("1700", "2700", "4700", "7700")) Host().use { host ->
            host.publish()
            bits(host.base, permissions)
            assertEquals(permissions, "UNKNOWN\n", host.probe())
        }
    }

    @Test fun `owner directory and record exact permissions reject sticky setgid and setuid`() {
        for (permissions in listOf("1700", "2700", "4700", "7700")) Host().use { host ->
            host.publish()
            bits(host.recordDir, permissions)
            assertEquals("directory $permissions", "UNKNOWN\n", host.probe())
        }
        for (permissions in listOf("1600", "2600", "4600", "7600")) Host().use { host ->
            host.publish()
            bits(host.record, permissions)
            assertEquals("record $permissions", "UNKNOWN\n", host.probe())
        }
    }

    @Test fun `owner identity snapshots retain full mode and cleanup preserves specially chmodded records`() {
        Host().use { host ->
            host.publish()
            val body = "nonce=${q(nonce)}; moke_owner_base && moke_owner_identity || exit 9; printf '%s\\n' \"\$moke_record_dir_id\""
            val (code, directoryId) = host.execute(body)
            assertEquals(directoryId, 0, code)
            assertTrue(directoryId, directoryId.endsWith(":40700\n"))
            val (_, proof) = host.execute(TmuxDarwin.probe + "\nprintf '%s\\n' \"\$first_record_id\"", args = arrayOf(nonce))
            assertTrue(proof, proof.lineSequence().any { it.contains(":100600:") })
        }
        Host().use { host ->
            val payload = "chmod 4600 ${q(host.record.path)}; exit 7"
            assertEquals(7, host.execute(TmuxDarwin.observer, args = arrayOf(nonce, "command", payload)).first)
            assertTrue("cleanup must not remove a record with changed special mode bits", host.record.isFile)
        }
    }

    @Test fun `production capability uses official osascript stdin contract and fake rejects dev stdin path`() {
        Host().use { host ->
            val (code, output) = host.production(TmuxRemote.capabilityCommand())
            assertEquals(output, 0, code)
            assertEquals(output, TmuxObserverCapability.Supported, parseTmuxObserverCapability(output))
            val incorrect = host.core.replace("JavaScript - ", "JavaScript /dev/stdin ") +
                "\nmoke_native capability moke-native-check"
            assertEquals("fake must not silently accept the original broken contract", 97, host.execute(incorrect).first)
        }
    }

    @Test fun `complete production observation and peer guard use exactly one platform owner probe`() {
        Host().use { host ->
            host.publish()
            val assets = listOf(File("src/main/assets/tmux"), File("app/src/main/assets/tmux")).first { it.isDirectory }
            val scripts = TmuxScripts(File(assets, "observer.sh").readText(), File(assets, "probe.sh").readText())
            val (code, output) = host.production(TmuxRemote.observe(scripts, "full_darwin", nonce, true))
            assertEquals(output, 0, code)
            val observation = TmuxRemote.parseObservation(output, "full_darwin")
            assertNotNull(output, observation)
            assertEquals(output, TmuxProbeKind.NO_SERVER, observation!!.kind)
            assertEquals(nonce, observation.owner?.nonce)
            assertEquals("/dev/ttys000", observation.owner?.tty)
            assertEquals(1, output.lineSequence().count { it.startsWith("__MOKE_TMUX_V1__|OWNER|") })
            assertFalse(output, output.lineSequence().any { it == "UNKNOWN" })
            assertTrue(TmuxCoordinatorRules.verified(observation, nonce, true, null))
            val operation = "printf '__MOKE_TMUX_V1__|OP_BEGIN\\n__MOKE_TMUX_V1__|RESULT|OK\\n__MOKE_TMUX_V1__|OP_END|0\\n'"
            val (guardCode, guarded) = host.production(TmuxRemote.guardPeer(scripts, nonce, true, boot, operation))
            assertEquals(guarded, 0, guardCode)
            assertEquals(guarded, TmuxOperationCode.OK, TmuxRemote.parseOperation(guarded)?.code)
        }
    }

    @Test fun `owner record is pending only when truly absent and requires exact stable native proof`() {
        Host().use { host ->
            assertEquals("PENDING\n", host.probe())
            host.publish()
            assertEquals("WITNESS|200|${host.uid}|-1700000000123456|/dev/ttys000\n", host.probe())
            assertEquals("UNKNOWN\n", host.probe(birth = "-1700000001123456"))
            mode(host.record, "rw-r--r--")
            assertEquals("UNKNOWN\n", host.probe())
        }
    }

    @Test fun `record and directory replacement during native verification withdraw the witness`() {
        for (scenario in listOf("record", "directory")) Host().use { host ->
            host.publish()
            assertEquals(scenario, "UNKNOWN\n", host.probe(scenario))
            assertTrue(host.record.isFile)
        }
    }

    @Test fun `truncation extra lines oversized records and symlink records fail closed`() {
        for (value in listOf("OWNER|truncated", "x".repeat(4096), "wrong\nextra\n")) Host().use { host ->
            host.publish(value)
            assertEquals("UNKNOWN\n", host.probe())
        }
        Host().use { host ->
            host.publish()
            val old = File(host.recordDir, "old")
            assertTrue(host.record.renameTo(old))
            Files.createSymbolicLink(host.record.toPath(), old.toPath())
            try { assertEquals("UNKNOWN\n", host.probe()) }
            finally { Files.delete(host.record.toPath()) }
        }
    }

    @Test fun `owner base canonicalizes system style aliases but rejects unsafe permissions`() {
        Host().use { host ->
            host.publish()
            Files.createSymbolicLink(host.alias.toPath(), host.base.toPath())
            assertTrue(host.probe(tmp = host.alias).startsWith("WITNESS|"))
            mode(host.base, "rwxrwxrwx")
            assertEquals("UNKNOWN\n", host.probe(tmp = host.alias))
        }
    }

    @Test fun `default socket follows canonical TMUX_TMPDIR not owner TMPDIR and never creates parent`() {
        Host().use { host ->
            Files.createSymbolicLink(host.alias.toPath(), host.base.toPath())
            val body = "moke_default || exit 9; printf '%s\\n' \"\$moke_socket\""
            val result = host.execute(body, tmp = host.root)
            assertEquals(0, result.first)
            assertEquals("${host.base.path}/tmux-${host.uid}/default\n", result.second)
            assertFalse(File(host.base, "tmux-${host.uid}").exists())
            val unsafe = "moke_socket=${q(host.alias.path + "/tmux-${host.uid}/default")}; moke_select_socket; printf '%s\\n' \"\$?\""
            assertEquals("1\n", host.execute(unsafe).second)
        }
    }

    @Test fun `prior process disappearance requires ESRCH or different tagged birth not permissions`() {
        Host().use { host ->
            val server = TmuxServerId(200, 1700000000, "/private/tmp/tmux-${host.uid}/default", boot, -1700000000123456, 1, 2)
            val body = TmuxDarwin.previousServer(server) + "\nmoke_prior_gone; printf '%s\\n' \"\$?\""
            assertEquals("1\n", host.execute(body).second)
            assertEquals("0\n", host.execute(body, birth = "-1700000001123456").second)
            assertEquals("0\n", host.execute(body, scenario = "absent").second)
            assertEquals("2\n", host.execute(body, scenario = "permission").second)
            val wrongPlatform = TmuxDarwin.previousServer(server.copy(startedTicks = 1234)) +
                "\nmoke_prior_gone; printf '%s\\n' \"\$?\""
            assertEquals("2\n", host.execute(wrongPlatform).second)
        }
    }

    @Test fun `observer atomically publishes before payload preserves exit code and cleans only its record`() {
        Host().use { host ->
            val payload = "test -f ${q(host.record.path)} && printf 'PUBLISHED\\n'; exit 7"
            val result = host.execute(TmuxDarwin.observer, args = arrayOf(nonce, "command", payload))
            assertEquals(7, result.first)
            assertEquals("PUBLISHED\n", result.second)
            assertFalse(host.recordDir.exists())
        }
        Host().use { host ->
            val original = host.recordDir.path + ".original"
            val payload = "mv ${q(host.recordDir.path)} ${q(original)}; mkdir -m 700 ${q(host.recordDir.path)}; " +
                "printf 'replacement\\n' > ${q(host.record.path)}; exit 7"
            assertEquals(7, host.execute(TmuxDarwin.observer, args = arrayOf(nonce, "command", payload)).first)
            assertEquals("replacement\n", host.record.readText())
            assertTrue(File(original, "record").isFile)
        }
    }
}
