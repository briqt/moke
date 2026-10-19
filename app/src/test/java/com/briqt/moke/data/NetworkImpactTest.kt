package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkImpactTest {
    private val networks = ListState.Ready(listOf(NetworkEntry(id = "n"), NetworkEntry(id = "other")))
    private fun count(vararg hosts: Host, network: String = "n") =
        hostsUsingNetwork(network, ListState.Ready(hosts.toList()), networks)

    @Test fun `direct routes count only the selected network`() {
        assertEquals(1, count(Host(id = "direct", networkId = "n"), Host(id = "plain"), Host(id = "other", networkId = "other")))
    }

    @Test fun `a jump and all five targets using it are affected`() {
        val jump = Host(id = "jump", networkId = "n")
        val targets = (1..5).map { Host(id = "target-$it", jumpHostId = jump.id, networkId = "other") }
        assertEquals(6, count(jump, *targets.toTypedArray()))
    }

    @Test fun `a target network hidden by another jump network is not a dependency`() {
        val jump = Host(id = "jump", networkId = "other")
        val target = Host(id = "target", networkId = "n", jumpHostId = jump.id)
        assertEquals(0, count(jump, target))
        assertEquals(2, count(jump, target, network = "other"))
    }

    @Test fun `a plain jump overrides the target network with direct access`() {
        assertEquals(0, count(Host(id = "jump"), Host(id = "target", networkId = "n", jumpHostId = "jump")))
    }

    @Test fun `missing self and chained jumps are not reported as usable routes`() {
        assertEquals(0, count(Host(id = "missing", networkId = "n", jumpHostId = "gone"), Host(id = "self", networkId = "n", jumpHostId = "self")))
        val root = Host(id = "root", networkId = "n")
        val jump = Host(id = "jump", networkId = "n", jumpHostId = root.id)
        val target = Host(id = "target", networkId = "n", jumpHostId = jump.id)
        assertEquals(2, count(root, jump, target))
    }

    @Test fun `unknown or missing data does not invent an affected count`() {
        val hosts = ListState.Ready(listOf(Host(id = "host", networkId = "n")))
        assertEquals(0, hostsUsingNetwork("n", ListState.Loading, networks))
        assertEquals(0, hostsUsingNetwork("n", ListState.Unreadable, networks))
        assertEquals(0, hostsUsingNetwork("n", hosts, ListState.Loading))
        assertEquals(0, hostsUsingNetwork("n", hosts, ListState.Unreadable))
        assertEquals(0, hostsUsingNetwork("n", hosts, ListState.Ready(emptyList())))
        assertEquals(0, hostsUsingNetwork("", hosts, networks))
    }
}
