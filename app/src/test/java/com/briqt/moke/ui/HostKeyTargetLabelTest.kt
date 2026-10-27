package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import org.junit.Assert.*
import org.junit.Test

class HostKeyTargetLabelTest {
    @Test fun `known network uses its human name without exposing its storage id`() {
        val network = NetworkEntry(id = "network-storage-id", name = "Work network")
        val storedTarget = "${network.id}/host.example:22"
        assertEquals(HostKeyTargetLabel("host.example:22", "Work network"), hostKeyTargetLabel(storedTarget, listOf(network)))
        assertEquals("network-storage-id/host.example:22", storedTarget)
    }

    @Test fun `unknown namespaces and direct IPv6 targets are not guessed or rewritten`() {
        assertEquals(HostKeyTargetLabel("missing/host.example:22", null), hostKeyTargetLabel("missing/host.example:22", emptyList()))
        assertEquals(HostKeyTargetLabel("[2001:db8::1]:22", null), hostKeyTargetLabel("[2001:db8::1]:22", emptyList()))
    }
}
