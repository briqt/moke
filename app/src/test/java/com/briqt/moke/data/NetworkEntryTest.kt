package com.briqt.moke.data

import com.briqt.moke.terminal.KnownHosts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkEntryTest {
    @Test fun `json round trip keeps every field`() {
        val e = NetworkEntry(id = "n1", name = "公司", kind = NetworkKind.PROXY, revision = 3, proxyType = ProxyType.HTTPS,
            proxyHost = "proxy.example", proxyPort = 8443, username = "u", password = "p", extraCaPem = "-----BEGIN CERTIFICATE-----")
        assertEquals(e, NetworkEntry.fromJson(JSONObject(e.toJson().toString())))
        val t = NetworkEntry(id = "t1", name = "家里", kind = NetworkKind.TAILSCALE, controlUrl = "https://hs.example", deviceName = "moke-phone")
        assertEquals(t, NetworkEntry.fromJson(t.toJson()))
    }

    @Test fun `unknown enum values and bad revision fall back safely`() {
        val e = NetworkEntry.fromJson(JSONObject("""{"id":"x","kind":"WIREGUARD","proxyType":"SOCKS6","revision":0}"""))
        assertEquals(NetworkKind.PROXY, e.kind)
        assertEquals(ProxyType.SOCKS5, e.proxyType)
        assertEquals(1, e.revision)
        assertEquals(NetworkEntry.OFFICIAL_CONTROL_URL, e.controlUrl)
    }

    @Test fun `only socks5 carries udp`() {
        assertEquals(listOf(ProxyType.SOCKS5), ProxyType.entries.filter { it.carriesUdp })
    }

    @Test fun `encode never falls back to plaintext and decode keeps unreadable apart`() {
        val list = listOf(NetworkEntry(id = "n1", password = "secret"))
        assertNull(encodeNetworks(list) { throw IllegalStateException("keystore gone") })
        val enc = encodeNetworks(list) { "ENC:" + it.reversed() }!!
        assertTrue(!enc.contains("secret"))
        val back = decodeHosts(enc) { it.removePrefix("ENC:").reversed() }
        assertEquals(list, parseNetworks(back.json))
        assertTrue(decodeHosts(enc) { throw IllegalStateException() }.unreadable)
    }

    @Test fun `host network id survives json and old records default to direct`() {
        val h = Host(id = "a", networkId = "n1")
        assertEquals("n1", Host.fromJson(h.toJson()).networkId)
        assertEquals("", Host.fromJson(JSONObject("""{"id":"old"}""")).networkId)
    }

    @Test fun `fingerprints are namespaced per network but direct keys stay unchanged`() {
        assertEquals("example.com:22", KnownHosts.idOf("example.com", 22))
        assertEquals("example.com:22", KnownHosts.idOf("example.com", 22, null))
        assertEquals("example.com:22", KnownHosts.idOf("example.com", 22, ""))
        assertEquals("n1/example.com:22", KnownHosts.idOf("example.com", 22, "n1"))
    }
}
