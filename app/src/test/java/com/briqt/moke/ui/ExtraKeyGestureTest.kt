package com.briqt.moke.ui

import com.briqt.moke.ui.ExtraKeyGestureTracker.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeyGestureTest {

    private fun tracker(
        hasSecondary: Boolean = false,
        hasLongPress: Boolean = false,
        isRepeatable: Boolean = false,
    ) = ExtraKeyGestureTracker(
        widthPx = 100f,
        heightPx = 68f,
        boundsSlopPx = 24f,
        swipeTriggerPx = 28f,
        swipeArmYMaxPx = -16f,
        swipeArmXMarginPx = 48f,
        hasSecondary = hasSecondary,
        hasLongPress = hasLongPress,
        isRepeatable = isRepeatable,
        longPressTimeoutMs = 400L,
        repeatInitialMs = 400L,
        repeatIntervalMs = 60L,
    )

    @Test
    fun `普通点击触发一次主动作 滑出边界后松手取消`() {
        val tap = tracker()
        tap.onDown(50f, 34f, nowMs = 1_000L)
        assertEquals(Effect.None, tap.onMove(52f, 35f))
        assertEquals(Effect.FirePrimary, tap.onUp())

        val slideOut = tracker()
        slideOut.onDown(50f, 34f, nowMs = 1_000L)
        assertEquals(Effect.LeftBounds, slideOut.onMove(50f, 100f))
        assertEquals(Effect.None, slideOut.onUp())
    }

    @Test
    fun `可连发键在手指轻微抖动下稳定进入400ms首次连发与60ms周期 且松手不重发`() {
        val repeat = tracker(hasSecondary = true, isRepeatable = true)
        repeat.onDown(50f, 34f, nowMs = 1_000L)

        // 模拟真实触摸屏静止按住时的亚像素/微抖动事件，不能重置 400ms 初始连发计时
        for (t in listOf(1_050L, 1_150L, 1_250L, 1_390L)) {
            assertEquals(Effect.None, repeat.onTimer(t))
            assertEquals(Effect.None, repeat.onMove(51.5f, 32.5f))
        }

        // 到达 400ms：立即触发第一次连发并锁定，后续即使上滑也不切进副动作
        assertEquals(Effect.FirePrimary, repeat.onTimer(1_400L))
        assertTrue(repeat.holdTriggered)
        assertEquals(Effect.None, repeat.onTimer(1_459L))
        assertEquals(Effect.FirePrimary, repeat.onTimer(1_460L))
        assertEquals(Effect.FirePrimary, repeat.onTimer(1_520L))

        // 连发已经开始后上滑不再触发副动作；滑出边界则停止连发
        assertEquals(Effect.LeftBounds, repeat.onMove(50f, -35f))
        assertFalse(repeat.swipeMode)
        assertEquals(Effect.None, repeat.onTimer(1_600L))
        assertEquals(Effect.None, repeat.onUp())
    }

    @Test
    fun `上滑超过阈值触发副动作预览 回落键面或横向远移自动撤回且不误发主动作`() {
        val swipe = tracker(hasSecondary = true, isRepeatable = true)
        swipe.onDown(50f, 40f, nowMs = 2_000L)

        // 120ms 内上滑 30px（>= 28px 阈值）：接管为上滑副动作并停掉连发计时
        assertEquals(Effect.SetSwipeArmed(armed = true, enteredSwipe = true), swipe.onMove(50f, 10f))
        assertTrue(swipe.swipeMode)
        assertTrue(swipe.secondaryArmed)
        assertEquals(Effect.None, swipe.onTimer(2_500L))
        assertEquals(Effect.FireSecondary, swipe.onUp())

        // 上滑后再往下拖回键面（dy = -10px > -16px）：撤回预备态，松手安全取消（主/副动作均不触发）
        val cancelDown = tracker(hasSecondary = true, isRepeatable = true)
        cancelDown.onDown(50f, 40f, nowMs = 3_000L)
        assertEquals(Effect.SetSwipeArmed(armed = true, enteredSwipe = true), cancelDown.onMove(50f, 8f))
        assertEquals(Effect.SetSwipeArmed(armed = false, enteredSwipe = false), cancelDown.onMove(50f, 30f))
        assertFalse(cancelDown.secondaryArmed)
        assertEquals(Effect.None, cancelDown.onUp())

        // 撤回后需再次上滑跨过完整触发阈值（dy <= -28px）才重新进入预备态，避免 -16px 附近微抖闪烁
        val rearmAndSideCancel = tracker(hasSecondary = true)
        rearmAndSideCancel.onDown(50f, 40f, nowMs = 4_000L)
        assertEquals(Effect.SetSwipeArmed(armed = true, enteredSwipe = true), rearmAndSideCancel.onMove(50f, 8f))
        // 已预备时在 dy = -20px（<= -16px）保持预备不抖动
        assertEquals(Effect.None, rearmAndSideCancel.onMove(50f, 20f))
        assertTrue(rearmAndSideCancel.secondaryArmed)
        // 回落到 dy = -5px（> -16px）：撤回预备态
        assertEquals(Effect.SetSwipeArmed(armed = false, enteredSwipe = false), rearmAndSideCancel.onMove(50f, 35f))
        // 在 -16px 与 -28px 滞回区间内（dy = -22px、-27px）抖动保持撤回态，不反复弹泡震动
        assertEquals(Effect.None, rearmAndSideCancel.onMove(50f, 18f))
        assertEquals(Effect.None, rearmAndSideCancel.onMove(50f, 13f))
        assertFalse(rearmAndSideCancel.secondaryArmed)
        // 再次上滑达到完整阈值 dy = -28px（y = 12f）：重新进入预备态
        assertEquals(Effect.SetSwipeArmed(armed = true, enteredSwipe = false), rearmAndSideCancel.onMove(50f, 12f))
        assertTrue(rearmAndSideCancel.secondaryArmed)
        // 横向滑出过远同样撤回
        assertEquals(Effect.SetSwipeArmed(armed = false, enteredSwipe = false), rearmAndSideCancel.onMove(160f, 12f))
        assertEquals(Effect.None, rearmAndSideCancel.onUp())
    }

    @Test
    fun `修饰键点按切换单次 长按进入锁定且松手不再次翻转`() {
        val tapMod = tracker(hasLongPress = true)
        tapMod.onDown(50f, 34f, nowMs = 5_000L)
        assertEquals(Effect.None, tapMod.onTimer(5_200L))
        assertEquals(Effect.FirePrimary, tapMod.onUp())

        val holdMod = tracker(hasLongPress = true)
        holdMod.onDown(50f, 34f, nowMs = 6_000L)
        assertEquals(Effect.FireLongPress, holdMod.onTimer(6_400L))
        assertEquals(Effect.None, holdMod.onTimer(6_500L))
        assertEquals(Effect.None, holdMod.onUp())
    }
}
