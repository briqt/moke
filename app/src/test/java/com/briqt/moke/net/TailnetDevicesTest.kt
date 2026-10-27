package com.briqt.moke.net

import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import org.junit.Assert.*
import org.junit.Test

class TailnetDevicesTest {
    private fun device(id: String = "peer-1", name: String = "builder", ssh: Boolean = true, online: Boolean = true) =
        TailnetDevice(id, name, "$name.example.ts.net.", listOf("100.64.0.2"), "linux", online, ssh)

    @Test fun `decode exposes only peer metadata and keeps offline devices`() {
        val result = parseTailnetDevices("""{"peers":[
            {"id":"b","hostname":"Offline","dnsName":"off.example.ts.net.","ips":["100.64.0.3"],"os":"linux","online":false,"tailscaleSsh":true},
            {"id":"a","hostname":"Online","dnsName":"on.example.ts.net.","ips":["100.64.0.2"],"os":"linux","online":true,"tailscaleSsh":false}
        ]}""")
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals("on.example.ts.net", result.first().address)
        assertTrue(result.last().tailscaleSsh)
        assertFalse(result.last().online)
    }

    @Test fun `unnamed IPv6-only peers still have a usable address`() {
        val peer = TailnetDevice("v6", "", "", listOf("fd7a:115c:a1e0::2"), "", false, false)
        assertEquals("fd7a:115c:a1e0::2", peer.address)
        assertEquals(peer.address, peer.name)
        assertTrue(parseTailnetDevices("""{"peers":[]}""").isEmpty())
    }

    @Test fun `search matches names full DNS addresses and OS without case sensitivity`() {
        val peers = listOf(device(), device("peer-2", "desktop", ssh = false))
        assertEquals(listOf("peer-1"), filterTailnetDevices(peers, " BUILDer ").map { it.id })
        assertEquals(2, filterTailnetDevices(peers, "EXAMPLE.TS.NET").size)
        assertEquals(2, filterTailnetDevices(peers, "100.64").size)
        assertEquals(2, filterTailnetDevices(peers, "LINUX").size)
        assertTrue(filterTailnetDevices(peers, "missing").isEmpty())
        assertEquals(peers, filterTailnetDevices(peers, "  "))
    }

    @Test fun `new SSH device suggests none auth without inventing system credentials`() {
        val draft = Host(username = "operator", networkId = "old")
        val selected = selectTailnetDevice(draft, "chosen", device(), true, null, false)
        assertEquals("builder", selected.label)
        assertEquals("builder.example.ts.net", selected.host)
        assertEquals("chosen", selected.networkId)
        assertEquals(AuthType.TAILSCALE_SSH, selected.authType)
        assertEquals("operator", selected.username)
        assertEquals(draft.password, selected.password)
        assertEquals(draft.privateKeyPem, selected.privateKeyPem)
        assertEquals(22, selected.port)
        assertEquals("", draft.host)
    }

    @Test fun `typed credentials keep their authentication mode even without a chip change`() {
        for (draft in listOf(Host(password = "typed password"), Host(authType = AuthType.KEY, privateKeyPem = "typed key"))) {
            val selected = selectTailnetDevice(draft, "tailnet", device(), true, null, false)
            assertEquals(draft.authType, selected.authType)
            assertEquals(draft.password, selected.password)
            assertEquals(draft.privateKeyPem, selected.privateKeyPem)
        }
    }

    @Test fun `explicit names and authentication are never overwritten`() {
        val draft = Host(label = "My server", username = "admin", authType = AuthType.KEY, port = 2222)
        val selected = selectTailnetDevice(draft, "tailnet", device(), true, "old generated", true)
        assertEquals("My server", selected.label)
        assertEquals(AuthType.KEY, selected.authType)
        assertEquals(2222, selected.port)
        assertEquals("admin", selected.username)
    }

    @Test fun `editing an existing host does not silently migrate authentication or port`() {
        val draft = Host(label = "Existing", port = 2222, authType = AuthType.PASSWORD)
        val selected = selectTailnetDevice(draft, "net", device(), false, null, false)
        assertEquals(AuthType.PASSWORD, selected.authType)
        assertEquals(2222, selected.port)
    }

    @Test fun `only automatic labels follow subsequent device selection`() {
        val first = selectTailnetDevice(Host(), "one", device(), true, null, false)
        val second = selectTailnetDevice(first, "two", device("second", "other"), true, first.label, false)
        assertEquals("other", second.label)
        assertEquals("two", second.networkId)
        val renamed = selectTailnetDevice(first.copy(label = "Personal"), "two", device("second", "other"), true, first.label, false)
        assertEquals("Personal", renamed.label)
    }

    @Test fun `lack of SSH advertisement does not assume it is enabled or erase a deliberate auth choice`() {
        val normal = selectTailnetDevice(Host(), "net", device(ssh = false), true, null, false)
        assertEquals(AuthType.PASSWORD, normal.authType)
        val explicit = selectTailnetDevice(Host(authType = AuthType.TAILSCALE_SSH), "net", device(ssh = false), true, null, true)
        assertEquals(AuthType.TAILSCALE_SSH, explicit.authType)
    }

    @Test fun `Tailscale SSH serializes without changing old password and key records`() {
        for (method in AuthType.entries) {
            val host = Host(authType = method)
            assertEquals(method, Host.fromJson(host.toJson()).authType)
        }
    }
}
