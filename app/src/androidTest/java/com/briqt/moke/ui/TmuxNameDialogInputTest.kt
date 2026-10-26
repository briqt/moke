package com.briqt.moke.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.ui.theme.MokeTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real dialog/input component tests, not a connection journey or a remote-create authorization. */
@RunWith(AndroidJUnit4::class)
class TmuxNameDialogInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val evidenceEnabled = InstrumentationRegistry.getArguments().getString("evidence") == "true"

    @Test
    fun initialSelectionAndContinuousTypingRespectAManuallyMovedCaret() = withDialog { _, _ ->
        captureEvidence("initial-selection.png")
        instrumentation.sendStringSync("a")
        assertEditor("a", 1)
        instrumentation.sendStringSync("b")
        assertEditor("ab", 2)

        edit("move caret between letters") { setSelection(1, 1) }
        assertEditor("ab", 1)
        instrumentation.sendStringSync("c")
        assertEditor("acb", 2)
        captureEvidence("continuous-input.png")
    }

    @Test
    fun composingTextAndSelectionSurviveParentRefreshAndError() = withDialog { scenario, state ->
        edit("start Chinese composition") { setComposingText("中", 1) }
        assertEditor("中", 1)
        edit("extend Chinese composition") { setComposingText("中文", 1) }
        assertEditor("中文", 2)

        val error = "Component refresh error"
        scenario.onActivity {
            state.value = DialogState(
                initial = "new-background-default",
                ui = TmuxUiState(phase = TmuxPhase.ERROR, message = error),
            )
        }
        awaitNode("parent error rendered") { node -> node.text?.toString()?.takeIf { it == error } }
        assertEditor("中文", 2)

        // This must replace the composing range, not append to text whose composition was lost.
        edit("continue composition after refresh") { setComposingText("中文名", 1) }
        assertEditor("中文名", 3)
        edit("commit Chinese and emoji") { commitText("中文🚀", 1) }
        assertEditor("中文🚀", 4)
        edit("move caret inside committed text") { setSelection(1, 1) }
        assertEditor("中文🚀", 1)

        val refreshed = "Component refresh completed"
        scenario.onActivity {
            state.value = DialogState(
                initial = "another-background-default",
                ui = TmuxUiState(phase = TmuxPhase.READY, message = refreshed),
            )
        }
        awaitNode("second parent state rendered") { node -> node.text?.toString()?.takeIf { it == refreshed } }
        assertEditor("中文🚀", 1)
        edit("insert at the retained caret") { commitText("x", 1) }
        assertEditor("中x文🚀", 2)
        captureEvidence("composition-refresh.png")
    }

    private fun withDialog(
        test: (MokeActivityFixture, MutableState<DialogState>) -> Unit,
    ) {
        val state = mutableStateOf(DialogState())
        MokeActivityFixture.open().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MokeTheme {
                        TmuxNameDialog(
                            initial = state.value.initial,
                            state = state.value.ui,
                            onDismiss = {},
                            onSubmit = { throw AssertionError("This fixture has no remote-create permit") },
                            onSuccess = { throw AssertionError("This fixture must not submit") },
                        )
                    }
                }
            }
            assertEditor("moke", 0, 4)
            test(scenario, state)
        }
    }

    private fun edit(description: String, action: InputConnection.() -> Boolean) {
        onView(isRoot()).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription(): String = description

            override fun perform(uiController: UiController, view: View) {
                val editor = focusedEditorView(view)
                    ?: throw AssertionError("No focused text editor in the actual dialog root")
                val connection = editor.onCreateInputConnection(EditorInfo())
                    ?: throw AssertionError("The focused dialog editor supplied no InputConnection")
                assertTrue("InputConnection rejected: $description", connection.action())
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    /** Opt-in only: save a newly committed dialog frame, never just a matching accessibility tree. */
    private fun captureEvidence(fileName: String) {
        if (!evidenceEnabled) return
        val committed = CountDownLatch(1)
        val callback = Runnable { committed.countDown() }
        var observer: ViewTreeObserver? = null
        try {
            onView(isRoot()).inRoot(isDialog()).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isRoot()
                override fun getDescription(): String = "commit a fresh dialog frame for $fileName"

                override fun perform(uiController: UiController, view: View) {
                    assertTrue("Frame commit evidence requires hardware acceleration", view.isHardwareAccelerated)
                    val currentObserver = view.viewTreeObserver
                    assertTrue("Dialog ViewTreeObserver is not alive", currentObserver.isAlive)
                    observer = currentObserver
                    currentObserver.registerFrameCommitCallback(callback)
                    view.invalidate()
                }
            })
            // Wait on the test thread, leaving the UI/render threads free to submit the new frame.
            assertTrue("Dialog frame was not committed for $fileName", committed.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
            }
        }

        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("UiAutomation returned no screenshot for $fileName")
        try {
            val directory = File(instrumentation.targetContext.cacheDir, "tmux-name-evidence")
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

    private fun focusedEditorView(view: View): View? {
        if (view.isFocused && view.onCheckIsTextEditor()) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                focusedEditorView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun assertEditor(text: String, start: Int, end: Int = start) {
        val expected = EditorState(text, start, end)
        val actual = awaitNode("editor state $expected") { node ->
            if (node.isFocused && node.isEditable && node.className == "android.widget.EditText") {
                EditorState(node.text?.toString().orEmpty(), node.textSelectionStart, node.textSelectionEnd)
                    .takeIf { it == expected }
            } else null
        }
        assertEquals(expected, actual)
    }

    private fun <T : Any> awaitNode(description: String, read: (AccessibilityNodeInfo) -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                readNode(root, read)?.let { return it }
            }
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description in the active window")
    }

    @Suppress("DEPRECATION")
    private fun <T : Any> readNode(node: AccessibilityNodeInfo, read: (AccessibilityNodeInfo) -> T?): T? {
        try {
            read(node)?.let { return it }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> readNode(child, read)?.let { return it } }
            }
            return null
        } finally {
            node.recycle()
        }
    }

    private data class DialogState(val initial: String = "moke", val ui: TmuxUiState = TmuxUiState())
    private data class EditorState(val text: String, val start: Int, val end: Int)
}
