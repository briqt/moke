package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
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
    private val context: Context,
    private val onMessage: (String) -> Unit,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fp = known.fingerprint(key)
        return when (val saved = known.stored(id)) {
            null -> {
                known.store(id, fp)
                onMessage(context.getString(R.string.hostkey_first_seen, id, fp))
                true
            }
            fp -> true
            else -> {
                // 资源里用 \n 断行，喂终端需 \r\n。
                onMessage(context.getString(R.string.hostkey_changed, saved, fp).replace("\n", "\r\n"))
                false
            }
        }
    }

    // sshj 用于主机密钥算法协商；TOFU 场景无偏好，返回空表示不限定。
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
