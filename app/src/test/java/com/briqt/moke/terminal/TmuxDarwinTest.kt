package com.briqt.moke.terminal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// type: source-linked native protocol tests with a private fake JXA bridge (not macOS execution)
class TmuxDarwinTest {
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val p = "__MOKE_TMUX_V1__|"

    // This fake implements only the primitive memory bridge. The parser, same-uid gate,
    // birth checks, nonce comparison and descriptor validation are production JavaScript.
    private val bridge = """
        const scenario = process.argv[1];
        const nonce = '0123456789abcdef0123456789abcdef';
        const bootId = '01234567-89ab-cdef-0123-456789abcdef';
        const errorPointer = { data: Buffer.alloc(4) };
        let targetReads = 0, targetArgvReads = 0;
        function write(pointer, data) { data.copy(pointer.data); }
        function word(data, offset, value) { data.writeUInt32LE(value, offset); }
        function wide(data, offset, value) { data.writeBigUInt64LE(BigInt(value), offset); }
        function argv(words) {
            const count = Buffer.alloc(4); word(count, 0, words.length);
            return Buffer.concat([count, Buffer.from('/bin/sh\0\0'), Buffer.from(words.join('\0') + '\0'),
                Buffer.from('SECRET_CREDENTIAL=never-output\0' + nonce + '\0')]);
        }
        const api = {
            getpid: () => 100, getuid: () => 501,
            malloc: (size) => ({data: Buffer.alloc(size)}), free: () => {},
            __error: () => errorPointer,
            open: () => 9, close: () => 0,
            proc_pidinfo: (pid, flavor, arg, pointer, size) => {
                if (flavor !== 3 || size !== 136) throw Error('bad call');
                if (pid === 100 && scenario === 'layout') return 135;
                if (pid === 200) {
                    targetReads++;
                    if (scenario === 'absent' || scenario === 'permission') {
                        word(errorPointer.data, 0, scenario === 'absent' ? 3 : 1); return 0;
                    }
                    if (scenario === 'short-bsd') return 135;
                }
                const data = Buffer.alloc(136);
                word(data, 12, pid); word(data, 20, pid === 200 && scenario === 'uid' ? 502 : 501);
                word(data, 108, pid === 200 ? 1234 : 0);
                wide(data, 120, 1700000000 + (pid === 200 && scenario === 'birth' && targetReads > 1 ? 1 : 0));
                wide(data, 128, 123456); write(pointer, data); return 136;
            },
            proc_pidfdinfo: (pid, fd, flavor, pointer, size) => {
                if (flavor !== 2 || size !== 1200) throw Error('bad fd call');
                if (scenario === 'short-fd') return 1199;
                const data = Buffer.alloc(1200);
                data.writeUInt16LE(scenario === 'regular' ? 32768 : 8192, 28);
                word(data, 40, scenario === 'fd-uid' ? 502 : 501);
                word(data, 140, fd === 9 ? 99 : scenario === 'tty' ? 5678 : 1234);
                const path = fd === 9 ? '/dev/null' : scenario === 'path' ? '/dev/ttys000/../x' : '/dev/ttys000';
                Buffer.from(path + '\0').copy(data, 176); write(pointer, data); return 1200;
            },
            sysctlbyname: (name, pointer, size) => {
                const value = Buffer.from((scenario === 'boot' ? 'wrong' : bootId) + '\0');
                write(pointer, value); wide(size.data, 0, value.length); return 0;
            },
            sysctl: (mib, count, pointer, size) => {
                const pid = mib.data.readUInt32LE(8);
                if (pid === 200) targetArgvReads++;
                let words = pid === 100 ? ['osascript', 'capability', 'moke-native-check'] : ['sh', nonce, 'command'];
                if (scenario === 'nonce') words = ['sh', nonce + 'suffix', 'x' + nonce];
                if (scenario === 'duplicate') words = ['sh', nonce, nonce];
                if (scenario === 'env-only') words = ['sh', 'not-the-nonce'];
                let data = argv(words);
                if (scenario === 'argv-truncated') data = data.subarray(0, 18);
                if (scenario === 'argc') word(data, 0, 20000);
                write(pointer, data); wide(size.data, 0, data.length); return 0;
            }
        };
        function §(value) { return {dataUsingEncoding: () => ({getBytesLength: (pointer) => {
            write(pointer, Buffer.from(value, 'latin1'));
        }})}; }
        §.NSISOLatin1StringEncoding = 5;
        §.NSData = {dataWithBytesLength: (pointer, size) => pointer.data.subarray(0, size)};
        §.NSString = {alloc: {initWithDataEncoding: (data) => data.toString('latin1')}};
        const ObjC = {import: () => {}, unwrap: (value) => value, bindFunction: (name) => {
            if (scenario === 'api') throw Error('missing API');
            §[name] = api[name];
        }};
    """.trimIndent().replace('§', '$')

    private fun native(scenario: String, action: String = "owner"): Pair<String, Int> {
        assumeTrue(System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "node").canExecute() })
        val args = when (action) {
            "capability" -> "['capability','moke-native-check']"
            "boot" -> "['boot']"
            "proc" -> "['proc','200']"
            else -> "['owner','200',nonce]"
        }
        val program = bridge + "\n" + TmuxDarwin.native +
            "\nconst result = run($args); console.log(result); console.log(targetArgvReads);"
        val process = ProcessBuilder("node", "-e", program, scenario).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readLines()
        assertEquals(output.toString(), 0, process.exitValue())
        assertEquals(output.toString(), 2, output.size)
        assertFalse(output.toString().contains("SECRET_CREDENTIAL"))
        assertTrue(output.first().length <= 256)
        return output[0] to output[1].toInt()
    }

    @Test fun `real primitive parser returns a bounded stable tagged birth and exact tty`() {
        assertEquals("OWNER|200|501|-1700000000123456|/dev/ttys000|1234|$boot", native("good").first)
        assertEquals("PROC|200|501|-1700000000123456|$boot", native("good", "proc").first)
        assertEquals("BOOT|$boot", native("good", "boot").first)
        assertEquals("SUPPORTED", native("good", "capability").first)
    }

    @Test fun `wrong exact nonce env only nonce duplicate nonce truncated argv and invalid argc fail closed`() {
        for (scenario in listOf("nonce", "env-only", "duplicate", "argv-truncated", "argc"))
            assertEquals(scenario, "FAILURE", native(scenario).first)
    }

    @Test fun `birth uid tty fd device mode path and incomplete native responses fail closed`() {
        for (scenario in listOf("birth", "uid", "tty", "fd-uid", "regular", "path", "short-bsd", "short-fd"))
            assertEquals(scenario, "FAILURE", native(scenario).first)
        assertEquals("different uid must stop before argv", 0, native("uid").second)
    }

    @Test fun `native ESRCH is absence but permissions and unsupported API or layouts are distinct`() {
        assertEquals("ABSENT", native("absent", "proc").first)
        assertEquals("FAILURE", native("permission", "proc").first)
        for (scenario in listOf("api", "layout", "boot"))
            assertEquals(scenario, "UNSUPPORTED", native(scenario, "capability").first)
    }

    private fun frame(ownerBirth: String = "-1700000000123456", serverBirth: String = "-1700000000123456", tty: String = "/dev/ttys000") =
        "${p}BEGIN|darwin\n${p}PEER|$boot\n${p}OWNER|$nonce|200|501|$ownerBirth|$tty\n" +
            "${p}DEFAULT_SOCKET|/private/tmp/tmux-501/default\n" +
            "${p}SERVER|300|1700000000|$serverBirth|7|88|$boot|/private/tmp/tmux-501/default\n" +
            "${p}SESSION|\$1|1700000001|1|1|work\n${p}CLIENT|400|1700000002|\$1|$tty\n${p}KIND|READY\n${p}END|darwin|0\n"

    @Test fun `Darwin frames bind uniquely but cannot mix Linux ticks tty or arbitrary tty paths`() {
        val observed = TmuxRemote.parseObservation(frame(), "darwin")!!
        assertTrue(TmuxCoordinatorRules.verified(observed, nonce, true, null))
        val scope = TmuxScope("target", "connection")
        assertEquals("/dev/ttys000", TmuxCoordinatorRules.binding(scope, observed)?.client?.tty)
        val ambiguous = observed.copy(clients = observed.clients + observed.clients.single().copy(identity = TmuxClientId(401, 1700000003, "/dev/ttys000")))
        assertFalse(TmuxCoordinatorRules.verified(ambiguous, nonce, true, null))
        assertNull(TmuxCoordinatorRules.binding(scope, ambiguous))
        for (tty in listOf("/dev/ttys000suffix", "/dev/ttys", "/dev/ttys000/../x", "/tmp/ttys000", "/dev/pts/1"))
            assertNull(tty, TmuxRemote.parseObservation(frame(tty = tty), "darwin"))
        assertNull(TmuxRemote.parseObservation(frame(ownerBirth = "1234"), "darwin"))
        assertNull(TmuxRemote.parseObservation(frame(serverBirth = "1234"), "darwin"))
        assertNull(TmuxRemote.parseObservation(frame(ownerBirth = "-0"), "darwin"))
        assertNull(TmuxRemote.parseObservation(frame(serverBirth = "-9007199254740992"), "darwin"))
        assertFalse(observed.server == observed.server!!.copy(startedTicks = -observed.server.startedTicks))
    }

    @Test fun `ready data with unsupported failed or missing owner capability never stays pending`() = runBlocking {
        for (capability in listOf(TmuxObserverCapability.Unsupported, TmuxObserverCapability.Failure, null)) {
            val state = MutableStateFlow(TmuxUiState())
            val coordinator = TmuxCoordinator(TmuxScope("target", "source"), TmuxIntent.None, TmuxScripts("", ""),
                object : TmuxCoordinator.Bridge {
                    override suspend fun observe(command: String): String? = error("must not probe without carrier")
                    override suspend fun operation(command: String): String? = error("must not mutate")
                }, { null }, true, { true }, { true }, state, { it.name }, observerCapability = { capability })
            coordinator.refresh()
            assertEquals(TmuxPhase.ERROR, state.value.phase)
            assertEquals(TmuxPresence.Unknown(if (capability == TmuxObserverCapability.Unsupported)
                TmuxUnknownReason.UNSUPPORTED else TmuxUnknownReason.RECONNECT_REQUIRED), state.value.presence)
            assertEquals(if (capability == TmuxObserverCapability.Unsupported)
                TmuxProblem.PLATFORM_UNSUPPORTED.name else TmuxProblem.OWNER_UNAVAILABLE.name, state.value.message)
            assertFalse(state.value.canCreate)
        }
    }

    @Test fun `SSH startup preflight failure does not permanently gate refresh after network recovery`() = runBlocking {
        val observed = TmuxRemote.parseObservation(frame().lineSequence()
            .filterNot { it.startsWith("${p}OWNER|") }.joinToString("\n"), "darwin")!!
        val state = MutableStateFlow(TmuxUiState())
        val scripts = TmuxScripts("", "")
        val startup = sshTmuxStartup(null, null, nonce, scripts, observeDefaultShell = true) { TmuxObserverCapability.Failure }
        assertNull(startup.command)
        assertNull(startup.nonce)
        assertEquals(TmuxObserverCapability.Failure, startup.capability)
        var ready = false
        var networkRecovered = false
        var observations = 0
        val coordinator = TmuxCoordinator(TmuxScope("target", "ssh-source"), TmuxIntent.None, scripts,
            object : TmuxCoordinator.Bridge {
                override suspend fun observe(command: String): String? {
                    observations++
                    return if (networkRecovered) "authenticated recovered response" else null
                }
                override suspend fun operation(command: String): String? = error("must not replay or mutate")
            }, { startup.nonce }, false, { ready }, { true }, state, { it.name },
            observationParser = { _, _ -> observed }, observerCapability = { startup.capability })
        coordinator.refresh()
        assertEquals(0, observations)
        assertEquals(TmuxPresence.Pending, state.value.presence)
        ready = true
        coordinator.refresh()
        assertEquals("historical preflight failure must not bypass a fresh SSH read", 1, observations)
        assertEquals(TmuxPresence.Unknown(), state.value.presence)
        networkRecovered = true
        coordinator.refresh()
        assertEquals(2, observations)
        assertEquals(TmuxPhase.READY, state.value.phase)
        assertTrue(state.value.snapshot!!.verified)
        assertTrue(state.value.canManage)
        assertNull(state.value.currentBinding)
        assertNull(state.value.snapshot!!.observation.owner)
        assertEquals("an ownerless management list is never a fabricated current attachment", TmuxPresence.Unknown(), state.value.presence)
    }

    @Test fun `generated shell frames parse on Linux without treating another OS as missing server`() {
        val directory = Files.createTempDirectory("moke-platform-").toFile()
        try {
            File(directory, "uname").apply { writeText("#!/bin/sh\nprintf 'FreeBSD\\n'\n"); setExecutable(true) }
            val builder = ProcessBuilder("/bin/sh", "-c", TmuxRemote.observe(TmuxScripts("", ""), "other", null, false))
            builder.environment()["PATH"] = directory.path + File.pathSeparator + System.getenv("PATH")
            val process = builder.redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertEquals(output, TmuxProbeKind.UNSUPPORTED, TmuxRemote.parseObservation(output, "other")?.kind)
        } finally { directory.deleteRecursively() }
    }
}
