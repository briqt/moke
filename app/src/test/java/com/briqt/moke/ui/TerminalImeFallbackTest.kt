package com.briqt.moke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalImeFallbackTest {
    private val cramped = TerminalInputBudget(52, 210, 164)
    private val restored = TerminalInputBudget(806, 210, 164)

    @Test fun `full footer is counted even if a constrained view would clip it`() {
        assertEquals(0, cramped.terminalHeight)
        assertFalse(cramped.sufficient)
        assertTrue(cramped.sufficientWithoutIme(754))
    }

    @Test fun `sufficient space includes the complete minimum terminal rows`() {
        assertTrue(TerminalInputBudget(374, 210, 164).sufficient)
        assertFalse(TerminalInputBudget(373, 210, 164).sufficient)
        assertFalse(TerminalInputBudget(0, 0, 0).sufficient)
    }

    @Test fun `keyboard request hides once until actual recovery then notifies once`() {
        val guard = TerminalImeFallback()
        assertTrue(guard.requestKeyboard())
        assertEquals(TerminalImeAction.HIDE, guard.update(754, true, cramped))
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped))
        assertFalse(guard.requestKeyboard())
        assertEquals(TerminalImeAction.NOTICE, guard.update(0, true, restored))
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, restored))
    }

    @Test fun `hidden inset before the next layout does not lose the recovery notice`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, cramped))
        assertEquals(TerminalImeAction.NOTICE, guard.update(0, true, restored))
    }

    @Test fun `closing popup focus loss does not forget the keyboard request`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, restored, windowFocused = false))
        assertEquals(TerminalImeAction.HIDE, guard.update(754, true, cramped, windowFocused = true))
    }

    @Test fun `retained IME on terminal entry acquires one fallback attempt`() {
        val guard = TerminalImeFallback()
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped, windowFocused = false))
        assertEquals(TerminalImeAction.HIDE, guard.update(754, true, cramped, windowFocused = true))
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped))
        assertEquals(TerminalImeAction.NOTICE, guard.update(0, true, restored))
    }

    @Test fun `normal landscape space is not limited by orientation`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        assertEquals(TerminalImeAction.NONE, guard.update(100, true, restored))
    }

    @Test fun `hardware input without an occluding IME never hides anything`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, cramped))
    }

    @Test fun `another editor window is not affected by an old terminal attempt`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        assertEquals(TerminalImeAction.NONE, guard.update(754, false, cramped))
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped, windowFocused = false))
    }

    @Test fun `a window that remains too small without IME does not loop hides`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        assertEquals(TerminalImeAction.NONE, guard.update(100, true, cramped))
    }

    @Test fun `IME reopening without a user request cannot create a show hide loop`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        guard.update(0, true, restored)
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped))
        assertTrue(guard.requestKeyboard())
        assertEquals(TerminalImeAction.HIDE, guard.update(754, true, cramped))
    }

    @Test fun `page leave during hide cancels the old notification`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        guard.update(754, false, cramped)
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, restored))
    }

    @Test fun `ignored hide requests never produce a false hidden message`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        repeat(10) { assertEquals(TerminalImeAction.NONE, guard.update(754, true, cramped)) }
    }

    @Test fun `a failed hide deadline releases explicit retry without claiming success`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        val attempt = guard.attempt
        guard.update(754, true, cramped)
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, restored))
        guard.abandonPendingHide(attempt)
        assertTrue(guard.requestKeyboard())
        assertEquals(TerminalImeAction.NONE, guard.update(754, true, restored))
    }

    @Test fun `zero occluding inset during animation is not yet proof that IME is hidden`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        assertEquals(TerminalImeAction.NONE, guard.update(0, true, restored, imeVisible = true))
        assertEquals(TerminalImeAction.NOTICE, guard.update(0, true, restored, imeVisible = false))
    }

    @Test fun `an old hide deadline cannot cancel a newer attempt`() {
        val guard = TerminalImeFallback()
        guard.requestKeyboard()
        val old = guard.attempt
        guard.update(754, true, cramped)
        guard.update(0, true, restored)
        guard.requestKeyboard()
        guard.update(754, true, cramped)
        guard.abandonPendingHide(old)
        assertFalse(guard.requestKeyboard())
        assertEquals(TerminalImeAction.NOTICE, guard.update(0, true, restored))
    }
}
