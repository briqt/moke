package com.briqt.moke.sync

import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.KeyEntry
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.deterministicKeyId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncBundleTest {
    private val key = KeyEntry(
        id = "k1",
        name = "prod-key",
        privateKeyPem = "-----BEGIN KEY-----",
        passphrase = "pp",
        publicKey = "ssh-ed25519 AAAA prod-key",
        fingerprint = "SHA256:xyz",
        algorithmLabel = "Ed25519",
        createdAt = 123L,
    )
    private val unreferencedKey = KeyEntry(
        id = "k2",
        name = "standalone",
        privateKeyPem = "-----BEGIN OTHER KEY-----",
        passphrase = "",
        publicKey = "ssh-ed25519 BBBB standalone",
        fingerprint = "SHA256:uvw",
        algorithmLabel = "Ed25519",
        createdAt = 456L,
    )
    private val host = Host(
        id = "h1", label = "prod", host = "10.0.0.1", username = "me", authType = AuthType.KEY,
        keyId = "k1", networkId = "n1",
        lastConnectedAt = 99L, persistence = SessionPersistence.TMUX, tmuxSessionName = "main", tmuxTargetStamp = "stamp",
    )
    private val tailnet = NetworkEntry(id = "n1", name = "home", kind = NetworkKind.TAILSCALE, revision = 7,
        controlUrl = "https://hs.example.com", deviceName = "moke-phone")
    private val proxy = NetworkEntry(id = "n2", name = "socks", kind = NetworkKind.PROXY, revision = 3,
        proxyType = ProxyType.SOCKS5, proxyHost = "127.0.0.1", proxyPort = 1080, username = "u", password = "p")

    private fun bundle() = SyncBundle(
        meta = BundleMeta("0.2.4", "android", "phone", "2026-11-15T14:30:05+08:00"),
        hosts = listOf(host),
        hostGroupOrder = listOf("work", ""),
        hostCollapsedGroups = listOf("work"),
        networks = listOf(tailnet, proxy),
        knownHosts = mapOf("10.0.0.1:22" to "SHA256:abc", "n1/10.0.0.1:22" to "SHA256:def"),
        settings = mapOf("theme_mode" to PrefValue.S("DARK"), "keep_screen_on" to PrefValue.B(false),
            "font_size_sp_f" to PrefValue.F(12.5f), "cursor_style" to PrefValue.I(2)),
        language = "zh",
        keys = listOf(key, unreferencedKey),
    )

    @Test
    fun `往返后独立密钥保留、导出双写兼容旧版且解码剥离内嵌副本`() {
        val rawJson = bundle().toJson()
        val exportedHost = rawJson.getJSONObject("hosts").getJSONArray("items").getJSONObject(0)
        // 旧版 v0.2.3 只看 format=1 与主机里的 privateKeyPem/passphrase，双写后旧版也能读。
        assertEquals(1, rawJson.getInt("format"))
        assertEquals("k1", exportedHost.getString("keyId"))
        assertEquals("-----BEGIN KEY-----", exportedHost.getString("privateKeyPem"))
        assertEquals("pp", exportedHost.getString("passphrase"))

        val back = SyncBundle.decode(bundle().encode())
        val h = back.hosts.single()
        assertEquals("k1", h.keyId)
        assertEquals("", h.privateKeyPem)
        assertEquals("", h.passphrase)
        assertEquals(0L, h.lastConnectedAt)
        assertEquals("", h.tmuxSessionName)
        assertEquals("", h.tmuxTargetStamp)
        assertEquals(SessionPersistence.TMUX, h.persistence)
        assertEquals(listOf(key, unreferencedKey), back.keys)
        assertEquals(listOf("work"), back.hostGroupOrder)
        assertEquals(bundle().knownHosts, back.knownHosts)
        assertEquals(bundle().settings, back.settings)
        assertEquals("zh", back.language)
    }

    @Test
    fun `旧版 v1 无 keys 数组的备份自动去重提取为确定性 ID 的密钥条目`() {
        val v1Host1 = Host(id = "h1", label = "Web 1", host = "10.0.0.1", username = "root", authType = AuthType.KEY, privateKeyPem = "-----BEGIN V1-----", passphrase = "pw")
        val v1Host2 = Host(id = "h2", label = "Web 2", host = "10.0.0.2", username = "root", authType = AuthType.KEY, privateKeyPem = "  -----BEGIN V1-----\n", passphrase = "pw")
        val v1Json = JSONObject()
            .put("format", 1)
            .put("meta", JSONObject().put("version", "0.2.3").put("createdAt", "2026-11-15T14:30:05+08:00"))
            .put("hosts", JSONObject().put("items", JSONArray().put(v1Host1.toJson()).put(v1Host2.toJson())))
        val decoded = SyncBundle.decode(v1Json.toString().toByteArray(Charsets.UTF_8))

        val expectedId = deterministicKeyId("-----BEGIN V1-----", "pw")
        assertEquals(1, decoded.keys.size)
        assertEquals(expectedId, decoded.keys.single().id)
        assertEquals("Web 1", decoded.keys.single().name)
        assertEquals("-----BEGIN V1-----", decoded.keys.single().privateKeyPem)
        assertEquals("pw", decoded.keys.single().passphrase)
        assertEquals(listOf(expectedId, expectedId), decoded.hosts.map { it.keyId })
        assertTrue(decoded.hosts.all { it.privateKeyPem.isEmpty() && it.passphrase.isEmpty() })
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
        assertTrue(SyncBundle.sameSyncedHost(host, host.copy(lastConnectedAt = 1L, tmuxSessionName = "x")))
        assertFalse(SyncBundle.sameSyncedHost(host, host.copy(password = "x")))
        assertFalse(SyncBundle.sameSyncedHost(host, host.copy(keyId = "other")))
        assertTrue(SyncBundle.sameSyncedKey(key, key.copy(createdAt = 999999L)))
        assertFalse(SyncBundle.sameSyncedKey(key, key.copy(name = "renamed")))
        assertTrue(SyncBundle.sameSyncedNetwork(tailnet, tailnet.copy(deviceName = "other", revision = 9)))
        assertFalse(SyncBundle.sameSyncedNetwork(proxy, proxy.copy(proxyPort = 1081)))
    }

    @Test
    fun `更新的正文格式拒绝读取，缺字段与坏条目宽容跳过`() {
        try {
            SyncBundle.decode(JSONObject().put("format", 99).toString().toByteArray())
            fail()
        } catch (_: SyncBundle.NewerFormatException) { }
        val loose = SyncBundle.decode("""{"format":1,"hosts":{"items":[{"label":"no id"},{"id":"ok"}]},"keys":[{"id":"bad-no-pem","name":"n"},{"name":"missing-both"}],"networks":[{"id":"x"}],"settings":{"a":{"t":"zz","v":1}}}""".toByteArray())
        assertEquals(listOf("ok"), loose.hosts.map { it.id })
        assertEquals(emptyList<KeyEntry>(), loose.keys)
        assertEquals(emptyList<NetworkEntry>(), loose.networks)
        assertEquals(emptyMap<String, PrefValue>(), loose.settings)
        assertNull(loose.language)
    }

    @Test
    fun `keys 数组缺少 id 字段的异常备份重复解码与合并恢复保持幂等不产生重复密钥`() {
        val rawBackup = JSONObject()
            .put("format", 1)
            .put("meta", JSONObject().put("version", "0.2.4").put("createdAt", "2026-11-24T12:00:00+08:00"))
            .put(
                "keys",
                JSONArray()
                    .put(JSONObject().put("name", "idless-key").put("privateKeyPem", "-----BEGIN KEY-----\nXYZ\n-----END KEY-----").put("passphrase", "p1"))
                    .put(JSONObject().put("id", "").put("name", "idless-key-dup").put("privateKeyPem", "-----BEGIN KEY-----\r\nXYZ\r\n-----END KEY-----\n").put("passphrase", "p1")),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val decoded1 = SyncBundle.decode(rawBackup)
        val decoded2 = SyncBundle.decode(rawBackup)
        val expectedId = deterministicKeyId("-----BEGIN KEY-----\nXYZ\n-----END KEY-----", "p1")

        assertEquals(1, decoded1.keys.size)
        assertEquals(expectedId, decoded1.keys.single().id)
        assertEquals(decoded1.keys, decoded2.keys)

        val firstRestore = RestorePlanner.plan(
            bundle = decoded1,
            local = LocalState(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList()),
            categories = setOf(RestoreCategory.KEYS),
            mode = RestoreMode.MERGE,
            defaultDeviceName = "phone",
        )
        val firstKeys = firstRestore.keys!!
        assertEquals(1, firstKeys.result.size)

        // Restoring the exact same malformed backup a second time must produce ZERO added keys!
        val secondRestore = RestorePlanner.plan(
            bundle = SyncBundle.decode(rawBackup),
            local = LocalState(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), firstKeys.result),
            categories = setOf(RestoreCategory.KEYS),
            mode = RestoreMode.MERGE,
            defaultDeviceName = "phone",
        )
        val secondKeys = secondRestore.keys!!
        assertEquals(1, secondKeys.result.size)
        assertTrue(secondKeys.diff.isEmpty)
    }
}
