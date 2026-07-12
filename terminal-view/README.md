# terminal-view (vendored, 极小改动)

复制自 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-view` 模块，
许可 **Apache License 2.0**（源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。

## moke 的修改

除 `build.gradle` → `build.gradle.kts` 外，仅为支持行距/字间距做了向后兼容的加法式改动：

- `TerminalRenderer`：新增可选构造参数——行距倍数（缩放 `mFontLineSpacing`）与字间距（em，作用于 `mTextPaint`）；默认 `1.0 / 0` 等价上游。
- `TerminalView`：新增 `setFontSpacing(lineSpacingMul, letterSpacingEm)`，重建渲染器并重算行列。

其余定制仍应在 `app/` 或通过 `TerminalTransport` 完成，以便跟进上游。详见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
