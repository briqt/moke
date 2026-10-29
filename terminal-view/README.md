# terminal-view (vendored)

复制自 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-view` 模块，
许可 **Apache License 2.0**（源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。

## moke 的修改

除构建脚本改为Kotlin DSL外，本地修改如下：

- `TerminalRenderer`：保留行距和字间距接口；背景、光标与前景分层绘制，普通字形保持比例。
- `CellGlyph` / `TerminalGraphics`：按单元格绘制块元素、线画和常用分隔符。
- `GlyphLayout`：处理字形落位、右侧兼容空格借位及新增行距的上下分配。
- `TerminalView`：
  - 新增 `setFontSpacing(lineSpacingMul, letterSpacingEm)`，重建渲染器并重算行列；
  - 全屏程序内的滑动处理：按应用层选择的模式决定滚本地历史、发滚轮事件或发方向键；滚屏位置变化与"无处可滚"回调；`mokeScrollToBottom()` 跳回最新输出。
- 新增 `MokeScroll.java`：上述滑动决策的纯函数实现（无 Android 依赖，由单元测试覆盖）。
- `TextSelectionCursorController`：去掉文本选择工具条中的 "More…" 项（本项目未注册上下文菜单，该项无作用）。

其余定制应在 `app/` 或通过 `TerminalTransport` 完成，以便跟进上游。详见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
