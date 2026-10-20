package com.briqt.moke.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

// type: tmux preference ordering regression
class TmuxChoiceTicketsTest {
    @Test fun `no unissued or zero ticket is current`() {
        val tickets = TmuxChoiceTickets()
        assertFalse(tickets.isCurrent("a", 0))
        assertFalse(tickets.isCurrent("a", 1))
        assertFalse(tickets.claim("a", 0) { fail("unissued claim") })
        assertFalse(tickets.claim("a", 1) { fail("unissued claim") })
    }

    @Test fun `slow earlier selection cannot overwrite a later accepted selection`() {
        val tickets = TmuxChoiceTickets()
        val earlier = tickets.issue("a")
        val later = tickets.issue("a")
        var remembered = 0L
        assertTrue(tickets.claim("a", later) { remembered = later })
        assertFalse(tickets.claim("a", earlier) { remembered = earlier })
        assertEquals(later, remembered)
        assertTrue(tickets.isCurrent("a", later))
        assertFalse(tickets.isCurrent("a", earlier))
    }

    @Test fun `host choices are independent`() {
        val tickets = TmuxChoiceTickets()
        val a = tickets.issue("a")
        val b = tickets.issue("b")
        tickets.issue("a")
        assertFalse(tickets.isCurrent("a", a))
        assertTrue(tickets.isCurrent("b", b))
    }

    @Test fun `network invalidation ticket rejects pending old results`() {
        val tickets = TmuxChoiceTickets()
        val original = tickets.issue("a")
        val invalidation = tickets.issue("a")
        assertFalse(tickets.claim("a", original) { fail("old identity was accepted") })
        assertTrue(tickets.isCurrent("a", invalidation))
    }

    @Test fun `invalidating an old N1 session preserves the newer N2 choice before it binds`() {
        val tickets = TmuxChoiceTickets()
        val n1Session = tickets.issue("host")
        assertTrue(tickets.claim("host", n1Session) {})
        val pendingN2 = tickets.issue("host")

        assertFalse(tickets.invalidate("host", n1Session))
        assertTrue(tickets.isCurrent("host", pendingN2))
        var remembered = 0L
        assertTrue(tickets.claim("host", pendingN2) { remembered = pendingN2 })
        assertEquals(pendingN2, remembered)
    }

    @Test fun `invalidating the affected current choice rejects its late result`() {
        val tickets = TmuxChoiceTickets()
        val current = tickets.issue("host")
        assertTrue(tickets.claim("host", current) {})

        assertTrue(tickets.invalidate("host", current))
        assertFalse(tickets.isCurrent("host", current))
        assertFalse(tickets.claim("host", current) { fail("invalidated result was accepted") })
        val next = tickets.issue("host")
        assertTrue(tickets.isCurrent("host", next))
    }

    @Test fun `zero ticket cannot revoke the current choice`() {
        val tickets = TmuxChoiceTickets()
        val current = tickets.issue("host")
        assertFalse(tickets.invalidate("host", 0L))
        assertTrue(tickets.isCurrent("host", current))
    }

    @Test fun `unissued zero negative or future ticket cannot create or revoke a choice`() {
        val tickets = TmuxChoiceTickets()
        assertFalse(tickets.invalidate("host", 0L))
        assertFalse(tickets.invalidate("host", -1L))
        assertFalse(tickets.invalidate("host", 1L))
        val current = tickets.issue("host")
        assertEquals(1L, current)
        assertFalse(tickets.invalidate("host", current + 1))
        assertTrue(tickets.isCurrent("host", current))
    }

    @Test fun `duplicate affected references invalidate once and do not revoke a later choice`() {
        val tickets = TmuxChoiceTickets()
        val affected = tickets.issue("host")
        assertTrue(tickets.invalidate("host", affected))
        assertFalse(tickets.invalidate("host", affected))
        val later = tickets.issue("host")
        assertFalse(tickets.invalidate("host", affected))
        assertFalse(tickets.invalidate("host", affected))
        assertTrue(tickets.isCurrent("host", later))
    }

    @Test fun `exact ticket invalidation is independent across host ids`() {
        val tickets = TmuxChoiceTickets()
        val first = tickets.issue("first")
        val second = tickets.issue("second")
        assertTrue(tickets.invalidate("first", first))
        assertFalse(tickets.isCurrent("first", first))
        assertTrue(tickets.isCurrent("second", second))
    }

    @Test fun `a concurrent new issue is not revoked by an old invalidation waiting for the lock`() {
        val tickets = TmuxChoiceTickets()
        val affected = tickets.issue("host")
        var later = 0L
        var invalidated: Boolean? = null
        var invalidator: Thread? = null
        try {
            assertTrue(tickets.claim("host", affected) {
                val worker = thread(name = "old-network-invalidation") {
                    invalidated = tickets.invalidate("host", affected)
                }
                invalidator = worker
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.BLOCKED && worker.isAlive && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals("invalidation must wait on the ticket lock", Thread.State.BLOCKED, worker.state)
                // issue is reentrant on this thread, so the new intent is accepted before the old invalidation obtains the lock.
                later = tickets.issue("host")
            })
        } finally {
            invalidator?.join(5_000)
        }
        assertFalse("invalidation thread did not finish", invalidator!!.isAlive)
        assertEquals(false, invalidated)
        assertTrue(tickets.isCurrent("host", later))
        assertTrue(tickets.claim("host", later) {})
    }

    @Test fun `acceptance is atomic with a concurrently issued choice`() {
        val tickets = TmuxChoiceTickets()
        val first = tickets.issue("a")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val issued = CountDownLatch(1)
        var second = 0L
        var accepted = false
        val old = thread {
            accepted = tickets.claim("a", first) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        var newer: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            newer = thread {
                attempted.countDown()
                second = tickets.issue("a")
                issued.countDown()
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertEquals(1L, issued.count)
        } finally {
            release.countDown()
            old.join(5_000)
            newer?.join(5_000)
        }
        assertFalse(old.isAlive)
        assertFalse(newer!!.isAlive)
        assertTrue(accepted)
        assertEquals(0L, issued.count)
        assertTrue(tickets.isCurrent("a", second))
        assertFalse(tickets.claim("a", first) { fail("late assignment") })
    }
}
