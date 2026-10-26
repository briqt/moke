package com.briqt.moke.ui

import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxPresence
import com.briqt.moke.terminal.TmuxUiState
import com.briqt.moke.terminal.TmuxUnknownReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxObservationRecoveryTest {
    @Test
    fun unsupportedBackendDoesNotOfferAnIneffectiveRefresh() {
        assertFalse(canRefreshTmuxObservation(state(TmuxPhase.ERROR, TmuxUnknownReason.UNSUPPORTED)))
    }

    @Test
    fun missingCarrierRequiresAnExplicitReconnectNotRefresh() {
        assertFalse(canRefreshTmuxObservation(state(TmuxPhase.ERROR, TmuxUnknownReason.RECONNECT_REQUIRED)))
    }

    @Test
    fun transientObservationFailureCanStillRefresh() {
        assertTrue(canRefreshTmuxObservation(state(TmuxPhase.ERROR, TmuxUnknownReason.UNAVAILABLE)))
    }

    @Test
    fun installingTmuxDoesNotRequireRecreatingTheConnection() {
        assertTrue(canRefreshTmuxObservation(state(TmuxPhase.NOT_INSTALLED, TmuxUnknownReason.UNSUPPORTED)))
    }

    @Test
    fun verifiedManagementListCanRefreshWhenCurrentIdentityIsUnknown() {
        assertTrue(canRefreshTmuxObservation(state(TmuxPhase.READY, TmuxUnknownReason.UNSUPPORTED)))
    }

    @Test
    fun initialObservationKeepsItsExistingRefreshEntry() {
        assertTrue(canRefreshTmuxObservation(TmuxUiState()))
    }

    private fun state(phase: TmuxPhase, reason: TmuxUnknownReason) =
        TmuxUiState(phase = phase, presence = TmuxPresence.Unknown(reason))
}
