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

    /** 收录标准：软键盘打得出的字面字符不占位（rc.3 砍掉整页符号后的回归闸门）。 */
    @Test
    fun `键表里没有字面字符键`() {
        val rows = KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS
        val chars = allKeys(rows).filterIsInstance<ExtraKey.Key>()
            .filter { it.key is com.briqt.moke.terminal.KeyId.Chars }
        assertTrue("这些键输入法本来就能打：${chars.map { it.label }}", chars.isEmpty())
    }

    /** 面板浮在常驻两排之上，同一个键出现两次只会让人不知道该按哪个。 */
    @Test
    fun `面板不与常驻两排重复`() {
        val resident = allKeys(DEFAULT_EXTRA_KEYS).filterIsInstance<ExtraKey.Key>().map { it.key }.toSet()
        val dup = allKeys(KEY_SECTIONS.flatMap { it.rows }).filterIsInstance<ExtraKey.Key>()
            .filter { it.key in resident }
        assertTrue("面板与常驻两排重复：${dup.map { it.label }}", dup.isEmpty())
    }

    /** 修饰键三态的高亮共用一份状态，同一个修饰键出现两处会各画各的。 */
    @Test
    fun `每个修饰键只出现一次`() {
        val mods = allKeys(KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS)
            .filterIsInstance<ExtraKey.Mod>().map { it.kind }
        assertEquals(mods.distinct().size, mods.size)
    }

    /** 倒 T 方向键：↑ 必须正对着 ↓，否则拇指要重新找位置。 */
    @Test
    fun `常驻两排是倒 T 方向键`() {
        assertEquals(7, DEFAULT_EXTRA_KEYS[0].size)
        assertEquals(7, DEFAULT_EXTRA_KEYS[1].size)
        assertEquals("↑", DEFAULT_EXTRA_KEYS[0][3].label)
        assertEquals(listOf("←", "↓", "→"), DEFAULT_EXTRA_KEYS[1].subList(2, 5).map { it.label })
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
