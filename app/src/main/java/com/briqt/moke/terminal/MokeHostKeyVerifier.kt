package com.briqt.moke.terminal

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * TOFU（Trust On First Use）主机密钥校验：
 *  - 首次连接：记录指纹并放行（在终端提示指纹）。
 *  - 之后连接：指纹一致放行；不一致拒绝并提示可能的中间人攻击。
 *
 * 相比 PromiscuousVerifier（接受任意密钥），能在首次信任后检测密钥变更。
 */
class MokeHostKeyVerifier(
    private val known: KnownHosts,
    private val onMessage: (String) -> Unit,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fp = known.fingerprint(key)
        return when (val saved = known.stored(id)) {
            null -> {
                known.store(id, fp)
                onMessage("首次连接 $id，已记录主机指纹 $fp")
                true
            }
            fp -> true
            else -> {
                onMessage("⚠️ 主机密钥已变更！\r\n  记录: $saved\r\n  当前: $fp\r\n可能存在中间人攻击，已拒绝连接。若确为服务器变更，请删除该主机后重建。")
                false
            }
        }
    }

    // sshj 用于主机密钥算法协商；TOFU 场景无偏好，返回空表示不限定。
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
