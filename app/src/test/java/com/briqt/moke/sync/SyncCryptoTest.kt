package com.briqt.moke.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncCryptoTest {
    /** 单测用小参数，只验证格式与正确性；强度参数另有一条用默认值的用例。 */
    private val fast = SyncCrypto.KdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val plain = "{\"hosts\":[\"密码 p@ss\"]}".toByteArray()

    private fun expect(failure: SyncCrypto.Failure, block: () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: SyncCrypto.CryptoException) {
            assertEquals(failure, e.failure)
        }
    }

    @Test
    fun `往返一致，且同一明文每次密文不同`() {
        val a = SyncCrypto.encrypt(plain, "correct horse", fast)
        val b = SyncCrypto.encrypt(plain, "correct horse", fast)
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plain, SyncCrypto.decrypt(a, "correct horse"))
    }

    @Test
    fun `默认强度参数也能往返（参数写在头部）`() {
        val blob = SyncCrypto.encrypt(plain, "pw-12345678")
        assertEquals(SyncCrypto.DEFAULT_PARAMS, SyncCrypto.inspect(blob))
        assertArrayEquals(plain, SyncCrypto.decrypt(blob, "pw-12345678"))
    }

    @Test
    fun `密码错误`() {
        val blob = SyncCrypto.encrypt(plain, "right password", fast)
        expect(SyncCrypto.Failure.WRONG_PASSWORD) { SyncCrypto.decrypt(blob, "wrong password") }
    }

    @Test
    fun `改动头部参数或密文都无法解密`() {
        val blob = SyncCrypto.encrypt(plain, "pw", fast)
        val header = blob.clone().also { it[13] = (it[13] + 1).toByte() } // 内存参数低位：仍合法，但派生出的密钥不同
        expect(SyncCrypto.Failure.WRONG_PASSWORD) { SyncCrypto.decrypt(header, "pw") }
        val body = blob.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        expect(SyncCrypto.Failure.WRONG_PASSWORD) { SyncCrypto.decrypt(body, "pw") }
    }

    @Test
    fun `不是备份文件、截断文件、离谱参数`() {
        expect(SyncCrypto.Failure.NOT_BACKUP) { SyncCrypto.decrypt("hello".toByteArray(), "pw") }
        val blob = SyncCrypto.encrypt(plain, "pw", fast)
        expect(SyncCrypto.Failure.CORRUPT) { SyncCrypto.decrypt(blob.copyOfRange(0, 20), "pw") }
        val huge = blob.clone().also { it[10] = 0x7f } // 内存参数高位
        expect(SyncCrypto.Failure.CORRUPT) { SyncCrypto.decrypt(huge, "pw") }
    }

    @Test
    fun `更新格式版本的文件提示升级`() {
        val blob = SyncCrypto.encrypt(plain, "pw", fast).also { it[8] = (SyncCrypto.FORMAT + 1).toByte() }
        expect(SyncCrypto.Failure.NEWER_FORMAT) { SyncCrypto.decrypt(blob, "pw") }
    }
}
