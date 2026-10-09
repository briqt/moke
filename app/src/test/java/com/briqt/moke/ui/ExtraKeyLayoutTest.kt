package com.briqt.moke.ui

import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.ModKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeyLayoutTest {

    @Test
    fun `空配置是默认两排 且行尾固定`() {
        val rows = ExtraKeyLayout.rows(null)
        assertEquals(rows, ExtraKeyLayout.rows(""))
        assertEquals(rows, ExtraKeyLayout.rows("   "))
        assertEquals(7, rows[0].size)
        assertEquals(7, rows[1].size)
        assertEquals(ACTION_PANEL, (rows[0].last() as ExtraKey.Action).id)
        assertEquals(ACTION_COMPOSER, (rows[1].last() as ExtraKey.Action).id)
        assertEquals(
            listOf("ESC", "CTRL", "ALT", "↑", "HOME", "END"),
            rows[0].dropLast(1).map { it.label },
        )
        assertEquals(
            listOf("TAB", "⇧TAB", "←", "↓", "→", "^C"),
            rows[1].dropLast(1).map { it.label },
        )
    }

    @Test
    fun `默认令牌来回写一致`() {
        val slots = ExtraKeyLayout.slots(ExtraKeyLayout.DEFAULT)
        assertEquals(ExtraKeyLayout.DEFAULT, ExtraKeyLayout.encode(slots))
        assertEquals(slots, ExtraKeyLayout.slots(ExtraKeyLayout.encode(slots)))
    }

    @Test
    fun `非法配置整表退回默认`() {
        val fallback = ExtraKeyLayout.slots(null)
        listOf(
            "nope",
            "esc,ctrl|tab",
            "esc,no-such,alt,up,home,end|tab,btab,left,down,right,cc",
            "esc,ctrl,alt,up,home,end|tab,btab,left,down,right",
            "esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc|extra",
            " esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc",
            "ESC,ctrl,alt,up,home,end|tab,btab,left,down,right,cc",
        ).forEach { bad ->
            assertEquals(bad, fallback, ExtraKeyLayout.slots(bad))
        }
    }

    @Test
    fun `换一格能存回去 行尾不动`() {
        val slots = ExtraKeyLayout.slots(null).map { it.toMutableList() }
        slots[0][0] = "shift"
        slots[1][5] = "f12"
        val stored = ExtraKeyLayout.encode(slots.map { it.toList() })
        val rows = ExtraKeyLayout.rows(stored)
        assertEquals(ModKind.Shift, (rows[0].first() as ExtraKey.Mod).kind)
        assertEquals("F12", rows[1][5].label)
        assertEquals(ACTION_PANEL, (rows[0].last() as ExtraKey.Action).id)
        assertEquals(ACTION_COMPOSER, (rows[1].last() as ExtraKey.Action).id)
        assertEquals(slots.map { it.toList() }, ExtraKeyLayout.slots(stored))
    }

    @Test
    fun `选择列表里的每个 id 都能变成键 且没有遗漏`() {
        val listed = ExtraKeyLayout.groups.flatMap { it.second }
        assertEquals(listed.distinct().size, listed.size)
        listed.forEach { id ->
            val key = ExtraKeyLayout.key(id)
            assertTrue(id, key.label.isNotEmpty())
            if (key is ExtraKey.Key) {
                assertTrue(id, key.key !is KeyId.Chars)
            }
        }
        assertEquals("\u0003", (ExtraKeyLayout.key("cc") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals("\u001b[Z", (ExtraKeyLayout.key("btab") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals("\u001b\r", (ExtraKeyLayout.key("altenter") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals((1..12).map { "F$it" }, (1..12).map { ExtraKeyLayout.label("f$it") })
    }
}
