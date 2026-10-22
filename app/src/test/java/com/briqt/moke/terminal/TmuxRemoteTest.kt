package com.briqt.moke.terminal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

// type: pure JVM remote protocol regression tests
class TmuxRemoteTest {
    private val p = "__MOKE_TMUX_V1__|"
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val socket = "/tmp/tmux-1000/default"
    private val server = TmuxServerId(123, 1700000000, socket, boot, 45678, 2049, 555)
    private val target = TmuxSessionRef(server, "\$7", 1700000001, "中文 | it's work")
    private val serverRow = "123|1700000000|45678|2049|555|$boot|$socket"
    private val sessionRow = "\$7|1700000001|2|1|中文 | it's work"
    private val scripts: TmuxScripts by lazy {
        val root = listOf(File("src/main/assets/tmux"), File("app/src/main/assets/tmux"))
            .first { it.isDirectory }
        TmuxScripts(File(root, "observer.sh").readText(), File(root, "probe.sh").readText())
    }

    private fun observation(vararg rows: String, id: String = "request_1") =
        p + "BEGIN|$id\n" + rows.joinToString("") { "$p$it\n" } + p + "END|$id|0\n"
    private fun ready() = observation(
        "PEER|$boot", "OWNER|$nonce|321|1000|44444|/dev/pts/8", "DEFAULT_SOCKET|$socket",
        "SERVER|$serverRow", "SESSION|$sessionRow", "CLIENT|456|1700000002|\$7|/dev/pts/8",
        "TERM|xterm-256color", "KIND|READY",
    )
    private fun operation(vararg rows: String) = p + "OP_BEGIN\n" +
        rows.joinToString("") { "$p$it\n" } + p + "OP_END|0\n"

    // Decode only our POSIX shell quoting, entirely in JVM. No script/process/tmux is run.
    private fun words(command: String): List<String> {
        val words = mutableListOf<String>()
        var word = StringBuilder()
        var quoted = false
        var inWord = false
        var index = 0
        while (index < command.length) {
            val c = command[index++]
            when {
                c == '\'' -> { quoted = !quoted; inWord = true }
                !quoted && c == '\\' -> { word.append(command[index++]); inWord = true }
                !quoted && c.isWhitespace() -> if (inWord) {
                    words += word.toString(); word = StringBuilder(); inWord = false
                }
                else -> { word.append(c); inWord = true }
            }
        }
        assertFalse("Unterminated shell quote", quoted)
        if (inWord) words += word.toString()
        return words
    }
    private fun body(command: String): String {
        val words = words(command)
        assertEquals(listOf("sh", "-c"), words.take(2))
        assertEquals("moke-tmux", words[3])
        return words[2]
    }
    private inline fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `real assets are carried intact with argv and original payload`() {
        val command = "printf '%s\\n' \"\$TERM\"; exec weird 'x|y' \"a'b\"\n# payload"
        val argv = words(TmuxRemote.launch(scripts, nonce, command))
        assertEquals(scripts.observer, argv[2])
        assertEquals(listOf(nonce, "command", command), argv.drop(4))
        assertEquals(listOf(nonce, "shell", ""), words(TmuxRemote.launch(scripts, nonce, null)).drop(4))
        assertEquals(listOf(nonce, "command", ""), words(TmuxRemote.launch(scripts, nonce, "")).drop(4))
    }

    @Test fun `default shell keeps strict login argv0 and original BASH_ENV`() {
        val observer = scripts.observer
        assertTrue(observer.contains("BASH_ENV=/dev/null /bin/bash --noprofile --norc -c"))
        assertTrue(observer.contains("exec -a \"\$1\" \"\$2\""))
        assertTrue(observer.contains("\"-\$shell_name\" \"\$shell_path\" \"\${BASH_ENV+x}\" \"\${BASH_ENV-}\""))
        assertTrue(observer.contains("export BASH_ENV=\"\$4\"; else unset BASH_ENV"))
        assertTrue(observer.contains("command) sh -c \"\$payload\""))
        assertTrue(observer.contains("trap cleanup 0"))
        assertFalse(observer.contains("exec sh -c"))
        assertFalse(observer.contains("export TERM="))
    }

    @Test fun `assets preserve live owner birth nonce and exact kernel tty checks`() {
        val probe = scripts.probe
        listOf("/proc/\$pid/stat", "\${proc_stat##*) }", "\${20}", "grep -Fzqx -- \"\$nonce\"",
            "/proc/\$pid/fd/0", "\$expected_tty", "actual_tty_nr", "4294967296", "1048320",
            "stat -c %t", "stat -c %T", "stat -c '%d:%i'", "expected_boot", "second=\$(proc_fields)")
            .forEach { assertTrue(it, probe.contains(it)) }
        assertFalse(probe.contains("stat -c %r"))
        assertTrue(probe.contains("[ ! -e \"\$record_dir\" ] && [ ! -L \"\$record_dir\" ]"))
        assertTrue(probe.contains("printf 'PENDING"))
        assertTrue(probe.contains("printf 'UNKNOWN"))
        assertTrue(probe.contains("\"\$uid 700\""))
        assertTrue(probe.contains("\"\$uid 600\""))
    }

    @Test fun `assets use safe parents atomic publication and exact cleanup only`() {
        for (asset in listOf(scripts.observer, scripts.probe)) {
            assertTrue(asset.contains("readlink -f -- \"\$base\""))
            assertTrue(asset.contains("[ ! -L \"\$record_dir\" ]"))
            assertTrue(asset.contains("/proc/sys/kernel/random/boot_id"))
            assertFalse(asset.contains("rm -r"))
            assertFalse(asset.contains("pkill"))
            assertFalse(asset.contains("kill-server"))
            assertFalse(asset.contains("set-hook"))
        }
        assertTrue(scripts.observer.contains("mkdir -m 700 -- \"\$record_dir\""))
        assertTrue(scripts.observer.contains("record.new\" \"\$record_dir/record\""))
        assertTrue(scripts.observer.contains("\"\$record_identity\""))
        assertTrue(scripts.observer.contains("\"\$current_value\" = \"\$record_value\""))
        assertTrue(scripts.observer.contains("rmdir -- \"\$record_dir\""))
    }

    @Test fun `capability command is read only and validates helpers and Linux strong fields`() {
        val command = body(TmuxRemote.capabilityCommand())
        assertTrue(command.contains("CAPABILITY|SUPPORTED"))
        assertTrue(command.contains("CAPABILITY|UNSUPPORTED"))
        assertTrue(command.contains("BASH_ENV=/dev/null /bin/bash --noprofile --norc"))
        assertTrue(command.contains("exec -a moke-capability"))
        assertTrue(command.contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(command.contains("grep -Fzqx"))
        // Directory creation exists only in an uncalled core function, never the capability path.
        val path = command.substringAfter("for moke_tool")
        assertFalse(path.contains("mkdir -m"))
        assertFalse(path.contains("new-session"))
        assertFalse(path.contains("tmux -N"))
    }

    @Test fun `strict ready observation keeps full identity Unicode quotes pipes and exact client tty`() {
        val actual = TmuxRemote.parseObservation(ready(), "request_1")!!
        assertEquals(TmuxProbeKind.READY, actual.kind)
        assertEquals(server, actual.server)
        assertEquals(boot, actual.peerBootId)
        assertEquals(socket, actual.defaultSocketPath)
        assertTrue(actual.server!!.witnessed)
        assertEquals(target.name, actual.sessions.single().name)
        assertEquals(target, actual.sessions.single().ref(server))
        assertEquals(TmuxOwnerProof(nonce, 321, 1000, 44444, "/dev/pts/8"), actual.owner)
        assertEquals(TmuxClientId(456, 1700000002, "/dev/pts/8"), actual.clients.single().identity)
    }

    @Test fun `complete frame can follow banner but banner alone cannot imply empty sessions`() {
        assertNotNull(TmuxRemote.parseObservation("Welcome to host\n" + ready() + "logout\n", "request_1"))
        listOf("", "Welcome\n", "no server running\n", "Permission denied\n", "KIND|READY\n", "tmux: command not found\n")
            .forEach { assertNull(it, TmuxRemote.parseObservation(it, "request_1")) }
    }

    @Test fun `wrong request duplicate frames truncation command failure and garbage are rejected`() {
        val good = ready()
        val invalid = listOf(
            good + good, good.replace("END|request_1|0", "END|request_1|1"),
            good.substringBefore(p + "END"), good.trimEnd('\n'),
            good.replace("KIND|READY", "KIND|READY\nnot-a-record"),
            good.replace("KIND|READY", "KIND|READY\n${p}KIND|READY"),
            good.replace("KIND|READY", "KIND|BROKEN"),
            good.replace("KIND|READY", "STRANGER|42\n${p}KIND|READY"),
            good.replace("KIND|READY", "KIND|READY\n${p}TERM|xterm"),
        )
        invalid.forEach { assertNull(it, TmuxRemote.parseObservation(it, "request_1")) }
        assertNull(TmuxRemote.parseObservation(good, "another-request"))
        assertNull(TmuxRemote.parseObservation(good, "bad\nrequest"))
    }

    @Test fun `control characters invalid numeric identity and weak witnesses are rejected`() {
        val good = ready()
        val invalid = listOf(
            good.replace("中文", "中\t文"), good.replace("中文", "中\u001b文"), good.replace("中文", "中\u007f文"),
            good.replace("中文", "中\u0085文"), good.replace("|45678|", "|0|"), good.replace("|555|", "|0|"),
            good.replace("|2049|", "|-1|"), good.replace("|45678|", "|9223372036854775808|"),
            good.replace("SERVER|123|", "SERVER|1|"), good.replace("SERVER|123|", "SERVER|abc|"),
            good.replace("SESSION|\$7|", "SESSION|7|"), good.replace("|2|1|中文", "|0|1|中文"),
            good.replace("|2|1|中文", "|2|-1|中文"), good.replace("|2|1|中文", "|2147483648|1|中文"),
            good.replace("|1700000001|", "|0|"), good.replace("OWNER|$nonce", "OWNER|bad"),
            good.replace("|1000|44444|", "|-1|44444|"), good.replace("CLIENT|456|", "CLIENT|0|"),
            good.replace("/dev/pts/8", "/dev/pts/8evil"), good.replace("|/tmp/tmux", "|tmp/tmux"),
            good.replace("TERM|xterm-256color", "TERM|xterm;exec bad"),
            good.replace("PEER|$boot", "PEER|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            good.replace(boot, "not-a-boot-id"),
        )
        invalid.forEach { assertNull(it, TmuxRemote.parseObservation(it, "request_1")) }
        assertNotNull(TmuxRemote.parseObservation(good.replace("\n", "\r\n"), "request_1"))
    }

    @Test fun `cross session clients duplicate records and ready empty lists are rejected`() {
        val good = ready()
        listOf(
            good.replace("|\$7|/dev/pts/8", "|\$8|/dev/pts/8"),
            good.replace("${p}SESSION|$sessionRow\n", ""),
            good.replace("SESSION|$sessionRow", "SESSION|$sessionRow\n${p}SESSION|$sessionRow"),
            good.replace("CLIENT|456|1700000002|\$7|/dev/pts/8", "CLIENT|456|1700000002|\$7|/dev/pts/8\n${p}CLIENT|456|1700000002|\$7|/dev/pts/8"),
            good.replace("PEER|$boot", "PEER|$boot\n${p}PEER|$boot"),
            good.replace("DEFAULT_SOCKET|$socket", "DEFAULT_SOCKET|/elsewhere"),
        ).forEach { assertNull(it, TmuxRemote.parseObservation(it, "request_1")) }
    }

    @Test fun `missing server is distinct and requires controlled peer and default socket`() {
        val good = observation("PEER|$boot", "DEFAULT_SOCKET|$socket", "KIND|NO_SERVER")
        val actual = TmuxRemote.parseObservation(good, "request_1")!!
        assertEquals(TmuxProbeKind.NO_SERVER, actual.kind)
        assertEquals(socket, actual.defaultSocketPath)
        assertNull(actual.server)
        assertTrue(actual.sessions.isEmpty())
        assertNull(TmuxRemote.parseObservation(observation("KIND|NO_SERVER"), "request_1"))
        assertNull(TmuxRemote.parseObservation(good.replace("KIND|NO_SERVER", "SERVER|$serverRow\n${p}KIND|NO_SERVER"), "request_1"))
        for (kind in listOf("PENDING", "UNKNOWN", "ERROR", "NOT_INSTALLED")) {
            assertEquals(kind, TmuxRemote.parseObservation(observation("PEER|$boot", "KIND|$kind"), "request_1")!!.kind.name)
        }
        assertNull(TmuxRemote.parseObservation(observation("OWNER|$nonce|321|1000|44|/dev/pts/8", "KIND|PENDING"), "request_1"))
    }

    @Test fun `ownerless SSH observations have no fabricated current owner`() {
        val output = ready().lineSequence().filterNot { it.startsWith(p + "OWNER|") }.joinToString("\n")
        assertNull(TmuxRemote.parseObservation(output, "request_1")!!.owner)
    }

    @Test fun `observation carries actual probe and checks owner before touching tmux`() {
        val command = body(TmuxRemote.observe(scripts, "r1", nonce, true))
        assertTrue(command.contains(qForTest(scripts.probe)))
        val witness = command.indexOf("moke_witness=\$(sh -c")
        val listing = command.indexOf("moke_sessions=\$(tmux")
        assertTrue(witness in 1 until listing)
        val path = command.substring(command.indexOf("moke_witness=\$(sh -c"))
        assertTrue(path.indexOf("moke_finish PENDING") < path.indexOf("moke_default"))
        assertTrue(path.indexOf("moke_finish UNKNOWN") < path.indexOf("moke_default"))
        assertTrue(path.contains("unset TMUX TMUX_PANE"))
        assertTrue(path.contains("DEFAULT_SOCKET|%s"))
        assertTrue(path.contains("moke_server && [ \"\$moke_before\" = \"\$moke_server_row\" ] || moke_finish ERROR"))
        assertTrue(path.contains("moke_finish READY"))
        assertFalse(path.contains("|| true"))
        // Reject control-bearing tmux names before serialization, preventing forged newline records.
        assertTrue(path.contains("#{m/r:[[:cntrl:]],#{session_name}}"))
        assertTrue(path.contains("${p}INVALID"))
    }

    @Test fun `default discovery follows upstream TMUX_TMPDIR and never parses inherited TMUX`() {
        val command = body(TmuxRemote.observe(scripts, "r1", null, false))
        assertTrue(command.contains("readlink -f -- \"\$TMUX_TMPDIR\""))
        assertTrue(command.contains("moke_socket=\"\$moke_dir/default\""))
        assertTrue(command.contains("[ \"\$((0\$moke_mode & 7))\" -eq 0 ]"))
        assertFalse(command.contains("\${TMUX,"))
        assertFalse(command.contains("\${TMUX%%"))
        assertFalse(command.contains("tmux -N -u display-message"))
        assertFalse(command.contains("tmux -u list-sessions"))
        val path = command.substringAfter("printf '${p}BEGIN|r1")
        assertFalse(path.contains("mkdir"))
    }

    @Test fun `known socket observation restore and create never rediscover a changed default`() {
        val known = "/tmp/known private/tmux-1000/default"
        val observe = body(TmuxRemote.observe(scripts, "r1", nonce, true, socketPath = known))
            .substringAfter("unset TMUX TMUX_PANE")
        val restore = body(TmuxRemote.restore("\$7 | literal", socketPath = known))
            .substringAfter("unset TMUX TMUX_PANE")
        val create = body(TmuxRemote.create("literal", socketPath = known))
            .substringAfter("unset TMUX TMUX_PANE")
        for (path in listOf(observe, restore, create)) {
            assertTrue(path.contains("moke_socket='$known'"))
            assertFalse(path.contains("moke_default"))
            assertFalse(path.contains("TMUX_TMPDIR"))
            assertTrue(path.contains("moke_server"))
        }
        assertTrue(observe.contains("readlink -f -- \"\$moke_known_parent\""))
        assertTrue(observe.contains("moke_safe_base \"\$moke_known_parent\""))
        assertTrue(create.contains("moke_create_parent"))
        invalid { TmuxRemote.observe(scripts, "r1", null, false, "relative") }
        invalid { TmuxRemote.restore("ok", socketPath = "/bad\npath") }
    }

    @Test fun `null prior preserves first discovery and genuine initial no server behavior`() {
        assertEquals(
            TmuxRemote.observe(scripts, "r1", null, false),
            TmuxRemote.observe(scripts, "r1", null, false, previousServer = null),
        )
        val command = body(TmuxRemote.observe(scripts, "r1", null, false))
        assertFalse(command.contains("moke_prior_gone"))
        assertTrue(command.contains("2) moke_finish NO_SERVER"))
    }

    @Test fun `known binding pins prior socket and gates absent or replaced control server before listing`() {
        val command = body(TmuxRemote.observe(scripts, "r1", nonce, true, previousServer = server))
        assertTrue(command.contains("moke_prior_row='$serverRow'"))
        val path = command.substringAfter("unset TMUX TMUX_PANE")
        assertTrue(path.contains("moke_socket='$socket'"))
        assertFalse(path.contains("moke_default"))
        assertTrue(path.contains("0) [ \"\$moke_server_row\" = \"\$moke_prior_row\" ] || { moke_prior_gone || moke_finish UNKNOWN; }"))
        assertTrue(path.contains("2) moke_prior_gone || moke_finish UNKNOWN; moke_finish NO_SERVER"))
        assertTrue(path.indexOf("moke_prior_gone") < path.indexOf("moke_sessions=\$(tmux"))
        assertTrue(command.indexOf("moke_witness=\$(sh -c") < command.indexOf("moke_prior_gone()"))
        val unknown = TmuxRemote.parseObservation(observation("PEER|$boot", "OWNER|$nonce|321|1000|44444|/dev/pts/8", "DEFAULT_SOCKET|$socket", "KIND|UNKNOWN"), "request_1")!!
        assertEquals(TmuxProbeKind.UNKNOWN, unknown.kind)
        assertNull(unknown.server)
        assertTrue(unknown.sessions.isEmpty())
    }

    @Test fun `prior birth proof distinguishes live reused and zombie state without socket existence guessing`() {
        val command = body(TmuxRemote.observe(scripts, "r1", null, false, previousServer = server))
        val proof = command.substringAfter("moke_prior_gone() {").substringBefore("command -v tmux")
        assertTrue(proof.contains("moke_boot && [ \"\$moke_boot_id\" = '$boot' ] || return 2"))
        assertTrue(proof.contains("read -r moke_prior_stat < /proc/123/stat"))
        assertTrue(proof.contains("[ \"\${20}\" = '45678' ] || return 0"))
        assertTrue(proof.contains("Z|X|x) return 0"))
        assertTrue(proof.contains("R|S|D|T|t|W|I|P) return 1"))
        assertTrue(proof.contains("*) return 2"))
        assertTrue(proof.contains("[ \"\$moke_prior_uid\" = \"\$(id -u)\" ] || return 2"))
        assertFalse(proof.contains("kill "))
        assertFalse(proof.contains("/proc/123/fd"))
        // A socket rename says nothing about process death: no test of the old socket here.
        assertFalse(proof.contains("\$moke_socket"))
    }

    @Test fun `absent prior PID requires known ENOENT plus visible Linux procfs and permissions fail closed`() {
        val command = body(TmuxRemote.observe(scripts, "r1", null, false, socket, server))
        assertTrue(command.contains("stat -f -c %T -- /proc"))
        assertTrue(command.contains("done < /proc/mounts"))
        assertTrue(command.contains("hidepid=0) :;; hidepid=*) return 1"))
        assertTrue(command.contains("stat: cannot stat '/proc/123': No such file or directory"))
        assertTrue(command.contains("stat: cannot statx '/proc/123': No such file or directory"))
        assertTrue(command.contains("moke_prior_proc_visible 2>/dev/null || return 2"))
        assertTrue(command.contains("moke_uint \"\$moke_prior_uid\" || return 2"))
        assertFalse(command.contains("[ ! -d /proc/123 ]"))
        assertFalse(command.contains("Permission denied\") return 0"))
    }

    @Test fun `prior hint must be a complete strong identity on the same selected socket`() {
        for (bad in listOf(
            server.copy(pid = 1), server.copy(started = 0), server.copy(startedTicks = 0),
            server.copy(socketInode = 0), server.copy(socketDevice = -1), server.copy(bootId = ""),
            server.copy(bootId = "not-a-boot"), server.copy(socketPath = "relative"), server.copy(socketPath = "/bad\npath"),
        )) invalid { TmuxRemote.observe(scripts, "r1", null, false, previousServer = bad) }
        invalid { TmuxRemote.observe(scripts, "r1", null, false, "/another/server", server) }
    }

    @Test fun `C diagnostics do not leak locale into new server foreground attach or nested operations`() {
        for (command in listOf(TmuxRemote.create("literal"), TmuxRemote.open(target), TmuxRemote.restore("literal"))) {
            val body = body(command)
            assertTrue(body.contains("moke_saved_lc_set=\${LC_ALL+x}"))
            assertTrue(body.contains("export LC_ALL=C"))
            assertTrue(body.contains("moke_user_locale tmux"))
            assertTrue(body.contains("else unset LC_ALL"))
        }
        val peer = body(TmuxRemote.guardPeer(scripts, null, false, boot, "NESTED_OPERATION"))
        assertTrue(peer.endsWith("moke_restore_locale\nNESTED_OPERATION"))
        val open = body(TmuxRemote.open(target))
        val failure = open.substringAfter("moke_attach_failure() {").substringBefore("moke_socket=")
        assertTrue(failure.indexOf("moke_restore_locale") < failure.indexOf("moke_login_shell="))
        assertTrue(failure.contains("exec \${SHELL:-/bin/sh} -l"))
    }

    @Test fun `known absence is narrow C locale diagnostic not permissions or generic failure`() {
        val command = body(TmuxRemote.observe(scripts, "r1", nonce, true))
        assertTrue(command.contains("export LC_ALL=C"))
        assertTrue(command.contains("(No such file or directory)"))
        assertTrue(command.contains("(Connection refused)"))
        assertFalse(command.contains("Permission denied)\"|"))
        assertTrue(command.contains("*) return 1;;"))
        assertTrue(command.contains("moke_socket_before"))
        assertTrue(command.contains("[ ! -L \"\$moke_socket\" ]"))
    }

    @Test fun `operations require explicit complete result and created reference`() {
        for (code in TmuxOperationCode.entries) {
            assertEquals(code, TmuxRemote.parseOperation(operation("RESULT|${code.name}"))!!.code)
        }
        val result = TmuxRemote.parseOperation(operation("SERVER|$serverRow", "CREATED|$sessionRow", "RESULT|OK"))!!
        assertTrue(result.ok)
        assertEquals(target, result.created)
        assertNull(TmuxRemote.parseOperation(""))
        assertNull(TmuxRemote.parseOperation("OK\n"))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|OK").trimEnd('\n')))
        assertNull(TmuxRemote.parseOperation(operation("SERVER|$serverRow", "RESULT|OK")))
        assertNull(TmuxRemote.parseOperation(operation("CREATED|$sessionRow", "RESULT|OK")))
        assertNull(TmuxRemote.parseOperation(operation("SERVER|$serverRow", "CREATED|$sessionRow", "RESULT|FAILED")))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|OK", "RESULT|OK")))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|OK") + operation("RESULT|OK")))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|OK").replace("OP_END|0", "OP_END|1")))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|bad")))
        assertNull(TmuxRemote.parseOperation(operation("RESULT|OK", "DETAIL|bad\u001b")))
        assertEquals("a|b\n中文", TmuxRemote.parseOperation(operation("DETAIL|a|b", "DETAIL|中文", "RESULT|FAILED"))!!.output)
    }

    @Test fun `strict create uses supplied socket no attach no implicit reuse and returns created identity`() {
        val command = body(TmuxRemote.create("中文 | it's ; \$(touch bad)", "/custom/private/socket"))
        assertTrue(command.contains("moke_socket='/custom/private/socket'"))
        assertTrue(command.contains("new-session -d -P -F"))
        assertTrue(command.contains("CREATED|#{session_id}|#{session_created}|#{session_windows}|#{session_attached}|#{session_name}"))
        assertTrue(command.contains("moke_result EXISTS"))
        assertTrue(command.contains("RESULT|OK"))
        assertTrue(command.contains("moke_create_parent"))
        assertTrue(command.contains("moke_create_guard"))
        assertTrue(command.contains("tmux -N -u -S"))
        assertTrue(command.contains("mkdir -m 700 --"))
        assertFalse(command.contains("mkdir -p")) // explanatory comment uses no such phrase
        assertFalse(command.contains(" -A"))
        assertFalse(command.contains("attach-session"))
        assertFalse(command.contains("kill-session"))
        assertEquals("中文 | it's ; \$(touch bad)", words(TmuxRemote.create("中文 | it's ; \$(touch bad)")).last())
    }

    @Test fun `frozen operations guard peer birth inode and queue session identity never names`() {
        for (command in listOf(TmuxRemote.rename(target, "renamed | it's"), TmuxRemote.detach(target), TmuxRemote.kill(target), TmuxRemote.open(target))) {
            val body = body(command)
            listOf("moke_expected_boot='$boot'", "moke_expected_ticks=45678", "moke_expected_dev=2049", "moke_expected_ino=555",
                "moke_verify", "stat -c %d", "stat -c %i", "tmux -N -u -S", "if-shell -F -t", "#{session_created},1700000001", "#{pid},123", "#{start_time},1700000000", "#{session_id},\$7")
                .forEach { assertTrue(it, body.contains(it)) }
            assertFalse(body.contains(target.name))
            assertFalse(body.contains("kill-server"))
            assertFalse(body.contains("pkill"))
            assertFalse(body.contains("if-shell -b"))
            assertFalse(body.contains("new-session"))
        }
        assertTrue(body(TmuxRemote.kill(target)).contains("kill-session -t"))
        assertTrue(body(TmuxRemote.detach(target)).contains("detach-client -s"))
    }

    @Test fun `opt in scroll setup retains frozen target and checks complete bounded ACKs`() {
        val command = body(TmuxRemote.scrollSetup(target))
        assertTrue(command.contains("moke_expected_boot='$boot'"))
        assertTrue(command.contains("moke_expected_ticks=45678"))
        assertTrue(command.contains("unset TMUX TMUX_PANE"))
        assertTrue(command.contains("moke_expected_dev=2049"))
        assertTrue(command.contains("moke_expected_ino=555"))
        assertTrue(command.contains("moke_verify"))
        assertTrue(command.contains("tmux -N -u -S \"\$moke_socket\" has-session -t"))
        assertTrue(command.contains("if-shell -F -t"))
        assertTrue(command.contains("#{session_created},1700000001"))
        assertTrue(command.contains("moke_result STALE"))
        assertTrue(command.contains("moke_result OK"))
        assertTrue(command.contains("moke_result UNKNOWN 'scroll setup not confirmed'"))
        assertFalse(command.contains(target.name))
        assertFalse(command.contains("set-hook"))
        assertFalse(command.contains(" -g "))
        assertFalse(command.contains("|| true"))
        assertTrue(command.contains("head -c 4097"))
        assertTrue(command.contains("SCROLL_ACK"))
        assertTrue(command.contains("SCROLL_EXIT|0!"))
        invalid { TmuxRemote.scrollSetup(target.copy(server = server.copy(startedTicks = 0))) }
        val peer = body(TmuxRemote.guardPeer(scripts, nonce, true, boot, TmuxRemote.scrollSetup(target)))
        assertTrue(peer.indexOf("moke_witness=\$(sh -c") < peer.indexOf(TmuxRemote.scrollSetup(target)))
    }

    @Test fun `scroll setup neither installs copy policy nor queries unsupported key formats`() {
        val command = body(TmuxRemote.scrollSetup(target))
        for (forbidden in listOf("bind -n", "bind -T copy", "bind-key -T copy", "unbind", "kill-server",
            "source-file", "key_command", "list-keys -F", "eval ", "sed ", "tr ")) {
            assertFalse(forbidden, command.contains(forbidden))
        }
        assertTrue(command.contains("list-keys -T root WheelUpPane"))
        assertTrue(command.contains("list-keys -T root WheelDownPane"))
        assertTrue(command.contains("moke_scroll_before=\$moke_scroll_out"))
        assertTrue(command.contains("[ \"\$moke_scroll_out\" = \"\$moke_scroll_before\" ]"))
    }

    @Test fun `pane cwd is guarded by server session and exact actual client context`() {
        val client = TmuxClientId(456, 1700000002, "/dev/pts/8")
        val command = body(TmuxRemote.paneCwd(target, client))
        assertTrue(command.contains("moke_expected_boot='$boot'"))
        assertTrue(command.contains("moke_expected_ticks=45678"))
        assertTrue(command.contains("unset TMUX TMUX_PANE"))
        assertTrue(command.contains("moke_expected_dev=2049"))
        assertTrue(command.contains("moke_expected_ino=555"))
        assertTrue(command.contains("tmux -N -u -S \"\$moke_socket\" if-shell -F -t"))
        val invocation = words(command.lineSequence().single { it.contains("moke_out=\$(tmux") })
        val read = words(invocation[invocation.indexOf("if-shell") + 5])
        assertEquals(listOf("list-clients", "-F"), read.take(2))
        val format = read[2]
        for (field in listOf("#{client_pid},456", "#{client_created},1700000002", "#{client_tty},/dev/pts/8", "#{session_id},\$7", "#{session_created},1700000001", "#{pid},123", "#{start_time},1700000000")) {
            assertTrue(field, format.contains(field))
        }
        assertTrue(format.contains("#{m/r:[[:cntrl:]],#{pane_current_path}}"))
        assertTrue(format.contains("#{m/r:^/,#{pane_current_path}}"))
        assertTrue(format.contains("${p}PATH|#{pane_current_path}"))
        assertEquals(2, Regex("moke_verify ").findAll(command).count())
        assertFalse(command.contains("display-message -p -c"))
        assertFalse(command.contains("kill-session"))
        assertFalse(command.contains("new-session"))
        assertFalse(command.contains(target.name))
        invalid { TmuxRemote.paneCwd(target, client.copy(tty = "/dev/pts/8;bad")) }
        invalid { TmuxRemote.paneCwd(target, client.copy(pid = 0)) }
        invalid { TmuxRemote.paneCwd(target.copy(server = server.copy(socketInode = 0)), client) }
    }

    @Test fun `pane cwd parser requires one complete unambiguous absolute path frame`() {
        fun cwd(vararg rows: String) = p + "CWD_BEGIN\n" + rows.joinToString("") { "$p$it\n" } + p + "CWD_END|0\n"
        val path = "/home/中文 | it's #work/ folder"
        val good = cwd("SKIP", "PATH|$path", "SKIP")
        assertEquals(path, TmuxRemote.parsePaneCwd(good))
        assertEquals(path, TmuxRemote.parsePaneCwd("banner\n" + good))
        for (bad in listOf(
            "", "just a path\n", cwd("SKIP"), cwd("UNAVAILABLE"), cwd("INVALID"),
            cwd("PATH|"), cwd("PATH|relative/path"), cwd("PATH|$path", "PATH|$path"),
            cwd("PATH|/bad\tpath"), cwd("PATH|/bad\u001bpath"), cwd("PATH|/bad\npath"),
            cwd("PATH|$path", "STRANGER|x"), good + good, good.trimEnd('\n'),
            good.substringBefore(p + "CWD_END"), good.replace("CWD_END|0", "CWD_END|1"),
        )) assertNull(bad, TmuxRemote.parsePaneCwd(bad))
    }

    @Test fun `open and restore are attach only and negotiate TERM inline with safe failure feedback`() {
        val open = body(TmuxRemote.open(target, true, "screen-256color"))
        val restore = body(TmuxRemote.restore("中文 | it's ; work"))
        for (body in listOf(open, restore)) {
            assertTrue(body.contains("tput clear"))
            assertTrue(body.contains("export TERM"))
            assertTrue(body.contains("attach-session"))
            assertTrue(body.contains("ordinary shell"))
            assertTrue(body.contains("exec \${SHELL:-/bin/sh} -l"))
            assertTrue(body.contains("moke_attach_failure"))
            assertTrue(body.contains("moke_rc=0"))
            assertTrue(body.contains("|| moke_rc=\$?"))
            assertFalse(body.contains("no longer exists"))
            assertFalse(body.contains("new-session"))
            // The helper function is inert; no frontend error path calls the control-frame printer.
            assertFalse(body.substringAfter("moke_verify()", body).substringAfter("}\n").contains("moke_result "))
        }
        assertTrue(open.contains("for t in screen-256color xterm-256color xterm vt100"))
        assertTrue(open.contains("attach-session -d -t"))
        assertTrue(restore.contains("[ \"\$moke_name\" = \"\$1\" ]"))
        assertTrue(restore.contains("moke_name=\${moke_rest#*|}"))
        assertFalse(restore.contains("-t \"=\$1\""))
        assertTrue(restore.contains("if-shell -F -t \"\$moke_id\""))
        assertFalse(body(TmuxRemote.open(target, term = "x;bad-command")).contains("bad-command"))
        assertEquals("中文 | it's ; work", words(TmuxRemote.restore("中文 | it's ; work")).last())
    }

    @Test fun `missing restore and unknown server retain the supervised source via ordinary login shell`() {
        val restore = body(TmuxRemote.restore("missing | 中文", socketPath = socket))
        assertTrue(restore.contains("moke_server || moke_attach_failure"))
        assertTrue(restore.contains("2>/dev/null) || moke_attach_failure"))
        assertTrue(restore.contains("[ \"\$moke_invalid_rows\" -eq 0 ] && [ -n \"\$moke_id\" ] || moke_attach_failure"))
        assertTrue(restore.contains("moke_verify || moke_attach_failure"))
        assertFalse(Regex("(?m)^\\s*exit 1[;\\s]*$").containsMatchIn(restore))
        assertFalse(restore.contains("session no longer exists"))
        assertFalse(restore.contains("new-session"))
    }

    @Test fun `missing open and stale socket fall back while normal detach still ends carrier`() {
        for (ref in listOf(target, target.copy(server = server.copy(socketPath = "/tmp/stale/socket")))) {
            val open = body(TmuxRemote.open(ref))
            assertTrue(open.contains("moke_verify || moke_attach_failure"))
            assertTrue(open.contains("if [ \"\$moke_rc\" -eq 0 ]; then exit 0; fi\nmoke_attach_failure"))
            val invocation = words(open.lineSequence().single { it.contains("moke_user_locale tmux") })
            val reject = invocation[invocation.indexOf("if-shell") + 6]
            assertEquals(listOf("run-shell", "exit 1"), words(reject))
            assertFalse(reject.contains("-b"))
            assertFalse(open.contains("no longer exists"))
        }
        val restore = body(TmuxRemote.restore("missing", socketPath = socket))
        assertTrue(restore.contains("[ \"\$moke_rc\" -eq 0 ] && exit 0\nmoke_attach_failure"))
        val invocation = restore.lineSequence().single { it.contains("moke_user_locale tmux") }
        val rejected = invocation.substringAfter("\"attach-session -t '\$moke_id'\" ").substringBefore(" || moke_rc=")
        assertEquals(listOf("run-shell", "exit 1"), words(words(rejected).single()))
    }

    @Test fun `malformed restore rows never start fallback inside redirected list stdin`() {
        val restore = body(TmuxRemote.restore("missing", socketPath = socket))
        val loop = restore.substringAfter("while IFS= read -r moke_row; do").substringBefore("done <<MOKE_RESTORE_ROWS")
        assertFalse(loop.contains("moke_attach_failure"))
        assertTrue(loop.contains("moke_invalid_rows=1; break"))
        assertFalse(loop.contains("exit 1"))
        assertTrue(restore.substringAfter("\nMOKE_RESTORE_ROWS\n").contains("|| moke_attach_failure"))
    }

    @Test fun `tmux fallback keeps native login argv0 and BASH_ENV but unsupported helper still has a terminal`() {
        val open = body(TmuxRemote.open(target))
        assertTrue(open.contains("moke-fallback-check"))
        assertTrue(open.contains("BASH_ENV=/dev/null /bin/bash --noprofile --norc -c"))
        assertTrue(open.contains("exec -a \"\$1\" \"\$2\""))
        assertTrue(open.contains("export BASH_ENV=\"\$4\"; else unset BASH_ENV"))
        assertTrue(open.contains("\"-\$moke_login_name\" \"\$moke_login_shell\" \"\${BASH_ENV+x}\" \"\${BASH_ENV-}\""))
        assertTrue(open.contains("exec \${SHELL:-/bin/sh} -l"))
        assertTrue(open.contains("exit \"\$?\""))
    }

    @Test fun `ordinary startup exit seven stays an ordinary command not a tmux fallback`() {
        val launched = words(TmuxRemote.launch(scripts, nonce, "exit 7"))
        assertEquals(listOf(nonce, "command", "exit 7"), launched.drop(4))
        assertEquals(scripts.observer, launched[2])
        assertFalse(scripts.observer.contains("moke_attach_failure"))
        assertTrue(scripts.observer.contains("command) sh -c \"\$payload\""))
        assertTrue(scripts.observer.contains("exit \"\$result\""))
    }

    @Test fun `tmux format expansion in create and rename cannot reinterpret literal names`() {
        val malicious = "-中文 | '#{session_name} #(touch bad) #; work"
        val escaped = malicious.replace("#", "##")
        val create = words(TmuxRemote.create(malicious))
        assertEquals(malicious, create[4])
        assertEquals(escaped, create[5])
        val rename = body(TmuxRemote.rename(target, malicious))
        assertTrue(rename.contains("##{session_name}"))
        assertTrue(rename.contains("##(touch bad)"))
        assertTrue(rename.contains(" -- "))
        // Exact-name lookup compares raw text, including a leading $ that tmux -t treats as an ID.
        assertEquals(malicious, words(TmuxRemote.restore(malicious)).last())
        assertEquals("\$7", words(TmuxRemote.restore("\$7")).last())
        val row = sessionRow.substringBeforeLast("|中文") + "|$malicious"
        assertEquals(malicious, TmuxRemote.parseOperation(operation("SERVER|$serverRow", "CREATED|$row", "RESULT|OK"))!!.created!!.name)
    }

    @Test fun `generated heredocs and tmux guard expressions are structurally complete`() {
        val commands = listOf(
            TmuxRemote.capabilityCommand(), TmuxRemote.observe(scripts, "r1", nonce, true),
            TmuxRemote.create("quote ' pipe |"), TmuxRemote.open(target), TmuxRemote.restore("quote ' pipe |"),
            TmuxRemote.create("work", socket), TmuxRemote.restore("work", socketPath = socket),
            TmuxRemote.observe(scripts, "r1", nonce, true, socket),
            TmuxRemote.rename(target, "quote ' pipe |"), TmuxRemote.detach(target), TmuxRemote.kill(target), TmuxRemote.scrollSetup(target),
            TmuxRemote.paneCwd(target, TmuxClientId(456, 1700000002, "/dev/pts/8")),
        )
        for (command in commands) {
            val body = body(command)
            for (marker in Regex("<<([A-Z_]+)").findAll(body).map { it.groupValues[1] }) {
                assertTrue("Missing exact terminator: $marker", body.lineSequence().any { it == marker })
            }
            for (guard in body.lineSequence().filter { it.contains("#{&&:") }) {
                assertEquals("Unbalanced guard: $guard", guard.windowed(2).count { it == "#{" }, guard.count { it == '}' })
            }
        }
    }

    @Test fun `peer guard repeats actual owner and boot proof inside same operation command`() {
        val create = TmuxRemote.create("work", socket)
        val command = body(TmuxRemote.guardPeer(scripts, nonce, true, boot, create))
        assertTrue(command.contains(qForTest(scripts.probe)))
        assertTrue(command.contains("[ \"\$moke_boot_id\" = '$boot' ]"))
        assertTrue(command.indexOf("moke_witness=\$(sh -c") < command.indexOf(create))
        assertTrue(command.contains("moke_result STALE"))
        assertTrue(command.contains("moke_result UNKNOWN"))
        assertFalse(body(TmuxRemote.guardPeer(scripts, nonce, true, null, "DO_NOT_RUN")).contains("DO_NOT_RUN"))
        assertFalse(body(TmuxRemote.guardPeer(scripts, nonce, true, "bad-boot", "DO_NOT_RUN")).contains("DO_NOT_RUN"))
        val missingOwner = body(TmuxRemote.guardPeer(scripts, null, true, boot, "DO_NOT_RUN"))
        assertTrue(missingOwner.indexOf("exit 0") < missingOwner.indexOf("DO_NOT_RUN"))
        val ssh = body(TmuxRemote.guardPeer(scripts, null, false, boot, "CONTROL_OPERATION"))
        assertFalse(ssh.contains("moke_witness="))
        assertTrue(ssh.endsWith("CONTROL_OPERATION"))
    }

    @Test fun `untrusted inputs cannot inject protocol or shell structure`() {
        for (bad in listOf("", "x\ny", "x\ty", "x\u0000y", "bad:name", "bad.name")) {
            invalid { TmuxRemote.create(bad) }
            invalid { TmuxRemote.restore(bad) }
            invalid { TmuxRemote.rename(target, bad) }
        }
        invalid { TmuxRemote.launch(scripts, "x;bad", null) }
        invalid { TmuxRemote.observe(scripts, "x\ny", nonce, true) }
        invalid { TmuxRemote.observe(scripts, "r1", "x;bad", true) }
        invalid { TmuxRemote.open(target.copy(server = server.copy(bootId = ""))) }
        invalid { TmuxRemote.kill(target.copy(id = "\$7;kill-server")) }
        invalid { TmuxRemote.create("ok", "relative/path") }
        invalid { TmuxRemote.create("ok", "/tmp/bad\npath") }
    }

    @Test fun `joint pane protocol preserves pipes percent Unicode shell and format literals`() {
        fun encode(value: String) = value.replace("%", "%25").replace("|", "%7C")
        val title = "default-host | 中文 %7C #{session_name} #(touch bad) ' ; $(bad)"
        val path = "/home/中文 | percent%7C/' folder"
        val row = "CLIENT_PANE|456|1700000002|\$7|1700000001|/dev/pts/8|%9|${encode(title)}|${encode(path)}"
        val good = ready().replace("CLIENT|456|1700000002|\$7|/dev/pts/8", row)
        val actual = TmuxRemote.parseObservation(good, "request_1")!!.clients.single()
        assertEquals(TmuxPane("%9", title, path), actual.pane)
        assertEquals(1700000001L, actual.sessionCreated)
        assertEquals(TmuxClientId(456, 1700000002, "/dev/pts/8"), actual.identity)
        val empty = good.replace(row, "CLIENT_PANE|456|1700000002|\$7|1700000001|/dev/pts/8|%9||")
        assertEquals(TmuxPane("%9", "", null), TmuxRemote.parseObservation(empty, "request_1")!!.clients.single().pane)
    }

    @Test fun `joint pane rows still reject malformed escaping wire controls identity mismatch and frame splicing`() {
        val prefix = "CLIENT_PANE|456|1700000002|\$7|1700000001|/dev/pts/8|%9|"
        fun withRow(row: String) = ready().replace("CLIENT|456|1700000002|\$7|/dev/pts/8", row)
        val good = withRow(prefix + "title|/cwd")
        val badRows = listOf(
            prefix + "title|/cwd|extra", prefix + "raw|pipe|/cwd",
            prefix + "bad%|/cwd", prefix + "bad%0A|/cwd", prefix + "bad%7c|/cwd", prefix + "bad%20|/cwd",
            prefix + "bad\u001btitle|/cwd", prefix + "bad\u0085title|/cwd",
            prefix + "bad\ntitle|/cwd", prefix + "title|/bad\tpath", prefix + "title|/bad\u007fpath",
            (prefix + "title|/cwd").replace("|1700000001|", "|1700000003|"),
            (prefix + "title|/cwd").replace("|%9|", "|9|"),
            (prefix + "title|/cwd").replace("|%9|", "|%12345678901|"),
        )
        badRows.forEach { assertNull(it, TmuxRemote.parseObservation(withRow(it), "request_1")) }
        assertNull(TmuxRemote.parseObservation(good + good, "request_1"))
        assertNull(TmuxRemote.parseObservation(good.trimEnd('\n'), "request_1"))
        assertNull(TmuxRemote.parseObservation(good.substringBefore(p + "END"), "request_1"))
        assertNull(TmuxRemote.parseObservation(good, "request_2"))
        assertNull(TmuxRemote.parseObservation(good.replace("KIND|READY", "CLIENT|456|1700000002|\$7|/dev/pts/8\n${p}KIND|READY"), "request_1"))
    }

    @Test fun `unsafe optional pane text degrades only that client and preserves exact identities`() {
        val ours = "CLIENT_PANE|456|1700000002|\$7|1700000001|/dev/pts/8|%9|current title|/current"
        val unrelated = "CLIENT_PANE|457|1700000003|\$7|1700000001|/dev/pts/9|%10|"
        val invalidFields = listOf(
            "bad title|/other", "bad title|/other", "other title|/bad path", "other title|/bad path",
            "other title|relative", "x".repeat(SESSION_TITLE_LIMIT + 1) + "|/other",
            "%25".repeat(SESSION_TITLE_LIMIT + 1) + "|/other", "other title|/" + "x".repeat(SESSION_PATH_LIMIT),
        )
        for (fields in invalidFields) {
            val output = ready().replace("CLIENT|456|1700000002|\$7|/dev/pts/8", "$ours\n$p$unrelated$fields")
            val actual = TmuxRemote.parseObservation(output, "request_1")
            assertNotNull("Optional pane must not invalidate observation", actual)
            assertEquals(server, actual!!.server)
            assertEquals(target, actual.sessions.single().ref(server))
            assertEquals(TmuxPane("%9", "current title", "/current"), actual.clients[0].pane)
            assertEquals(TmuxClientId(457, 1700000003, "/dev/pts/9"), actual.clients[1].identity)
            assertEquals("\$7", actual.clients[1].sessionId)
            assertEquals(1700000001L, actual.clients[1].sessionCreated)
            assertNull("No half-valid pane title or cwd may escape", actual.clients[1].pane)
        }
    }

    @Test fun `unsafe optional metadata cannot hide strong identity errors duplicate clients or broken framing`() {
        val client = "CLIENT_PANE|456|1700000002|\$7|1700000001|/dev/pts/8|%9|bad title|/other"
        fun output(row: String) = ready().replace("CLIENT|456|1700000002|\$7|/dev/pts/8", row)
        val malformed = listOf(
            client.replace("CLIENT_PANE|456|", "CLIENT_PANE|1|"),
            client.replace("|1700000002|", "|0|"), client.replace("|1700000001|", "|0|"),
            client.replace("|1700000001|", "|1700000009|"), client.replace("|\$7|", "|\$99|"),
            client.replace("|/dev/pts/8|", "|not-a-tty|"), client.replace("|%9|", "|invalid-pane-id|"),
            "$client\n$p$client", "$client\n${p}CLIENT|456|1700000002|\$7|/dev/pts/8",
            "$client|extra", client.substringBeforeLast('|'), client.replace("title", "title%0A"),
        )
        malformed.forEach { assertNull(it, TmuxRemote.parseObservation(output(it), "request_1")) }
        val good = output(client)
        assertNull(TmuxRemote.parseObservation(good + good, "request_1"))
        assertNull(TmuxRemote.parseObservation(good.trimEnd('\n'), "request_1"))
        assertNull(TmuxRemote.parseObservation(good.substringBefore(p + "END"), "request_1"))
        assertNull(TmuxRemote.parseObservation(good, "another-request"))
    }

    @Test fun `observation obtains pane fields once in actual client context without reevaluating returned text`() {
        val command = body(TmuxRemote.observe(scripts, "r1", nonce, true))
        val listing = command.lineSequence().single { it.contains("moke_clients=\$(tmux") }
        val args = words(listing)
        val format = args[args.indexOf("-F") + 1]
        assertTrue(format.contains("${p}CLIENT_PANE|#{client_pid}|#{client_created}|#{session_id}|#{session_created}|#{client_tty}|#{pane_id}|"))
        assertTrue(format.contains("#{s/[|]/%7C/:#{s/%/%25/:pane_title}}"))
        assertTrue(format.contains("#{s/[|]/%7C/:#{s/%/%25/:pane_current_path}}"))
        for (field in listOf("pane_title", "pane_current_path")) {
            assertTrue(format.contains("#{m/r:[[:cntrl:]],#{$field}}"))
            assertTrue(format.contains("#{n:$field},4096"))
        }
        assertEquals(1, command.lineSequence().count { it.contains("list-clients -F") })
        assertFalse(listing.contains("#{E:"))
        assertFalse(listing.contains("#{T:"))
        assertFalse(listing.contains("display-message -c"))
        assertFalse(command.contains("eval "))
        assertFalse(command.contains("set-hook"))
    }

    private fun qForTest(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
