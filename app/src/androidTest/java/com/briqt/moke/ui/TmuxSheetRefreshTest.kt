package com.briqt.moke.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.R
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.ui.theme.MokeTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Window-attached production sheet/refresh control. No transport or remote mutation is started. */
@RunWith(AndroidJUnit4::class)
class TmuxSheetRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val refreshLabel get() = context.getString(R.string.tmux_refresh)
    private val checkingLabel get() = context.getString(R.string.tmux_checking)

    @Test fun initialCheckingRemainsVisibleAndReadyPollingDoesNotAnimateTheRefreshButton() = withSheet { fixture ->
        fixture.window.onActivity { fixture.state.value = TmuxUiState(phase = TmuxPhase.CHECKING) }
        awaitNode("initial checking") { it.text?.toString() == checkingLabel }
        fixture.window.onActivity { fixture.state.value = ready() }
        awaitNode("known row") { it.contentDescription?.toString() == "alpha" }
        for (reading in listOf(true, false, true, false)) {
            fixture.window.onActivity { fixture.state.value = ready().copy(refreshing = reading) }
            awaitRefresh("background read must be silent") { it.isEnabled && it.stateDescription.isNullOrEmpty() }
            awaitNode("known list survives polling") { it.contentDescription?.toString() == "beta" }
        }
        capture("background-stable.png")
    }

    @Test fun explicitRefreshShowsLocalProgressKeepsRowsAndRestoresTheButton() = withSheet { fixture ->
        awaitRefresh("ready refresh") { it.isEnabled }
        clickRefresh()
        awaitRefresh("explicit request is pending") { !it.isEnabled && it.stateDescription?.toString() == checkingLabel }
        awaitNode("rows remain during a user read") { it.contentDescription?.toString() == "alpha" }
        val bounds = Rect()
        awaitRefresh("refresh keeps its touch area") { node ->
            node.getBoundsInScreen(bounds)
            val minimum = (48 * context.resources.displayMetrics.density).toInt()
            bounds.width() >= minimum && bounds.height() >= minimum
        }
        assertEquals(1, fixture.calls)
        capture("explicit-refresh.png")
        fixture.window.onActivity { fixture.request.complete() }
        awaitRefresh("request completion clears progress") { it.isEnabled && it.stateDescription.isNullOrEmpty() }
        fixture.window.onActivity { fixture.state.value = ready().copy(refreshing = true) }
        awaitRefresh("later polling cannot revive the spinner") { it.isEnabled && it.stateDescription.isNullOrEmpty() }
        capture("completed.png")
    }

    @Test fun dismissingDuringRefreshDoesNotCancelTheReadOrLeaveALatchedIndicator() = withSheet { fixture ->
        awaitRefresh("ready refresh") { it.isEnabled }
        clickRefresh()
        awaitRefresh("pending read") { !it.isEnabled && it.stateDescription?.toString() == checkingLabel }
        val dismiss = awaitNode("sheet dismiss action") { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }
        }
        try { assertTrue(dismiss.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)) }
        finally { dismiss.recycle() }
        await("sheet dismissed while observation remains active") { !fixture.visible.value }
        assertTrue("Only the sheet waiter is cancelled", fixture.request.isActive)
        await("disposed feedback cleared") { !fixture.feedback.refreshing.value }
        fixture.window.onActivity { fixture.request.complete() }
        assertFalse(fixture.feedback.refreshing.value)
    }

    @Test fun anAlreadyCompletedRefreshCannotLeaveAWindowSpinner() = withSheet { fixture ->
        fixture.window.onActivity { fixture.request.complete() }
        awaitRefresh("ready refresh") { it.isEnabled }
        clickRefresh()
        await("fast request executed") { fixture.calls == 1 }
        awaitRefresh("fast completion") { it.isEnabled && it.stateDescription.isNullOrEmpty() }
        assertFalse(fixture.feedback.refreshing.value)
    }

    private class Fixture(val window: MokeActivityFixture, scope: CoroutineScope) {
        val feedback = TmuxRefreshFeedback(scope)
        val state = mutableStateOf(ready())
        val visible = mutableStateOf(true)
        val request = Job()
        @Volatile var calls = 0
    }

    private fun withSheet(test: (Fixture) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            MokeActivityFixture.open().use { window ->
                val fixture = Fixture(window, scope)
                window.onActivity { activity ->
                    activity.setContent {
                        MokeTheme {
                            if (fixture.visible.value) {
                                val refreshing by fixture.feedback.refreshing.collectAsState()
                                DisposableEffect(fixture.feedback) { onDispose { fixture.feedback.dispose() } }
                                TmuxSessionSheet(
                                    state = fixture.state.value.copy(refreshing = refreshing),
                                    targetLabel = "Component target",
                                    defaultName = "sample",
                                    onDismiss = { fixture.visible.value = false },
                                    onOpen = { error("This fixture cannot open a remote session") },
                                    onOpened = {},
                                    onNew = { error("This fixture cannot create a remote session") },
                                    onRefresh = { fixture.feedback.refresh { fixture.calls++; fixture.request } },
                                )
                            }
                        }
                    }
                }
                try { test(fixture) } catch (error: Throwable) {
                    capture("failure-${SystemClock.uptimeMillis()}.png")
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val report = StringBuilder().appendLine("calls=${fixture.calls} feedback=${fixture.feedback.refreshing.value} requestActive=${fixture.request.isActive}")
                    fun visit(node: AccessibilityNodeInfo) {
                        report.appendLine("text=${node.text} desc=${node.contentDescription} state=${node.stateDescription} enabled=${node.isEnabled} clickable=${node.isClickable}")
                        repeat(node.childCount) { index -> node.getChild(index)?.let { child -> try { visit(child) } finally { child.recycle() } } }
                    }
                    if (root != null) try { visit(root) } finally { root.recycle() }
                    val file = File(context.getExternalFilesDir(null), "tmux-refresh/failure-${SystemClock.uptimeMillis()}.txt")
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.writeText(report.toString())
                    throw error
                } finally {
                    window.onActivity { fixture.feedback.dispose(); fixture.request.cancel() }
                }
            }
        } finally { scope.cancel() }
    }

    private fun clickRefresh() {
        val label = awaitNode("refresh action") { it.contentDescription?.toString() == refreshLabel }
        val button = clickTarget(label)
        try { assertTrue(button.isEnabled); assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { button.recycle(); label.recycle() }
    }

    private fun awaitRefresh(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val node = awaitNode(label) {
            if (it.contentDescription?.toString() != refreshLabel) false else {
                val button = clickTarget(it)
                try { predicate(button) } finally { button.recycle() }
            }
        }
        node.recycle()
    }

    private fun clickTarget(label: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = AccessibilityNodeInfo.obtain(label)
        while (!current.isClickable) {
            val parent = current.parent ?: error("Refresh label has no clickable ancestor")
            current.recycle()
            current = parent
        }
        return current
    }

    private fun awaitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await(label) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            try { found = find(root, predicate) } finally { root.recycle() }
            found != null
        }
        return checkNotNull(found)
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try { find(child, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do { if (predicate()) return; SystemClock.sleep(25) } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out: $label")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("evidence") != "true") return
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Window screenshot unavailable")
        try {
            val file = File(context.getExternalFilesDir(null), "tmux-refresh/$name")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    companion object {
        private fun ready() = TmuxUiState(phase = TmuxPhase.READY, sessions = listOf(
            TmuxSession("\$1", "alpha", 1, 0, 1), TmuxSession("\$2", "beta", 1, 0, 2),
        ))
    }
}
