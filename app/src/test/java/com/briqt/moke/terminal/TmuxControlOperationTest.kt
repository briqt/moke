package com.briqt.moke.terminal

// type: transport protocol regression tests
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.*
import org.junit.Test

class TmuxControlOperationTest {
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val scripts = TmuxScripts("observer test payload", "probe test payload")
    private val supported = "__MOKE_TMUX_V1__|CAPABILITY|SUPPORTED\n"

    @Test fun `result accepts exact byte boundary including multibyte UTF8`() {
        val text = "墨".repeat(4096)
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, readTmuxResult(ByteArrayInputStream(bytes), bytes.size))
        assertNull(readTmuxResult(ByteArrayInputStream(bytes), bytes.size - 1))
        assertEquals("", readTmuxResult(ByteArrayInputStream(byteArrayOf()), 0))
        assertNull(readTmuxResult(ByteArrayInputStream(byteArrayOf(1)), 0))
    }

    @Test fun `default result limit refuses one excess byte rather than returning a truncated frame`() {
        val exact = ByteArray(262_144) { 'a'.code.toByte() }
        assertEquals(exact.size, readTmuxResult(ByteArrayInputStream(exact))?.length)
        assertNull(readTmuxResult(ByteArrayInputStream(exact + 'b'.code.toByte())))
    }

    @Test fun `result refuses malformed truncated overlong surrogate and out of range UTF8`() {
        for (bytes in listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xe4.toByte(), 0xb8.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )) assertNull(readTmuxResult(ByteArrayInputStream(bytes)))
    }

    @Test fun `capability distinguishes unsupported from malformed truncated conflicting and failed probes`() {
        assertEquals(TmuxObserverCapability.Supported, parseTmuxObserverCapability(supported))
        assertEquals(TmuxObserverCapability.Supported, parseTmuxObserverCapability("login notice\r\n" + supported.replace("\n", "\r\n")))
        assertEquals(TmuxObserverCapability.Unsupported, parseTmuxObserverCapability(supported.replace("SUPPORTED", "UNSUPPORTED")))
        for (output in listOf(
            "", supported.trimEnd(), " $supported", supported.replace("SUPPORTED", "FAILURE"),
            supported + supported, supported + "__MOKE_TMUX_V1__|CAPABILITY|UNSUPPORTED\n",
            supported + "__MOKE_TMUX_V1__|CAPABILITY|SUPP", supported.replace("SUPPORTED", "SUPPORTED "),
        )) assertEquals(output, TmuxObserverCapability.Failure, parseTmuxObserverCapability(output))
    }

    @Test fun `ordinary SSH startShell and Windows or host configured commands do not even probe`() {
        var probes = 0
        val capability = { probes++; TmuxObserverCapability.Supported }
        assertEquals(TmuxStartup(null), sshTmuxStartup(null, null, null, scripts, capability = capability))
        assertEquals(TmuxStartup(null), sshTmuxStartup(null, null, nonce, scripts, capability = capability))
        assertEquals(TmuxStartup("powershell.exe"), sshTmuxStartup(null, "powershell.exe", nonce, scripts, capability = capability))
        assertEquals(TmuxStartup("cmd.exe"), sshTmuxStartup("cmd.exe", "cmd.exe", null, scripts, capability = capability))
        assertEquals(TmuxStartup(null), sshTmuxStartup(" ", null, nonce, scripts, capability = capability))
        assertEquals(0, probes)
    }

    @Test fun `explicit default SSH observation keeps startShell and preserves capability outcome`() {
        var probes = 0
        val unsupported = { probes++; TmuxObserverCapability.Unsupported }
        assertEquals(TmuxStartup(null, capability = TmuxObserverCapability.Unsupported),
            sshTmuxStartup(null, null, nonce, scripts, observeDefaultShell = true, capability = unsupported))
        assertEquals(1, probes)
        assertEquals(TmuxStartup(null), sshTmuxStartup(null, null, null, scripts, observeDefaultShell = true) { error("must not probe") })
        assertEquals(TmuxStartup(null, capability = TmuxObserverCapability.Failure),
            sshTmuxStartup(null, null, nonce, scripts, observeDefaultShell = true) { error("probe failed") })
    }

    @Test fun `explicit default SSH observation uses Remote shell carrier not a fabricated login command`() {
        var probes = 0
        val result = sshTmuxStartup(null, null, nonce, scripts, observeDefaultShell = true) { probes++; TmuxObserverCapability.Supported }
        assertEquals(TmuxStartup(TmuxRemote.launch(scripts, nonce, null), nonce, TmuxObserverCapability.Supported), result)
        assertEquals(1, probes)
    }

    @Test fun `unsupported or failed SSH capability leaves protocol exec command unchanged`() {
        val original = "tmux attach-session -t '\$1'"
        assertEquals(TmuxStartup(original, capability = TmuxObserverCapability.Unsupported),
            sshTmuxStartup(original, original, nonce, scripts) { TmuxObserverCapability.Unsupported })
        assertEquals(TmuxStartup(original, capability = TmuxObserverCapability.Failure),
            sshTmuxStartup(original, original, nonce, scripts) { error("unsupported shell") })
        assertEquals(TmuxStartup(original), sshTmuxStartup(original, original, nonce, null) { error("must not probe") })
    }

    @Test fun `supported explicit SSH startup is exactly the Remote launcher and publishes nonce`() {
        val original = "tmux attach-session -t '\$1'"
        val expected = TmuxStartup(TmuxRemote.launch(scripts, nonce, original), nonce, TmuxObserverCapability.Supported)
        assertEquals(expected, sshTmuxStartup(original, original, nonce, scripts) { TmuxObserverCapability.Supported })
    }

    @Test fun `unsupported mosh preserves default null carrier and custom command exactly`() {
        for (original in listOf(null, "printf 'hello'; exec bash -l")) {
            assertEquals(TmuxStartup(original, capability = TmuxObserverCapability.Unsupported),
                tmuxStartup(original, nonce, scripts) { TmuxObserverCapability.Unsupported })
            assertEquals(TmuxStartup(original, capability = TmuxObserverCapability.Failure),
                tmuxStartup(original, nonce, scripts) { error("capability failed") })
            assertEquals(TmuxStartup(original), tmuxStartup(original, null, scripts) { error("no nonce") })
        }
    }

    @Test fun `supported mosh wraps default and command carriers without inventing a shell`() {
        for (original in listOf(null, "exec tmux attach-session -t '\$1'")) {
            assertEquals(TmuxStartup(TmuxRemote.launch(scripts, nonce, original), nonce, TmuxObserverCapability.Supported),
                tmuxStartup(original, nonce, scripts) { TmuxObserverCapability.Supported })
        }
    }

    @Test fun `invalid nonce cannot break fallback terminal`() {
        assertEquals(TmuxStartup(null, capability = TmuxObserverCapability.Failure),
            tmuxStartup(null, "invalid", scripts) { TmuxObserverCapability.Supported })
    }

    private class ChannelFixture(val failure: String = "", val output: ByteArray = "ACK\n".toByteArray()) {
        var sessionsClosed = 0
        var commandsClosed = 0
        var execs = 0
        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, block: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> block(method.name, args ?: emptyArray()) } as T
        private val command = proxy(Session.Command::class.java) { method, args ->
            when (method) {
                "join" -> {
                    assertEquals(listOf(10L, TimeUnit.SECONDS), args.toList())
                    if (failure == "join") error("interrupted command")
                    null
                }
                "isOpen" -> failure == "timeout"
                "getExitStatus" -> when (failure) { "status" -> 7; "missing-status" -> null; else -> 0 }
                "getInputStream" -> if (failure == "read") error("broken stream") else ByteArrayInputStream(output)
                "close" -> { commandsClosed++; null }
                else -> error("unexpected command call $method")
            }
        }
        private val session = proxy(Session::class.java) { method, _ ->
            when (method) {
                "exec" -> { execs++; if (failure == "exec") error("exec failed"); command }
                "close" -> { sessionsClosed++; null }
                else -> error("unexpected session call $method")
            }
        }
        val client = object : SSHClient() { override fun startSession(): Session = session }
    }

    @Test fun `short channel closes session and command on success without replay`() {
        val fixture = ChannelFixture()
        assertEquals("ACK\n", execTmuxChannel(fixture.client, "operation"))
        assertEquals(1, fixture.execs)
        assertEquals(1, fixture.commandsClosed)
        assertEquals(1, fixture.sessionsClosed)
    }

    @Test fun `short channel releases every acquired owner on exception timeout and invalid output`() {
        for (failure in listOf("exec", "join", "timeout", "status", "missing-status", "read", "utf8", "oversize")) {
            val bytes = when (failure) {
                "utf8" -> byteArrayOf(0xff.toByte())
                "oversize" -> ByteArray(262_145)
                else -> "ACK\n".toByteArray()
            }
            val fixture = ChannelFixture(failure, bytes)
            assertNull(failure, runCatching { execTmuxChannel(fixture.client, "operation") }.getOrNull())
            assertEquals(failure, 1, fixture.execs)
            assertEquals(failure, if (failure == "exec") 0 else 1, fixture.commandsClosed)
            assertEquals(failure, 1, fixture.sessionsClosed)
        }
    }

    @Test fun `capability command error or missing exit status is not supported`() {
        for (failure in listOf("exec", "join", "timeout", "status", "missing-status")) {
            val fixture = ChannelFixture(failure, supported.toByteArray())
            assertEquals(failure, TmuxObserverCapability.Failure, supportsTmuxObserver(fixture.client))
            assertEquals(1, fixture.sessionsClosed)
        }
    }
}
