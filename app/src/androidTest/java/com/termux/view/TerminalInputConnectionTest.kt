// type: 真实Android输入状态协议测试；不把同一回调串的去重作为正确目标。
package com.termux.view

import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.terminal.TerminalController
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TerminalInitialInputStateTest {
    @Test fun aFreshFullEditorReportsItsKnownEmptySelection() = main {
        val fixture = MemoryView()
        try {
            val info = EditorInfo()
            fixture.view.onCreateInputConnection(info)
            assertEquals("Known initial caret must not be -1", 0, info.initialSelStart)
            assertEquals(0, info.initialSelEnd)
        } finally { fixture.close() }
    }
}

@RunWith(AndroidJUnit4::class)
class TerminalInputConnectionContractTest {
    @Test fun composingAndSelectionUpdatesReportActualRanges() = main {
        val fixture = ReporterFixture()
        val c = fixture.connection
        c.setComposingText("h", 1)
        assertEquals(listOf(1, 1, 0, 1), fixture.reports.last())
        c.setComposingText("hello", 1)
        assertEquals(listOf(5, 5, 0, 5), fixture.reports.last())
        c.setSelection(2, 3)
        assertEquals(listOf(2, 3, 0, 5), fixture.reports.last())
        c.setComposingRegion(0, 2)
        assertEquals(listOf(2, 3, 0, 2), fixture.reports.last())
        c.finishComposingText()
        assertEquals(listOf(2, 3, -1, -1), fixture.reports.last())
    }

    @Test fun nestedBatchesPublishOnlyTheFinalState() = main {
        val f = ReporterFixture(); val c = f.connection
        c.beginBatchEdit(); c.beginBatchEdit()
        c.setComposingText("hello", 1); c.setSelection(1, 2)
        assertTrue(f.reports.isEmpty())
        assertTrue(c.endBatchEdit())
        assertTrue(f.reports.isEmpty())
        assertFalse(c.endBatchEdit())
        assertEquals(listOf(listOf(1, 2, 0, 5)), f.reports)
    }

    @Test fun clearingAfterCommitReportsTheEmptyTerminalBufferNotAnIntermediateCaret() = main {
        val f = ReporterFixture(); val c = f.connection
        c.beginBatchEdit()
        c.commitText("hello", 1)
        checkNotNull(c.editable).clear()
        assertTrue(f.reports.isEmpty())
        c.endBatchEdit()
        // The final state equals the initial known state; no redundant notification is required.
        assertTrue(f.reports.isEmpty())
        c.setComposingText("next", 1)
        c.beginBatchEdit(); c.commitText("next", 1); checkNotNull(c.editable).clear(); c.endBatchEdit()
        assertEquals(listOf(0, 0, -1, -1), f.reports.last())
        assertEquals(2, f.reports.size)
    }

    @Test fun duplicateAndUnmatchedEndsDoNotInventUpdates() = main {
        val f = ReporterFixture(); val c = f.connection
        assertFalse(c.endBatchEdit())
        c.setSelection(0, 0)
        assertTrue(f.reports.isEmpty())
        c.setComposingText("x", 1)
        val count = f.reports.size
        c.setSelection(1, 1)
        c.beginBatchEdit(); c.endBatchEdit(); c.endBatchEdit()
        assertEquals(count, f.reports.size)
    }

    @Test fun inactiveAndReplacedConnectionsDoNotReportToTheCurrentEditor() = main {
        val f = ReporterFixture(); val c = f.connection
        f.active.set(false); c.setComposingText("hello", 1)
        assertTrue(f.reports.isEmpty())
        f.active.set(true); c.setSelection(5, 5)
        assertEquals(listOf(5, 5, 0, 5), f.reports.last())
        val count = f.reports.size
        f.view.mCurrentInputConnection = TerminalInputConnection(f.view, { _, _, _, _ -> error("Unexpected reporter") }, { true })
        c.setComposingText("hellos", 1)
        assertEquals(count, f.reports.size)
    }

    @Test fun closingDoesNotReportTransientCleanupRanges() = main {
        val f = ReporterFixture(); val c = f.connection
        c.setComposingText("hello", 1)
        val count = f.reports.size
        c.closeConnection()
        assertEquals(count, f.reports.size)
        c.setSelection(0, 0)
        assertEquals(count, f.reports.size)
    }

    @Test fun failedReportCanRetryWithoutBreakingTheEdit() = main {
        val view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext, null)
        val calls = mutableListOf<List<Int>>()
        var failures = 1
        val c = TerminalInputConnection(view, { s, e, a, b -> if (failures-- > 0) error("Test reporter failure") else calls.add(listOf(s, e, a, b)) }, { true })
        view.mCurrentInputConnection = c; c.initializeEditorInfo(EditorInfo())
        assertTrue(c.setComposingText("hello", 1))
        assertTrue(calls.isEmpty())
        assertTrue(c.setSelection(5, 5))
        assertEquals(listOf(5, 5, 0, 5), calls.single())
    }

    @Test fun actualViewRetainsCommitFinishOnlyAndIndependentRepeats() = main {
        val f = MemoryView()
        try {
            for (mode in KeyboardMode.entries) {
                f.controller.keyboardMode = mode
                val info = EditorInfo(); val c = checkNotNull(f.view.onCreateInputConnection(info))
                assertEquals(0, info.initialSelStart)
                val before = f.bytes.size()
                val beforeCharacters = f.bytes.toString("UTF-8").length
                for (s in listOf("h", "he", "hel", "hell", "hello")) c.setComposingText(s, 1)
                assertEquals(before, f.bytes.size())
                c.commitText("hello", 1)
                c.setComposingText("中文🚀", 1); c.finishComposingText()
                c.setComposingText("l", 1); c.finishComposingText()
                c.setComposingText("l", 1); c.finishComposingText()
                assertEquals("hello中文🚀ll", f.bytes.toString("UTF-8").substring(beforeCharacters))
            }
        } finally { f.close() }
    }

    @Test fun callbacksWithoutReplacementStillAppendRatherThanSilentlyDeduplicate() = main {
        val f = MemoryView()
        try {
            val c = checkNotNull(f.view.onCreateInputConnection(EditorInfo()))
            for (s in listOf("h", "he", "hel", "hell")) { c.setComposingText(s, 1); c.finishComposingText() }
            c.commitText("hello", 1)
            assertEquals("hhehelhellhello", f.bytes.toString("UTF-8"))
        } finally { f.close() }
    }

    private class ReporterFixture {
        val view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext, null)
        val reports = mutableListOf<List<Int>>()
        val active = AtomicBoolean(true)
        val connection = TerminalInputConnection(view, { s, e, a, b -> reports.add(listOf(s, e, a, b)); Unit }, { active.get() })
        init { view.mCurrentInputConnection = connection; connection.initializeEditorInfo(EditorInfo()) }
    }
}

private class MemoryView {
    val bytes = ByteArrayOutputStream()
    val controller = TerminalController(InstrumentationRegistry.getInstrumentation().targetContext).apply { keyboardMode = KeyboardMode.IME; cursorBlink = false }
    private val transport = object : TerminalTransport {
        override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
        override fun write(data: ByteArray, offset: Int, count: Int) { bytes.write(data, offset, count) }
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
        override fun close() { session.onTransportFinished(0) }
    }
    private val session: TerminalSession = TerminalSession(transport, 200, controller).apply { updateSize(80, 24, 8, 16) }
    val view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext, null).apply {
        setTerminalViewClient(controller); setTextSize(24); setTypeface(Typeface.MONOSPACE); layout(0, 0, 800, 500); attachSession(session)
    }
    init { controller.view = view }
    fun close() { session.finishIfRunning(); controller.view = null }
}

private fun main(action: () -> Unit) {
    val task = FutureTask(Callable { action() }); InstrumentationRegistry.getInstrumentation().runOnMainSync(task); task.get(10, TimeUnit.SECONDS)
}
