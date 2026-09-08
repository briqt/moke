package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读不出来」必须与「一台都没有」严格分开。
 *
 * 两者混为一谈的后果是静默毁数据：Keystore 密钥失效（备份恢复到新机）时列表显示为空，
 * 接着任何一次写入都会把还在磁盘上的密文覆盖成空数组。
 */
class HostStoreDecodeTest {

    private val ok: (String) -> String = { "[{\"id\":\"a\"}]" }
    private val broken: (String) -> String = { throw IllegalStateException("key gone") }

    @Test
    fun `absent or blank storage is an empty list, not a failure`() {
        for (raw in listOf(null, "", "   ")) {
            val d = decodeHosts(raw, broken)
            assertEquals("raw=$raw", "[]", d.json)
            assertFalse("raw=$raw", d.unreadable)
        }
    }

    @Test
    fun `legacy plaintext is read as-is without touching the decryptor`() {
        val plain = """[{"id":"legacy"}]"""
        val d = decodeHosts(plain, broken)
        assertEquals(plain, d.json)
        assertFalse(d.unreadable)
    }

    @Test
    fun `leading whitespace still counts as legacy plaintext`() {
        val d = decodeHosts("\n  [{\"id\":\"legacy\"}]", broken)
        assertFalse(d.unreadable)
    }

    @Test
    fun `decryptable ciphertext yields the plaintext`() {
        val d = decodeHosts("base64ciphertext==", ok)
        assertEquals("[{\"id\":\"a\"}]", d.json)
        assertFalse(d.unreadable)
    }

    @Test
    fun `undecryptable ciphertext is flagged, never silently empty`() {
        val d = decodeHosts("base64ciphertext==", broken)
        // 仍然给出空列表让 UI 不至于崩，但必须带上 unreadable —— save() 据此拒绝覆盖。
        assertEquals("[]", d.json)
        assertTrue(d.unreadable)
    }
}
