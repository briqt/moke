package com.briqt.moke.terminal

import com.briqt.moke.data.normalizeKeyPem
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import com.hierynomus.sshj.transport.cipher.BlockCiphers
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import java.io.IOException
import java.io.StringReader
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.cipher.Cipher
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Base64

enum class KeyGenAlgorithm(val defaultName: String) {
    ED25519("Ed25519"),
    RSA_4096("RSA 4096"),
}

enum class KeyParseError {
    EMPTY_KEY,
    PASSPHRASE_REQUIRED,
    WRONG_PASSPHRASE,
    INVALID_KEY,
}

data class ParsedKey(
    val privateKeyPem: String,
    val passphrase: String,
    val publicKey: String,
    val fingerprint: String,
    val algorithmLabel: String,
    val comment: String,
)

sealed interface KeyParseResult {
    data class Ok(val key: ParsedKey) : KeyParseResult
    data class Invalid(val error: KeyParseError) : KeyParseResult
}

/**
 * Pure JVM/Android SSH private key generation (`openssh-key-v1`), validation, fingerprint,
 * and `authorized_keys` public key formatting backed by the exact same `sshj` key readers
 * used at connection time.
 */
object SshKeys {
    private const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_END = "-----END OPENSSH PRIVATE KEY-----"
    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    private const val BCRYPT_ROUNDS = 16
    private const val MAX_KEY_PEM_LENGTH = 256 * 1024
    private const val MAX_RAW_KEY_CHARS = 1024 * 1024
    private const val MAX_BCRYPT_ROUNDS = 1024L
    private const val MIN_BCRYPT_SALT_BYTES = 8
    private const val MAX_BCRYPT_SALT_BYTES = 64
    private const val MIN_ARGON2_MEMORY_KIB = 8
    private const val MAX_ARGON2_MEMORY_KIB = 256 * 1024
    private const val MAX_ARGON2_PASSES = 64
    private const val MAX_ARGON2_PARALLELISM = 8
    private val PROC_TYPE_ENCRYPTED = Regex("Proc-Type:\\s*4\\s*,\\s*ENCRYPTED", RegexOption.IGNORE_CASE)
    private val COMMENT_CONTROL_CHARS = Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]+")

    /** Generate a standard `openssh-key-v1` key pair and verify round-trip loading through `sshj`. */
    fun generate(
        algorithm: KeyGenAlgorithm,
        passphrase: String = "",
        comment: String = "",
    ): ParsedKey {
        ensureProvider()
        val random = SecureRandom()
        val cleanComment = sanitizeComment(comment)
        val pem = when (algorithm) {
            KeyGenAlgorithm.ED25519 -> generateEd25519OpenSsh(random, passphrase, cleanComment)
            KeyGenAlgorithm.RSA_4096 -> generateRsaOpenSsh(4096, random, passphrase, cleanComment)
        }
        return when (val parsed = inspectPrivateKey(pem, passphrase, commentOverride = cleanComment)) {
            is KeyParseResult.Ok -> parsed.key
            is KeyParseResult.Invalid -> throw IllegalStateException("Generated key failed verification: ${parsed.error}")
        }
    }

    /**
     * Validate any supported private key (`openssh-key-v1`, PKCS#1 RSA/EC/DSA, PKCS#8 plain &
     * PBES1/PBES2 encrypted, `.ppk`) in memory without touching disk.
     */
    fun inspectPrivateKey(
        pem: String,
        passphrase: String = "",
        commentOverride: String? = null,
    ): KeyParseResult {
        ensureProvider()
        if (pem.length > MAX_RAW_KEY_CHARS) {
            return KeyParseResult.Invalid(if (pem.isBlank()) KeyParseError.EMPTY_KEY else KeyParseError.INVALID_KEY)
        }
        val normalized = normalizeKeyPem(pem)
        if (normalized.isEmpty()) return KeyParseResult.Invalid(KeyParseError.EMPTY_KEY)
        if (normalized.length > MAX_KEY_PEM_LENGTH) return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
        if (!hasSafeKdfParameters(normalized)) return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)

        val encrypted = isEncryptedPrivateKey(normalized)
        if (encrypted && passphrase.isEmpty()) {
            return KeyParseResult.Invalid(KeyParseError.PASSPHRASE_REQUIRED)
        }

        val kp: KeyProvider = try {
            loadKeyProvider(normalized, passphrase) ?: return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
        } catch (e: Exception) {
            return KeyParseResult.Invalid(classifyException(e, encrypted, passphrase.isNotEmpty()))
        }

        val pub: PublicKey
        val type: KeyType
        try {
            pub = kp.public ?: return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
            if (kp.private == null) return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
            type = kp.type ?: return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
            if (type == KeyType.UNKNOWN) return KeyParseResult.Invalid(KeyParseError.INVALID_KEY)
        } catch (e: Exception) {
            return KeyParseResult.Invalid(classifyException(e, encrypted, passphrase.isNotEmpty()))
        }

        val cleanOverride = commentOverride?.let(::sanitizeComment)
        val effectiveComment = cleanOverride ?: extractEmbeddedComment(normalized, passphrase)
        return KeyParseResult.Ok(
            ParsedKey(
                privateKeyPem = normalized,
                passphrase = passphrase,
                publicKey = formatAuthorizedKey(type, pub, effectiveComment),
                fingerprint = HostKeyFingerprint.of(pub),
                algorithmLabel = algorithmLabel(type, pub),
                comment = effectiveComment,
            )
        )
    }

    /** Replace or append a single-line comment on an `authorized_keys` public key line when absent. */
    fun withCommentIfBlank(parsed: ParsedKey, fallbackComment: String): String {
        if (parsed.comment.isNotBlank()) return parsed.publicKey
        val clean = sanitizeComment(fallbackComment)
        return if (clean.isEmpty()) parsed.publicKey else "${parsed.publicKey} $clean"
    }

    fun formatAuthorizedKey(type: KeyType, pub: PublicKey, comment: String = ""): String {
        val blob = Base64.toBase64String(Buffer.PlainBuffer().putPublicKey(pub).compactData)
        val clean = sanitizeComment(comment)
        return if (clean.isEmpty()) "$type $blob" else "$type $blob $clean"
    }

    fun algorithmLabel(type: KeyType, pub: PublicKey): String = when (type) {
        KeyType.ED25519 -> "Ed25519"
        KeyType.RSA -> listOfNotNull("RSA", (pub as? RSAPublicKey)?.modulus?.bitLength()?.toString()).joinToString(" ")
        KeyType.ECDSA256 -> "ECDSA P-256"
        KeyType.ECDSA384 -> "ECDSA P-384"
        KeyType.ECDSA521 -> "ECDSA P-521"
        KeyType.DSA -> listOfNotNull("DSA", (pub as? DSAPublicKey)?.params?.p?.bitLength()?.toString()).joinToString(" ")
        KeyType.SK_ED25519 -> "SK-Ed25519"
        KeyType.SK_ECDSA -> "SK-ECDSA"
        else -> type.toString()
    }

    /** Fallback label for legacy unverified keys migrated from older app versions. */
    fun guessKeyKindLabel(pem: String): String = when {
        pem.contains("OPENSSH PRIVATE KEY") -> "OpenSSH"
        pem.contains("RSA PRIVATE KEY") -> "RSA"
        pem.contains("EC PRIVATE KEY") -> "ECDSA"
        pem.contains("DSA PRIVATE KEY") -> "DSA"
        pem.contains("PuTTY-User-Key-File") -> "PPK"
        pem.contains("PRIVATE KEY") -> "PKCS#8"
        else -> "SSH"
    }

    fun sanitizeComment(comment: String): String =
        comment.replace(COMMENT_CONTROL_CHARS, " ").trim()

    internal fun loadKeyProvider(normalizedPem: String, passphrase: String): KeyProvider? {
        if (EncryptedPkcs8.matches(normalizedPem)) {
            val passChars = passphrase.toCharArray()
            val plain = try {
                EncryptedPkcs8.decryptToPem(normalizedPem, passChars)
            } finally {
                passChars.fill('\u0000')
            }
            return PKCS8KeyFile().apply { init(StringReader(plain)) }
        }
        val format = KeyProviderUtil.detectKeyFileFormat(normalizedPem, false)
        if (format == null || format == KeyFormat.Unknown) return null
        val provider: FileKeyProvider = Factory.Named.Util.create(DefaultConfig().fileKeyProviderFactories, format.toString())
            ?: return null
        provider.init(normalizedPem, null, keyPasswordFinder(passphrase))
        return provider
    }

    internal fun isEncryptedPrivateKey(pem: String): Boolean {
        val normalized = if (pem.length <= MAX_RAW_KEY_CHARS) normalizeKeyPem(pem) else return false
        if (EncryptedPkcs8.matches(normalized)) return true
        if (PROC_TYPE_ENCRYPTED.containsMatchIn(normalized)) return true
        openSshCipherName(normalized)?.let { return it != "none" }
        puttyHeaders(normalized)?.get("encryption")?.let { return !it.equals("none", ignoreCase = true) }
        return false
    }

    /** Pre-validate untrusted KDF work/memory parameters before handing input to `sshj`. */
    private fun hasSafeKdfParameters(normalizedPem: String): Boolean {
        if (normalizedPem.contains(OPENSSH_BEGIN)) {
            val bytes = openSshBodyBytes(normalizedPem) ?: return false
            try {
                val buf = Buffer.PlainBuffer(bytes)
                val magic = ByteArray(OPENSSH_MAGIC.size)
                buf.readRawBytes(magic)
                if (!magic.contentEquals(OPENSSH_MAGIC)) return false
                val cipherName = buf.readString()
                val kdfName = buf.readString()
                val kdfOptions = buf.readBytes()
                if (cipherName == "none") {
                    return kdfName == "none" && kdfOptions.isEmpty()
                }
                if (kdfName != "bcrypt") return false
                val kdfBuf = Buffer.PlainBuffer(kdfOptions)
                val salt = kdfBuf.readBytes()
                val rounds = kdfBuf.readUInt32()
                return kdfBuf.available() == 0 &&
                    salt.size in MIN_BCRYPT_SALT_BYTES..MAX_BCRYPT_SALT_BYTES &&
                    rounds in 1L..MAX_BCRYPT_ROUNDS
            } catch (_: Exception) {
                return false
            } finally {
                bytes.fill(0)
            }
        }

        val ppkHeaders = puttyHeaders(normalizedPem)
        if (ppkHeaders != null) {
            val encryption = ppkHeaders["encryption"] ?: return false
            if (!encryption.equals("none", ignoreCase = true)) {
                val isV3 = normalizedPem.startsWith("PuTTY-User-Key-File-3:", ignoreCase = true)
                val kdf = ppkHeaders["key-derivation"]
                if (isV3 || kdf != null) {
                    val memory = ppkHeaders["argon2-memory"]?.toIntOrNull() ?: return false
                    val passes = ppkHeaders["argon2-passes"]?.toIntOrNull() ?: return false
                    val parallelism = ppkHeaders["argon2-parallelism"]?.toIntOrNull() ?: return false
                    if (memory !in MIN_ARGON2_MEMORY_KIB..MAX_ARGON2_MEMORY_KIB) return false
                    if (passes !in 1..MAX_ARGON2_PASSES) return false
                    if (parallelism !in 1..MAX_ARGON2_PARALLELISM) return false
                }
            }
        }
        return true
    }

    private fun generateEd25519OpenSsh(random: SecureRandom, passphrase: String, comment: String): String {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val pair = gen.generateKeyPair()
        val seed32 = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub32 = (pair.public as Ed25519PublicKeyParameters).encoded
        val seedAndPub = ByteArray(64)
        try {
            System.arraycopy(seed32, 0, seedAndPub, 0, 32)
            System.arraycopy(pub32, 0, seedAndPub, 32, 32)
            val pubWire = Buffer.PlainBuffer()
                .putString("ssh-ed25519")
                .putBytes(pub32)
                .compactData
            return encodeOpenSshV1(pubWire, passphrase, comment, random) { inner ->
                inner.putString("ssh-ed25519")
                inner.putBytes(pub32)
                inner.putBytes(seedAndPub)
            }
        } finally {
            seed32.fill(0)
            seedAndPub.fill(0)
        }
    }

    private fun generateRsaOpenSsh(bits: Int, random: SecureRandom, passphrase: String, comment: String): String {
        // Prefer Android's native BoringSSL provider on device (~100ms for 4096-bit RSA vs ~2s in pure Java).
        val kpg = try {
            KeyPairGenerator.getInstance("RSA", "AndroidOpenSSL")
        } catch (_: Exception) {
            KeyPairGenerator.getInstance("RSA")
        }
        kpg.initialize(bits, random)
        val kp = kpg.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val crt: RSAPrivateCrtKeySpec = when (val priv = kp.private) {
            is RSAPrivateCrtKey -> RSAPrivateCrtKeySpec(
                priv.modulus,
                priv.publicExponent,
                priv.privateExponent,
                priv.primeP,
                priv.primeQ,
                priv.primeExponentP,
                priv.primeExponentQ,
                priv.crtCoefficient,
            )
            else -> KeyFactory.getInstance("RSA").getKeySpec(priv, RSAPrivateCrtKeySpec::class.java)
        }
        val pubWire = Buffer.PlainBuffer()
            .putString("ssh-rsa")
            .putMPInt(pub.publicExponent)
            .putMPInt(pub.modulus)
            .compactData
        return encodeOpenSshV1(pubWire, passphrase, comment, random) { inner ->
            // Exact field order expected by OpenSSH PROTOCOL.key and sshj's OpenSSHKeyV1KeyFile.readRsaPrivateKeySpec:
            // n, e, d, iqmp (crtCoefficient), p, q.
            inner.putString("ssh-rsa")
            inner.putMPInt(crt.modulus)
            inner.putMPInt(crt.publicExponent)
            inner.putMPInt(crt.privateExponent)
            inner.putMPInt(crt.crtCoefficient)
            inner.putMPInt(crt.primeP)
            inner.putMPInt(crt.primeQ)
        }
    }

    private fun encodeOpenSshV1(
        pubWire: ByteArray,
        passphrase: String,
        comment: String,
        random: SecureRandom,
        writePrivateFields: (Buffer.PlainBuffer) -> Unit,
    ): String {
        val inner = Buffer.PlainBuffer()
        val check = random.nextInt()
        inner.putUInt32FromInt(check)
        inner.putUInt32FromInt(check)
        writePrivateFields(inner)
        inner.putString(comment)

        val encrypted = passphrase.isNotEmpty()
        val blockSize = if (encrypted) 16 else 8
        var pad = 1
        while (inner.available() % blockSize != 0) {
            inner.putByte((pad and 0xff).toByte())
            pad++
        }

        val innerBytes = inner.compactData
        inner.array().fill(0)

        val cipherName: String
        val kdfName: String
        val kdfOptions: ByteArray
        val privatePayload: ByteArray

        if (!encrypted) {
            cipherName = "none"
            kdfName = "none"
            kdfOptions = ByteArray(0)
            privatePayload = innerBytes
        } else {
            cipherName = "aes256-ctr"
            kdfName = "bcrypt"
            val salt = ByteArray(16).also(random::nextBytes)
            kdfOptions = Buffer.PlainBuffer().putBytes(salt).putUInt32(BCRYPT_ROUNDS.toLong()).compactData
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val keyIv = ByteArray(48)
            var key32: ByteArray? = null
            var iv16: ByteArray? = null
            try {
                BCrypt().pbkdf(passBytes, salt, BCRYPT_ROUNDS, keyIv)
                key32 = keyIv.copyOfRange(0, 32)
                iv16 = keyIv.copyOfRange(32, 48)
                val payload = innerBytes.copyOf()
                val cipher = BlockCiphers.AES256CTR().create()
                cipher.init(Cipher.Mode.Encrypt, key32, iv16)
                cipher.update(payload, 0, payload.size)
                privatePayload = payload
            } finally {
                innerBytes.fill(0)
                passBytes.fill(0)
                keyIv.fill(0)
                key32?.fill(0)
                iv16?.fill(0)
            }
        }

        val outer = Buffer.PlainBuffer()
        var outerBytes: ByteArray? = null
        try {
            outer.putRawBytes(OPENSSH_MAGIC)
            outer.putString(cipherName)
            outer.putString(kdfName)
            outer.putBytes(kdfOptions)
            outer.putUInt32(1L)
            outer.putBytes(pubWire)
            outer.putBytes(privatePayload)

            val rawOuter = outer.compactData
            outerBytes = rawOuter
            val b64 = Base64.toBase64String(rawOuter)
            return buildString(OPENSSH_BEGIN.length + OPENSSH_END.length + b64.length + b64.length / 70 + 8) {
                append(OPENSSH_BEGIN).append('\n')
                var i = 0
                while (i < b64.length) {
                    val end = minOf(i + 70, b64.length)
                    append(b64, i, end).append('\n')
                    i = end
                }
                append(OPENSSH_END)
            }
        } finally {
            privatePayload.fill(0)
            outerBytes?.fill(0)
            outer.array().fill(0)
        }
    }

    private fun classifyException(e: Exception, encrypted: Boolean, hasPassphrase: Boolean): KeyParseError {
        var cur: Throwable? = e
        val visited = HashSet<Throwable>()
        while (cur != null && visited.add(cur)) {
            if (cur is KeyDecryptionFailedException) {
                return if (hasPassphrase) KeyParseError.WRONG_PASSPHRASE else KeyParseError.PASSPHRASE_REQUIRED
            }
            if (encrypted && hasPassphrase && cur is IOException) {
                val msg = cur.message.orEmpty()
                if (msg.contains("Integrity check failed", ignoreCase = true) ||
                    msg.contains("Invalid passphrase", ignoreCase = true) ||
                    msg.contains("decryption failed", ignoreCase = true) ||
                    msg.contains("mac check", ignoreCase = true)
                ) {
                    return KeyParseError.WRONG_PASSPHRASE
                }
            }
            cur = cur.cause
        }
        return KeyParseError.INVALID_KEY
    }

    private fun extractEmbeddedComment(pem: String, passphrase: String): String {
        puttyHeaders(pem)?.get("comment")?.let { return sanitizeComment(it) }
        return extractOpenSshComment(pem, passphrase)
    }

    private fun openSshBodyBytes(pem: String): ByteArray? {
        val start = pem.indexOf(OPENSSH_BEGIN)
        if (start < 0) return null
        val end = pem.indexOf(OPENSSH_END, start + OPENSSH_BEGIN.length)
        if (end < 0) return null
        val b64 = pem.substring(start + OPENSSH_BEGIN.length, end).filterNot(Char::isWhitespace)
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64)
        } catch (_: Exception) {
            null
        }
    }

    private fun openSshCipherName(pem: String): String? {
        val bytes = openSshBodyBytes(pem) ?: return null
        return try {
            val buf = Buffer.PlainBuffer(bytes)
            val magic = ByteArray(OPENSSH_MAGIC.size)
            buf.readRawBytes(magic)
            if (!magic.contentEquals(OPENSSH_MAGIC)) null else buf.readString()
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun extractOpenSshComment(pem: String, passphrase: String): String {
        val bytes = openSshBodyBytes(pem) ?: return ""
        return try {
            val outer = Buffer.PlainBuffer(bytes)
            val magic = ByteArray(OPENSSH_MAGIC.size)
            outer.readRawBytes(magic)
            if (!magic.contentEquals(OPENSSH_MAGIC)) return ""
            val cipherName = outer.readString()
            val kdfName = outer.readString()
            val kdfOptions = outer.readBytes()
            if (outer.readUInt32AsInt() != 1) return ""
            outer.readBytes() // public key wire blob
            val privBytes = outer.readBytes()

            val plainBytes: ByteArray = if (cipherName == "none") {
                privBytes
            } else {
                if (kdfName != "bcrypt" || passphrase.isEmpty()) return ""
                val factory = when (cipherName) {
                    "aes256-ctr" -> BlockCiphers.AES256CTR()
                    "aes192-ctr" -> BlockCiphers.AES192CTR()
                    "aes128-ctr" -> BlockCiphers.AES128CTR()
                    "aes256-cbc" -> BlockCiphers.AES256CBC()
                    "aes192-cbc" -> BlockCiphers.AES192CBC()
                    "aes128-cbc" -> BlockCiphers.AES128CBC()
                    else -> return ""
                }
                val kdfBuf = Buffer.PlainBuffer(kdfOptions)
                val salt = kdfBuf.readBytes()
                val roundsLong = kdfBuf.readUInt32()
                if (kdfBuf.available() != 0 ||
                    salt.size !in MIN_BCRYPT_SALT_BYTES..MAX_BCRYPT_SALT_BYTES ||
                    roundsLong !in 1L..MAX_BCRYPT_ROUNDS
                ) {
                    return ""
                }
                val rounds = roundsLong.toInt()
                val cipher = factory.create()
                val keyLen = cipher.blockSize
                val ivLen = cipher.ivSize
                val passBytes = passphrase.toByteArray(Charsets.UTF_8)
                val keyIv = ByteArray(keyLen + ivLen)
                var key: ByteArray? = null
                var iv: ByteArray? = null
                try {
                    BCrypt().pbkdf(passBytes, salt, rounds, keyIv)
                    key = keyIv.copyOfRange(0, keyLen)
                    iv = keyIv.copyOfRange(keyLen, keyLen + ivLen)
                    val decrypted = privBytes.copyOf()
                    cipher.init(Cipher.Mode.Decrypt, key, iv)
                    cipher.update(decrypted, 0, decrypted.size)
                    decrypted
                } finally {
                    passBytes.fill(0)
                    keyIv.fill(0)
                    key?.fill(0)
                    iv?.fill(0)
                }
            }

            try {
                val inner = Buffer.PlainBuffer(plainBytes)
                val check1 = inner.readUInt32AsInt()
                val check2 = inner.readUInt32AsInt()
                if (check1 != check2) return ""
                when (inner.readString()) {
                    "ssh-ed25519" -> {
                        inner.readBytes()
                        inner.readBytes()
                    }
                    "ssh-rsa" -> repeat(6) { inner.readMPInt() }
                    "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                        inner.readString()
                        inner.readBytes()
                        inner.readMPInt()
                    }
                    "ssh-dss" -> repeat(5) { inner.readMPInt() }
                    "sk-ssh-ed25519@openssh.com" -> {
                        inner.readBytes()
                        inner.readString()
                        inner.readByte()
                        inner.readBytes()
                        inner.readBytes()
                    }
                    "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                        inner.readString()
                        inner.readBytes()
                        inner.readString()
                        inner.readByte()
                        inner.readBytes()
                        inner.readBytes()
                    }
                    else -> return ""
                }
                sanitizeComment(inner.readString())
            } finally {
                plainBytes.fill(0)
            }
        } catch (_: Exception) {
            ""
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Parse `.ppk` `Name: Value` lines using the same `": "` delimiter rule as `PuTTYKeyFile`
     * (including `Key-Derivation` and `Argon2-*` lines that follow `Public-Lines:`).
     */
    private fun puttyHeaders(pem: String): Map<String, String>? {
        if (!pem.startsWith("PuTTY-User-Key-File-", ignoreCase = true)) return null
        val headers = HashMap<String, String>()
        for (line in pem.lineSequence()) {
            val sep = line.indexOf(": ")
            if (sep > 0) {
                headers[line.substring(0, sep).lowercase(java.util.Locale.ROOT)] = line.substring(sep + 2).trim()
            }
        }
        return headers
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Exception) {
        }
    }
}
