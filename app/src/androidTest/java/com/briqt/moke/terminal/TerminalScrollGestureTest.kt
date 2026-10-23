package com.briqt.moke.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import com.termux.view.TerminalView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android gesture detectors and production session/controller callbacks, without an Activity.
 * The view is laid out but not window-attached. Remote-scroll tests explicitly pump its pending
 * production frame; these tests do not claim to exercise window lifecycle, rendering or system IME.
 */
@RunWith(AndroidJUnit4::class)
class TerminalScrollGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        onMain { fixture = Fixture(instrumentation.targetContext) }
    }

    @After
    fun tearDown() {
        if (!::fixture.isInitialized) return
        onMain {
            fixture.view.mokeCancelScroll()
            fixture.view.setTerminalCursorBlinkerState(false, false)
            fixture.controller.onActivity = null
            fixture.controller.onKeyboardRequest = null
            fixture.controller.onFontSizeSp = null
            fixture.controller.view = null
            fixture.session.finishIfRunning()
        }
        await(fixture.finished, "Session did not finish after transport cleanup")
        onMain { assertFalse(fixture.session.isRunning) }
    }

    @Test
    fun mouseTrackingTapSendsExactlyOneClickAndRequestsKeyboard() {
        output("\u001b[?1000h\u001b[?1006h")

        touches(*tap().toTypedArray())
        onMain { assertClickCount(1) }
        awaitTapConfirmation()

        onMain { assertClickCount(1) }
    }

    @Test
    fun mouseTrackingDoubleTapSendsTwoClicksAndRequestsKeyboardForEachTap() {
        output("\u001b[?1000h\u001b[?1006h")
        val steps = tap() + tap(startMs = 110)

        touches(*steps.toTypedArray())
        onMain { assertClickCount(2) }
        awaitTapConfirmation()

        onMain { assertClickCount(2) }
    }

    @Test
    fun normalShellTapRequestsKeyboardWithoutSendingInput() {
        touches(*tap().toTypedArray())
        awaitTapConfirmation()
        onMain {
            assertEquals(1, fixture.keyboardRequests)
            assertEquals("Showing the keyboard must not type into the shell", "", fixture.transport.text())
        }
    }

    @Test
    fun mouseTrackingTapDoesNotAlsoOpenALink() {
        val row = "\r\n".repeat(4) + "https://example.test/path"
        output(row + "\u001b[?1000h\u001b[?1006h")
        var links = 0
        onMain { fixture.controller.onLinkTapped = { links++ } }
        touches(*tap().toTypedArray())
        awaitTapConfirmation()
        onMain {
            assertClickCount(1)
            assertEquals("The program owns its mouse click even over URL-shaped text", 0, links)
        }
    }

    @Test
    fun physicalMouseClickDoesNotRequestTheSoftKeyboard() {
        output("\u001b[?1000h\u001b[?1006h")
        onMain {
            val now = SystemClock.uptimeMillis()
            val pointer = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val point = arrayOf(MotionEvent.PointerCoords().apply {
                x = fixture.view.mRenderer.fontWidth * 3.5f
                y = fixture.view.getPointY(4) + fixture.view.mRenderer.fontLineSpacing / 2f
            })
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, 1, pointer, point,
                    0, if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
                    1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                try { assertTrue(fixture.view.onTouchEvent(event)) } finally { event.recycle() }
            }
            assertEquals("\u001b[<0;4;5M\u001b[<0;4;5m", fixture.transport.text())
            assertEquals(0, fixture.keyboardRequests)
        }
        awaitTapConfirmation()
        onMain { assertEquals(0, fixture.keyboardRequests) }
    }

    @Test
    fun horizontalSwipeDoesNotBecomeARemoteClick() {
        output("\u001b[?1000h\u001b[?1006h")
        val size = onMain { fixture.view.width.toFloat() to fixture.view.height.toFloat() }
        touches(
            step(0, MotionEvent.ACTION_DOWN, size.first * 0.2f to size.second * 0.5f),
            step(40, MotionEvent.ACTION_MOVE, size.first * 0.7f to size.second * 0.5f),
            step(80, MotionEvent.ACTION_UP, size.first * 0.7f to size.second * 0.5f),
            pumpRemoteFrames = true,
        )
        awaitTapConfirmation()
        onMain { assertClickCount(0) }
    }

    @Test
    fun draggingTheSecondTouchOfADoubleTapDoesNotClickAtItsEndpoint() {
        output("\u001b[?1000h\u001b[?1006h")
        val point = onMain {
            fixture.view.mRenderer.fontWidth * 3.5f to (fixture.view.getPointY(4) + fixture.view.mRenderer.fontLineSpacing / 2f)
        }
        val moved = point.first + fixture.dp(150) to point.second
        val steps = tap() + listOf(
            step(110, MotionEvent.ACTION_DOWN, point),
            step(150, MotionEvent.ACTION_MOVE, moved),
            step(190, MotionEvent.ACTION_UP, moved),
        )
        touches(*steps.toTypedArray(), pumpRemoteFrames = true)
        awaitTapConfirmation()
        onMain { assertClickCount(1) }
    }

    @Test
    fun cancelledMouseScrollDoesNotConsumeTheNextTap() {
        output("\u001b[?1000h\u001b[?1006h")

        touches(*dragAndCancel().toTypedArray(), pumpRemoteFrames = true)
        onMain {
            assertTrue("The cancelled sequence must have actually scrolled", fixture.transport.text().contains("\u001b[<64;"))
            fixture.transport.clear()
        }
        touches(*tap().toTypedArray())
        awaitTapConfirmation()

        onMain { assertClickCount(1) }
    }

    @Test
    fun longPressDoesNotBecomeAMouseClickOrKeyboardRequest() {
        output("\r\n".repeat(4) + "selectable content\u001b[?1000h\u001b[?1006h")
        val point = onMain {
            fixture.view.mRenderer.fontWidth * 3.5f to
                (fixture.view.getPointY(4) + fixture.view.mRenderer.fontLineSpacing / 2f)
        }
        touches(step(0, MotionEvent.ACTION_DOWN, point),
            step(ViewConfiguration.getLongPressTimeout().toLong() + 150, MotionEvent.ACTION_UP, point))
        awaitTapConfirmation()
        onMain {
            assertEquals("", fixture.transport.text())
            assertEquals(0, fixture.keyboardRequests)
        }
    }

    @Test
    fun asymmetricPinchChangesFontWithoutSendingWheelOrLastFingerTap() {
        output("\u001b[?1000h\u001b[?1006h")
        val fontSizes = mutableListOf<Float>()
        val initialFontSize = onMain {
            fixture.controller.onFontSizeSp = { fontSizes += it }
            fixture.controller.fontSizeSp
        }
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        fun point(x: Int, y: Int) = fixture.dp(x) to fixture.dp(y)
        val stationary = point(100, 100)

        touches(
            step(0, MotionEvent.ACTION_DOWN, stationary),
            step(30, pointerDown, stationary, point(240, 310)),
            step(70, MotionEvent.ACTION_MOVE, stationary, point(320, 430)),
            step(110, MotionEvent.ACTION_MOVE, stationary, point(410, 570)),
            step(150, MotionEvent.ACTION_MOVE, stationary, point(500, 710)),
            step(190, pointerUp, stationary, point(500, 710)),
            step(230, MotionEvent.ACTION_MOVE, point(100, 380)),
            step(260, MotionEvent.ACTION_UP, point(100, 380)),
            pumpRemoteFrames = true,
        )
        awaitTapConfirmation()

        onMain {
            assertTrue("Pinching must continue across the first font resize", fontSizes.size >= 2)
            assertTrue(fixture.controller.fontSizeSp > initialFontSize)
            assertEquals("A pinch must not also scroll the remote program", "", fixture.transport.text())
            assertEquals(0, fixture.keyboardRequests)
        }
    }

    @Test
    fun readingHistoryKeepsVisibleContentWhenNormalSessionOutputArrives() {
        val rows = onMain { fixture.view.mEmulator.mRows }
        output((0 until rows + 60).joinToString(separator = "", transform = { "line-${it.toString().padStart(3, '0')}\r\n" }))
        touches(*dragAndCancel().toTypedArray())
        val before = onMain {
            assertTrue("The gesture must leave the viewport in history", fixture.view.topRow < 0)
            fixture.view.topRow to visibleText()
        }

        // Use processToEmulator -> session Handler -> TerminalController.onTextChanged, not a
        // direct emulator append or the skip-scrolling overload of onScreenUpdated.
        output("\r\nnew-output-byte")

        onMain {
            assertEquals("One new history row must shift the reading anchor", before.first - 1, fixture.view.topRow)
            assertEquals("New output must not replace the content being read", before.second, visibleText())
            assertTrue(fixture.view.mEmulator.screen.transcriptText.contains("new-output-byte"))
            assertEquals("Reading history must not generate remote input", "", fixture.transport.text())
            assertEquals(0, fixture.keyboardRequests)
        }
    }

    @Test
    fun alternateScrollRequestRoutesArrowsEvenWithBracketedPasteEnabled() {
        output("\u001b[?1049h\u001b[?1l\u001b[?2004h")
        onMain {
            assertTrue(fixture.view.mEmulator.isAlternateBufferActive)
            assertTrue(fixture.view.mEmulator.isBracketedPasteMode)
        }
        touches(*dragAndCancel().toTypedArray(), pumpRemoteFrames = true)
        onMain {
            assertEquals("Bracketed paste alone must not imply arrow scrolling", "", fixture.transport.text())
        }

        output("\u001b[?1007h")
        touches(*dragAndCancel().toTypedArray(), pumpRemoteFrames = true)

        onMain {
            val actual = fixture.transport.text()
            val up = "\u001b[A"
            assertTrue("Explicit alternate scrolling must emit input", actual.isNotEmpty())
            assertEquals("The entire batch must consist only of Up keys", up.repeat(actual.length / up.length), actual)
            assertEquals(0, fixture.keyboardRequests)
        }
    }

    @Test
    fun remoteOwnedMouseInputRevealsTheCurrentScreenInsteadOfActingBehindHistory() {
        val rows = onMain { fixture.view.mEmulator.mRows }
        output((0 until rows + 30).joinToString("") { "history-$it\r\n" })
        onMain { fixture.view.setTopRow(-8) }
        output("\u001b[?1000;1006h")
        onMain { assertTrue(fixture.view.topRow < 0) }

        touches(*tap().toTypedArray())
        onMain {
            assertEquals(0, fixture.view.topRow)
            assertTrue(fixture.transport.text().contains("\u001b[<0;"))
            assertEquals(1, fixture.keyboardRequests)
            fixture.transport.clear()
            fixture.view.setTopRow(-8)
        }
        touches(*dragAndCancel().toTypedArray(), pumpRemoteFrames = true)
        onMain {
            assertEquals(0, fixture.view.topRow)
            assertTrue(fixture.transport.text().contains("\u001b[<64;"))
            assertEquals("Dragging after a tap must not request the keyboard again", 1, fixture.keyboardRequests)
        }
    }

    @Test
    fun bracketedPasteChangeDoesNotInterruptAnExistingWheelGesture() {
        output("\u001b[?1000;1006;2004h")
        val generation = onMain { fixture.view.mEmulator.inputModeGeneration }
        var firstFrameBytes = 0
        touches(
            *dragAndCancel().toTypedArray(),
            pumpRemoteFrames = true,
            afterStep = { index ->
                if (index == 1) {
                    firstFrameBytes = fixture.transport.text().length
                    val bytes = "\u001b[?2004l".toByteArray(Charsets.UTF_8)
                    fixture.session.processToEmulator(bytes, bytes.size)
                }
            },
        )
        onMain {
            assertTrue(firstFrameBytes > 0)
            assertTrue("Later drag frames must still reach the same scroll owner", fixture.transport.text().length > firstFrameBytes)
            assertEquals(generation, fixture.view.mEmulator.inputModeGeneration)
            assertFalse(fixture.view.mEmulator.isBracketedPasteMode)
        }
    }

    private fun assertClickCount(count: Int) {
        // tap() targets zero-based column 3, row 4, hence SGR coordinates 4;5.
        val click = "\u001b[<0;4;5M\u001b[<0;4;5m"
        assertEquals(click.repeat(count), fixture.transport.text())
        assertEquals("Each touch tap must request IME without duplicating its remote click", count, fixture.keyboardRequests)
    }

    private fun visibleText(): String {
        val view = fixture.view
        val emulator = view.mEmulator
        return emulator.screen.getSelectedText(0, view.topRow, emulator.mColumns - 1, view.topRow + emulator.mRows - 1)
    }

    private fun tap(startMs: Long = 0): List<TouchStep> = onMain {
        val view = fixture.view
        val point = view.mRenderer.fontWidth * 3.5f to (view.getPointY(4) + view.mRenderer.fontLineSpacing / 2f)
        listOf(step(startMs, MotionEvent.ACTION_DOWN, point), step(startMs + 30, MotionEvent.ACTION_UP, point))
    }

    private fun dragAndCancel(): List<TouchStep> = onMain {
        val x = fixture.view.width / 2f
        val height = fixture.view.height.toFloat()
        listOf(
            step(0, MotionEvent.ACTION_DOWN, x to height * 0.25f),
            step(40, MotionEvent.ACTION_MOVE, x to height * 0.45f),
            step(80, MotionEvent.ACTION_MOVE, x to height * 0.70f),
            step(110, MotionEvent.ACTION_CANCEL, x to height * 0.70f),
        )
    }

    private data class TouchStep(val delayMs: Long, val action: Int, val points: List<Pair<Float, Float>>)

    private fun step(delayMs: Long, action: Int, vararg points: Pair<Float, Float>) = TouchStep(delayMs, action, points.toList())

    /** Schedule real-time events on the main looper; never sleep or wait on that thread. */
    private fun touches(vararg steps: TouchStep, pumpRemoteFrames: Boolean = false, afterStep: ((Int) -> Unit)? = null) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val token = Any()
        onMain {
            val start = SystemClock.uptimeMillis()
            var downTime = start
            steps.forEachIndexed { index, touch ->
                mainHandler.postAtTime({
                    if (failure.get() == null) {
                        try {
                            val now = SystemClock.uptimeMillis()
                            if (touch.action == MotionEvent.ACTION_DOWN) downTime = now
                            val properties = Array(touch.points.size) { pointer ->
                                MotionEvent.PointerProperties().apply {
                                    id = pointer
                                    toolType = MotionEvent.TOOL_TYPE_FINGER
                                }
                            }
                            val coordinates = Array(touch.points.size) { pointer ->
                                MotionEvent.PointerCoords().apply {
                                    x = touch.points[pointer].first
                                    y = touch.points[pointer].second
                                    pressure = 1f
                                    size = 1f
                                }
                            }
                            val event = MotionEvent.obtain(
                                downTime, now, touch.action, touch.points.size, properties, coordinates,
                                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                            )
                            try {
                                assertTrue(fixture.view.onTouchEvent(event))
                                if (pumpRemoteFrames) pumpPendingScrollFrame()
                                afterStep?.invoke(index)
                            } finally {
                                event.recycle()
                            }
                        } catch (error: Throwable) {
                            failure.set(error)
                            completed.countDown()
                        }
                    }
                    if (index == steps.lastIndex) completed.countDown()
                }, token, start + touch.delayMs)
            }
        }
        try {
            await(completed, "Touch sequence did not complete")
        } finally {
            onMain { mainHandler.removeCallbacksAndMessages(token) }
        }
        failure.get()?.let { throw it }
    }

    private fun pumpPendingScrollFrame() {
        // postOnAnimation cannot run for an unattached view. Invoke only an actually queued frame,
        // without constructing private gesture objects or replacing the production scroll path.
        val posted = TerminalView::class.java.getDeclaredField("mScrollFramePosted").apply { isAccessible = true }
        if (!posted.getBoolean(fixture.view)) return
        val frame = TerminalView::class.java.getDeclaredField("mScrollFrame").apply { isAccessible = true }
            .get(fixture.view) as Runnable
        fixture.view.removeCallbacks(frame)
        frame.run()
    }

    private fun output(text: String) {
        val changed = CountDownLatch(1)
        onMain {
            fixture.controller.onActivity = { changed.countDown() }
            val bytes = text.toByteArray(Charsets.UTF_8)
            fixture.session.processToEmulator(bytes, bytes.size)
        }
        try {
            await(changed, "Normal session text-change callback did not arrive")
        } finally {
            // Also a barrier: onActivity runs before the controller updates the view.
            onMain { fixture.controller.onActivity = null }
        }
    }

    private fun awaitTapConfirmation() {
        val confirmed = CountDownLatch(1)
        val barrier = Runnable { confirmed.countDown() }
        mainHandler.postDelayed(barrier, ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        try {
            await(confirmed, "Main looper did not pass the double-tap timeout")
        } finally {
            mainHandler.removeCallbacks(barrier)
        }
    }

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(message, latch.await(10, TimeUnit.SECONDS))
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

    private class Fixture(context: Context) {
        private val density = context.resources.displayMetrics.density
        val finished = CountDownLatch(1)
        val transport = MemoryTransport()
        val controller = TerminalController(context, onFinished = { finished.countDown() })
        val session = TerminalSession(transport, 2_000, controller)
        val view = TerminalView(context, null)
        var keyboardRequests = 0

        init {
            controller.view = view
            controller.cursorBlink = false
            controller.fontSizeSp = 16f
            controller.onKeyboardRequest = { keyboardRequests++; false }
            view.setTerminalViewClient(controller)
            view.setTextSize(dp(16).toInt())
            view.layout(0, 0, dp(600).toInt(), dp(800).toInt())
            view.attachSession(session)
            assertNotNull("Nonzero layout and attached session must initialize the emulator", view.mEmulator)
            assertFalse("This fixture intentionally has no attached window", view.isAttachedToWindow)
        }

        fun dp(value: Int): Float = value * density
    }

    /** Synchronous in-memory transport: no sockets, threads, credentials or shared state. */
    private class MemoryTransport : TerminalTransport {
        private var session: TerminalSession? = null
        private val writes = ByteArrayOutputStream()

        override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            this.session = session
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            writes.write(data, offset, count)
        }

        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit

        override fun close() {
            val current = session ?: return
            session = null
            current.onTransportFinished(0)
        }

        fun text(): String = writes.toByteArray().toString(Charsets.UTF_8)
        fun clear() = writes.reset()
    }
}
