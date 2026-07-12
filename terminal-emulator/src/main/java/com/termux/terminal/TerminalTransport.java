package com.termux.terminal;

/**
 * 传输层抽象：把某种字节流通道（SSH shell / mosh / 本地 demo）接到一个 {@link TerminalSession}。
 *
 * <p>这是 moke 项目总纲中 {@code ITerminalSession} 抽象的落地：{@link com.termux.view.TerminalView}
 * 只认 {@link TerminalSession}（渲染绑定），底层是 PTY、SSH 还是 UDP(mosh) 由本接口的实现决定。
 * 具体实现（SshTransport / MoshTransport / LocalDemoTransport）位于 app 层。
 *
 * <p>线程约定：
 * <ul>
 *   <li>实现自行管理读线程：从远端读到的字节调用 {@link TerminalSession#processToEmulator(byte[], int)}。</li>
 *   <li>远端关闭/进程退出时调用 {@link TerminalSession#onTransportFinished(int)}。</li>
 *   <li>{@link #write} 可能在 UI 线程被调用，实现需避免阻塞（内部缓冲/独立写线程）。</li>
 * </ul>
 */
public interface TerminalTransport {

    /**
     * 会话就绪、emulator 已按初始尺寸创建后调用一次。实现应在此建立连接并启动 I/O。
     *
     * @param session           回调目标：远端输出 -> {@link TerminalSession#processToEmulator},
     *                          结束 -> {@link TerminalSession#onTransportFinished}
     * @param columns           初始列数
     * @param rows              初始行数
     * @param cellWidthPixels   单元格像素宽
     * @param cellHeightPixels  单元格像素高
     */
    void start(TerminalSession session, int columns, int rows, int cellWidthPixels, int cellHeightPixels) throws Exception;

    /** 用户输入字节 -> 远端。 */
    void write(byte[] data, int offset, int count);

    /** 终端尺寸变化 -> 通知远端（SSH window-change / mosh resize）。 */
    void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** 关闭并释放资源。可重复调用。 */
    void close();
}
