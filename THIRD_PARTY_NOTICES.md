# 第三方组件与许可

以下组件保留各自的版权与许可。本文是组件说明，不替代各项目的完整许可文本。

## 内置终端源码

`terminal-emulator/` 与 `terminal-view/` 来自 [termux/termux-app](https://github.com/termux/termux-app)，源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)（Jack Palevich 等），采用 **Apache License 2.0**，全文见 [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)。

本项目修改了会话传输、PTY JNI、鼠标与粘贴模式查询、终端通知、排版与滑动处理。具体修改见 [terminal-emulator/README.md](terminal-emulator/README.md) 与 [terminal-view/README.md](terminal-view/README.md)。

## 随 APK 分发的字体

字体位于 `app/src/main/res/font/`。

| 字体 | 文件 | 许可与修改 |
|---|---|---|
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | `jetbrains_mono.ttf` | SIL OFL 1.1，未修改 |
| [Noto Sans SC / 思源黑体](https://github.com/notofonts/noto-cjk) | `noto_sans_sc.otf` | SIL OFL 1.1；子集化为 GB2312 汉字与常用标点，非完整字符集 |
| [Noto Sans Symbols 2](https://github.com/notofonts/symbols) | `noto_sans_symbols2.ttf` | SIL OFL 1.1，未修改；符号回退 |
| [Symbols Nerd Font Mono](https://github.com/ryanoasis/nerd-fonts) | `nerd_symbols_design.ttf` | nerd-fonts 项目为 MIT，所含图标集另有各自许可；取官方 v3.4.0 `SymbolsNerdFontMono-Regular`，**修改**：从 cmap 摘除 U+E0A0–U+E0D7（Powerline Symbols / Powerline Extra），改由下面的格子度量字体提供，以免 1em 设计尺寸的分隔符在 1.32em 行高下露出行缝。字形轮廓未改动 |
| [JetBrainsMono Nerd Font Mono](https://github.com/ryanoasis/nerd-fonts) | `nerd_symbols_mono.ttf` | 同上项目；取官方 v3.4.0 `JetBrainsMonoNerdFontMono-Regular`，未修改。其每个字形 advance = 0.6em，与默认主字体同度量，负责 powerline / box drawing / 块元素这类必须与格子严丝合缝的线画 |
| [Maple Mono NF CN](https://github.com/subframe7536/maple-font) | `maple_mono.ttf` | SIL OFL 1.1；官方 v7.9 NormalNL-NF-CN unhinted Regular，未修改；发布包内置，Debug 未准备时可下载 |

含 Nerd 图标的字体还须遵守上游列明的图标集许可，见 [nerd-fonts LICENSE](https://github.com/ryanoasis/nerd-fonts/blob/master/LICENSE)。

## 按需下载的字体

这些字体不随 APK 分发，用户可在字体设置中下载，文件存于应用私有目录。下载来源包括项目官方仓库、GitHub Releases 和 `google/fonts`；具体文件与校验值见 [FontCatalog.kt](app/src/main/java/com/briqt/moke/terminal/FontCatalog.kt)。

| 字体 | 许可 | 获取来源 |
|---|---|---|
| [Fira Code](https://github.com/tonsky/FiraCode) | SIL OFL 1.1 | 官方 Release |
| [Hack](https://github.com/source-foundry/Hack) | MIT-derived（Hack Open Font License） | 官方 Release |
| [IBM Plex Mono](https://github.com/IBM/plex) | SIL OFL 1.1 | google/fonts |
| [Source Code Pro](https://github.com/adobe-fonts/source-code-pro) | SIL OFL 1.1 | google/fonts |
| [Roboto Mono](https://github.com/googlefonts/RobotoMono) | [SIL OFL 1.1](https://github.com/google/fonts/blob/main/ofl/robotomono/OFL.txt) | google/fonts |
| [Ubuntu Mono](https://design.ubuntu.com/font) | Ubuntu Font Licence（UFL） | google/fonts |
| [Inconsolata](https://github.com/googlefonts/Inconsolata) | SIL OFL 1.1 | google/fonts |
| [Space Mono](https://github.com/googlefonts/spacemono) | SIL OFL 1.1 | google/fonts |
| [Anonymous Pro](https://www.marksimonson.com/fonts/view/anonymous-pro) | SIL OFL 1.1 | google/fonts |
| [Cascadia Code](https://github.com/microsoft/cascadia-code) | SIL OFL 1.1 | google/fonts |
| [JetBrainsMono Nerd Font](https://github.com/ryanoasis/nerd-fonts) | SIL OFL 1.1；图标集许可另见上游 | nerd-fonts 官方仓库 |
| [Victor Mono](https://github.com/rubjo/victor-mono) | SIL OFL 1.1 | google/fonts |
| [DejaVu Sans Mono](https://github.com/dejavu-fonts/dejavu-fonts) | Bitstream Vera License | 官方 Release |

用户导入的 TTF / OTF 仅存于设备本地，不随 APK 分发；其许可由用户自行负责。

## 应用依赖

| 组件 | 许可 | 用途 |
|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | Apache-2.0 | SSH 传输 |
| [Bouncy Castle](https://www.bouncycastle.org/)（`bcprov-jdk18on`、`bcpkix-jdk18on`） | MIT-style（Bouncy Castle License） | 加密算法 |
| [SLF4J](https://www.slf4j.org/)（`slf4j-api`） | MIT | 日志接口 |
| [Tailscale](https://github.com/tailscale/tailscale)（`tailscale.com`） | BSD-3-Clause | 用户态 tailnet 节点 |
| [ProtonMail/go-crypto](https://github.com/ProtonMail/go-crypto) | BSD-3-Clause | mosh 中继 AES-OCB 认证 |
| Go 运行时与 `golang.org/x/*` | BSD-3-Clause | 网络桥接 |
| AndroidX / Jetpack Compose / Material3（含 AndroidX Browser） | Apache-2.0 | Android 界面与浏览器登录 |
| Kotlin 标准库与协程 | Apache-2.0 | 语言运行时 |

Android 依赖版本见 `gradle/libs.versions.toml`；Go 依赖的选定版本见 `netbridge/go.mod`，校验值见 `netbridge/go.sum`。其他 Go 模块保留各自许可，例如 `github.com/tailscale/wireguard-go` 为 MIT、`gvisor.dev/gvisor` 为 Apache-2.0。

## mosh native 与分发义务

`libmosh-client.so` 来自 [mobile-shell/mosh](https://github.com/mobile-shell/mosh) 1.4.0 前端与 [rjyo/mosh-android](https://github.com/rjyo/mosh-android) 预编译静态库，采用 **GPLv3**。它是通过 PTY 运行的独立可执行文件，不与应用产品层链接。构建入口为 `scripts/build-mosh-native.sh`，二进制不入库。

分发含 GPL 组件的 APK 或其他二进制时，须按适用 GPL 条款提供与所分发产物对应的完整源码，包括修改、必要构建脚本及 GPL 要求涵盖的链接依赖源码，并保留版权和许可通知。引用上游仓库或提供预编译库下载地址，不能代替对应源码义务；链接依赖仍须遵守各自许可。产品层的许可另见 [LICENSE](LICENSE) 与 [COPYRIGHT.md](COPYRIGHT.md)。
