package com.briqt.moke.terminal

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.PublicKey
import java.security.Security

/**
 * 钉死两件事：① 显示的指纹与 `ssh-keygen -lf` 逐字一致（首连弹窗让用户照这个核对）；
 * ② v0.1.20 存下的旧格式能被识别并迁移，而换了密钥依旧判为变更。
 *
 * 期望值全部来自 OpenSSH 的 `ssh-keygen -lf`；旧格式期望值由独立脚本对 X.509 编码取哈希得到。
 */
class HostKeyFingerprintTest {

    // 与 MokeApplication 一致：用随包的完整 BouncyCastle 提供 ECDSA / Ed25519 等算法。
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
    }

    private fun key(blob: String): PublicKey = Buffer.PlainBuffer(Base64.decode(blob)).readPublicKey()

    private val ed25519 = key("AAAAC3NzaC1lZDI1NTE5AAAAICX2C+2ED3ddnUjUXBy3mM74kE3kD8DpzYrytn+OFMCy")
    private val ecdsa = key(
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBL6mL0rJ5FZSO5vZOUAjxonbaFqIEKSwPj3vLap6gO+Y+6KiX9mmi0GKs3k9Oj5jutTAEE2gX2qJh8UdbBlXsAE="
    )
    private val rsa = key(
        "AAAAB3NzaC1yc2EAAAADAQABAAABAQCxsqEnXn3xG2RdMYuXz9qL7Qb8qZwhwbZ/Sj5vR3DnsjHNG9nFWtPtGMzIEq3uNQpUIQ9YC5FoGmo6bQ2f8vX6aMScfGaioEIVqBVQ0H6nnUjnrvYaEV/EA/utgBaC7NHQfydoHTmDkNT5elov3/HeK3jZIMOd9IQsgznu+GDySSrQ8Ljo8PFwjrGyBachBo5ycT9nE35IUYlFRxX4OnBIHneTNHP1M8NpnIgJPUC6WYYJ4cfRh/kGILNtjudJzetHimSPDHSRbPu5jVIZKBhTbGs6fk5opdgvRvBGcKo/NKSDFyf+BHsFTFYcegucnJqkVmJi1bKRW+hi+fIIA7nV"
    )

    @Test
    fun `matches ssh-keygen for common key types`() {
        assertEquals("SHA256:eIdRL/RagzDlK2ZqJ/pgpX/PztTOBKbJDnJZOc+DCas", HostKeyFingerprint.of(ed25519))
        assertEquals("SHA256:ShC2NwYXqBI1iZFoXXeljTz0sLy9yEHqebo+jOgGZHY", HostKeyFingerprint.of(ecdsa))
        assertEquals("SHA256:F3q9aUP/j+Soy0SRwhfplHh7YsxUDpWmRUVB6rDpuDU", HostKeyFingerprint.of(rsa))
    }

    @Test
    fun `legacy format reproduces what v0_1_20 stored`() {
        assertEquals("SHA256:48Yw4IHnFVTPaSwm/wUH4I4HN133sGQT/BNdVgwWOg8", HostKeyFingerprint.legacyOf(ed25519))
    }

    @Test
    fun `check covers first use, match, migration and change`() {
        assertEquals(HostKeyFingerprint.Result.UNKNOWN, HostKeyFingerprint.check(null, ed25519))
        assertEquals(HostKeyFingerprint.Result.MATCH, HostKeyFingerprint.check(HostKeyFingerprint.of(ed25519), ed25519))
        assertEquals(
            HostKeyFingerprint.Result.LEGACY_MATCH,
            HostKeyFingerprint.check(HostKeyFingerprint.legacyOf(ed25519), ed25519),
        )
        // 换了一把密钥：新旧两种格式的记录都必须判为变更，迁移不能成为放行的后门。
        assertEquals(HostKeyFingerprint.Result.CHANGED, HostKeyFingerprint.check(HostKeyFingerprint.of(ed25519), rsa))
        assertEquals(HostKeyFingerprint.Result.CHANGED, HostKeyFingerprint.check(HostKeyFingerprint.legacyOf(ed25519), rsa))
    }

    @Test
    fun `host certificate is fingerprinted by the key it certifies`() {
        fun cert(serial: Long) = Certificate.getBuilder<PublicKey>()
            .publicKey(ed25519)
            .serial(BigInteger.valueOf(serial))
            .nonce(ByteArray(32) { serial.toByte() })
            .build()
        assertEquals(HostKeyFingerprint.of(ed25519), HostKeyFingerprint.of(cert(1)))
        // 证书重签（序列号、nonce 都变了）不算换密钥。
        assertEquals(HostKeyFingerprint.Result.MATCH, HostKeyFingerprint.check(HostKeyFingerprint.of(cert(1)), cert(2)))
        assertEquals(
            HostKeyFingerprint.Result.LEGACY_MATCH,
            HostKeyFingerprint.check(HostKeyFingerprint.legacyOf(ed25519), cert(1)),
        )
    }
}
