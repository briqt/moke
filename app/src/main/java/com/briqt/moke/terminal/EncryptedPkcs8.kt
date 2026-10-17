package com.briqt.moke.terminal

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import java.io.StringReader
import java.io.StringWriter
import java.security.Provider
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

/**
 * 加口令的 PKCS#8 私钥（`BEGIN ENCRYPTED PRIVATE KEY`）用 BouncyCastle 解密。
 *
 * sshj 0.41 起改用 JDK 的 `EncryptedPrivateKeyInfo.getAlgParameters()` 解这类密钥，而 Android 对
 * PBES2（`ssh-keygen -m PKCS8`、`openssl pkcs8 -topk8` 的默认）取不到参数、直接空指针。这里沿用
 * sshj 0.38 的做法（BouncyCastle 的 OpenSSL PKCS#8 解密器），解出的明文 PKCS#8 只在内存里交给 sshj。
 */
object EncryptedPkcs8 {
    private const val HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----"

    fun matches(pem: String): Boolean = pem.contains(HEADER)

    /** 解密成不加密的 PKCS#8 PEM（`BEGIN PRIVATE KEY`）。口令错误或格式不符抛 [KeyDecryptionFailedException]。 */
    fun decryptToPem(pem: String, passphrase: CharArray): String {
        val encrypted = PEMParser(StringReader(pem)).use { it.readObject() } as? PKCS8EncryptedPrivateKeyInfo
            ?: throw KeyDecryptionFailedException("Not an encrypted PKCS8 private key")
        val info = try {
            encrypted.decryptPrivateKeyInfo(
                JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(provider()).build(passphrase)
            )
        } catch (e: Exception) {
            throw KeyDecryptionFailedException("PKCS8 key decryption failed", e)
        }
        return StringWriter().also { out ->
            PemWriter(out).use { it.writeObject(PemObject("PRIVATE KEY", info.encoded)) }
        }.toString()
    }

    /** 应用启动时已把完整 BouncyCastle 注册为 "BC"（见 MokeApplication）；JVM 单测里没有就临时建一个。 */
    private fun provider(): Provider =
        Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            ?.takeIf { it is BouncyCastleProvider } ?: BouncyCastleProvider()
}
