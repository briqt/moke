package com.briqt.moke

import android.app.Application
import android.os.Debug
import android.util.Log
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.terminal.HostKeyPrompt
import com.briqt.moke.terminal.SessionManager
import com.briqt.moke.terminal.sftp.TransferManager
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
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

    /** 全应用唯一的传输队列（与会话同理：不能随 Activity/ViewModel 销毁而中断）。 */
    val transfers: TransferManager by lazy { TransferManager(this, HostStore(this)) }

    override fun onCreate() {
        super.onCreate()
        installOomHprofDumper()
        installTerminalStatusText()
        mirrorHostKeyPolicy()
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) // "BC"
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (t: Throwable) {
            // 极少数系统禁止替换；保底不崩，连接时再由 sshj 报错。
        }
    }

    /**
     * 把「首连自动信任主机密钥」镜像给 [HostKeyPrompt]。
     *
     * 主机密钥校验发生在连接线程上、必须同步返回 true/false，不能在那儿读一次 DataStore；
     * 也不能挂在 ViewModel 上——后台传输/保活会话在没有界面时同样会建连接。所以在**进程作用域**
     * 维护这个开关的镜像；默认 false（先问），拿不到值时的失败方向是"问"，而不是"静默信任"。
     */
    private fun mirrorHostKeyPolicy() {
        val settings = SettingsStore(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.autoTrustNewHostKey.collect { HostKeyPrompt.autoTrust = it }
        }
    }

    /**
     * 会话状态行（「连接失败」「会话结束」）直接写进终端画面，属于用户可见文案，但产生它们的
     * `TerminalSession` 在 vendored 模块里、拿不到 Android 资源，默认只能是英文。这里换成
     * 按**应用内语言**取资源的实现——每次调用现取，切语言即时生效（issue #1）。
     */
    private fun installTerminalStatusText() {
        TerminalSession.statusText = object : TerminalSession.StatusText {
            override fun connectFailed(reason: String): String =
                localized(R.string.term_connect_failed, reason)

            override fun sessionEnded(exitCode: Int): String = when {
                exitCode > 0 -> localized(R.string.term_session_ended_code, exitCode)
                exitCode < 0 -> localized(R.string.term_session_ended_signal, -exitCode)
                else -> localized(R.string.term_session_ended)
            }
        }
    }

    /**
     * 进程发生 OOM 时把堆快照写到 app 外部文件目录（`Android/data/<pkg>/files/moke-oom.hprof`），
     * 用于事后定位"到底谁把堆吃满了"。**正式包同样启用**：内存类问题只在真实使用中暴露，
     * 缺了这份快照就只能靠猜。只在崩溃那一刻写一次，正常运行零开销。
     * 包住既有的默认 handler（LeakCanary 也会装 handler，链式委托保证互不吞掉）。
     * 路径预先算好，避免 OOM 时再分配字符串失败。
     */
    private fun installOomHprofDumper() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val hprofPath = File(dir, "moke-oom.hprof").absolutePath
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var t: Throwable? = throwable
            var isOom = false
            while (t != null) { if (t is OutOfMemoryError) { isOom = true; break }; t = t.cause }
            if (isOom) {
                try {
                    Debug.dumpHprofData(hprofPath)
                    Log.e("moke", "OOM! heap dump written to $hprofPath")
                } catch (e: Throwable) {
                    Log.e("moke", "OOM heap dump failed", e)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
