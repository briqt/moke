package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifiersTest {

    @Test
    fun `点一下打开一次性 再点一下关掉`() {
        var m = Modifiers()
        m = m.tap(ModKind.Ctrl)
        assertEquals(ModState.Once, m.ctrl)
        m = m.tap(ModKind.Ctrl)
        assertEquals(ModState.Off, m.ctrl)
    }

    @Test
    fun `按住进入锁定 再点一下关掉`() {
        var m = Modifiers().hold(ModKind.Ctrl)
        assertEquals(ModState.Locked, m.ctrl)
        m = m.hold(ModKind.Ctrl)
        assertEquals(ModState.Locked, m.ctrl)
        m = m.tap(ModKind.Ctrl)
        assertEquals(ModState.Off, m.ctrl)
    }

    @Test
    fun `一次性修饰用一次即熄灭`() {
        val m = Modifiers().tap(ModKind.Ctrl)
        assertTrue(m.ctrlOn)
        assertFalse(m.consumeOnce().ctrlOn)
    }

    /** 锁定态正是"按住不放"：连发 Ctrl+C、连走光标都靠它。 */
    @Test
    fun `锁定态不被消费`() {
        val m = Modifiers().hold(ModKind.Ctrl)
        assertEquals(ModState.Locked, m.ctrl)
        assertEquals(ModState.Locked, m.consumeOnce().consumeOnce().ctrl)
    }

    @Test
    fun `多个修饰互不干扰 且只熄灭一次性的那些`() {
        val m = Modifiers()
            .tap(ModKind.Ctrl)
            .hold(ModKind.Alt)
            .tap(ModKind.Shift)
        val after = m.consumeOnce()
        assertEquals(ModState.Off, after.ctrl)
        assertEquals(ModState.Locked, after.alt)
        assertEquals(ModState.Off, after.shift)
    }

    @Test
    fun `按当前修饰编码按键`() {
        val ctrl = Modifiers().tap(ModKind.Ctrl)
        assertEquals("\u001b[1;5D", ctrl.encode(KeyId.Left))
        val shift = Modifiers().tap(ModKind.Shift)
        assertEquals("\u001b[Z", shift.encode(KeyId.Tab))
        assertEquals("\t", Modifiers().encode(KeyId.Tab))
    }

    @Test
    fun `编码使用本次传入的光标应用模式`() {
        val mods = Modifiers()
        assertEquals("\u001b[A", mods.encode(KeyId.Up, cursorKeysApplication = false))
        assertEquals("\u001bOA", mods.encode(KeyId.Up, cursorKeysApplication = true))
        assertEquals("\u001b[A", mods.encode(KeyId.Up, cursorKeysApplication = false))
    }

    @Test
    fun `应用模式下一次性修饰仍只消费一次`() {
        val once = Modifiers().tap(ModKind.Ctrl).tap(ModKind.Alt).tap(ModKind.Shift)
        assertEquals("\u001b[1;8D", once.encode(KeyId.Left, cursorKeysApplication = true))
        val consumed = once.consumeOnce()
        assertEquals(Modifiers(), consumed)
        assertEquals("\u001bOD", consumed.encode(KeyId.Left, cursorKeysApplication = true))
    }

    @Test
    fun `应用模式下混合修饰消费后锁定态继续生效`() {
        val mixed = Modifiers().hold(ModKind.Ctrl).tap(ModKind.Alt).hold(ModKind.Shift)
        assertEquals("\u001b[1;8H", mixed.encode(KeyId.Home, cursorKeysApplication = true))
        val consumed = mixed.consumeOnce()
        assertEquals(Modifiers(ctrl = ModState.Locked, shift = ModState.Locked), consumed)
        assertEquals("\u001b[1;6H", consumed.encode(KeyId.Home, cursorKeysApplication = true))
        assertEquals(consumed, consumed.consumeOnce())
    }

    @Test
    fun `应用模式不改写宏且宏后仅消费一次性修饰`() {
        val mixed = Modifiers().tap(ModKind.Ctrl).hold(ModKind.Alt).tap(ModKind.Shift)
        assertEquals("\u001b[A", mixed.encode(KeyId.Macro("\u001b[A"), cursorKeysApplication = true))
        val consumed = mixed.consumeOnce()
        assertEquals(Modifiers(alt = ModState.Locked), consumed)
        assertEquals("\u001bb", consumed.encode(KeyId.Chars("b"), cursorKeysApplication = true))
        assertEquals("\u001b[1;3A", consumed.encode(KeyId.Up, cursorKeysApplication = true))
    }

    @Test
    fun `点一下不会把一次性变成锁定`() {
        val once = Modifiers().tap(ModKind.Alt)
        assertEquals(ModState.Once, once.alt)
        assertEquals(ModState.Off, once.tap(ModKind.Alt).alt)
        assertEquals(ModState.Locked, once.hold(ModKind.Alt).alt)
    }

    @Test
    fun `锁定后编码仍带修饰 且点另一个修饰不影响它`() {
        val locked = Modifiers().hold(ModKind.Ctrl)
        assertEquals("\u001b[1;5D", locked.encode(KeyId.Left))
        val withShift = locked.tap(ModKind.Shift)
        assertEquals(ModState.Locked, withShift.ctrl)
        assertEquals(ModState.Once, withShift.shift)
        assertEquals(ModState.Locked, withShift.consumeOnce().ctrl)
        assertEquals(ModState.Off, withShift.consumeOnce().shift)
        assertEquals(ModState.Locked, withShift.tap(ModKind.Alt).ctrl)
    }
}
