package com.briqt.moke.terminal

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.util.encoders.Base64
import java.security.MessageDigest
import java.security.PublicKey

/**
 * 主机密钥指纹（纯函数，无 Android 依赖，可被 JVM 单测覆盖）。
 *
 * 首连确认弹窗让用户在服务器上执行 `ssh-keygen -lf …` 核对，所以显示的值必须和它**逐字一致**：
 * 对 SSH 线格式公钥（`string 类型名 + 密钥参数`）取 SHA-256，base64 去掉填充。
 *
 * v0.1.20 及以前错把 `PublicKey.encoded`（X.509 SubjectPublicKeyInfo）拿去算，显示值永远对不上
 * ssh-keygen，而这种格式已经存进了用户的已知主机表——所以保留 [legacyOf] 专门用来识别旧记录，
 * 见 [check]。
 */
object HostKeyFingerprint {

    /** 与 `ssh-keygen -lf` 一致的 `SHA256:…` 指纹。 */
    fun of(key: PublicKey): String =
        format(Buffer.PlainBuffer().putPublicKey(hostKeyOf(key)).compactData)

    /** 旧版（≤ v0.1.20）存下的格式。只用于识别与迁移旧记录，不再展示给用户。 */
    fun legacyOf(key: PublicKey): String = format(hostKeyOf(key).encoded)

    /**
     * 服务器出示主机证书时 sshj 交来的是证书本身；`ssh-keygen -lf` 对着服务器上的主机公钥核对，
     * 证书每次重签也不该被当成"密钥变了"，所以一律对证书里的那把公钥取指纹。
     */
    fun hostKeyOf(key: PublicKey): PublicKey = if (key is Certificate<*>) key.key else key

    enum class Result {
        /** 没有记录：首次连接。 */
        UNKNOWN,
        /** 与记录一致。 */
        MATCH,
        /** 记录是旧格式、且就是这把密钥：放行，并应改存成新格式。 */
        LEGACY_MATCH,
        /** 与记录不一致：密钥变了。 */
        CHANGED,
    }

    /**
     * 拿当前密钥和已存记录比对。旧格式记录用旧算法对**同一把**密钥重算后比较，
     * 所以迁移不会放过任何一把不同的密钥。
     */
    fun check(saved: String?, key: PublicKey): Result = when (saved) {
        null -> Result.UNKNOWN
        of(key) -> Result.MATCH
        legacyOf(key) -> Result.LEGACY_MATCH
        else -> Result.CHANGED
    }

    private fun format(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "SHA256:" + Base64.toBase64String(digest).trimEnd('=')
    }
}
