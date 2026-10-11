package com.briqt.moke.terminal

import com.briqt.moke.data.Host
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortForwardsTest {

    @Test
    fun `parse ports - separators, dedupe, order`() {
        val p = PortForwards.parsePorts("5173, 8080  3000，5173\n9229")
        assertEquals(listOf(5173, 8080, 3000, 9229), p.ports)
        assertEquals(emptyList<String>(), p.invalid)
    }

    @Test
    fun `parse ports - invalid tokens are reported, not dropped silently`() {
        val p = PortForwards.parsePorts("5173, abc, 0, 70000, 8080")
        assertEquals(listOf(5173, 8080), p.ports)
        assertEquals(listOf("abc", "0", "70000"), p.invalid)
        assertEquals(PortForwards.Parsed(emptyList(), emptyList()), PortForwards.parsePorts("  "))
    }

    @Test
    fun `remote port of local links`() {
        assertEquals(5173, PortForwards.remotePortOf("http://localhost:5173/"))
        assertEquals(8080, PortForwards.remotePortOf("http://127.0.0.1:8080/api?x=1"))
        assertEquals(3000, PortForwards.remotePortOf("http://0.0.0.0:3000"))
        assertEquals(8000, PortForwards.remotePortOf("http://[::1]:8000/"))
        assertEquals(80, PortForwards.remotePortOf("http://localhost/"))
        assertEquals(443, PortForwards.remotePortOf("https://localhost/"))
        assertNull(PortForwards.remotePortOf("https://example.com:8080/"))
        // 端口写坏了不猜：既不退回 80，也不拿越界值去转发。
        assertNull(PortForwards.remotePortOf("http://localhost:99999/"))
        assertNull(PortForwards.remotePortOf("http://localhost:0/"))
        assertNull(PortForwards.remotePortOf("http://localhost:abc/"))
    }

    @Test
    fun `rewrite keeps scheme, path, query and fragment`() {
        assertEquals("http://127.0.0.1:41234/app?x=1#top", PortForwards.rewriteToLocal("http://localhost:5173/app?x=1#top", 41234))
        assertEquals("http://127.0.0.1:41234", PortForwards.rewriteToLocal("http://localhost:5173", 41234))
        assertEquals("http://127.0.0.1:41234/", PortForwards.rewriteToLocal("http://[::1]:8000/", 41234))
        assertEquals("https://127.0.0.1:41234/?q", PortForwards.rewriteToLocal("https://127.0.0.1:8443/?q", 41234))
    }

    @Test
    fun `host forwardPorts survives json round trip and defaults to empty for old data`() {
        val h = Host(host = "a", username = "u", forwardPorts = "5173, 8080")
        assertEquals("5173, 8080", Host.fromJson(h.toJson()).forwardPorts)
        val old = JSONObject().put("id", "x").put("host", "a").put("username", "u")
        assertEquals("", Host.fromJson(old).forwardPorts)
    }
}
