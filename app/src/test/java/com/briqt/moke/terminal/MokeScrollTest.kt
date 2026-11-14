package com.briqt.moke.terminal

import com.termux.view.MokeScroll
import org.junit.Assert.assertEquals
import org.junit.Test

/** App/view contract: automatic mode behaves like a desktop terminal's mouse wheel. */
class MokeScrollTest {
    @Test
    fun `automatic protocol priority matrix`() {
        for (alternate in listOf(false, true)) {
            for (mouse in listOf(false, true)) {
                val expected = when {
                    mouse -> MokeScroll.ACTION_WHEEL
                    alternate -> MokeScroll.ACTION_ARROWS
                    else -> MokeScroll.ACTION_LOCAL
                }
                assertEquals(expected, MokeScroll.decide(0, alternate, mouse))
            }
        }
    }

    @Test
    fun `temporary explicit modes are deliberate input not program classification`() {
        assertEquals(0, MokeScroll.MODE_SMART)
        assertEquals(1, MokeScroll.MODE_WHEEL)
        assertEquals(2, MokeScroll.MODE_ARROWS)
        for (alternate in listOf(false, true)) {
            for (mouse in listOf(false, true)) {
                assertEquals(MokeScroll.ACTION_WHEEL, MokeScroll.decide(1, alternate, mouse))
                assertEquals(MokeScroll.ACTION_ARROWS, MokeScroll.decide(2, alternate, mouse))
            }
        }
    }
}
