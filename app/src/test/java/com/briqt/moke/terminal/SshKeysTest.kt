package com.briqt.moke.terminal

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SshKeysTest {

    companion object {
        private const val ECDSA_PKCS8_PBES2 = """-----BEGIN ENCRYPTED PRIVATE KEY-----
MIHsMFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAhajQp89NmL0AICCAAw
DAYIKoZIhvcNAgkFADAdBglghkgBZQMEAQIEEACVThbHZLpD4W0nei6F0+4EgZCm
w0YrMIqkhLPwfjkVGNnz3coMspgCke1aua7qGgzPo9hwCWZxWlbBVgJTVXzRCIZO
mjJufmYZ8fgamY7OT96avJl8/eLq4dwK6z7/APrKm8cgvCcZoyt6VLiLxCxYN1TU
XIWwTQg0VeqUHcO05RPHDNEInjyCto/zhIFuiiQNzdUwXdLv/0oyagSC4sbM18o=
-----END ENCRYPTED PRIVATE KEY-----
"""
        private const val ECDSA_PKCS8_PBES2_PUB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKe72vZprXYPYChyhrQ2AtWQJ55eL0OPUfFbrzho4A7JHMrAc1fxLraKO6dZFDCj/9Raj791qgHJrcF7DbJWdas="

        @JvmStatic
        @BeforeClass
        fun installBc() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    private fun verifySshSignature(pem: String, passphrase: String) {
        val kp = OpenSSHKeyV1KeyFile().apply {
            init(pem, null, keyPasswordFinder(passphrase))
        }
        val challenge = "moke-ssh-key-verification-payload".toByteArray(Charsets.UTF_8)
        val alg: KeyAlgorithm = DefaultConfig().keyAlgorithms
            .map { it.create() }
            .firstOrNull { it.keyFormat == kp.type }
            ?: error("Missing sshj KeyAlgorithm for ${kp.type}")
        val signer = alg.newSignature()
        assertNotNull(signer)
        signer.initSign(kp.private)
        signer.update(challenge)
        val wireSig = Buffer.PlainBuffer()
            .putString(signer.signatureName)
            .putBytes(signer.encode(signer.sign()))
            .compactData

        val verifier = alg.newSignature()
        verifier.initVerify(kp.public)
        verifier.update(challenge)
        assertTrue("SSH signature verification failed for ${kp.type}", verifier.verify(wireSig))
    }

    @Test
    fun `generate Ed25519 plain and encrypted keys load in sshj and sign`() {
        val plain = SshKeys.generate(KeyGenAlgorithm.ED25519, passphrase = "", comment = "work-laptop")
        assertTrue(plain.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(plain.privateKeyPem.endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertEquals("Ed25519", plain.algorithmLabel)
        assertEquals("work-laptop", plain.comment)
        assertTrue(plain.fingerprint.startsWith("SHA256:"))
        assertTrue(plain.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(plain.publicKey.endsWith(" work-laptop"))
        verifySshSignature(plain.privateKeyPem, "")

        val pass = "  correct horse battery staple  "
        val enc = SshKeys.generate(KeyGenAlgorithm.ED25519, passphrase = pass, comment = "sec\nkey")
        assertEquals("Ed25519", enc.algorithmLabel)
        assertEquals("sec key", enc.comment)
        assertTrue(enc.publicKey.endsWith(" sec key"))
        verifySshSignature(enc.privateKeyPem, pass)

        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(enc.privateKeyPem, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(enc.privateKeyPem, "wrong"),
        )
    }

    @Test
    fun `generate RSA 4096 plain and encrypted keys load in OpenSSHKeyV1KeyFile and sign`() {
        val plain = SshKeys.generate(KeyGenAlgorithm.RSA_4096, passphrase = "", comment = "prod-rsa")
        assertEquals("RSA 4096", plain.algorithmLabel)
        assertEquals("prod-rsa", plain.comment)
        assertTrue(plain.fingerprint.startsWith("SHA256:"))
        assertTrue(plain.publicKey.startsWith("ssh-rsa "))
        assertTrue(plain.publicKey.endsWith(" prod-rsa"))
        verifySshSignature(plain.privateKeyPem, "")

        // Also verify JCA SHA256withRSA (rsa-sha2-256) using the decoded CRT private key.
        val kp = OpenSSHKeyV1KeyFile().apply { init(plain.privateKeyPem, null, null) }
        val data = "rsa-crt-check".toByteArray(Charsets.UTF_8)
        val jcaSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(data)
        }.sign()
        val jcaVer = Signature.getInstance("SHA256withRSA").apply {
            initVerify(kp.public)
            update(data)
        }
        assertTrue(jcaVer.verify(jcaSig))

        val enc = SshKeys.generate(KeyGenAlgorithm.RSA_4096, passphrase = "rsa-secret-pass", comment = "enc-rsa")
        assertEquals("RSA 4096", enc.algorithmLabel)
        assertEquals("enc-rsa", enc.comment)
        verifySshSignature(enc.privateKeyPem, "rsa-secret-pass")
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(enc.privateKeyPem, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(enc.privateKeyPem, "bad-pass"),
        )
    }

    @Test
    fun `import traditional PKCS1 plain and Proc-Type encrypted RSA keys`() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val pair = kpg.generateKeyPair()

        val plainPem = StringWriter().also { out ->
            JcaPEMWriter(out).use { it.writeObject(pair) }
        }.toString()
        val parsedPlain = (SshKeys.inspectPrivateKey(plainPem) as KeyParseResult.Ok).key
        assertEquals("RSA 2048", parsedPlain.algorithmLabel)
        assertEquals(HostKeyFingerprint.of(pair.public), parsedPlain.fingerprint)

        val encPem = StringWriter().also { out ->
            val enc = JcePEMEncryptorBuilder("AES-128-CBC")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build("pem-pass".toCharArray())
            JcaPEMWriter(out).use { it.writeObject(pair, enc) }
        }.toString()
        assertTrue(encPem.contains("Proc-Type: 4,ENCRYPTED"))
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(encPem, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(encPem, "wrong-pass"),
        )
        val parsedEnc = (SshKeys.inspectPrivateKey(encPem, "pem-pass", commentOverride = "imported") as KeyParseResult.Ok).key
        assertEquals("RSA 2048", parsedEnc.algorithmLabel)
        assertEquals(HostKeyFingerprint.of(pair.public), parsedEnc.fingerprint)
        assertTrue(parsedEnc.publicKey.endsWith(" imported"))
    }

    @Test
    fun `import PKCS8 plain and PBES1 PBES2 encrypted keys and reject unsupported PKCS8 Ed25519`() {
        // 1. OpenSSL / ssh-keygen ECDSA P-256 PKCS#8 (PBES2 encrypted + decrypted plain PKCS#8).
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(ECDSA_PKCS8_PBES2, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(ECDSA_PKCS8_PBES2, "nope"),
        )
        val parsedEcEnc = (SshKeys.inspectPrivateKey(ECDSA_PKCS8_PBES2, "pw123") as KeyParseResult.Ok).key
        assertEquals("ECDSA P-256", parsedEcEnc.algorithmLabel)
        assertEquals("ssh-ecdsa-nistp256@openssh.com", parsedEcEnc.publicKey.substringBefore(' ').let {
            // Standard SSH wire name for ECDSA P-256 is ecdsa-sha2-nistp256
            if (it == "ecdsa-sha2-nistp256") "ssh-ecdsa-nistp256@openssh.com" else it
        })
        assertTrue(parsedEcEnc.publicKey.startsWith("ecdsa-sha2-nistp256 $ECDSA_PKCS8_PBES2_PUB"))

        val plainEcPkcs8 = EncryptedPkcs8.decryptToPem(ECDSA_PKCS8_PBES2, "pw123".toCharArray())
        val parsedEcPlain = (SshKeys.inspectPrivateKey(plainEcPkcs8) as KeyParseResult.Ok).key
        assertEquals("ECDSA P-256", parsedEcPlain.algorithmLabel)
        assertEquals(parsedEcEnc.fingerprint, parsedEcPlain.fingerprint)

        // 2. RSA PKCS#8 (plain & PBES1 encrypted).
        val rsaPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val plainRsaPkcs8 = StringWriter().also { out ->
            JcaPEMWriter(out).use { it.writeObject(JcaPKCS8Generator(rsaPair.private, null)) }
        }.toString()
        val parsedRsaPlain = (SshKeys.inspectPrivateKey(plainRsaPkcs8) as KeyParseResult.Ok).key
        assertEquals("RSA 2048", parsedRsaPlain.algorithmLabel)
        assertEquals(HostKeyFingerprint.of(rsaPair.public), parsedRsaPlain.fingerprint)

        val encRsaPkcs8 = StringWriter().also { out ->
            val encryptor = JceOpenSSLPKCS8EncryptorBuilder(ASN1ObjectIdentifier(JceOpenSSLPKCS8EncryptorBuilder.PBE_SHA1_3DES))
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .setPassword("p8-pass".toCharArray())
                .build()
            JcaPEMWriter(out).use { it.writeObject(JcaPKCS8Generator(rsaPair.private, encryptor)) }
        }.toString()
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(encRsaPkcs8, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(encRsaPkcs8, "nope"),
        )
        val parsedRsaEnc = (SshKeys.inspectPrivateKey(encRsaPkcs8, "p8-pass") as KeyParseResult.Ok).key
        assertEquals("RSA 2048", parsedRsaEnc.algorithmLabel)
        assertEquals(HostKeyFingerprint.of(rsaPair.public), parsedRsaEnc.fingerprint)

        // 3. PKCS#8 Ed25519 is unsupported by sshj 0.41's PKCS8KeyFile: verify clear classification.
        val edGen = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val edPair = edGen.generateKeyPair()
        val plainEdPkcs8 = StringWriter().also { out ->
            JcaPEMWriter(out).use { it.writeObject(JcaPKCS8Generator(edPair.private, null)) }
        }.toString()
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(plainEdPkcs8),
        )
        val encEdPkcs8 = StringWriter().also { out ->
            val encryptor = JceOpenSSLPKCS8EncryptorBuilder(ASN1ObjectIdentifier(JceOpenSSLPKCS8EncryptorBuilder.AES_256_CBC))
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .setPassword("ed-pass".toCharArray())
                .build()
            JcaPEMWriter(out).use { it.writeObject(JcaPKCS8Generator(edPair.private, encryptor)) }
        }.toString()
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(encEdPkcs8, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(encEdPkcs8, "wrong"),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(encEdPkcs8, "ed-pass"),
        )
    }

    @Test
    fun `empty and malformed inputs classify cleanly`() {
        assertEquals(KeyParseResult.Invalid(KeyParseError.EMPTY_KEY), SshKeys.inspectPrivateKey("   \n\t  "))
        assertEquals(KeyParseResult.Invalid(KeyParseError.EMPTY_KEY), SshKeys.inspectPrivateKey("\uFEFF  \r\n "))
        assertEquals(KeyParseResult.Invalid(KeyParseError.INVALID_KEY), SshKeys.inspectPrivateKey("not a key"))
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"),
        )
        val b64Block48 = org.bouncycastle.util.encoders.Base64.toBase64String(ByteArray(48))
        val validHeaderPuttyV3 = """
            PuTTY-User-Key-File-3: ssh-ed25519
            Encryption: aes256-cbc
            Comment: ppk-comment
            Public-Lines: 1
            AAAA
            Key-Derivation: Argon2id
            Argon2-Memory: 64
            Argon2-Passes: 2
            Argon2-Parallelism: 1
            Argon2-Salt: 00112233445566778899aabbccddeeff
            Private-Lines: 1
            $b64Block48
            Private-MAC: 0000000000000000000000000000000000000000000000000000000000000000
        """.trimIndent()
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED),
            SshKeys.inspectPrivateKey(validHeaderPuttyV3, ""),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.WRONG_PASSPHRASE),
            SshKeys.inspectPrivateKey(validHeaderPuttyV3, "wrong-passphrase"),
        )
    }

    @Test
    fun `enforces PEM size cap, KDF DoS bounds, UTF-8 BOM normalization, and control-char comment sanitization`() {
        // 1. Input size cap (> 256 KiB normalized -> INVALID_KEY).
        val oversized = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(260 * 1024) + "\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(oversized, "pass"),
        )

        // 2. OpenSSH bcrypt rounds / salt bounds rejected before running KDF.
        fun craftedOpenSshBcrypt(saltLen: Int, rounds: Long): String {
            val kdfOpts = Buffer.PlainBuffer()
                .putBytes(ByteArray(saltLen))
                .putUInt32(rounds)
                .compactData
            val outer = Buffer.PlainBuffer()
                .putRawBytes("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
                .putString("aes256-ctr")
                .putString("bcrypt")
                .putBytes(kdfOpts)
                .putUInt32(1L)
                .putBytes(ByteArray(16))
                .putBytes(ByteArray(32))
                .compactData
            val b64 = org.bouncycastle.util.encoders.Base64.toBase64String(outer)
            return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----"
        }
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedOpenSshBcrypt(saltLen = 16, rounds = 2048L), "pass"),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedOpenSshBcrypt(saltLen = 4, rounds = 16L), "pass"),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedOpenSshBcrypt(saltLen = 16, rounds = 0L), "pass"),
        )

        // 3. PPK v3 Argon2 memory / passes / parallelism bounds rejected before running Argon2.
        fun craftedPpkV3(memory: Int, passes: Int, parallelism: Int): String = """
            PuTTY-User-Key-File-3: ssh-ed25519
            Encryption: aes256-cbc
            Comment: dos-test
            Public-Lines: 1
            AAAA
            Key-Derivation: Argon2id
            Argon2-Memory: $memory
            Argon2-Passes: $passes
            Argon2-Parallelism: $parallelism
            Argon2-Salt: 00112233445566778899aabbccddeeff
            Private-Lines: 1
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            Private-MAC: 0000000000000000000000000000000000000000000000000000000000000000
        """.trimIndent()
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedPpkV3(memory = 512 * 1024, passes = 2, parallelism = 1), "pass"),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedPpkV3(memory = 64, passes = 100, parallelism = 1), "pass"),
        )
        assertEquals(
            KeyParseResult.Invalid(KeyParseError.INVALID_KEY),
            SshKeys.inspectPrivateKey(craftedPpkV3(memory = 64, passes = 2, parallelism = 16), "pass"),
        )

        // 4. UTF-8 BOM normalization + terminal escape / Unicode separator stripping in comments.
        val generated = SshKeys.generate(KeyGenAlgorithm.ED25519, comment = "clean")
        val withBom = "\uFEFF" + generated.privateKeyPem.replace("\n", "\r\n") + "\r\n"
        val evilComment = "ops\u001b[31m-injected\u0007\u0085line2\u2028line3\u2029end\t!"
        val parsed = (SshKeys.inspectPrivateKey(withBom, commentOverride = evilComment) as KeyParseResult.Ok).key
        assertEquals(generated.privateKeyPem, parsed.privateKeyPem)
        assertEquals("ops [31m-injected line2 line3 end !", parsed.comment)
        assertTrue(parsed.publicKey.endsWith(" ops [31m-injected line2 line3 end !"))
        assertTrue(parsed.publicKey.none { it.isISOControl() || it == '\u0085' || it == '\u2028' || it == '\u2029' })
    }
}
