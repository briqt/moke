# terminal-emulator (vendored, 有修改)

复制自 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator` 模块，
许可 **Apache License 2.0**（源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。

## moke 的修改

为把本地 PTY 终端改造为"网络传输无关"的会话：

- **改写** `TerminalSession.java`：不再自己 fork 本地 shell，改为面向 `TerminalTransport`；保留 `TerminalView` 依赖的公有 API；
  远端字节沿用 `ByteQueue` + 主线程 `Handler` 喂给 `TerminalEmulator`；会话结束 / 连接失败等状态文案由应用层提供（跟随应用内语言）。
- **新增** `TerminalTransport.java`：传输抽象（SSH / mosh 实现位于 `app/`）。
- **保留** `JNI.java` 与 `src/main/jni/termux.c`（PTY 子进程），`JNI` 改为 public：`app/` 用它把随包的 `mosh-client` 作为独立子进程运行在 PTY 上。
- `TerminalEmulator.java`：两处加法式改动——把 DECSET 1003（any-event 鼠标跟踪）视为鼠标跟踪已开启；新增 `isBracketedPasteMode()` 查询。

其余文件与上游一致。详见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
