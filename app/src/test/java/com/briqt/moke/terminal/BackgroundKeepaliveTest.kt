package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundKeepaliveTest {

    @Test
    fun `singleLiveSessionId returns session id only when exactly one live session exists`() {
        assertNull(BackgroundKeepalive.singleLiveSessionId(emptyList()))
        assertEquals(
            "s1",
            BackgroundKeepalive.singleLiveSessionId(listOf(LiveSessionInfo("s1", "prod"))),
        )
        assertNull(
            BackgroundKeepalive.singleLiveSessionId(
                listOf(LiveSessionInfo("s1", "prod"), LiveSessionInfo("s2", "staging")),
            ),
        )
    }

    @Test
    fun `resolveOpenSessionTarget prefers valid requested session then single live session over dead scrollback`() {
        assertEquals(
            "s2",
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = "s2",
                allSessionIds = listOf("s1", "s2"),
                liveSessionIds = listOf("s1"),
            ),
        )
        // Stale requested ID falls back to the single live session even when dead scrollback tabs remain.
        assertEquals(
            "live-1",
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = "gone",
                allSessionIds = listOf("dead-1", "live-1", "dead-2"),
                liveSessionIds = listOf("live-1"),
            ),
        )
        assertEquals(
            "live-1",
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = null,
                allSessionIds = listOf("dead-1", "live-1"),
                liveSessionIds = listOf("live-1"),
            ),
        )
        // Multiple live sessions route to the session list (null).
        assertNull(
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = null,
                allSessionIds = listOf("live-1", "live-2"),
                liveSessionIds = listOf("live-1", "live-2"),
            ),
        )
        // Only one dead session left: open that single session; multiple dead sessions: open list.
        assertEquals(
            "dead-1",
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = null,
                allSessionIds = listOf("dead-1"),
                liveSessionIds = emptyList(),
            ),
        )
        assertNull(
            BackgroundKeepalive.resolveOpenSessionTarget(
                requestedSessionId = null,
                allSessionIds = listOf("dead-1", "dead-2"),
                liveSessionIds = emptyList(),
            ),
        )
    }

    @Test
    fun `liveSessionSummary truncates overly long individual titles when multiple sessions are shown`() {
        assertEquals("", BackgroundKeepalive.liveSessionSummary(emptyList()))
        assertEquals("prod", BackgroundKeepalive.liveSessionSummary(listOf(" prod ", "")))
        assertEquals("prod, staging", BackgroundKeepalive.liveSessionSummary(listOf("prod", "staging")))
        assertEquals(
            "prod, staging…",
            BackgroundKeepalive.liveSessionSummary(listOf("prod", "staging", "db")),
        )

        val longFirst = "build-server:/srv/workspace/very-long-project-directory-name"
        val summary = BackgroundKeepalive.liveSessionSummary(listOf(longFirst, "db-primary"))
        assertEquals("build-server:/srv/worksp…, db-primary", summary)
    }

    @Test
    fun `formatMultiSessionNotification strips trailing separator when all titles are blank`() {
        assertEquals(
            "2 active · prod, db",
            BackgroundKeepalive.formatMultiSessionNotification(2, listOf("prod", "db")) { n, s -> "$n active · $s" },
        )
        assertEquals(
            "2 active",
            BackgroundKeepalive.formatMultiSessionNotification(2, listOf(" ", "")) { n, s -> "$n active · $s" },
        )
    }

    @Test
    fun `shouldMarkEndedInBackground excludes clean exit 0 and honors post-resume thaw grace window`() {
        // Clean exit (exitCode == 0) in background is never marked as an abnormal background drop.
        assertFalse(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = false,
                exitCode = 0,
                appVisible = false,
            ),
        )
        // Non-zero exit in background while established and unclosed -> marked.
        assertTrue(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = false,
                exitCode = -9,
                appVisible = false,
            ),
        )
        assertFalse(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = false,
                closedByUser = false,
                exitCode = 1,
                appVisible = false,
            ),
        )
        assertFalse(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = true,
                exitCode = 1,
                appVisible = false,
            ),
        )
        // Foreground disconnect with no recent background thaw -> not marked.
        assertFalse(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = false,
                exitCode = 1,
                appVisible = true,
                dataReadyAtElapsedMs = 5_000L,
                lastBackgroundedAtElapsedMs = 10_000L,
                lastResumedAtElapsedMs = 20_000L,
                nowElapsedMs = 25_000L,
            ),
        )
        // Thaw-on-resume: session established before resume, ECONNRESET arrives 600ms after onStart.
        assertTrue(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = false,
                exitCode = 1,
                appVisible = true,
                dataReadyAtElapsedMs = 5_000L,
                lastBackgroundedAtElapsedMs = 10_000L,
                lastResumedAtElapsedMs = 20_000L,
                nowElapsedMs = 20_600L,
            ),
        )
        // Session opened AFTER resume is not treated as a background freeze victim.
        assertFalse(
            BackgroundKeepalive.shouldMarkEndedInBackground(
                dataReady = true,
                closedByUser = false,
                exitCode = 1,
                appVisible = true,
                dataReadyAtElapsedMs = 20_300L,
                lastBackgroundedAtElapsedMs = 10_000L,
                lastResumedAtElapsedMs = 20_000L,
                nowElapsedMs = 20_600L,
            ),
        )
    }

    @Test
    fun `ForegroundServiceState uses stopSelfResult so pending startId prevents premature stopForeground`() {
        val state = ForegroundServiceState()
        // Initial emission before onStartCommand does nothing.
        assertEquals(FgsUpdateAction.NONE, state.onWorkChanged(hasActiveWork = false) { error("must not stop") })

        // Start command #1 with active work.
        assertFalse(state.onStartCommand(startId = 1, hasActiveWork = true) { error("must not stop") })
        assertTrue(state.started)
        assertEquals(1, state.lastStartId)
        assertEquals(FgsUpdateAction.NOTIFY, state.onWorkChanged(hasActiveWork = true) { error("must not stop") })

        // Work empties while startId #2 is already queued in AMS -> stopSelfResult(1) returns false!
        val stopCalls = mutableListOf<Int>()
        val actionDuringRace = state.onWorkChanged(hasActiveWork = false) { id ->
            stopCalls += id
            false
        }
        assertEquals(listOf(1), stopCalls)
        assertEquals(FgsUpdateAction.NONE, actionDuringRace)
        assertTrue("Service must stay started until pending startId #2 arrives", state.started)

        // Start command #2 arrives with active work -> stays started cleanly.
        assertFalse(state.onStartCommand(startId = 2, hasActiveWork = true) { error("must not stop") })
        assertEquals(2, state.lastStartId)
        assertTrue(state.started)

        // Work empties with no newer startId pending -> stopSelfResult(2) returns true -> STOP_FOREGROUND.
        val finalAction = state.onWorkChanged(hasActiveWork = false) { id ->
            stopCalls += id
            true
        }
        assertEquals(listOf(1, 2), stopCalls)
        assertEquals(FgsUpdateAction.STOP_FOREGROUND, finalAction)
        assertFalse(state.started)
    }
}
