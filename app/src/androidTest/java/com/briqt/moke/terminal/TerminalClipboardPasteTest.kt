package com.briqt.moke.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real clipboard callbacks and emulator parsing, with a synchronous transport and no window. */
@RunWith(AndroidJUnit4::class)
class TerminalClipboardPasteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun enabledBracketedPasteWrapsMultilineClipboardExactlyOnce() = withFixture { fixture, clipboard ->
        fixture.output("\u001b[?2004h")
        clipboard.setPlainText("first line\n第二行\r\nlast\r")

        fixture.session.onPasteTextFromClipboard()

        fixture.transport.assertSent("\u001b[200~", "first line\r第二行\rlast\r", "\u001b[201~")
    }

    @Test
    fun defaultPasteNormalizesNewlinesWithoutAddingBracketsOrAnEnter() = withFixture { fixture, clipboard ->
        clipboard.setPlainText("first line\n第二行\r\nlast")

        fixture.session.onPasteTextFromClipboard()

        fixture.transport.assertSent("first line\r第二行\rlast")
    }

    @Test
    fun remoteModeChangesApplyToEachClipboardPaste() = withFixture { fixture, clipboard ->
        clipboard.setPlainText("one\ntwo")
        fixture.output("\u001b[?2004h")
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent("\u001b[200~", "one\rtwo", "\u001b[201~")
        fixture.transport.clear()

        fixture.output("\u001b[?2004l")
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent("one\rtwo")
        fixture.transport.clear()

        fixture.output("\u001b[?2004h")
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent("\u001b[200~", "one\rtwo", "\u001b[201~")
    }

    @Test
    fun pasteUsesKernelEscapeAndC1FilteringInBothModes() = withFixture { fixture, clipboard ->
        val c1 = (0x80..0x9f).map { it.toChar() }.joinToString("")
        clipboard.setPlainText("\u001b[200~alpha\u001b[201~${c1}\r\n\t\u0003\u007f 中文")
        val sanitized = "[200~alpha[201~\r\t\u0003\u007f 中文"

        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent(sanitized)
        fixture.transport.clear()

        fixture.output("\u001b[?2004h")
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent("\u001b[200~", sanitized, "\u001b[201~")
    }

    @Test
    fun missingOrEmptyClipboardDoesNotSendEvenBracketMarkers() = withFixture { fixture, clipboard ->
        fixture.output("\u001b[?2004h")
        clipboard.clearPrimaryClip()
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent()

        clipboard.setPlainText("")
        fixture.session.onPasteTextFromClipboard()
        fixture.transport.assertSent()
    }

    @Test
    fun missingSessionDoesNotSendClipboardText() = withFixture { fixture, clipboard ->
        clipboard.setPlainText("not sent\n")

        fixture.controller.onPasteTextFromClipboard(null)

        fixture.transport.assertSent()
    }

    @Test
    fun missingEmulatorDoesNotFallBackToRawTransportWrite() = withFixture(initializeEmulator = false) { fixture, clipboard ->
        assertNull(fixture.session.emulator)
        clipboard.setPlainText("not sent\n")

        fixture.session.onPasteTextFromClipboard()

        fixture.transport.assertSent()
    }

    private fun withFixture(
        initializeEmulator: Boolean = true,
        block: (Fixture, ClipboardManager) -> Unit,
    ) {
        // The fixture has no focused window; adopt only the background clipboard read permission.
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            val fixture = onMain { Fixture(instrumentation.targetContext, initializeEmulator) }
            try {
                onMain {
                    val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val savedClip = clipboard.primaryClip
                    try {
                        block(fixture, clipboard)
                    } finally {
                        if (savedClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(savedClip)
                    }
                }
            } finally {
                onMain {
                    if (fixture.session.emulator == null) {
                        // An unstarted memory transport has no resources or session callback to close.
                        fixture.session.onTransportFinished(0)
                    } else {
                        fixture.session.finishIfRunning()
                    }
                }
                assertTrue("Session cleanup did not finish", fixture.finished.await(10, TimeUnit.SECONDS))
                onMain { assertFalse(fixture.session.isRunning) }
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun ClipboardManager.setPlainText(text: String) {
        setPrimaryClip(ClipData.newPlainText("paste regression", text))
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        instrumentation.runOnMainSync(task)
        return try {
            task.get(10, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private class Fixture(context: Context, initializeEmulator: Boolean) {
        val finished = CountDownLatch(1)
        val transport = CaptureTransport()
        val controller = TerminalController(context, onFinished = { finished.countDown() })
        val session = TerminalSession(transport, 200, controller)

        init {
            if (initializeEmulator) session.updateSize(80, 24, 8, 16)
        }

        fun output(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.emulator.append(bytes, bytes.size)
        }
    }

    private class CaptureTransport : TerminalTransport {
        private var session: TerminalSession? = null
        private val bytes = ByteArrayOutputStream()
        private val chunks = mutableListOf<String>()

        override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            this.session = session
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            bytes.write(data, offset, count)
            chunks += String(data, offset, count, Charsets.UTF_8)
        }

        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit

        override fun close() {
            val current = session ?: return
            session = null
            current.onTransportFinished(0)
        }

        fun assertSent(vararg expectedChunks: String) {
            assertArrayEquals(expectedChunks.joinToString("").toByteArray(Charsets.UTF_8), bytes.toByteArray())
            assertEquals("No duplicate writes or extra input", expectedChunks.toList(), chunks)
        }

        fun clear() {
            bytes.reset()
            chunks.clear()
        }
    }
}
