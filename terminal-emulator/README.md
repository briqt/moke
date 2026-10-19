# terminal-emulator

本模块来自 [termux/termux-app](https://github.com/termux/termux-app)，源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，采用 **Apache License 2.0**。许可全文见 [LICENSE-APACHE-2.0.txt](../LICENSE-APACHE-2.0.txt)。

## 本项目的修改

- `TerminalSession.java` 与新增的 `TerminalTransport.java`：会话改为传输无关，不再自行启动本地 shell；远端输出经 `ByteQueue` 与主线程 `Handler` 进入模拟器。保留视图所需的会话 API，分别上报连接失败与会话结束，状态文案由应用层提供。
- `JNI.java` 与 `src/main/jni/termux.c`：公开 PTY JNI，供应用以独立子进程运行 mosh；`waitFor` 在 `waitpid` 被信号打断时重试，其他等待错误向 Java 抛出异常。
- `TerminalEmulator.java`：识别 DECSET 1003 鼠标跟踪状态，提供 `isBracketedPasteMode()` 查询，并解析 OSC 9 / OSC 777 通知。
- `TerminalOutput.java`、`TerminalSessionClient.java` 与 `TerminalSession.java`：传递终端通知回调，由应用层决定是否提醒用户。

应用层的 SSH / mosh 实现位于 `app/`。第三方组件说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
