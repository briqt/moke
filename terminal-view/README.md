# terminal-view (vendored, 小幅改动)

复制自 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-view` 模块，
许可 **Apache License 2.0**（源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。

## moke 的修改

除 `build.gradle` → `build.gradle.kts` 外，均为向后兼容的加法式改动：

- `TerminalRenderer`：新增可选构造参数——行距倍数（缩放 `mFontLineSpacing`）与字间距（em，作用于 `mTextPaint`）；默认 `1.0 / 0` 等价上游。
- `TerminalView`：
  - 新增 `setFontSpacing(lineSpacingMul, letterSpacingEm)`，重建渲染器并重算行列；
  - 全屏程序内的滑动处理：按应用层选择的模式决定滚本地历史、发滚轮事件或发方向键；滚屏位置变化与"无处可滚"回调；`mokeScrollToBottom()` 跳回最新输出。
- 新增 `MokeScroll.java`：上述滑动决策的纯函数实现（无 Android 依赖，由单元测试覆盖）。
- `TextSelectionCursorController`：去掉文本选择工具条中的 "More…" 项（本项目未注册上下文菜单，该项无作用）。

其余定制应在 `app/` 或通过 `TerminalTransport` 完成，以便跟进上游。详见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
