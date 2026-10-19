package com.briqt.moke.ui

import com.briqt.moke.R
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPresentationTest {
    private val saved = NetworkEntry(id = "tailnet", name = "Home", kind = NetworkKind.TAILSCALE, deviceName = "moke")

    @Test fun `hostnames and IPv4 keep their usual host port form`() {
        assertEquals("proxy.example:1080", proxyAddressLabel("proxy.example", 1080))
        assertEquals("192.0.2.1:443", proxyAddressLabel("192.0.2.1", 443))
    }

    @Test fun `bare IPv6 is bracketed for display`() {
        assertEquals("[::1]:1080", proxyAddressLabel("::1", 1080))
        assertEquals("[2001:db8::42]:443", proxyAddressLabel("2001:db8::42", 443))
        assertEquals("[::ffff:192.0.2.1]:8080", proxyAddressLabel("::ffff:192.0.2.1", 8080))
        assertEquals("[fe80::1%wlan0]:1080", proxyAddressLabel("fe80::1%wlan0", 1080))
    }

    @Test fun `already bracketed IPv6 never gains a second pair`() {
        assertEquals("[::1]:1080", proxyAddressLabel("[::1]", 1080))
        assertEquals("[2001:db8::42]:443", proxyAddressLabel("[2001:db8::42]", 443))
        assertEquals("[fe80::1%wlan0]:1080", proxyAddressLabel("[fe80::1%wlan0]", 1080))
    }

    @Test fun `a new tailnet must be saved before either login method`() {
        assertTrue(tailnetLoginRequiresSave(null, saved))
    }

    @Test fun `unchanged saved configuration only needs login`() {
        assertFalse(tailnetLoginRequiresSave(saved, saved.copy()))
    }

    @Test fun `each persisted tailnet field changes the login save wording`() {
        assertTrue(tailnetLoginRequiresSave(saved, saved.copy(name = "Work")))
        assertTrue(tailnetLoginRequiresSave(saved, saved.copy(controlUrl = "https://control.example")))
        assertTrue(tailnetLoginRequiresSave(saved, saved.copy(deviceName = "moke-new")))
    }

    @Test fun `irrelevant proxy fields and revisions do not change tailnet login wording`() {
        assertFalse(tailnetLoginRequiresSave(saved, saved.copy(revision = 2, proxyHost = "proxy.example", password = "unused")))
        // Auth keys and login-method choices are deliberately absent from NetworkEntry and this comparison.
    }

    @Test fun `running without business leases means connected not in use`() {
        assertEquals(R.string.net_state_connected, tailnetPhaseLabelRes(TailnetPhase.ACTIVE, 0))
        assertEquals(R.string.net_state_active, tailnetPhaseLabelRes(TailnetPhase.ACTIVE, 1))
        assertEquals(R.string.net_state_active, tailnetPhaseLabelRes(TailnetPhase.ACTIVE, 3))
    }

    @Test fun `a lease count cannot replace another phase with active wording`() {
        val expected = mapOf(
            TailnetPhase.CHECKING to R.string.net_state_checking,
            TailnetPhase.NOT_LOGGED_IN to R.string.net_state_not_logged_in,
            TailnetPhase.CONNECTING to R.string.net_state_connecting,
            TailnetPhase.WAITING_BROWSER to R.string.net_state_waiting_browser,
            TailnetPhase.WAITING_APPROVAL to R.string.net_state_waiting_approval,
            TailnetPhase.EXPIRED to R.string.net_state_expired,
            TailnetPhase.LOGGED_IN to R.string.net_state_logged_in,
        )
        for ((phase, label) in expected) {
            assertEquals(label, tailnetPhaseLabelRes(phase, 0))
            assertEquals(label, tailnetPhaseLabelRes(phase, 2))
        }
    }
}
