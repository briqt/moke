package com.briqt.moke.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 首次连接新主机时的指纹确认。
 *
 * TOFU 的安全性全押在"第一次连的是真主机"这一刻：那时若被中间人截住，假指纹一入库，之后
 * 每次校验都只会稳稳地确认那把假密钥。所以默认要把指纹摆到用户面前问一次。
 *
 * 校验发生在**连接线程**里（sshj 的 `HostKeyVerifier.verify` 要同步返回 true/false），而回答
 * 只能来自 UI 线程，所以 [ask] 阻塞等待。四条约束：
 *
 * - **同一台主机只问一次**：mosh 主机一次连接会同时开引导连接与 tmux 控制连接，两条都撞上未知
 *   密钥。它们必须共用一次弹窗和同一个答案——否则用户要为同一台主机点两次，而排在后面那条
 *   还会因为等待超过 sshj 的握手超时而失败。
 * - **一次只显示一个弹窗**：不同主机同时首连时排队。
 * - **一定有结果**：超时（[TIMEOUT_MS]）按拒绝处理，绝不把连接线程永久挂住。
 * - **不放行未确认的密钥**：进程被杀、页面销毁、超时都落在"拒绝"这一侧。
 *
 * 对象是 Application 作用域的（连接线程与 UI 都要够得着，而 verifier 是每条连接新建的）。
 */
object HostKeyPrompt {

    /** 待确认的一次首连。[id] 用于把回答对回请求，避免过期弹窗的回答落到新请求上。 */
    data class Request(
        val id: String,
        /** `host:port`（校验发生在传输层，这里只认得目标地址，没有连接条目的展示名）。 */
        val target: String,
        /** `SHA256:…` 指纹。 */
        val fingerprint: String,
        /** 密钥算法（如 ssh-ed25519）。 */
        val keyType: String,
    )

    private class Pending(val request: Request) {
        val done = CountDownLatch(1)

        @Volatile
        var trusted = false
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * 首连是否直接信任（对应设置项，默认 false=先问）。
     *
     * 由 `MokeApplication` 在进程作用域镜像设置值：校验必须同步返回，不能在连接线程上读 DataStore，
     * 也不能只依赖某个 ViewModel 活着。
     */
    @Volatile
    var autoTrust: Boolean = false

    private val lock = ReentrantLock()
    private val slotFree = lock.newCondition()
    private var current: Pending? = null

    /** 弹一次确认并阻塞等待。返回 true=用户点了信任。 */
    fun ask(target: String, fingerprint: String, keyType: String): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var pending: Pending? = null
        lock.withLock {
            while (pending == null) {
                val active = current
                when {
                    // 同一台主机、同一把密钥 → 搭同一次弹窗的车，拿同一个答案。
                    active != null &&
                        active.request.target == target &&
                        active.request.fingerprint == fingerprint -> pending = active
                    active == null -> {
                        val fresh = Pending(Request(UUID.randomUUID().toString(), target, fingerprint, keyType))
                        current = fresh
                        _pending.value = fresh.request
                        pending = fresh
                    }
                    // 别的主机正在问：等它结束再排我的。
                    else -> {
                        val wait = deadline - System.currentTimeMillis()
                        if (wait <= 0 || !slotFree.await(wait, TimeUnit.MILLISECONDS)) return false
                    }
                }
            }
        }
        val waiting = pending ?: return false

        val left = deadline - System.currentTimeMillis()
        val answered = try {
            left > 0 && waiting.done.await(left, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        // 取不到答案（超时/UI 没了）一律当拒绝：宁可这次连不上，也不要静默信任一把没人看过的密钥。
        if (!answered) {
            finish(waiting, trusted = false)
            return false
        }
        return waiting.trusted
    }

    /** UI 回答。[id] 与当前请求不一致时忽略（过期弹窗）。 */
    fun resolve(id: String, trusted: Boolean) {
        val pending = lock.withLock { current?.takeIf { it.request.id == id } } ?: return
        finish(pending, trusted)
    }

    /** 弹窗被取消（返回键 / 页面销毁）等同于拒绝。 */
    fun dismiss(id: String) = resolve(id, false)

    private fun finish(pending: Pending, trusted: Boolean) {
        lock.withLock {
            if (current === pending) {
                current = null
                _pending.compareAndSet(pending.request, null)
                slotFree.signalAll()
            }
        }
        pending.trusted = trusted
        pending.done.countDown()
    }

    private const val TIMEOUT_MS = 120_000L
}
