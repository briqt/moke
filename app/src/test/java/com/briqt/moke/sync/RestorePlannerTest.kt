package com.briqt.moke.sync

import com.briqt.moke.data.AuthType
import com.briqt.moke.data.Host
import com.briqt.moke.data.KeyEntry
import com.briqt.moke.data.MAX_TAILNET_ENTRIES
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import com.briqt.moke.data.SessionPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePlannerTest {
    private val all = RestoreCategory.entries.toSet()

    private fun tailnet(id: String, name: String, url: String = "https://hs.example.com", device: String = "") =
        NetworkEntry(id = id, name = name, kind = NetworkKind.TAILSCALE, controlUrl = url, deviceName = device)

    private fun proxy(id: String, name: String, port: Int = 1080, revision: Int = 1) =
        NetworkEntry(id = id, name = name, kind = NetworkKind.PROXY, proxyType = ProxyType.SOCKS5, proxyHost = "127.0.0.1", proxyPort = port, revision = revision)

    private fun key(id: String, name: String, pem: String = "PEM-$id", pass: String = "", createdAt: Long = 10L) =
        KeyEntry(id = id, name = name, privateKeyPem = pem, passphrase = pass, publicKey = "pub-$id", fingerprint = "SHA256:$id", algorithmLabel = "Ed25519", createdAt = createdAt)

    private fun bundle(
        hosts: List<Host> = emptyList(),
        networks: List<NetworkEntry> = emptyList(),
        known: Map<String, String> = emptyMap(),
        order: List<String> = emptyList(),
        keys: List<KeyEntry> = emptyList(),
    ) = SyncBundle(BundleMeta("0.2.4", "android", "a", ""), hosts, order, emptyList(), networks, known, emptyMap(), null, keys)

    private fun local(
        hosts: List<Host> = emptyList(),
        networks: List<NetworkEntry> = emptyList(),
        known: Map<String, String> = emptyMap(),
        order: List<String> = emptyList(),
        keys: List<KeyEntry> = emptyList(),
    ) = LocalState(hosts, networks, known, order, emptyList(), keys)

    private fun plan(b: SyncBundle, l: LocalState, mode: RestoreMode, cats: Set<RestoreCategory> = all) =
        RestorePlanner.plan(b, l, cats, mode, "moke-this")

    @Test
    fun `合并：新增与更新，本机独有的保留`() {
        val l = local(hosts = listOf(Host(id = "a", label = "a"), Host(id = "mine", label = "mine")))
        val b = bundle(hosts = listOf(Host(id = "a", label = "a2"), Host(id = "new", label = "new")))
        val p = plan(b, l, RestoreMode.MERGE).hosts!!
        assertEquals(listOf("a", "mine", "new"), p.result.map { it.id })
        assertEquals(listOf("new"), p.diff.added.map { it.id })
        assertEquals(listOf("a2"), p.diff.updated.map { it.label })
        assertTrue(p.diff.removed.isEmpty())
    }

    @Test
    fun `替换：结果与文件一致，本机独有的列入移除`() {
        val l = local(hosts = listOf(Host(id = "a", label = "a"), Host(id = "mine", label = "mine")))
        val b = bundle(hosts = listOf(Host(id = "new", label = "new"), Host(id = "a", label = "a")))
        val p = plan(b, l, RestoreMode.REPLACE)
        assertEquals(listOf("new", "a"), p.hosts!!.result.map { it.id })
        assertEquals(listOf("mine"), p.hosts!!.diff.removed.map { it.id })
        assertTrue(p.hosts!!.diff.updated.isEmpty())
        assertTrue(p.hasRemovals)
    }

    @Test
    fun `本机运行态保留：最近连接时间总保留，tmux 记忆只在目标未变时保留`() {
        val old = Host(id = "a", host = "h", username = "u", persistence = SessionPersistence.TMUX,
            lastConnectedAt = 5L, tmuxSessionName = "main", tmuxTargetStamp = "s")
        val same = plan(bundle(hosts = listOf(old.copy(label = "x", lastConnectedAt = 0, tmuxSessionName = "", tmuxTargetStamp = ""))),
            local(hosts = listOf(old)), RestoreMode.MERGE).hosts!!.result.single()
        assertEquals(5L, same.lastConnectedAt)
        assertEquals("main", same.tmuxSessionName)
        val moved = plan(bundle(hosts = listOf(old.copy(host = "other", tmuxSessionName = "", tmuxTargetStamp = ""))),
            local(hosts = listOf(old)), RestoreMode.MERGE).hosts!!.result.single()
        assertEquals(5L, moved.lastConnectedAt)
        assertEquals("", moved.tmuxSessionName)
    }

    @Test
    fun `没变的不算更新，只勾偏好也算有改动`() {
        val h = Host(id = "a", label = "a")
        val p = plan(bundle(hosts = listOf(h)), local(hosts = listOf(h.copy(lastConnectedAt = 3))), RestoreMode.MERGE, setOf(RestoreCategory.HOSTS))
        assertTrue(p.hosts!!.diff.isEmpty)
        assertFalse(p.hasChanges)
        assertTrue(plan(bundle(), local(), RestoreMode.MERGE, setOf(RestoreCategory.SETTINGS)).hasChanges)
    }

    @Test
    fun `同私钥同口令的条目视为同一把密钥：保留本机 id，主机引用改指过去，不同密钥重名加序号`() {
        val localKey = key("local-k", "My Key", pem = "SAME-PEM", pass = "p", createdAt = 777L)
        val localOther = key("local-2", "Work", pem = "LOCAL-ONLY")
        val fileSame = localKey.copy(id = "file-k", privateKeyPem = "  SAME-PEM\n", createdAt = 111L)
        val fileCollision = key("file-2", "Work", pem = "DIFFERENT-PEM")

        val b = bundle(
            hosts = listOf(Host(id = "h", authType = AuthType.KEY, keyId = "file-k")),
            keys = listOf(fileSame, fileCollision),
        )
        val merged = plan(b, local(keys = listOf(localKey, localOther)), RestoreMode.MERGE)
        val mergedKeys = merged.keys!!
        assertEquals(listOf("local-k", "local-2", "file-2"), mergedKeys.result.map { it.id })
        assertEquals(listOf("My Key", "Work", "Work (2)"), mergedKeys.result.map { it.name })
        assertEquals(777L, mergedKeys.result.first().createdAt)
        assertTrue(mergedKeys.diff.updated.isEmpty()) // createdAt 差异不算同步变更
        assertEquals(listOf("file-2"), mergedKeys.diff.added.map { it.id })
        assertEquals("local-k", merged.hosts!!.result.single().keyId)
        assertEquals(0, merged.danglingKeyRefs)

        val replaced = plan(b, local(keys = listOf(localKey, localOther)), RestoreMode.REPLACE)
        val replacedKeys = replaced.keys!!
        assertEquals(listOf("local-k", "file-2"), replacedKeys.result.map { it.id })
        assertEquals(listOf("local-2"), replacedKeys.diff.removed.map { it.id })
        assertTrue(replaced.hasRemovals)
    }

    @Test
    fun `勾了主机没勾密钥时，能识别需要一起恢复的密钥；取消后计入失效引用`() {
        val k = key("k1", "Prod Key", pem = "PEM-1")
        val b = bundle(hosts = listOf(Host(id = "h", authType = AuthType.KEY, keyId = "k1")), keys = listOf(k))
        assertTrue(RestorePlanner.keysRequiredByHosts(b, local()))
        assertFalse(RestorePlanner.keysRequiredByHosts(b, local(keys = listOf(key("local-eq", "Local", pem = "PEM-1")))))
        assertEquals(1, plan(b, local(), RestoreMode.MERGE, setOf(RestoreCategory.HOSTS)).danglingKeyRefs)
        assertEquals(0, plan(b, local(), RestoreMode.MERGE).danglingKeyRefs)

        // 密码或 Tailscale SSH 主机上保留的休眠 keyId 不应强制勾上密钥，也不计入断连失效引用。
        val dormant = bundle(
            hosts = listOf(
                Host(id = "pw", authType = AuthType.PASSWORD, password = "p", keyId = "k1"),
                Host(id = "ts", authType = AuthType.TAILSCALE_SSH, keyId = "k1"),
            ),
            keys = listOf(k),
        )
        assertFalse(RestorePlanner.keysRequiredByHosts(dormant, local()))
        assertEquals(0, plan(dormant, local(), RestoreMode.MERGE, setOf(RestoreCategory.HOSTS)).danglingKeyRefs)

        // 经一级跳板连接、且跳板机使用密钥时，跳板缺失密钥同样计入需要恢复与失效引用。
        val jumpHost = Host(id = "j", authType = AuthType.KEY, keyId = "k1")
        val targetViaJump = Host(id = "app", authType = AuthType.PASSWORD, password = "p", jumpHostId = "j")
        val viaJumpBundle = bundle(hosts = listOf(jumpHost, targetViaJump), keys = listOf(k))
        assertTrue(RestorePlanner.keysRequiredByHosts(viaJumpBundle, local()))
        assertEquals(2, plan(viaJumpBundle, local(), RestoreMode.MERGE, setOf(RestoreCategory.HOSTS)).danglingKeyRefs)
    }

    @Test
    fun `合并恢复时两把已有密钥互换名称保持幂等，不误加序号`() {
        val localKeys = listOf(
            key("k1", "Prod", pem = "PEM-1"),
            key("k2", "Prod Old", pem = "PEM-2"),
            key("k3", "Untouched", pem = "PEM-3"),
        )
        val swappedInBackup = listOf(
            key("k1", "Prod Old", pem = "PEM-1"),
            key("k2", "Prod", pem = "PEM-2"),
        )
        val p = plan(bundle(keys = swappedInBackup), local(keys = localKeys), RestoreMode.MERGE).keys!!
        assertEquals(listOf("k1", "k2", "k3"), p.result.map { it.id })
        assertEquals(listOf("Prod Old", "Prod", "Untouched"), p.result.map { it.name })
    }

    @Test
    fun `写回密钥时保留本机创建时间与预览后新建的条目`() {
        val current = listOf(key("k1", "Key 1", createdAt = 500L), key("late", "Key 2", pem = "LATE"))
        val planned = listOf(key("k1", "Key 2", createdAt = 1L))
        val out = SyncController.reconcileKeys(current, planned, removedIds = emptySet())
        assertEquals(listOf("k1", "late"), out.map { it.id })
        assertEquals(500L, out[0].createdAt)
        assertEquals(listOf("Key 2", "Key 2 (2)"), out.map { it.name })
        assertEquals(listOf("k1"), SyncController.reconcileKeys(current, planned, removedIds = setOf("late")).map { it.id })
    }

    @Test
    fun `同名同目标的网络视为同一个：保留本机 id 与登录，主机和指纹改指过去`() {
        val mine = tailnet("local-id", "Home", device = "moke-mine")
        val theirs = tailnet("file-id", "home")
        val b = bundle(
            hosts = listOf(Host(id = "h", networkId = "file-id")),
            networks = listOf(theirs),
            known = mapOf("file-id/10.0.0.1:22" to "SHA256:x", "10.0.0.2:22" to "SHA256:y"),
        )
        val p = plan(b, local(networks = listOf(mine)), RestoreMode.REPLACE)
        assertEquals(listOf("local-id"), p.networks!!.result.map { it.id })
        assertEquals("moke-mine", p.networks!!.result.single().deviceName)
        assertTrue(p.networks!!.diff.removed.isEmpty())
        assertEquals("local-id", p.hosts!!.result.single().networkId)
        assertEquals(setOf("local-id/10.0.0.1:22", "10.0.0.2:22"), p.fingerprints!!.result.keys)
        assertEquals(0, p.danglingNetworkRefs)
    }

    @Test
    fun `新 tailnet 用本机默认设备名，控制服务器变了要重新登录`() {
        val p = plan(bundle(networks = listOf(tailnet("t", "home"))), local(), RestoreMode.MERGE)
        assertEquals("moke-this", p.networks!!.result.single().deviceName)
        val changed = plan(
            bundle(networks = listOf(tailnet("t", "home", url = "https://other.example.com"))),
            local(networks = listOf(tailnet("t", "home", device = "moke-mine"))), RestoreMode.MERGE,
        ).networks!!
        assertEquals(listOf("t"), changed.identityReset.map { it.id })
        assertEquals("moke-mine", changed.result.single().deviceName)
        assertEquals("https://other.example.com", changed.result.single().controlUrl)
    }

    @Test
    fun `不同网络撞名时加序号，tailnet 超上限的跳过`() {
        val p = plan(bundle(networks = listOf(proxy("f", "socks", port = 9999))), local(networks = listOf(proxy("l", "socks"))), RestoreMode.MERGE)
        assertEquals(listOf("socks", "socks (2)"), p.networks!!.result.map { it.name })
        val full = (1..MAX_TAILNET_ENTRIES).map { tailnet("l$it", "t$it", url = "https://$it.example.com") }
        val over = plan(bundle(networks = listOf(tailnet("x", "extra"))), local(networks = full), RestoreMode.MERGE).networks!!
        assertEquals(listOf("x"), over.skipped.map { it.id })
        assertEquals(MAX_TAILNET_ENTRIES, over.result.size)
    }

    @Test
    fun `勾了主机没勾网络时，能识别需要一起恢复的网络；取消后计入失效引用`() {
        val b = bundle(hosts = listOf(Host(id = "h", networkId = "n")), networks = listOf(proxy("n", "p")))
        assertTrue(RestorePlanner.networksRequiredByHosts(b, local()))
        assertFalse(RestorePlanner.networksRequiredByHosts(b, local(networks = listOf(proxy("n", "p")))))
        assertEquals(1, plan(b, local(), RestoreMode.MERGE, setOf(RestoreCategory.HOSTS)).danglingNetworkRefs)
        assertEquals(0, plan(b, local(), RestoreMode.MERGE).danglingNetworkRefs)
    }

    @Test
    fun `指纹：合并覆盖同键，替换移除本机独有`() {
        val l = local(known = mapOf("a:22" to "1", "b:22" to "2"))
        val b = bundle(known = mapOf("a:22" to "9", "c:22" to "3"))
        val m = plan(b, l, RestoreMode.MERGE).fingerprints!!
        assertEquals(mapOf("a:22" to "9", "b:22" to "2", "c:22" to "3"), m.result)
        assertEquals(1, m.added)
        assertEquals(1, m.updated)
        val r = plan(b, l, RestoreMode.REPLACE).fingerprints!!
        assertEquals(mapOf("a:22" to "9", "c:22" to "3"), r.result)
        assertEquals(1, r.removed)
    }

    @Test
    fun `分组顺序：合并时本机顺序在前，替换时用文件的`() {
        val l = local(order = listOf("x", "y"))
        val b = bundle(order = listOf("z", "x"))
        assertEquals(listOf("x", "y", "z"), plan(b, l, RestoreMode.MERGE).hosts!!.groupOrder)
        assertEquals(listOf("z", "x"), plan(b, l, RestoreMode.REPLACE).hosts!!.groupOrder)
    }

    @Test
    fun `写回时以存储当前值对齐：代理参数变了才升版本，tailnet 保留本机版本与设备名，预览后新建的保留`() {
        val current = listOf(proxy("p", "socks", revision = 4), tailnet("t", "home", device = "moke-mine").copy(revision = 6), proxy("late", "late"))
        val planned = listOf(proxy("p", "socks", port = 2000), tailnet("t", "renamed"))
        val out = SyncController.reconcileNetworks(current, planned, removedIds = emptySet())
        assertEquals(listOf("p", "t", "late"), out.map { it.id })
        assertEquals(5, out[0].revision)
        assertEquals(6, out[1].revision)
        assertEquals("moke-mine", out[1].deviceName)
        assertEquals("renamed", out[1].name)
        assertEquals(4, SyncController.reconcileNetworks(current, listOf(proxy("p", "socks")), emptySet())[0].revision)
        assertEquals(listOf("p", "t"), SyncController.reconcileNetworks(current, planned, setOf("late")).map { it.id })
    }

    @Test
    fun `写回主机时最近连接时间取当前值`() {
        val current = listOf(Host(id = "a", lastConnectedAt = 77))
        val out = SyncController.reconcileHosts(current, listOf(Host(id = "a", label = "x", lastConnectedAt = 1)), emptySet())
        assertEquals(77L, out.single().lastConnectedAt)
        assertEquals("x", out.single().label)
    }
}
