package com.briqt.moke.terminal

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 加口令 PKCS#8 私钥的解密（测试用一次性密钥，口令 pw123）。
 * 样本分别由 `ssh-keygen -m PKCS8`（PBES2 / AES-128-CBC）与 `openssl pkcs8 -v1 PBE-SHA1-3DES`（PBES1）生成，
 * 期望公钥取自 `ssh-keygen -y`。
 */
class EncryptedPkcs8Test {

    private fun publicKeyOf(plainPem: String): String {
        val kp = PKCS8KeyFile().apply { init(StringReader(plainPem)) }
        val pub = kp.public
        return Base64.toBase64String(Buffer.PlainBuffer().putPublicKey(pub).compactData)
    }

    @Test fun pbes2FromSshKeygen() {
        val plain = EncryptedPkcs8.decryptToPem(SSH_KEYGEN_PBES2, "pw123".toCharArray())
        assertTrue(plain.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertEquals(SSH_KEYGEN_PBES2_PUB, publicKeyOf(plain))
    }

    @Test fun pbes1FromOpenssl() {
        val plain = EncryptedPkcs8.decryptToPem(OPENSSL_PBES1, "pw123".toCharArray())
        assertEquals(OPENSSL_PBES1_PUB, publicKeyOf(plain))
    }

    /** 解密本身与算法无关；sshj 的 PKCS#8 读取器不认 Ed25519（0.38 起即如此），这里只钉解密。 */
    @Test fun ed25519Pbes2Decrypts() {
        val plain = EncryptedPkcs8.decryptToPem(OPENSSL_ED25519_PBES2, "pw123".toCharArray())
        assertTrue(plain.startsWith("-----BEGIN PRIVATE KEY-----"))
    }

    @Test(expected = KeyDecryptionFailedException::class)
    fun wrongPassphrase() {
        EncryptedPkcs8.decryptToPem(SSH_KEYGEN_PBES2, "nope".toCharArray())
    }

    @Test(expected = KeyDecryptionFailedException::class)
    fun notEncryptedPkcs8() {
        val plain = EncryptedPkcs8.decryptToPem(SSH_KEYGEN_PBES2, "pw123".toCharArray())
        EncryptedPkcs8.decryptToPem(plain, "pw123".toCharArray())
    }

    @Test fun matchesOnlyEncryptedPkcs8() {
        assertTrue(EncryptedPkcs8.matches(SSH_KEYGEN_PBES2))
        assertFalse(EncryptedPkcs8.matches("-----BEGIN PRIVATE KEY-----"))
        assertFalse(EncryptedPkcs8.matches("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED"))
        assertFalse(EncryptedPkcs8.matches("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }

    private companion object {
        const val SSH_KEYGEN_PBES2 = """-----BEGIN ENCRYPTED PRIVATE KEY-----
MIHsMFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAhajQp89NmL0AICCAAw
DAYIKoZIhvcNAgkFADAdBglghkgBZQMEAQIEEACVThbHZLpD4W0nei6F0+4EgZCm
w0YrMIqkhLPwfjkVGNnz3coMspgCke1aua7qGgzPo9hwCWZxWlbBVgJTVXzRCIZO
mjJufmYZ8fgamY7OT96avJl8/eLq4dwK6z7/APrKm8cgvCcZoyt6VLiLxCxYN1TU
XIWwTQg0VeqUHcO05RPHDNEInjyCto/zhIFuiiQNzdUwXdLv/0oyagSC4sbM18o=
-----END ENCRYPTED PRIVATE KEY-----
"""
        const val SSH_KEYGEN_PBES2_PUB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKe72vZprXYPYChyhrQ2AtWQJ55eL0OPUfFbrzho4A7JHMrAc1fxLraKO6dZFDCj/9Raj791qgHJrcF7DbJWdas="

        const val OPENSSL_PBES1 = """-----BEGIN ENCRYPTED PRIVATE KEY-----
MIGxMBwGCiqGSIb3DQEMAQMwDgQIfwH8oLS8B2ACAggABIGQZtjHgxPPdJKm3leH
vY2aqXL7XrGds0MI1Qj2UaUSnIYVOufBk2ywuIBgCzQeA4y3st3X4igTfBLeMyo5
ECQQfHfvuCJKCRt9k0rl4iUeCs24udIZ4E3Uf7X5Mo5cdlZ0R0YJ3BsLG5fuHKsY
/9xGyGAaQGAX3YwQZ5josceh+IFgi9EVN3FJ1VhRliEb7rhJ
-----END ENCRYPTED PRIVATE KEY-----
"""
        const val OPENSSL_PBES1_PUB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHtjGRMx3fb+TM0P/i+lEs08F9KnTWKje8aAyMkD7blka9Ov0zZnWgPB6Cmu6Duwn/yZ8k7YVQx8QN9XIL/A+Vg="

        const val OPENSSL_ED25519_PBES2 = """-----BEGIN ENCRYPTED PRIVATE KEY-----
MIGbMFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAi9I+EeeAvHMAICCAAw
DAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEIwHaiqgbcHCVdrwhRoNZfgEQJEs
opLTHjkg3MqcYjI+FAkkejv3r8N5+0wAOz1SnWfRKTSm+tdPjILdH+7Z+QmOpxwc
LNxI+rquGA1rg5KgLis=
-----END ENCRYPTED PRIVATE KEY-----
"""
    }
}
