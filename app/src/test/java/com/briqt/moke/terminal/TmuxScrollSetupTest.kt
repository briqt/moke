package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

// type: executable scroll setup protocol regression tests
class TmuxScrollSetupTest {
    private val p = "__MOKE_TMUX_V1__|"
    private val forward = "#{||:#{pane_in_mode},#{mouse_any_flag}}"
    private val server = TmuxServerId(123, 1700000000, "/tmp/scroll/socket",
        "01234567-89ab-cdef-0123-456789abcdef", 45678, 2049, 555)
    private val target = TmuxSessionRef(server, "\$7", 1700000001, "not a target name")
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    // Golden list-keys -T root <one key> output from the historical N3 and N1 generators.
    // Legacy double-quoted shell arguments and the witnessed POSIX-quoted generator serialize
    // identically. Do not normalize nested strings: even one changed space is not these bytes.
    private fun old(key: String, count: Int): String {
        val arrow = if (key == "WheelUpPane") "Up" else "Down"
        val fallback = if (key == "WheelUpPane") "copy-mode -e; send -M" else "send -M"
        return "bind-key -T root $key if-shell -F \"$forward\" \"send -M\" " +
            "\"if -F '#{alternate_on}' 'send -N$count $arrow' '$fallback'\"\n"
    }
    private val safeUp = "bind-key -T root WheelUpPane if-shell -F \"$forward\" \"send -M\" \"copy-mode -e; send -M\"\n"
    private val safeDown = "bind-key -T root WheelDownPane send-keys -M\n"
    private val writeUp = "bind-key -T root WheelUpPane if-shell -F ${q(forward)} 'send -M' 'copy-mode -e; send -M'"
    private val writeDown = "bind-key -T root WheelDownPane send-keys -M"

    private data class Run(val result: TmuxOperationResult?, val commands: List<String>, val writes: List<String>)
    private fun scripted(
        up: String = old("WheelUpPane", 1), down: String = old("WheelDownPane", 1),
        rereadUp: String = up, afterUp: String = safeUp,
        version: String = "3.3a\n", fault: String = "", faultAt: String = "",
        failVerify: Int = 0, verifyCode: Int = 2,
    ): Run {
        val dir = Files.createTempDirectory("moke-scroll-script-").toFile()
        try {
            listOf("up1" to up, "up2" to rereadUp, "up3" to afterUp,
                "down1" to down, "down2" to down, "down3" to safeDown,
                "version" to version).forEach { (name, value) ->
                File(dir, name).writeText(value.replace("__INJECTED__", File(dir, "injected").path))
            }
            // This harness replaces only the external identity/tmux boundary, not comparisons,
            // captures, guards, status/ACK validation or sequencing in the generated script.
            val stub = """
                moke_verify_count=0
                moke_verify() {
                    moke_verify_count=${'$'}((moke_verify_count + 1))
                    [ "${'$'}moke_verify_count" -ne $failVerify ] || return $verifyCode
                }
                tmux() {
                    [ "${'$'}1" = -N ] && [ "${'$'}2" = -u ] && [ "${'$'}3" = -S ] && [ "${'$'}4" = ${q(server.socketPath)} ] || return 9
                    shift 4
                    [ "${'$'}1" = has-session ] && [ "${'$'}2" = -t ] && [ "${'$'}3" = '${'$'}7' ] && [ "${'$'}4" = ';' ] || return 9
                    shift 4
                    [ "${'$'}1" = if-shell ] && [ "${'$'}2" = -F ] && [ "${'$'}3" = -t ] && [ "${'$'}4" = '${'$'}7' ] || return 9
                    shift 5
                    command=${'$'}{1%" ; display-message -p '${p}SCROLL_ACK'"}
                    printf '%s\n' "${'$'}command" >> ${q(File(dir, "commands").path)}
                    if [ "${'$'}command" = ${q(faultAt)} ] && [ ${q(fault)} = stale ]; then
                        printf '${p}STALE\n'; return 0
                    fi
                    case "${'$'}command" in
                        "display-message -p '#{version}'") cat ${q(File(dir, "version").path)};;
                        ${q("set-option -t '\$7' mouse on")}) :;;
                        ${q("show-options -v -t '\$7' mouse")}) printf 'on\n';;
                        "list-keys -T root WheelUpPane"|"list-keys -T root WheelDownPane")
                            case "${'$'}command" in *WheelUpPane) key=up;; *) key=down;; esac
                            file=${q(dir.path)}/${'$'}key-count
                            n=0; if [ -f "${'$'}file" ]; then read -r n < "${'$'}file"; fi
                            n=${'$'}((n + 1)); printf '%s\n' "${'$'}n" > "${'$'}file"
                            row=${q(dir.path)}/${'$'}key${'$'}n
                            if [ "${'$'}(cat "${'$'}row")" = MISSING ]; then
                                case "${'$'}key" in up) key=WheelUpPane;; *) key=WheelDownPane;; esac
                                printf 'unknown key: %s\n' "${'$'}key"; return 1
                            fi
                            cat "${'$'}row";;
                        ${q(writeUp)}|${q(writeDown)}) printf '%s\n' "${'$'}command" >> ${q(File(dir, "writes").path)};;
                        *) printf 'unexpected command\n'; return 9;;
                    esac
                    if [ "${'$'}command" = ${q(faultAt)} ]; then
                        case ${q(fault)} in
                            no-ack) return 0;;
                            failed) return 1;;
                            extra) printf 'unexpected\n';;
                            oversized) printf '%05000d' 0;;
                        esac
                    fi
                    printf '${p}SCROLL_ACK\n'
                }
            """.trimIndent()
            val command = TmuxRemote.scrollSetup(target)
            // Strip only our outer POSIX quoting, not any remote output.
            val body = command.removePrefix("sh -c '").removeSuffix("' moke-tmux").replace("'\\''", "'")
            val script = File(dir, "run.sh")
            script.writeText(body.replace("unset TMUX TMUX_PANE", "unset TMUX TMUX_PANE\n$stub"))
            val process = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(true).start()
            assertTrue("script timeout", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            fun lines(name: String) = File(dir, name).takeIf { it.exists() }?.readLines().orEmpty()
            val commands = lines("commands")
            val writes = lines("writes")
            val permitted = setOf("display-message -p '#{version}'", "set-option -t '\$7' mouse on",
                "show-options -v -t '\$7' mouse", "list-keys -T root WheelUpPane",
                "list-keys -T root WheelDownPane", writeUp, writeDown)
            assertTrue(commands.toString(), commands.all { it in permitted })
            assertTrue(writes.all { it == writeUp || it == writeDown })
            assertFalse("Read-back data was executed", File(dir, "injected").exists())
            return Run(TmuxRemote.parseOperation(output), commands, writes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `canonical N1 and N3 outputs correct only the two root wheel commands`() {
        for (version in listOf("3.2a\n", "3.3a\n", "3.4\n")) for (count in listOf(1, 3)) {
            val run = scripted(old("WheelUpPane", count), old("WheelDownPane", count), version = version)
            assertEquals(run.commands.toString(), TmuxOperationCode.OK, run.result?.code)
            assertEquals(listOf(writeUp, writeDown), run.writes)
            assertEquals(3, run.commands.count { it == "list-keys -T root WheelUpPane" })
            assertEquals(3, run.commands.count { it == "list-keys -T root WheelDownPane" })
        }
    }

    @Test fun `missing and unmatched bindings are preserved individually`() {
        for (row in listOf("MISSING\n", "bind-key -T root WheelUpPane send-keys -M\n", safeUp)) {
            val run = scripted(up = row)
            assertEquals(run.commands.toString(), TmuxOperationCode.OK, run.result?.code)
            assertEquals(listOf(writeDown), run.writes)
        }
        assertTrue(scripted(up = "MISSING\n", down = "MISSING\n").writes.isEmpty())
    }

    @Test fun `never infer from keywords or rewrite nested whitespace quoting and flags`() {
        val golden = old("WheelUpPane", 1)
        val mutations = listOf(
            golden.replace("send -N1", "send  -N1"), golden.replace("-N1", "-N 1"),
            golden.replace("bind-key -T", "bind-key -r -T"), golden.replace("send -M", "send-keys -M"),
            golden.replace("copy-mode -e;", "copy-mode -e ;"),
            golden.replace("send -N1 Up", "run-shell 'touch injected'"),
            golden.replace("send -N1 Up", "\$(touch __INJECTED__) `touch __INJECTED__`"),
            "bind-key -T root WheelUpPane display-message \"alternate_on send -N1 Up\"\n",
        )
        for (row in mutations) {
            val run = scripted(up = row, down = safeDown)
            assertEquals(run.commands.toString(), TmuxOperationCode.OK, run.result?.code)
            assertTrue(row, run.writes.isEmpty())
        }
    }

    @Test fun `malformed one-key envelopes stop without correcting another key`() {
        val golden = old("WheelUpPane", 1)
        for (row in listOf(golden.replace("root", "prefix"), golden.replace("WheelUpPane", "WheelDownPane"),
            golden + golden, golden + "\n", golden.trimEnd(), "\n$golden", "junk\n$golden", "bad\n", "")) {
            val run = scripted(up = row)
            assertEquals(row, TmuxOperationCode.UNKNOWN, run.result?.code)
            assertTrue(run.writes.isEmpty())
            assertFalse(run.commands.contains("list-keys -T root WheelDownPane"))
        }
    }

    @Test fun `changed near-write output stops without writes replay or rollback`() {
        for (changed in listOf(old("WheelUpPane", 3), safeUp, "MISSING\n", "bad\n")) {
            val run = scripted(rereadUp = changed)
            assertEquals(TmuxOperationCode.UNKNOWN, run.result?.code)
            assertTrue(run.writes.isEmpty())
            assertFalse(run.commands.contains("list-keys -T root WheelDownPane"))
        }
    }

    @Test fun `malformed oversized and incomplete responses cannot acknowledge success`() {
        for (fault in listOf("no-ack", "failed", "extra", "oversized")) {
            val run = scripted(fault = fault, faultAt = "set-option -t '\$7' mouse on")
            assertEquals(fault, TmuxOperationCode.UNKNOWN, run.result?.code)
            assertTrue(run.writes.isEmpty())
        }
        val huge = scripted(up = "x".repeat(5000))
        assertEquals(TmuxOperationCode.UNKNOWN, huge.result?.code)
        assertTrue(huge.writes.isEmpty())
        for (version in listOf("", "\n", "3.3a\nextra\n", "3.3a", "3.5 \n", "3.5\r\n")) {
            val run = scripted(version = version)
            assertEquals(TmuxOperationCode.UNKNOWN, run.result?.code)
            assertEquals(listOf("display-message -p '#{version}'"), run.commands)
        }
    }

    @Test fun `unknown valid versions retain verified mouse setup but explicitly skip legacy correction`() {
        for (version in listOf("3.5\n", "3.6a\n", "next-3.7\n", "master\n")) {
            val run = scripted(version = version)
            assertEquals(TmuxOperationCode.OK, run.result?.code)
            assertEquals("SCROLL_LEGACY_SKIPPED_UNSUPPORTED_VERSION", run.result?.output)
            assertEquals(listOf("display-message -p '#{version}'", "set-option -t '\$7' mouse on",
                "show-options -v -t '\$7' mouse"), run.commands)
            assertTrue(run.writes.isEmpty())
        }
        for (fault in listOf("no-ack", "failed", "extra", "oversized")) {
            val run = scripted(version = "3.5\n", fault = fault, faultAt = "show-options -v -t '\$7' mouse")
            assertEquals(TmuxOperationCode.UNKNOWN, run.result?.code)
            assertTrue(run.writes.isEmpty())
            assertFalse(run.commands.any { it.startsWith("list-keys") })
        }
    }

    @Test fun `partial write or post-write mismatch is uncertain and never replayed`() {
        for (run in listOf(scripted(afterUp = "bad\n"), scripted(fault = "no-ack", faultAt = writeUp),
            scripted(fault = "failed", faultAt = writeUp))) {
            assertEquals(TmuxOperationCode.UNKNOWN, run.result?.code)
            assertEquals(listOf(writeUp), run.writes)
            assertFalse(run.commands.contains("list-keys -T root WheelDownPane"))
        }
    }

    /** Explicit opt-in: never connect to the developer's default tmux server or read their config. */
    @Test fun `owned local tmux confirms canonical commands isolation and real target guards`() {
        org.junit.Assume.assumeTrue(java.lang.Boolean.getBoolean("moke.test.localTmux"))
        val dir = Files.createTempDirectory("moke-scroll-local-").toFile()
        val socket = File(dir, "socket ' literal").path
        fun process(vararg args: String): Pair<Int, String> {
            val builder = ProcessBuilder(*args).redirectErrorStream(true)
            builder.environment().apply {
                remove("TMUX"); remove("TMUX_PANE")
                put("HOME", dir.path); put("HISTFILE", "/dev/null"); put("LESSHISTFILE", "/dev/null")
                put("BASH_ENV", "/dev/null"); put("ENV", "/dev/null"); put("LC_ALL", "C")
            }
            val process = builder.start()
            assertTrue("local tmux timeout", process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        }
        fun tmux(vararg args: String): String {
            val (rc, output) = process("tmux", "-N", "-u", "-S", socket, *args)
            assertEquals(output, 0, rc)
            return output
        }
        try {
            val (rc, output) = process("tmux", "-u", "-S", socket, "-f", "/dev/null",
                "new-session", "-d", "-s", "scroll", "exec sleep 60")
            assertEquals(output, 0, rc)
            tmux("new-session", "-d", "-s", "other", "exec sleep 60")
            val identity = tmux("display-message", "-p", "#{pid}|#{start_time}").trim().split('|')
            val pid = identity[0].toLong()
            val ticks = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
            val witnessed = TmuxServerId(pid, identity[1].toLong(), socket,
                File("/proc/sys/kernel/random/boot_id").readText().trim(), ticks,
                (Files.getAttribute(File(socket).toPath(), "unix:dev") as Number).toLong(),
                (Files.getAttribute(File(socket).toPath(), "unix:ino") as Number).toLong())
            val session = tmux("display-message", "-p", "-t", "\$0", "#{session_id}|#{session_created}").trim().split('|')
            val ref = TmuxSessionRef(witnessed, session[0], session[1].toLong(), "ignored")
            fun setup(frozen: TmuxSessionRef = ref): TmuxOperationResult? {
                val (status, result) = process("/bin/sh", "-c", TmuxRemote.scrollSetup(frozen))
                assertEquals(result, 0, status)
                return TmuxRemote.parseOperation(result)
            }
            tmux("bind-key", "-T", "root", "F12", "display-message", "keep root")
            tmux("bind-key", "-T", "prefix", "WheelUpPane", "display-message", "keep prefix")
            for (table in listOf("copy-mode", "copy-mode-vi")) {
                tmux("bind-key", "-T", table, "WheelUpPane", "select-pane ; send -X -N 1 scroll-up")
                tmux("bind-key", "-T", table, "WheelDownPane", "select-pane ; send -X -N 1 scroll-down")
            }
            val tables = listOf("prefix", "copy-mode", "copy-mode-vi")
            val before = tables.associateWith { tmux("list-keys", "-T", it) }
            val untouchedSession = tmux("show-options", "-t", "\$1", "mouse")
            val globalMouse = tmux("show-options", "-g", "mouse")
            for (count in listOf(1, 3)) {
                for ((key, arrow, fallback) in listOf(Triple("WheelUpPane", "Up", "copy-mode -e; send -M"),
                    Triple("WheelDownPane", "Down", "send -M"))) {
                    tmux("bind", "-n", key, "if", "-F", forward, "send -M",
                        "if -F '#{alternate_on}' 'send -N$count $arrow' '$fallback'")
                    assertEquals(old(key, count), tmux("list-keys", "-T", "root", key))
                }
                val otherRoot = tmux("list-keys", "-T", "root").lineSequence()
                    .filterNot { it.contains(" WheelUpPane ") || it.contains(" WheelDownPane ") }.toList()
                assertEquals(TmuxOperationCode.OK, setup()?.code)
                assertEquals(safeUp, tmux("list-keys", "-T", "root", "WheelUpPane"))
                assertEquals(safeDown, tmux("list-keys", "-T", "root", "WheelDownPane"))
                assertEquals(otherRoot, tmux("list-keys", "-T", "root").lineSequence()
                    .filterNot { it.contains(" WheelUpPane ") || it.contains(" WheelDownPane ") }.toList())
                for (table in tables) assertEquals(before[table], tmux("list-keys", "-T", table))
                assertEquals(untouchedSession, tmux("show-options", "-t", "\$1", "mouse"))
                assertEquals(globalMouse, tmux("show-options", "-g", "mouse"))
                assertEquals("on\n", tmux("show-options", "-v", "-t", "\$0", "mouse"))
            }
            tmux("unbind-key", "-T", "root", "WheelUpPane")
            tmux("bind-key", "-T", "root", "WheelDownPane", "display-message", "keep custom")
            val customDown = tmux("list-keys", "-T", "root", "WheelDownPane")
            assertEquals(TmuxOperationCode.OK, setup()?.code)
            assertEquals(1, process("tmux", "-N", "-S", socket, "list-keys", "-T", "root", "WheelUpPane").first)
            assertEquals(customDown, tmux("list-keys", "-T", "root", "WheelDownPane"))
            val allRoot = tmux("list-keys", "-T", "root")
            for (stale in listOf(ref.copy(created = ref.created + 1), ref.copy(id = "\$999999"),
                ref.copy(server = witnessed.copy(startedTicks = ticks + 1)),
                ref.copy(server = witnessed.copy(socketInode = witnessed.socketInode + 1)))) {
                assertEquals(stale.toString(), TmuxOperationCode.STALE, setup(stale)?.code)
                assertEquals(allRoot, tmux("list-keys", "-T", "root"))
            }
        } finally {
            process("tmux", "-N", "-S", socket, "kill-server")
            assertEquals("owned server must be unreachable after cleanup", 1,
                process("tmux", "-N", "-S", socket, "display-message", "-p", "#{pid}").first)
            assertTrue("remove only our private socket/directory", dir.deleteRecursively())
        }
    }

    @Test fun `frozen guard failures stop before writes including after candidate reread`() {
        for (at in listOf(1, 2, 3, 4, 5)) for (code in listOf(2, 3)) {
            val run = scripted(failVerify = at, verifyCode = code)
            assertEquals(if (code == 2) TmuxOperationCode.STALE else TmuxOperationCode.UNKNOWN, run.result?.code)
            assertTrue(run.writes.isEmpty())
        }
        val stale = scripted(fault = "stale", faultAt = writeUp)
        assertEquals(TmuxOperationCode.STALE, stale.result?.code)
        assertTrue(stale.writes.isEmpty())
    }
}
