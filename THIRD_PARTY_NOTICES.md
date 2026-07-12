# 第三方组件与许可

moke 依赖或包含以下第三方组件。感谢这些项目的作者与维护者。

## Vendored（源码内置）

### terminal-emulator / terminal-view
- 来源：[termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator`、`terminal-view` 模块
- 上游来源：[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)（Jack Palevich 等）
- 许可：**Apache License 2.0**
- 修改说明：
  - `terminal-view`：**极小改动**——`TerminalRenderer` 增加可选行距倍数 / 字间距（em）参数，`TerminalView` 增加 `setFontSpacing(...)`；均向后兼容，默认值等价上游。
  - `terminal-emulator`：删除 `JNI.java` 与 `src/main/jni/`（本地 PTY 的 C 代码）；
    将 `TerminalSession.java` 改写为传输无关（移除 `forkpty`/JNI，改为面向 `TerminalTransport`），
    并新增 `TerminalTransport.java`。其余文件未修改。

### 字体（打包进 APK）
- [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono)（`res/font/jetbrains_mono.ttf`）——**OFL**，默认等宽主字体，未修改。
- [Noto Sans SC / 思源黑体](https://github.com/notofonts/noto-cjk)（`res/font/noto_sans_sc.otf`）——**OFL**，默认中文回退。为控制体积，**子集化**为常用汉字（GB2312 + 常用标点，约 2.9MB），非完整字符集。
- [Noto Sans Symbols 2](https://github.com/notofonts/symbols)（`res/font/noto_sans_symbols2.ttf`）——**OFL**，作**符号回退字体**（合成链末级），补主/回退字体常缺的媒体 / 几何 / 杂项符号等终端字形，未修改。

### 可下载字体（运行期按需下载，不打包进 APK）
用户在"设置 · 字体"中可选择下载。均从各项目官方 GitHub Releases 获取，存于 app 私有目录：

| 字体 | 许可 | 来源 |
|---|---|---|
| [Fira Code](https://github.com/tonsky/FiraCode) | OFL | 连字编程等宽（Regular） |
| [Maple Mono NF CN](https://github.com/subframe7536/maple-font) | OFL | 高分屏 unhinted 变体（NormalNL-NF-CN），含中文 2:1 + Nerd 图标 |
| [Hack](https://github.com/source-foundry/Hack) | MIT-derived (Hack Open Font License) | 经典编程等宽，无连字 |
| [IBM Plex Mono](https://github.com/IBM/plex) | OFL | 等宽（Regular，来自 google/fonts） |
| [Source Code Pro](https://github.com/adobe-fonts/source-code-pro) | OFL | 等宽（可变字重，来自 google/fonts） |
| [Roboto Mono](https://github.com/googlefonts/RobotoMono) | Apache-2.0 | 等宽（可变字重，来自 google/fonts） |
| [Ubuntu Mono](https://design.ubuntu.com/font) | UFL | 等宽（Regular，来自 google/fonts） |
| [Inconsolata](https://github.com/googlefonts/Inconsolata) | OFL | 等宽（可变字重，来自 google/fonts） |
| [Space Mono](https://github.com/googlefonts/spacemono) | OFL | 等宽（Regular，来自 google/fonts） |
| [Anonymous Pro](https://www.marksimonson.com/fonts/view/anonymous-pro) | OFL | 等宽（Regular，来自 google/fonts） |
| [Cascadia Code](https://github.com/microsoft/cascadia-code) | OFL | 连字等宽（可变字重，来自 google/fonts） |
| [JetBrainsMono Nerd Font](https://github.com/ryanoasis/nerd-fonts) | OFL（图标集见 nerd-fonts） | JetBrains Mono + Nerd 图标（Regular） |
| [Victor Mono](https://github.com/rubjo/victor-mono) | OFL | 连字 + 草书斜体（可变字重，来自 google/fonts） |
| [DejaVu Sans Mono](https://github.com/dejavu-fonts/dejavu-fonts) | Bitstream Vera License | 字形覆盖广的等宽（Regular，官方 Release） |

用户在"设置 · 字体 · 上传本地字体"导入的 TTF / OTF 文件仅存于设备本地、不随 APK 分发，其许可由用户自行负责。

## 运行期依赖（构建时拉取，未内置源码）

| 组件 | 许可 | 用途 |
|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | Apache-2.0 | SSH 传输 |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`, `bcpkix-jdk18on`) | MIT-style (Bouncy Castle License) | 加密算法（sshj 依赖） |
| [EdDSA-Java](https://github.com/str4d/ed25519-java) (`net.i2p.crypto:eddsa`) | CC0-1.0 | ed25519 密钥（sshj 依赖） |
| AndroidX / Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Kotlin 标准库与协程 | Apache-2.0 | 语言运行时 |

## mosh native

mosh 集成：`libmosh-client.so`（mosh 1.4.0 前端 + [rjyo/mosh-android](https://github.com/rjyo/mosh-android) 预编译静态库）
作为**独立可执行二进制**（GPLv3），以独立子进程 + PTY/管道 IPC 运行，**不与产品层链接**。
二进制不入库，由 `scripts/build-mosh-native.sh` 从公开源码复现。分发含该二进制的 APK 须随附对应 GPLv3 源码；商业分发前请法务确认。
