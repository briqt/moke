package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {

    @Test
    fun `copy marker only appears while dynamic titles collide`() {
        val base = TermSession.composeTitle(useMosh = false, raw = "work", custom = null)
        assertEquals("work (2)", TermSession.disambiguateTitle(base, null, "(2)", hasCollision = true))
        assertEquals("work", TermSession.disambiguateTitle(base, null, "(2)", hasCollision = false))
    }

    @Test
    fun `manual title is never changed by copy marker`() {
        val base = TermSession.composeTitle(useMosh = false, raw = "work", custom = "API logs")
        assertEquals("API logs", TermSession.disambiguateTitle(base, "API logs", "(2)", hasCollision = true))
    }

    @Test
    fun `mosh prefix is removed before collision comparison`() {
        assertEquals(
            "root@server",
            TermSession.composeTitle(useMosh = true, raw = "[mosh] root@server", custom = null),
        )
    }
}
