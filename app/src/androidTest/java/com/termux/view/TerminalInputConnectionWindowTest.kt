package com.termux.view

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.terminal.TerminalController
import com.briqt.moke.ui.MokeActivityFixture
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Real focused window and system IME, with a memory transport and no remote commands. */
@RunWith(AndroidJUnit4::class)
class TerminalInputConnectionWindowTest {
    @Test fun liveEditorKeepsCompositionUntilCommitAndPreservesFinishOnlyInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = ByteArrayOutputStream()
        MokeActivityFixture.open().use { fixture ->
            lateinit var view: TerminalView
            lateinit var session: TerminalSession
            lateinit var controller: TerminalController
            lateinit var connection: TerminalInputConnection
            fixture.onActivity { activity ->
                controller = TerminalController(activity).apply { keyboardMode = KeyboardMode.IME; cursorBlink = false }
                val transport = object : TerminalTransport {
                    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
                    override fun write(data: ByteArray, offset: Int, count: Int) { output.write(data, offset, count) }
                    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
                    override fun close() { session.onTransportFinished(0) }
                }
                session = TerminalSession(transport, 200, controller).apply { updateSize(80, 24, 8, 16) }
                view = TerminalView(activity, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTerminalViewClient(controller)
                    setTextSize(24)
                    setTypeface(Typeface.MONOSPACE)
                    attachSession(session)
                }
                controller.view = view
                activity.findViewById<ViewGroup>(android.R.id.content).addView(view, ViewGroup.LayoutParams(800, 500))
                view.requestFocus()
            }
            instrumentation.waitForIdleSync()
            fixture.onActivity { activity ->
                view.requestFocus()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.restartInput(view)
                imm.showSoftInput(view, 0)
            }
            instrumentation.waitForIdleSync()
            try {
                fixture.onActivity { activity ->
                    check(view.hasFocus() && view.hasWindowFocus() && view.isAttachedToWindow)
                    check((activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(view))
                    val info = EditorInfo()
                    connection = checkNotNull(view.onCreateInputConnection(info)) as TerminalInputConnection
                    assertEquals(0, info.initialSelStart)
                    assertEquals(0, info.initialSelEnd)
                    connection.beginBatchEdit()
                    for (text in listOf("h", "he", "hel", "hell", "hello")) connection.setComposingText(text, 1)
                    connection.endBatchEdit()
                    assertEquals("hello", connection.getTextBeforeCursor(20, 0)?.toString())
                    assertEquals("", output.toString("UTF-8"))
                }
                instrumentation.waitForIdleSync()
                fixture.onActivity { _ ->
                    assertEquals("", output.toString("UTF-8"))
                    assertTrue(connection.commitText("hello", 1))
                    assertEquals("hello", output.toString("UTF-8"))
                    assertEquals("", connection.getTextBeforeCursor(20, 0)?.toString())
                    assertTrue(connection.setComposingText("中文🚀", 1))
                    assertTrue(connection.finishComposingText())
                    assertTrue(connection.finishComposingText())
                    assertTrue(connection.commitText("l", 1))
                    assertTrue(connection.commitText("l", 1))
                    assertEquals("hello中文🚀ll", output.toString("UTF-8"))
                }
            } finally {
                fixture.onActivity { activity ->
                    activity.findViewById<ViewGroup>(android.R.id.content).removeView(view)
                    session.finishIfRunning()
                    controller.view = null
                }
            }
        }
    }
}
