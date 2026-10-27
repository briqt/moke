package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.localized
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * TOFU（Trust On First Use）主机密钥校验：
 *  - 首次连接：把指纹摆给用户确认（见 [HostKeyPrompt]），点信任才记录并放行；
 *    设置里打开「首连自动信任」则静默记录（旧行为）。
 *  - 之后连接：指纹一致放行；不一致拒绝并提示可能的中间人攻击。
 *
 * TOFU 的全部安全性都押在"第一次连的是真主机"上：那一刻若被中间人截住，假指纹一入库，
 * 后面每次校验都只会稳稳确认那把假密钥——所以默认要问一次。
 */
class MokeHostKeyVerifier(
    private val known: KnownHosts,
    private val context: Context,
    private val onMessage: (String) -> Unit,
    /** 经网络到达时的指纹命名空间（网络 id）；null = 直连，沿用旧键。 */
    private val namespace: String? = null,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = KnownHosts.idOf(hostname, port, namespace)
        val address = KnownHosts.idOf(hostname, port)
        val fp = HostKeyFingerprint.of(key)
        val saved = known.stored(id)
        return when (HostKeyFingerprint.check(saved, key)) {
            HostKeyFingerprint.Result.UNKNOWN -> {
                if (!HostKeyPrompt.autoTrust && !HostKeyPrompt.ask(id, fp, keyTypeOf(HostKeyFingerprint.hostKeyOf(key)))) {
                    onMessage(context.localized(R.string.hostkey_rejected, address).replace("\n", "\r\n"))
                    return false
                }
                known.store(id, fp)
                onMessage(context.localized(R.string.hostkey_first_seen, address, fp))
                true
            }
            HostKeyFingerprint.Result.MATCH -> true
            // 旧版存下的格式、且就是这把密钥：静默改存成新格式，用户无感。
            HostKeyFingerprint.Result.LEGACY_MATCH -> {
                known.store(id, fp)
                true
            }
            HostKeyFingerprint.Result.CHANGED -> {
                val savedFp = saved.orEmpty()
                onMessage(context.localized(R.string.hostkey_changed, savedFp, fp).replace("\n", "\r\n"))
                HostKeyChangePrompt.offer(id, savedFp, fp)
                false
            }
        }
    }

    // sshj 用于主机密钥算法协商；TOFU 场景无偏好，返回空表示不限定。
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    /** 密钥算法名（ssh-ed25519 / ssh-rsa …）；sshj 认不出就退回 JCA 的算法名。 */
    private fun keyTypeOf(key: PublicKey): String =
        runCatching { KeyType.fromKey(key).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: key.algorithm.orEmpty()
}
