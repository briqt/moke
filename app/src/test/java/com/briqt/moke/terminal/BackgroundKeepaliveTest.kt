package com.briqt.moke.terminal

import android.os.Build
import com.briqt.moke.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundKeepaliveTest {

    @Test
    fun `background wake lock is enabled by default`() {
        assertTrue(SettingsStore.DEFAULT_BACKGROUND_WAKE_LOCK)
        assertTrue(BackgroundKeepalive.wakeLockEnabled)
    }

    @Test
    fun `shouldCreateWifiLock only enables high-perf wifi lock on API 30 to 33`() {
        assertTrue(BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION_CODES.R))
        assertTrue(BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION_CODES.S))
        assertTrue(BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION_CODES.S_V2))
        assertTrue(BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION_CODES.TIRAMISU))
        assertFalse(BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertFalse(BackgroundKeepalive.shouldCreateWifiLock(35))
    }

    @Test
    fun `shouldHoldBackgroundLocks requires live sessions enabled switch and app in background`() {
        // Default switch OFF -> never holds wake/wifi locks even in background with live sessions.
        assertFalse(
            BackgroundKeepalive.shouldHoldBackgroundLocks(
                hasLiveSessions = true,
                wakeLockEnabled = false,
                appVisible = false,
            ),
        )
        // Switch ON, but app is in foreground -> must NOT hold locks!
        assertFalse(
            BackgroundKeepalive.shouldHoldBackgroundLocks(
                hasLiveSessions = true,
                wakeLockEnabled = true,
                appVisible = true,
            ),
        )
        // Switch ON, app in background, but no live sessions -> must NOT hold locks.
        assertFalse(
            BackgroundKeepalive.shouldHoldBackgroundLocks(
                hasLiveSessions = false,
                wakeLockEnabled = true,
                appVisible = false,
            ),
        )
        // Switch ON + live sessions + app in background -> holds locks.
        assertTrue(
            BackgroundKeepalive.shouldHoldBackgroundLocks(
                hasLiveSessions = true,
                wakeLockEnabled = true,
                appVisible = false,
            ),
        )
    }

    @Test
    fun `TerminalAlerts visibility tracks overlapping activity recreate without false background transition`() {
        TerminalAlerts.resetVisibilityForTest()
        try {
            assertFalse(TerminalAlerts.appVisible)
            assertFalse(TerminalAlerts.appVisibleFlow.value)

            TerminalAlerts.onAppStarted(nowElapsedMs = 1_000L)
            assertTrue(TerminalAlerts.appVisible)
            assertTrue(TerminalAlerts.appVisibleFlow.value)
            assertEquals(-1L, TerminalAlerts.lastBackgroundedAtElapsedMs)

            // Activity recreate: new instance starts before old instance stops.
            TerminalAlerts.onAppStarted(nowElapsedMs = 2_000L)
            TerminalAlerts.onAppStopped(nowElapsedMs = 2_050L)
            assertTrue(TerminalAlerts.appVisible)
            assertTrue(TerminalAlerts.appVisibleFlow.value)
            assertEquals(-1L, TerminalAlerts.lastBackgroundedAtElapsedMs)

            // Genuine move to background when last started Activity stops.
            TerminalAlerts.onAppStopped(nowElapsedMs = 3_000L)
            assertFalse(TerminalAlerts.appVisible)
            assertFalse(TerminalAlerts.appVisibleFlow.value)
            assertEquals(3_000L, TerminalAlerts.lastBackgroundedAtElapsedMs)

            // Returning to foreground records resume timestamp.
            TerminalAlerts.onAppStarted(nowElapsedMs = 8_500L)
            assertTrue(TerminalAlerts.appVisible)
            assertTrue(TerminalAlerts.appVisibleFlow.value)
            assertEquals(8_500L, TerminalAlerts.lastResumedAtElapsedMs)
        } finally {
            TerminalAlerts.resetVisibilityForTest()
        }
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
    fun `effectiveTerminalAlertsChecked and decideAlertToggle enforce permission and channel readiness`() {
        assertTrue(
            BackgroundKeepalive.effectiveTerminalAlertsChecked(
                prefEnabled = true,
                appNotificationsAllowed = true,
                alertChannelEnabled = true,
            ),
        )
        assertFalse(
            BackgroundKeepalive.effectiveTerminalAlertsChecked(
                prefEnabled = true,
                appNotificationsAllowed = false,
                alertChannelEnabled = true,
            ),
        )
        assertFalse(
            BackgroundKeepalive.effectiveTerminalAlertsChecked(
                prefEnabled = true,
                appNotificationsAllowed = true,
                alertChannelEnabled = false,
            ),
        )

        assertEquals(
            AlertToggleAction.DISABLE,
            BackgroundKeepalive.decideAlertToggle(
                wantChecked = false,
                permissionGranted = false,
                systemNotificationsEnabled = false,
                alertChannelEnabled = false,
            ),
        )
        assertEquals(
            AlertToggleAction.REQUEST_PERMISSION,
            BackgroundKeepalive.decideAlertToggle(
                wantChecked = true,
                permissionGranted = false,
                systemNotificationsEnabled = true,
                alertChannelEnabled = true,
            ),
        )
        assertEquals(
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS,
            BackgroundKeepalive.decideAlertToggle(
                wantChecked = true,
                permissionGranted = true,
                systemNotificationsEnabled = false,
                alertChannelEnabled = true,
            ),
        )
        assertEquals(
            AlertToggleAction.OPEN_ALERT_CHANNEL_SETTINGS,
            BackgroundKeepalive.decideAlertToggle(
                wantChecked = true,
                permissionGranted = true,
                systemNotificationsEnabled = true,
                alertChannelEnabled = false,
            ),
        )
        assertEquals(
            AlertToggleAction.ENABLE,
            BackgroundKeepalive.decideAlertToggle(
                wantChecked = true,
                permissionGranted = true,
                systemNotificationsEnabled = true,
                alertChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `decideAlertPermissionResult handles user denial fast auto-reject and slow cold-start USER_FIXED block`() {
        // Granted with everything ready -> enable directly.
        assertEquals(
            AlertToggleAction.ENABLE,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = true,
                systemNotificationsEnabled = true,
                alertChannelEnabled = true,
                wasRationaleBefore = false,
                isRationaleAfter = false,
                rationaleEverSeen = false,
                elapsedMs = 800L,
            ),
        )
        // Granted at runtime permission layer, but master app notifications are turned off in Settings.
        assertEquals(
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = true,
                systemNotificationsEnabled = false,
                alertChannelEnabled = true,
                wasRationaleBefore = false,
                isRationaleAfter = false,
                rationaleEverSeen = false,
                elapsedMs = 500L,
            ),
        )
        // Granted, but alert channel was muted -> open channel settings without prematurely enabling pref.
        assertEquals(
            AlertToggleAction.OPEN_ALERT_CHANNEL_SETTINGS,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = true,
                systemNotificationsEnabled = true,
                alertChannelEnabled = false,
                wasRationaleBefore = false,
                isRationaleAfter = false,
                rationaleEverSeen = false,
                elapsedMs = 500L,
            ),
        )
        // User intentionally tapped "Don't allow" on first prompt (rationale becomes true after).
        assertEquals(
            AlertToggleAction.DISABLE,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = false,
                systemNotificationsEnabled = false,
                alertChannelEnabled = false,
                wasRationaleBefore = false,
                isRationaleAfter = true,
                rationaleEverSeen = false,
                elapsedMs = 900L,
            ),
        )
        // User intentionally tapped "Don't allow" on second prompt (rationale was true before).
        assertEquals(
            AlertToggleAction.DISABLE,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = false,
                systemNotificationsEnabled = false,
                alertChannelEnabled = false,
                wasRationaleBefore = true,
                isRationaleAfter = false,
                rationaleEverSeen = true,
                elapsedMs = 1_200L,
            ),
        )
        // Fast silent OS auto-rejection (<350ms, rationale false before & after) -> open app notification settings.
        assertEquals(
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = false,
                systemNotificationsEnabled = false,
                alertChannelEnabled = false,
                wasRationaleBefore = false,
                isRationaleAfter = false,
                rationaleEverSeen = false,
                elapsedMs = 45L,
            ),
        )
        // Slow cold-start permissioncontroller on USER_FIXED (elapsedMs = 800ms > 350ms, rationaleEverSeen = true) -> open settings!
        assertEquals(
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS,
            BackgroundKeepalive.decideAlertPermissionResult(
                granted = false,
                systemNotificationsEnabled = false,
                alertChannelEnabled = false,
                wasRationaleBefore = false,
                isRationaleAfter = false,
                rationaleEverSeen = true,
                elapsedMs = 800L,
            ),
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
