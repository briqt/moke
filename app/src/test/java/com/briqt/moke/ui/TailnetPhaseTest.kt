package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.net.NetworkManager
import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetPhaseTest {
    private val e = NetworkEntry(id = "t", name = "home", kind = NetworkKind.TAILSCALE)

    private fun status(state: String, expired: Boolean = false, everLoggedIn: Boolean = false) =
        NetworkManager.NodeStatus(state, emptyList(), "", "", expired, everLoggedIn, 0)

    private fun phase(
        loggedIn: Boolean? = null,
        st: NetworkManager.NodeStatus? = null,
        flow: MokeViewModel.LoginFlow? = null,
    ) = tailnetPhase(
        e,
        MokeViewModel.NetworkLive(
            loggedIn = loggedIn?.let { mapOf("t" to it) }.orEmpty(),
            status = st?.let { mapOf("t" to it) }.orEmpty(),
        ),
        flow,
    )

    @Test fun `only local knowledge when the node is not running`() {
        assertEquals(TailnetPhase.NOT_LOGGED_IN, phase())
        assertEquals(TailnetPhase.NOT_LOGGED_IN, phase(loggedIn = false))
        assertEquals(TailnetPhase.LOGGED_IN, phase(loggedIn = true))
    }

    @Test fun `running node states win over local knowledge`() {
        assertEquals(TailnetPhase.ACTIVE, phase(loggedIn = true, st = status("Running")))
        assertEquals(TailnetPhase.EXPIRED, phase(loggedIn = true, st = status("Running", expired = true)))
        assertEquals(TailnetPhase.EXPIRED, phase(loggedIn = true, st = status("NeedsLogin", everLoggedIn = true)))
        assertEquals(TailnetPhase.WAITING_APPROVAL, phase(st = status("NeedsMachineAuth")))
    }

    @Test fun `login flow steps`() {
        assertEquals(TailnetPhase.CONNECTING, phase(flow = MokeViewModel.LoginFlow()))
        assertEquals(TailnetPhase.WAITING_BROWSER, phase(st = status("NeedsLogin"), flow = MokeViewModel.LoginFlow(url = "https://x")))
        // A failed attempt falls back to what is known locally; the error shows in the panel.
        assertEquals(TailnetPhase.NOT_LOGGED_IN, phase(flow = MokeViewModel.LoginFlow(error = "boom", busy = false)))
        // Authorized in the browser: the node is Running even while the flow is still recorded.
        assertEquals(TailnetPhase.ACTIVE, phase(st = status("Running"), flow = MokeViewModel.LoginFlow(url = "https://x")))
    }
}
