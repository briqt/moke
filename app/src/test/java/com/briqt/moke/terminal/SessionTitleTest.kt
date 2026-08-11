package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {

    private val base = "root@server"

    @Test
    fun `copy marker only appears while dynamic titles collide`() {
        val title = TermSession.composeTitle(useMosh = false, raw = "work", custom = null, base = base)
        assertEquals("work (2)", TermSession.disambiguateTitle(title, null, "(2)", hasCollision = true))
        assertEquals("work", TermSession.disambiguateTitle(title, null, "(2)", hasCollision = false))
    }

    @Test
    fun `manual title is never changed by copy marker`() {
        val title = TermSession.composeTitle(useMosh = false, raw = "work", custom = "API logs", base = base)
        assertEquals("API logs", TermSession.disambiguateTitle(title, "API logs", "(2)", hasCollision = true))
    }

    @Test
    fun `mosh prefix is removed before collision comparison`() {
        assertEquals(
            "root@server",
            TermSession.composeTitle(useMosh = true, raw = "[mosh] root@server", custom = null, base = base),
        )
    }

    /** 远端发空 OSC 时 mosh 只把 `[mosh] ` 前缀发过来，剥完是空串——必须回落基座，否则标题行整条空白。 */
    @Test
    fun `mosh prefix only title falls back to base`() {
        assertEquals(base, TermSession.composeTitle(useMosh = true, raw = "[mosh] ", custom = null, base = base))
        assertEquals(base, TermSession.composeTitle(useMosh = true, raw = "[mosh]", custom = null, base = base))
    }

    @Test
    fun `blank dynamic title falls back to base on ssh too`() {
        assertEquals(base, TermSession.composeTitle(useMosh = false, raw = "", custom = null, base = base))
        assertEquals(base, TermSession.composeTitle(useMosh = false, raw = "   ", custom = null, base = base))
    }

    @Test
    fun `custom title still wins over base fallback`() {
        assertEquals(
            "API logs",
            TermSession.composeTitle(useMosh = true, raw = "[mosh] ", custom = "API logs", base = base),
        )
    }
}
