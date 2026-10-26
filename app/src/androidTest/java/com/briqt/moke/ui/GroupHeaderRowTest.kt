package com.briqt.moke.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.ui.theme.MokeTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Window-attached production headers and group reordering, without a Compose test dependency.
 * These assertions cover handle bounds, full accessibility names and real pointer dispatch.
 * Single-line ellipsis and the short-name/count appearance still need screenshot review.
 */
@RunWith(AndroidJUnit4::class)
class GroupHeaderRowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val evidenceEnabled = InstrumentationRegistry.getArguments().getString("evidence") == "true"
    private val names = listOf(
        "production-services-with-a-very-long-unbroken-ASCII-group-name".repeat(3),
        "用于验证窄屏分组标题不会挤压排序手柄的中文名称".repeat(3),
        "/workspace/projects/mobile/terminal/session-groups/very-long-directory-name".repeat(3),
        "Dev",
    )

    @Test
    fun narrowHeadersKeepIndependentHandlesAndFullAccessibilityNames() {
        withFixture(names, fontScale = 1f) { fixture ->
            assertHandleBounds(fixture)
            assertFullNamesAccessible(names)
            captureEvidence("normal.png")
        }
    }

    @Test
    fun largeFontKeepsIndependentHandlesAndFullAccessibilityNames() {
        withFixture(names, fontScale = 2f) { fixture ->
            assertHandleBounds(fixture)
            assertFullNamesAccessible(names)
            captureEvidence("large-font.png")
        }
    }

    @Test
    fun longPressOnHandleChangesGroupOrderWithoutCollapsing() {
        withFixture(names.take(2), fontScale = 2f) { fixture ->
            assertHandleBounds(fixture)
            onUi("Long-press the actual handle and drag it onto the next group") { ui, view ->
                val start = fixture.handles.getValue("group-0").center.onScreen(view)
                val end = fixture.handles.getValue("group-1").center.onScreen(view)
                touch(ui, start, end, ViewConfiguration.getLongPressTimeout().toLong() + 150)
            }
            awaitUi("The drag must commit a different group order") {
                fixture.reorderCalls == 1 && fixture.order == listOf("group-1", "group-0") &&
                    fixture.headers.getValue("group-1").top < fixture.headers.getValue("group-0").top
            }
            onUi("Check that the drag did not toggle either group") { _, _ ->
                assertTrue("Dragging must not invoke the parent Surface click", fixture.toggledKeys.isEmpty())
                assertTrue("Both groups must remain expanded", fixture.collapsed.isEmpty())
            }
            assertHandleBounds(fixture)
            captureEvidence("dragged.png")
        }
    }

    @Test
    fun tappingHeaderBodyStillTogglesWithoutReordering() {
        withFixture(listOf("Dev", "Test"), fontScale = 1f) { fixture ->
            onUi("Tap the header body away from the handle") { ui, view ->
                val header = fixture.headers.getValue("group-0")
                val handle = fixture.handles.getValue("group-0")
                val point = Offset((header.left + handle.left) / 2f, header.center.y).onScreen(view)
                touch(ui, point)
            }
            awaitUi("The header body must still collapse its group") { "group-0" in fixture.collapsed }
            onUi("Check that tapping did not reorder") { _, _ ->
                assertEquals(listOf("group-0"), fixture.toggledKeys)
                assertEquals(listOf("group-0", "group-1"), fixture.order)
                assertEquals(0, fixture.reorderCalls)
            }
        }
    }

    private fun withFixture(names: List<String>, fontScale: Float, block: (Fixture) -> Unit) {
        val groups = names.mapIndexed { index, name ->
            ReorderGroup("group-$index", listOf("item-$index"), label = name)
        }
        val fixture = Fixture(groups.map { it.key })
        MokeActivityFixture.open().use { activityFixture ->
            activityFixture.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    // Only this composition changes font scale; never change device settings.
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                    ) {
                        MokeTheme {
                            SideEffect { fixture.density = density.density }
                            var currentGroups by remember { mutableStateOf(groups) }
                            var collapsed by remember { mutableStateOf(emptySet<String>()) }
                            Box(
                                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                                contentAlignment = Alignment.Center,
                            ) {
                                GroupedReorderableList(
                                    groups = currentGroups,
                                    itemKey = { it },
                                    collapsed = collapsed,
                                    onReorderGroups = { order ->
                                        val byKey = currentGroups.associateBy { it.key }
                                        currentGroups = order.map { byKey.getValue(it) }
                                        fixture.order = order
                                        fixture.reorderCalls++
                                    },
                                    onReorderItems = { _, _ -> fail("A header drag must not reorder group items") },
                                    modifier = Modifier.width(240.dp).height(420.dp).onGloballyPositioned {
                                        fixture.listBounds = it.unclippedBoundsInWindow()
                                    },
                                    header = { group, dragging, handle ->
                                        Box(Modifier.fillMaxWidth().onGloballyPositioned {
                                            fixture.headers[group.key] = it.unclippedBoundsInWindow()
                                        }) {
                                            GroupHeaderRow(
                                                name = group.label,
                                                count = group.items.size,
                                                collapsed = group.key in collapsed,
                                                onToggle = {
                                                    fixture.toggledKeys += group.key
                                                    collapsed = if (group.key in collapsed) {
                                                        collapsed - group.key
                                                    } else {
                                                        collapsed + group.key
                                                    }
                                                    fixture.collapsed = collapsed
                                                },
                                                dragHandle = handle.onGloballyPositioned {
                                                    fixture.handles[group.key] = it.unclippedBoundsInWindow()
                                                },
                                                dragging = dragging,
                                            )
                                        }
                                    },
                                ) { _, _, _ -> Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }
            }
            awaitUi("Production headers and handles must be laid out") {
                fixture.listBounds != null && fixture.density > 0f &&
                    fixture.headers.keys.containsAll(fixture.order) &&
                    fixture.handles.keys.containsAll(fixture.order)
            }
            block(fixture)
        }
    }

    private fun assertHandleBounds(fixture: Fixture) {
        onUi("Check actual handle bounds in each narrow production header") { _, _ ->
            val list = checkNotNull(fixture.listBounds)
            val minimum = 48f * fixture.density
            val tolerance = 1f // Layout rounds dp to whole pixels.
            fixture.order.forEach { key ->
                val header = fixture.headers.getValue(key)
                val handle = fixture.handles.getValue(key)
                assertTrue("$key must be visible inside the list", list.contains(header, tolerance))
                assertTrue("$key handle must stay inside its header", header.contains(handle, tolerance))
                assertTrue("$key handle width must be at least 48dp", handle.width + tolerance >= minimum)
                assertTrue("$key handle height must be at least 48dp", handle.height + tolerance >= minimum)
                assertEquals("$key handle must remain at the trailing edge", header.right, handle.right, tolerance)
            }
        }
    }

    /** With -e evidence true, save a newly committed Activity frame rather than just an idle tree. */
    private fun captureEvidence(fileName: String) {
        if (!evidenceEnabled) return
        val committed = CountDownLatch(1)
        val callback = Runnable { committed.countDown() }
        var observer: ViewTreeObserver? = null
        try {
            onUi("Commit a fresh Activity root frame for $fileName") { _, view ->
                assertTrue("Frame commit evidence requires hardware acceleration", view.isHardwareAccelerated)
                val currentObserver = view.viewTreeObserver
                assertTrue("Activity ViewTreeObserver is not alive", currentObserver.isAlive)
                observer = currentObserver
                currentObserver.registerFrameCommitCallback(callback)
                view.invalidate()
            }
            // Wait off the main thread so the UI/render threads can submit this frame.
            assertTrue("Activity frame was not committed for $fileName", committed.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
            }
        }

        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("UiAutomation returned no screenshot for $fileName")
        try {
            val directory = File(instrumentation.targetContext.cacheDir, "group-header-evidence")
            assertTrue("Cannot create evidence directory", directory.isDirectory || directory.mkdirs())
            val output = File(directory, fileName)
            output.outputStream().use { stream ->
                assertTrue("Cannot encode screenshot $fileName", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("Screenshot file is empty: $fileName", output.isFile && output.length() > 0)
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertFullNamesAccessible(names: List<String>) {
        var texts = emptyList<String>()
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) texts = accessibilityTexts(root)
            if (names.all { name -> texts.any { name in it } }) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Accessibility must retain every full, unellipsized group name; found $texts",
            names.all { name -> texts.any { name in it } })
    }

    @Suppress("DEPRECATION")
    private fun accessibilityTexts(node: AccessibilityNodeInfo): List<String> {
        return try {
            buildList {
                node.text?.let { add(it.toString()) }
                node.contentDescription?.let { add(it.toString()) }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { addAll(accessibilityTexts(it)) }
                }
            }
        } finally {
            node.recycle()
        }
    }

    private fun awaitUi(message: String, condition: () -> Boolean) {
        onUi(message) { ui, _ ->
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) {
                ui.loopMainThreadForAtLeast(16)
            }
            assertTrue(message, condition())
        }
    }

    private fun onUi(description: String, action: (UiController, View) -> Unit) {
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription(): String = description
            override fun perform(uiController: UiController, view: View) = action(uiController, view)
        })
    }

    private fun touch(ui: UiController, start: Offset, end: Offset = start, holdMs: Long = 30) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Offset) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Touch injection must succeed", ui.injectMotionEvent(event))
            } finally {
                event.recycle()
            }
        }
        var last = start
        var released = false
        send(MotionEvent.ACTION_DOWN, start)
        try {
            ui.loopMainThreadForAtLeast(holdMs)
            if (start != end) {
                for (step in 1..12) {
                    last = start + (end - start) * (step / 12f)
                    send(MotionEvent.ACTION_MOVE, last)
                    // Allow the real long-press detector, recomposition and list layout to advance.
                    ui.loopMainThreadForAtLeast(20)
                }
            }
            send(MotionEvent.ACTION_UP, end)
            released = true
            ui.loopMainThreadUntilIdle()
        } finally {
            if (!released) send(MotionEvent.ACTION_CANCEL, last)
        }
    }

    private fun Offset.onScreen(root: View): Offset {
        val screen = IntArray(2)
        val window = IntArray(2)
        root.getLocationOnScreen(screen)
        root.getLocationInWindow(window)
        return this + Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }

    private fun LayoutCoordinates.unclippedBoundsInWindow(): Rect {
        val position = positionInWindow()
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    private fun Rect.contains(other: Rect, tolerance: Float): Boolean =
        other.left >= left - tolerance && other.top >= top - tolerance &&
            other.right <= right + tolerance && other.bottom <= bottom + tolerance

    private class Fixture(var order: List<String>) {
        var density = 0f
        var listBounds: Rect? = null
        val headers = mutableMapOf<String, Rect>()
        val handles = mutableMapOf<String, Rect>()
        val toggledKeys = mutableListOf<String>()
        var collapsed = emptySet<String>()
        var reorderCalls = 0
    }
}
