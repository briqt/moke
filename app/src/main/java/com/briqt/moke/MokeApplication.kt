package com.briqt.moke

import android.app.Application
import com.briqt.moke.terminal.SessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android 自带的是精简版 BouncyCastle（注册名 "BC"，缺 X25519 等算法），
 * 会导致 sshj 的 curve25519-sha256 密钥交换报 "no such algorithm: X25519 for provider BC"。
 * 这里在进程启动时把 "BC" 替换为随包的完整 BouncyCastle（bcprov-jdk18on），使其支持完整算法集。
 *
 * 会话管理器 [sessions] 提升到 **Application 作用域**：会话不随 Activity/ViewModel 销毁而消失，
 * 配合前台服务 [com.briqt.moke.terminal.MokeSessionService] 让会话在 app 退到后台/关屏时仍存活
 * （参考 Termux/ConnectBot 的前台服务持有会话思路）。
 */
class MokeApplication : Application() {

    /** 全应用唯一的会话管理器。 */
    val sessions: SessionManager by lazy { SessionManager(this) }

    override fun onCreate() {
        super.onCreate()
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) // "BC"
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (t: Throwable) {
            // 极少数系统禁止替换；保底不崩，连接时再由 sshj 报错。
        }
    }
}
