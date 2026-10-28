package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行距/字间距的持久化值怎么规整。
 *
 * 这两个值直接乘进终端行高（`ceil(getFontSpacing() * 行距)`），所以「夹到滑轨端点」和「回到默认」
 * 不是同一种视觉效果：前者会让终端永远比默认松一截，而滑轨上显示的是个合法数字，用户看不出异常。
 * v0.1.3 把范围从 0.8–1.6 收窄到 0.7–1.3 时正是踩了这一点——旧值 1.5 被夹成 1.3（滑轨最右端），
 * 表现成「终端在 Y 轴被拉伸」，排查时却找不到任何渲染问题。
 */
class SpacingPreferenceTest {

    @Test
    fun `unset falls back to the default`() {
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(null))
    }

    @Test
    fun `values inside the current range are kept exactly`() {
        assertEquals(0.7f, SettingsStore.sanitizeSpacing(0.7f))
        assertEquals(0.8f, SettingsStore.sanitizeSpacing(0.8f))
        assertEquals(0.9f, SettingsStore.sanitizeSpacing(0.9f))
        assertEquals(1.0f, SettingsStore.sanitizeSpacing(1.0f))
        assertEquals(1.1f, SettingsStore.sanitizeSpacing(1.1f))
        assertEquals(1.3f, SettingsStore.sanitizeSpacing(1.3f))
    }

    @Test
    fun `the old lower bound is still a valid preference`() {
        // 0.8 是收窄前的最紧一档，现在仍在范围内：不能被当成陈旧值抹掉。
        assertEquals(0.8f, SettingsStore.sanitizeSpacing(0.8f))
    }

    @Test
    fun `a value stored under the old wider range falls back to neutral, not to the slider end`() {
        // 1.4–1.6 是 v0.1.3 之前允许的行距。夹到 MAX 会伪装成「用户自己把行距拉满」。
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(1.4f))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(1.5f))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(1.6f))
        assertNotEquals(SettingsStore.MAX_SPACING, SettingsStore.sanitizeSpacing(1.6f))
    }

    @Test
    fun `values outside the range and non-finite values fall back to the default`() {
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(0.69f))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(0f))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(-1f))
        // NaN 与 ±∞ 在 ClosedFloatingPointRange 里既不小于也不大于任何界，必须落到 else 分支。
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.NaN))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.POSITIVE_INFINITY))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `whatever is on disk, the effective value is one the slider can show`() {
        // 不变式：读取侧的结果必须落在滑轨范围内，否则滑轨缩略位置与实际行高会对不上。
        val stored = listOf<Float?>(-1f, 0f, 0.5f, 0.7f, 1f, 1.3f, 1.6f, 100f, Float.NaN, Float.POSITIVE_INFINITY, null)
        for (v in stored) {
            val effective = SettingsStore.sanitizeSpacing(v)
            assertTrue("$v -> $effective", effective in SettingsStore.MIN_SPACING..SettingsStore.MAX_SPACING)
        }
    }
}
