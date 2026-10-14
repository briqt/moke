package com.briqt.moke.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * 主机密钥变更后的清除确认。
 *
 * 连接线程发现指纹不一致时只拒绝这次连接，不等待弹窗答案。清除与否不影响本次握手。
 * 同一 `host:port` 同时有多条连接（mosh 引导与控制连接）时只保留第一张弹窗。
 */
object HostKeyChangePrompt {

    data class Request(
        val id: String,
        /** [KnownHosts.idOf]，即 `host:port`。 */
        val target: String,
        val saved: String,
        val current: String,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun offer(target: String, saved: String, current: String) {
        _pending.update { cur ->
            if (cur?.target == target) cur
            else Request(UUID.randomUUID().toString(), target, saved, current)
        }
    }

    fun dismiss() {
        _pending.value = null
    }
}
