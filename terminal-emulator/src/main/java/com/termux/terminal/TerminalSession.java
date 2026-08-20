package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 传输无关的终端会话：把 {@link TerminalEmulator}（解析/渲染状态）与一个 {@link TerminalTransport}
 * （SSH / mosh / 本地 demo）粘合起来。
 *
 * <p><b>来源与修改</b>：改写自 termux/termux-app 的 {@code TerminalSession}（Apache-2.0）。
 * 原类通过 JNI {@code forkpty} 绑定本地 PTY；moke 移除了本地 PTY / JNI 逻辑，改为面向
 * {@link TerminalTransport} 的网络会话，同时<b>保留对 {@link com.termux.view.TerminalView} 暴露的
 * 公有 API 不变</b>（{@code getEmulator/updateSize/write/writeCodePoint/reset/onCopy|PasteTextFromClipboard}），
 * 因此 {@code terminal-view} 模块无需改动即可复用。
 *
 * <p>远端输出经 {@link ByteQueue} + 主线程 {@link Handler} 交给 emulator（沿用 termux 的线程模型：
 * 所有 emulator 操作都在主线程进行）。
 */
public class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_TRANSPORT_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** 远端输出队列：传输读线程写入，主线程读出后喂给 {@link TerminalEmulator#append}. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);

    /** 用于把 code point 编码为 UTF-8 再发送。 */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** 会话回调（标题/文本变化/结束/日志等）。 */
    TerminalSessionClient mClient;

    /** 应用为用户标识设置的会话名（连接名），非终端标题。 */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final TerminalTransport mTransport;
    private final Integer mTranscriptRows;

    /** 传输是否已结束。 */
    private volatile boolean mFinished = false;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(TerminalTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** 首次调用时初始化 emulator 并启动传输；之后调用则 resize 并通知远端。 */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            if (!mFinished) {
                try {
                    mTransport.updateSize(columns, rows, cellWidthPixels, cellHeightPixels);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "transport updateSize error", e);
                }
            }
        }
    }

    /** 终端标题（通过转义序列设置），未设置则为 null。 */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** 创建 emulator 并启动底层传输。 */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        try {
            mTransport.start(this, columns, rows, cellWidthPixels, cellHeightPixels);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "transport start failed", e);
            String msg = "\r\n[连接失败: " + e.getMessage() + "]\r\n";
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            processToEmulator(b, b.length);
            onTransportFinished(1);
        }
    }

    /** 传输层收到远端字节后调用（任意线程）：入队并通知主线程喂给 emulator。 */
    public void processToEmulator(byte[] buffer, int length) {
        if (length <= 0) return;
        if (mProcessToTerminalIOQueue.write(buffer, 0, length)) {
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        }
    }

    /** 传输层在远端关闭/进程退出时调用（任意线程）。exitCode>0 视为退出码，<0 视为信号。 */
    public void onTransportFinished(int exitCode) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_TRANSPORT_FINISHED, exitCode));
    }

    /** 用户输入字节 -> 传输层（发往远端）。 */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mFinished) return;
        try {
            mTransport.write(data, offset, count);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "transport write error", e);
        }
    }

    /** 将 Unicode code point 以 UTF-8 编码写入终端（沿用 termux 实现）。 */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) 是最大 code point，[0xD800,0xDFFF] 是代理区。
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* 已确保 codePoint <= 1114111，最多 21 bits */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** 通知客户端屏幕已更新。 */
    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** 重置终端状态。 */
    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    /** 结束会话：关闭底层传输（幂等）。真正的 finished 状态由 transport 回调 onTransportFinished 置位。 */
    public void finishIfRunning() {
        if (!mFinished) {
            try {
                mTransport.close();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(mClient, LOG_TAG, "transport close error", e);
            }
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return !mFinished;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0 && mEmulator != null) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_TRANSPORT_FINISHED) {
                if (mFinished) return;
                mFinished = true;

                // 刷出队列里剩余的远端字节
                int extra;
                while ((extra = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0 && mEmulator != null) {
                    mEmulator.append(mReceiveBuffer, extra);
                }

                int exitCode = (msg.obj instanceof Integer) ? (Integer) msg.obj : 0;
                String desc = "\r\n[会话结束";
                if (exitCode > 0) {
                    desc += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    desc += " (signal " + (-exitCode) + ")";
                }
                desc += "]\r\n";
                byte[] b = desc.getBytes(StandardCharsets.UTF_8);
                if (mEmulator != null) {
                    mEmulator.append(b, b.length);
                    notifyScreenUpdate();
                }

                mProcessToTerminalIOQueue.close();
                if (mClient != null) mClient.onSessionFinished(TerminalSession.this);
            }
        }
    }
}
