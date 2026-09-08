package com.briqt.moke.terminal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 首连指纹确认的等待/回答契约。
 *
 * 校验在连接线程上必须**同步**给出 true/false，答案却只能来自 UI，所以这套握手是安全边界的一部分：
 * 答错一次（比如过期弹窗的答案落到新请求上、或者没人回答时默认放行）都等于静默信任一把陌生密钥。
 */
class HostKeyPromptTest {

    @After
    fun tearDown() {
        HostKeyPrompt.pending.value?.let { HostKeyPrompt.dismiss(it.id) }
        HostKeyPrompt.autoTrust = false
    }

    /** 在后台线程发起一次 ask，返回取答案用的队列。 */
    private fun askAsync(target: String = "example.com:22"): ArrayBlockingQueue<Boolean> {
        val result = ArrayBlockingQueue<Boolean>(1)
        Thread { result.put(HostKeyPrompt.ask(target, "SHA256:abc", "ssh-ed25519")) }.start()
        return result
    }

    private fun awaitPending(): HostKeyPrompt.Request {
        for (i in 0 until 200) {
            HostKeyPrompt.pending.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no pending request")
    }

    @Test
    fun `信任的回答让校验放行，并清掉待确认状态`() {
        val result = askAsync()
        val req = awaitPending()
        assertEquals("example.com:22", req.target)
        assertEquals("SHA256:abc", req.fingerprint)

        HostKeyPrompt.resolve(req.id, true)

        assertEquals(true, result.poll(2, TimeUnit.SECONDS))
        assertNull(HostKeyPrompt.pending.value)
    }

    @Test
    fun `取消等于拒绝`() {
        val result = askAsync()
        val req = awaitPending()
        HostKeyPrompt.dismiss(req.id)
        assertEquals(false, result.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun `过期弹窗的回答不会落到当前请求上`() {
        val result = askAsync()
        val req = awaitPending()

        // 上一次请求（或任何伪造）的 id：必须被忽略，否则一次陈旧的"信任"就能替新主机放行。
        HostKeyPrompt.resolve(req.id + "-stale", true)
        assertNull(result.poll(300, TimeUnit.MILLISECONDS))
        assertNotNull(HostKeyPrompt.pending.value)

        HostKeyPrompt.resolve(req.id, true)
        assertEquals(true, result.poll(2, TimeUnit.SECONDS))
    }

    /**
     * mosh 主机一次连接会同时开引导连接与 tmux 控制连接，两条都撞上未知密钥：必须共用一次弹窗和
     * 同一个答案。各弹一次的话，用户要为同一台主机点两次，而排在后面那条还会因为等待超过 sshj
     * 握手超时而失败——实测就是这么把 mosh 会话弄挂的。
     */
    @Test
    fun `同一台主机的并发首连共用一次弹窗和同一个答案`() {
        val first = askAsync("same.example:22")
        val req = awaitPending()
        val second = askAsync("same.example:22")
        Thread.sleep(200)
        // 还是那一个请求，没有第二个弹窗。
        assertEquals(req.id, HostKeyPrompt.pending.value?.id)

        HostKeyPrompt.resolve(req.id, true)

        assertEquals(true, first.poll(2, TimeUnit.SECONDS))
        assertEquals(true, second.poll(2, TimeUnit.SECONDS))
        assertNull(HostKeyPrompt.pending.value)
    }

    @Test
    fun `不同主机的首连排队，各自拿到自己的答案`() {
        val first = askAsync("a.example:22")
        val firstReq = awaitPending()
        // 第二条连接在锁上等着，此时不该抢占弹窗。
        val second = askAsync("b.example:22")
        Thread.sleep(200)
        assertEquals(firstReq.id, HostKeyPrompt.pending.value?.id)

        HostKeyPrompt.resolve(firstReq.id, false)
        assertEquals(false, first.poll(2, TimeUnit.SECONDS))

        val secondReq = awaitPending()
        assertTrue(secondReq.id != firstReq.id)
        assertEquals("b.example:22", secondReq.target)
        HostKeyPrompt.resolve(secondReq.id, true)
        assertEquals(true, second.poll(2, TimeUnit.SECONDS))
    }
}
