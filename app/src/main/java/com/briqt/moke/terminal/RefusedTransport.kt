package com.briqt.moke.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport

/**
 * 不能连的会话（跳板机或网络已删除、列表解不开…）：一启动就按「连接失败」报出原因，
 * 绝不退回直连。界面沿用连接失败的出路（编辑主机等）。
 */
class RefusedTransport(private val reason: String) : TerminalTransport {
    override fun start(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        session.onTransportConnectFailed(reason)
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {}
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {}
    override fun close() {}
}
