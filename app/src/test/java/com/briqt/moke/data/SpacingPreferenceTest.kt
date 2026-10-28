package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 间距读取兼容：保留支持的值，不将旧越界偏好伪装成端点选择。 */
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
        assertEquals(0.8f, SettingsStore.sanitizeSpacing(0.8f))
    }

    @Test
    fun `a value stored under the old wider range falls back to neutral, not to the slider end`() {
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
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.NaN))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.POSITIVE_INFINITY))
        assertEquals(SettingsStore.DEFAULT_SPACING, SettingsStore.sanitizeSpacing(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `whatever is on disk, the effective value is one the slider can show`() {
        val stored = listOf<Float?>(-1f, 0f, 0.5f, 0.7f, 1f, 1.3f, 1.6f, 100f, Float.NaN, Float.POSITIVE_INFINITY, null)
        for (v in stored) {
            val effective = SettingsStore.sanitizeSpacing(v)
            assertTrue("$v -> $effective", effective in SettingsStore.MIN_SPACING..SettingsStore.MAX_SPACING)
        }
    }

    @Test
    fun `supported non-step values are not rounded or rewritten`() {
        assertEquals(1.25f, SettingsStore.sanitizeSpacing(1.25f))
        assertEquals(0.75f, SettingsStore.sanitizeSpacing(0.75f))
    }
}
