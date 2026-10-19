package com.briqt.moke.net

import com.briqt.moke.data.MissingKind
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.Route
import com.briqt.moke.data.UnreadableKind
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRecoveryTest {
    private val network = NetworkEntry(id = "network", name = "name")
    private fun error(code: String) = NetworkException(code, network, "arbitrary translated text")

    @Test fun `stable codes choose actions rather than localized messages`() {
        val expected = mapOf(
            "NEEDS_LOGIN" to RecoveryKind.LOGIN_NETWORK,
            "LOGIN_EXPIRED" to RecoveryKind.LOGIN_NETWORK,
            "NEEDS_MACHINE_AUTH" to RecoveryKind.VIEW_NETWORK,
            "PROXY_AUTH" to RecoveryKind.EDIT_NETWORK,
            "PROXY_TLS" to RecoveryKind.EDIT_NETWORK,
            "PROXY_NO_UDP" to RecoveryKind.EDIT_NETWORK,
            "PROXY_REFUSED" to RecoveryKind.EDIT_NETWORK,
            "CONTROL_CHANGED" to RecoveryKind.EDIT_NETWORK,
            "NOT_IN_TAILNET" to RecoveryKind.EDIT_HOST,
            "NAME_NOT_FOUND" to RecoveryKind.EDIT_HOST,
            "TIMEOUT" to RecoveryKind.RETRY,
            "TARGET_UNREACHABLE" to RecoveryKind.RETRY,
            "PROXY_UNREACHABLE" to RecoveryKind.RETRY,
            "NOT_READY" to RecoveryKind.RETRY,
        )
        expected.forEach { (code, kind) -> assertEquals(code, kind, error(code).recovery?.kind) }
        assertEquals(network, error("PROXY_TLS").recovery?.network)
        assertNull(error("CANCELLED").recovery)
    }

    @Test fun `wrapped causes and cycles are handled without string matching`() {
        val original = error("LOGIN_EXPIRED")
        val wrapped = IllegalStateException("wrapper", RuntimeException(original))
        assertEquals(original, wrapped.networkException())
        assertEquals(RecoveryKind.LOGIN_NETWORK, wrapped.networkRecovery()?.kind)
        assertEquals(RecoveryKind.RETRY, RuntimeException(SocketTimeoutException()).networkRecovery()?.kind)
        assertNull(IllegalStateException("NEEDS_LOGIN TIMEOUT PROXY_TLS").networkRecovery())
        class Cyclic : Exception() { var next: Throwable? = null; override val cause: Throwable? get() = next }
        val a = Cyclic(); val b = Cyclic(); a.next = b; b.next = a
        assertNull(a.networkException())
        assertNull(a.networkRecovery())
    }

    @Test fun `route protection distinguishes missing references from unreadable or loading data`() {
        MissingKind.entries.forEach { assertEquals(RecoveryKind.EDIT_HOST, Route.Missing(it).recovery?.kind) }
        UnreadableKind.entries.forEach { assertEquals(RecoveryKind.RETRY, Route.Unreadable(it).recovery?.kind) }
        assertEquals(RecoveryKind.RETRY, Route.Loading.recovery?.kind)
    }
}
