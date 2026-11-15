package com.briqt.moke.sync

import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.data.SessionPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SyncBundleTest {
    private val host = Host(
        id = "h1", label = "prod", host = "10.0.0.1", username = "me", authType = AuthType.KEY,
        privateKeyPem = "-----BEGIN KEY-----", passphrase = "pp", networkId = "n1",
        lastConnectedAt = 99L, persistence = SessionPersistence.TMUX, tmuxSessionName = "main", tmuxTargetStamp = "stamp",
    )
    private val tailnet = NetworkEntry(id = "n1", name = "home", kind = NetworkKind.TAILSCALE, revision = 7,
        controlUrl = "https://hs.example.com", deviceName = "moke-phone")
    private val proxy = NetworkEntry(id = "n2", name = "socks", kind = NetworkKind.PROXY, revision = 3,
        proxyType = ProxyType.SOCKS5, proxyHost = "127.0.0.1", proxyPort = 1080, username = "u", password = "p")

    private fun bundle() = SyncBundle(
        meta = BundleMeta("0.2.2", "android", "phone", "2026-11-15T14:30:05+08:00"),
        hosts = listOf(host),
        hostGroupOrder = listOf("work", ""),
        hostCollapsedGroups = listOf("work"),
        networks = listOf(tailnet, proxy),
        knownHosts = mapOf("10.0.0.1:22" to "SHA256:abc", "n1/10.0.0.1:22" to "SHA256:def"),
        settings = mapOf("theme_mode" to PrefValue.S("DARK"), "keep_screen_on" to PrefValue.B(false),
            "font_size_sp_f" to PrefValue.F(12.5f), "cursor_style" to PrefValue.I(2)),
        language = "zh",
    )

    @Test
    fun `往返后凭据保留，本机运行态去掉`() {
        val back = SyncBundle.decode(bundle().encode())
        val h = back.hosts.single()
        assertEquals("-----BEGIN KEY-----", h.privateKeyPem)
        assertEquals("pp", h.passphrase)
        assertEquals(0L, h.lastConnectedAt)
        assertEquals("", h.tmuxSessionName)
        assertEquals("", h.tmuxTargetStamp)
        assertEquals(SessionPersistence.TMUX, h.persistence)
        assertEquals(listOf("work"), back.hostGroupOrder) // 空串分组名不进文件
        assertEquals(bundle().knownHosts, back.knownHosts)
        assertEquals(bundle().settings, back.settings)
        assertEquals("zh", back.language)
    }

    @Test
    fun `tailnet 只带 id、名称和控制服务器；代理带凭据不带版本`() {
        val json = bundle().toJson().getJSONArray("networks")
        val t = json.getJSONObject(0)
        assertEquals(setOf("id", "name", "kind", "controlUrl"), t.keys().asSequence().toSet())
        assertFalse(json.getJSONObject(1).has("revision"))
        val back = SyncBundle.decode(bundle().encode())
        assertEquals("", back.networks[0].deviceName)
        assertEquals(1, back.networks[0].revision)
        assertEquals("p", back.networks[1].password)
        assertEquals(1, back.networks[1].revision)
    }

    @Test
    fun `比较只看同步字段`() {
        assert(SyncBundle.sameSyncedHost(host, host.copy(lastConnectedAt = 1L, tmuxSessionName = "x")))
        assertFalse(SyncBundle.sameSyncedHost(host, host.copy(password = "x")))
        assert(SyncBundle.sameSyncedNetwork(tailnet, tailnet.copy(deviceName = "other", revision = 9)))
        assertFalse(SyncBundle.sameSyncedNetwork(proxy, proxy.copy(proxyPort = 1081)))
    }

    @Test
    fun `更新的正文格式拒绝读取，缺字段与坏条目宽容跳过`() {
        try {
            SyncBundle.decode(JSONObject().put("format", 99).toString().toByteArray())
            fail()
        } catch (_: SyncBundle.NewerFormatException) { }
        val loose = SyncBundle.decode("""{"format":1,"hosts":{"items":[{"label":"no id"},{"id":"ok"}]},"networks":[{"id":"x"}],"settings":{"a":{"t":"zz","v":1}}}""".toByteArray())
        assertEquals(listOf("ok"), loose.hosts.map { it.id })
        assertEquals(emptyList<NetworkEntry>(), loose.networks)
        assertEquals(emptyMap<String, PrefValue>(), loose.settings)
        assertNull(loose.language)
    }
}
