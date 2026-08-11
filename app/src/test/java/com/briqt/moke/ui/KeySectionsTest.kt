package com.briqt.moke.ui

import com.briqt.moke.terminal.Modifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板/常驻两排的键表是手写的，这里钉住"每个键都真的能发出字节、排布不超宽"。 */
class KeySectionsTest {

    private val plain = Modifiers()

    private fun allKeys(rows: List<List<ExtraKey>>) = rows.flatten()

    @Test
    fun `每个普通键都能编码出非空字节`() {
        val rows = KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS
        allKeys(rows).filterIsInstance<ExtraKey.Key>().forEach { key ->
            assertTrue("按键 ${key.label} 编码为空", plain.encode(key.key).isNotEmpty())
        }
    }

    /** 键均分宽度不滚动：一排超过 7 个就会挤到看不清。 */
    @Test
    fun `每排不超过 7 个键`() {
        (KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS).forEach { row ->
            assertTrue("一排 ${row.size} 个键，超过 7", row.size <= 7)
            assertTrue("空行", row.isNotEmpty())
        }
    }

    @Test
    fun `常驻两排保留更多键与文本段入口`() {
        val actions = allKeys(DEFAULT_EXTRA_KEYS).filterIsInstance<ExtraKey.Action>().map { it.id }
        assertTrue(ACTION_PANEL in actions)
        assertTrue(ACTION_COMPOSER in actions)
    }

    @Test
    fun `功能键分段覆盖 F1 到 F12`() {
        val fnSection = KEY_SECTIONS.first { section ->
            allKeys(section.rows).any { it.label == "F1" }
        }
        val labels = allKeys(fnSection.rows).map { it.label }
        assertEquals((1..12).map { "F$it" }, labels)
    }
}
