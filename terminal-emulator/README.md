# terminal-emulator (vendored, 有修改)

复制自 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator` 模块，
许可 **Apache License 2.0**（源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。

## moke 的修改

为把本地 PTY 终端改造为"网络传输无关"的会话：

- **删除** `JNI.java` 与 `src/main/jni/`（`forkpty` 的 C 代码）——v0.1 无需 NDK。
- **改写** `TerminalSession.java`：移除 JNI/PTY，保留 `TerminalView` 依赖的公有 API；
  远端字节沿用 `ByteQueue` + 主线程 `Handler` 喂给 `TerminalEmulator`。
- **新增** `TerminalTransport.java`：传输抽象（SSH/mosh/local 实现位于 `app/`）。

其余文件（`TerminalEmulator`、`TerminalBuffer`、`KeyHandler`、`WcWidth` 等）与上游一致。

详见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
