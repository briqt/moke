package com.termux.terminal;

/**
 * Native methods for creating and managing pseudoterminal subprocesses. C code is in jni/termux.c
 * （Apache-2.0，源自 termux）。moke 用它为 mosh-client 提供 PTY 子进程（见 app 层 MoshTransport）。
 *
 * 由 moke 从原 termux 恢复并置为 public（原为包私有）。native 库 libtermux.so 以预编译形式随 app 打包。
 */
public final class JNI {

    static {
        System.loadLibrary("termux");
    }

    /**
     * 创建一个用 PTY 通信的子进程。
     *
     * @param cmd       要执行的程序（可执行文件绝对路径）
     * @param cwd       工作目录
     * @param args      参数数组（含 argv[0]）
     * @param envVars   形如 "VAR=value" 的环境变量数组
     * @param processId 单元素数组，写回子进程 pid
     * @return 打开 /dev/ptmx 主设备得到的 fd；子进程的 stdin/stdout/stderr 接到从设备。
     */
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns, int cellWidth, int cellHeight);

    /** 设置 pty 窗口尺寸，让子程序感知屏幕大小。 */
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);

    /** 阻塞等待子进程结束；>=0 为退出码，<0 为导致停止的信号取负。 */
    public static native int waitFor(int processId);

    /** close(2) 关闭文件描述符。 */
    public static native void close(int fileDescriptor);

}
