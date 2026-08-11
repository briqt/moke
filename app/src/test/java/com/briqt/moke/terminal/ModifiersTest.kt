package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifiersTest {

    @Test
    fun `三态循环 关到一次性到锁定再回关`() {
        var m = Modifiers()
        assertEquals(ModState.Off, m.ctrl)
        m = m.toggle(ModKind.Ctrl)
        assertEquals(ModState.Once, m.ctrl)
        m = m.toggle(ModKind.Ctrl)
        assertEquals(ModState.Locked, m.ctrl)
        m = m.toggle(ModKind.Ctrl)
        assertEquals(ModState.Off, m.ctrl)
    }

    @Test
    fun `一次性修饰用一次即熄灭`() {
        val m = Modifiers().toggle(ModKind.Ctrl)
        assertTrue(m.ctrlOn)
        assertFalse(m.consumeOnce().ctrlOn)
    }

    /** 锁定态正是"按住不放"：连发 Ctrl+C、连走光标都靠它。 */
    @Test
    fun `锁定态不被消费`() {
        val m = Modifiers().toggle(ModKind.Ctrl).toggle(ModKind.Ctrl)
        assertEquals(ModState.Locked, m.ctrl)
        assertEquals(ModState.Locked, m.consumeOnce().consumeOnce().ctrl)
    }

    @Test
    fun `多个修饰互不干扰 且只熄灭一次性的那些`() {
        val m = Modifiers()
            .toggle(ModKind.Ctrl)                      // Once
            .toggle(ModKind.Alt).toggle(ModKind.Alt)   // Locked
            .toggle(ModKind.Shift)                     // Once
        val after = m.consumeOnce()
        assertEquals(ModState.Off, after.ctrl)
        assertEquals(ModState.Locked, after.alt)
        assertEquals(ModState.Off, after.shift)
    }

    @Test
    fun `按当前修饰编码按键`() {
        val ctrl = Modifiers().toggle(ModKind.Ctrl)
        assertEquals("\u001b[1;5D", ctrl.encode(KeyId.Left))
        val shift = Modifiers().toggle(ModKind.Shift)
        assertEquals("\u001b[Z", shift.encode(KeyId.Tab))
        assertEquals("\t", Modifiers().encode(KeyId.Tab))
    }
}
