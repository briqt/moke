package com.briqt.moke.sync

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的端到端加密：同步密码经 Argon2id 派生 256 位密钥，AES-256-GCM 加密正文。
 *
 * 文件布局（大端）：`MOKESYNC` · 格式版本 u8 · KDF u8(1=Argon2id) · 内存 KiB u32 · 迭代 u32 · 并行 u8 ·
 * salt 长度 u8 + salt · nonce 长度 u8 + nonce · 密文（含 16 字节 tag）。整个头部作为 AAD 参与认证，
 * 改动任何参数都会让解密失败。参数写在头部里，以后调高强度不影响读旧文件。
 */
object SyncCrypto {
    const val FORMAT = 1
    private val MAGIC = "MOKESYNC".toByteArray(Charsets.US_ASCII)
    private const val KDF_ARGON2ID = 1
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** 上限防止恶意文件用离谱参数拖垮设备。 */
    private const val MAX_MEMORY_KIB = 256 * 1024
    private const val MAX_ITERATIONS = 16
    private const val MAX_PARALLELISM = 8

    data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

    val DEFAULT_PARAMS = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    enum class Failure { NOT_BACKUP, NEWER_FORMAT, WRONG_PASSWORD, CORRUPT }

    class CryptoException(val failure: Failure) : Exception(failure.name)

    fun encrypt(plain: ByteArray, password: String, params: KdfParams = DEFAULT_PARAMS, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(params, salt, nonce)
        val key = derive(password, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val parsed = parseHeader(blob)
        val key = derive(password, parsed.salt, parsed.params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, parsed.nonce))
            cipher.updateAAD(blob, 0, parsed.headerLength)
            return cipher.doFinal(blob, parsed.headerLength, blob.size - parsed.headerLength)
        } catch (_: AEADBadTagException) {
            // GCM 无法区分「密码错」与「文件被改」；绝大多数情况是前者，界面据此再问一次密码。
            throw CryptoException(Failure.WRONG_PASSWORD)
        } catch (_: GeneralSecurityException) {
            throw CryptoException(Failure.CORRUPT)
        } finally {
            key.fill(0)
        }
    }

    /** 只看头部：是不是备份文件、格式是否能读（不需要密码）。 */
    fun inspect(blob: ByteArray): KdfParams = parseHeader(blob).params

    private class Header(val params: KdfParams, val salt: ByteArray, val nonce: ByteArray, val headerLength: Int)

    private fun header(params: KdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(FORMAT)
            d.writeByte(KDF_ARGON2ID)
            d.writeInt(params.memoryKiB)
            d.writeInt(params.iterations)
            d.writeByte(params.parallelism)
            d.writeByte(salt.size)
            d.write(salt)
            d.writeByte(nonce.size)
            d.write(nonce)
        }
        return out.toByteArray()
    }

    private fun parseHeader(blob: ByteArray): Header {
        if (blob.size < MAGIC.size || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CryptoException(Failure.NOT_BACKUP)
        }
        try {
            val b = ByteBuffer.wrap(blob)
            b.position(MAGIC.size)
            val format = b.get().toInt() and 0xff
            if (format > FORMAT) throw CryptoException(Failure.NEWER_FORMAT)
            if (format < 1) throw CryptoException(Failure.CORRUPT)
            val kdf = b.get().toInt() and 0xff
            if (kdf != KDF_ARGON2ID) throw CryptoException(Failure.NEWER_FORMAT)
            val memory = b.int
            val iterations = b.int
            val parallelism = b.get().toInt() and 0xff
            if (memory < 8 * parallelism || memory > MAX_MEMORY_KIB || iterations !in 1..MAX_ITERATIONS || parallelism !in 1..MAX_PARALLELISM) {
                throw CryptoException(Failure.CORRUPT)
            }
            val salt = ByteArray(b.get().toInt() and 0xff)
            if (salt.size < 8) throw CryptoException(Failure.CORRUPT)
            b.get(salt)
            val nonce = ByteArray(b.get().toInt() and 0xff)
            if (nonce.size != NONCE_BYTES) throw CryptoException(Failure.CORRUPT)
            b.get(nonce)
            if (b.remaining() < TAG_BITS / 8) throw CryptoException(Failure.CORRUPT)
            return Header(KdfParams(memory, iterations, parallelism), salt, nonce, b.position())
        } catch (e: CryptoException) {
            throw e
        } catch (_: RuntimeException) {
            throw CryptoException(Failure.CORRUPT)
        }
    }

    private fun derive(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build()
        )
        val pass = password.toByteArray(Charsets.UTF_8)
        val out = ByteArray(KEY_BYTES)
        try {
            generator.generateBytes(pass, out)
        } finally {
            pass.fill(0)
        }
        return out
    }
}
