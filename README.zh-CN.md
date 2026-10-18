<div align="center">

# Moke · 墨客

**Android 原生 SSH / mosh 终端**（Kotlin + Jetpack Compose）

[![CI](https://github.com/briqt/moke/actions/workflows/ci.yml/badge.svg)](https://github.com/briqt/moke/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/briqt/moke?sort=semver)](https://github.com/briqt/moke/releases)
[![minSdk](https://img.shields.io/badge/minSdk-30-blue)](#)

</div>

<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

## 这是什么

Moke 是一个 Android 原生 SSH / mosh 终端。它在 app 内直接连接远程服务器——跑 shell、tmux、vim，或 Claude Code 这类全屏 TUI 工具；终端渲染复用 termux 的 `terminal-view` / `terminal-emulator`（Apache-2.0），不自研 ANSI 解析。

## 功能

- **SSH**：密码 / 私钥（PEM）认证；主机密钥 TOFU 校验，首次连接先确认指纹（与 `ssh-keygen -lf` 一致）；跳板机（ProxyJump）；按主机指定启动命令（如在 Windows 主机上选择 cmd、PowerShell 或 WSL）；登录后自动执行命令；保活心跳。
- **端口转发**：把远端端口转到手机本机（只监听本机），用浏览器访问远端的 dev server 等服务；SSH 与 mosh 会话都支持；可按主机设置连接后自动转发。
- **网络**：经 HTTP / HTTPS / SOCKS4 / SOCKS5 代理连接主机；内置用户态 Tailscale，可登录官方或自建控制服务器，同时接入多个 tailnet，不需要另装 Tailscale 应用，也不占用系统 VPN。可按 tailnet 地址或设备名连接主机，mosh 会话同样可用。每台主机可单独选择网络；代理凭据与 tailnet 登录状态经 Android Keystore 加密保存。
- **mosh**：随包 native `mosh-client`，独立子进程 + PTY 运行；UDP 漫游（关屏 / 切网不断线）；关闭会话时一并结束远端 `mosh-server`。
- **tmux**：面板内附加 / 新建 / 重命名 / 离开 / 关闭远端会话；可设置连接即自动进入；可让 tmux 接管滑动滚屏。
- **文件**：经 SFTP 浏览远端目录（SSH 与 mosh 主机均可）；上传 / 下载支持断点续传；传输在后台继续；可把文件路径发送到终端。
- **会话与连接**：多会话常驻，前台服务在后台保持连接；分组 / 排序（含手动拖动）；创建副本；自定义会话标题；一键清理已结束会话；复制连接命令。
- **终端**：双排附加键（可更换）+ 可展开的全键盘（编辑键、F1–F12、Ctrl 组合键），修饰键按住锁定；文本段输入；点按链接打开或复制，本机链接一步转发并打开；响铃与通知提醒（OSC 9 / 777）；三种键盘模式（字符模式 / 标准 / 输入法优先），中文输入顺畅；复制粘贴，支持远端写入剪贴板（OSC 52）；捏合缩放；全屏 TUI 内滑动滚屏（模式可选）与「跳到底部」；顶部状态条（协议 / 主机 / 延迟）。
- **外观**：实时预览；浅色 / 深色 / 跟随系统，两种模式可分别指定终端配色；字号 / 行距 / 字间距、光标样式可调；字体管理（主字体 + 中文回退）。
- **安全、多语言与更新**：连接凭据、代理密码与 tailnet 登录状态经 Android Keystore（AES-GCM）加密后存储，且不进入系统备份；中英双语，默认跟随系统语言，设置内可切换；应用内检查更新（可选包含预览版）。

## 截图

<div align="center">
<img src="docs/screenshot-connections.png" alt="连接" width="160"/>&nbsp;<img src="docs/screenshot-terminal.png" alt="终端 SSH 跑 Claude Code" width="160"/>&nbsp;<img src="docs/screenshot-tmux.png" alt="tmux 面板" width="160"/>&nbsp;<img src="docs/screenshot-files.png" alt="远端文件" width="160"/>&nbsp;<img src="docs/screenshot-appearance.png" alt="外观（浅色主题）" width="160"/>
<br/><sub>连接 · 终端（SSH 跑 Claude Code）· tmux 面板 · 远端文件 · 外观（浅色主题）</sub>
</div>

## 安装

前往 [Releases](https://github.com/briqt/moke/releases) 下载 `moke-vX.Y.Z.apk`，允许"安装未知来源应用"后安装。发布包使用固定签名，可覆盖升级。

## 模块

| 模块 | 说明 | 许可 |
|---|---|---|
| `app` | 产品层（Compose UI / 会话编排 / 传输实现） | GPL-3.0-or-later |
| `terminal-emulator` | 终端解析 / 状态内核（vendored；`TerminalSession` 改为传输无关；保留 PTY JNI 供 mosh 子进程使用） | Apache-2.0 |
| `terminal-view` | 终端渲染 View（vendored；为行距 / 字间距与滑动滚屏做加法式改动） | Apache-2.0 |

## 构建

需要 JDK 17 及以上 + Android SDK（compileSdk 37 / build-tools 36）；构建网络桥接还需要 Go 1.27.2 与 Android NDK r30。在项目根创建 `local.properties` 指向 SDK：`sdk.dir=/path/to/Android/sdk`。

```bash
# 网络桥接：每次构建都需要（Go 1.27.2 + NDK r30）
ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-netbridge.sh

./gradlew assembleDebug        # 调试 APK
./gradlew testDebugUnitTest    # 单元测试

# release 还需要 mosh native 与内置字体
ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-mosh-native.sh
./scripts/fetch-maple-font.sh && ./gradlew assembleRelease
```

mosh native 产物由 [`scripts/build-mosh-native.sh`](scripts/build-mosh-native.sh) 从公开源码（mosh 1.4.0 + rjyo/mosh-android 预编译库）复现构建，需 NDK r30；GPLv3 二进制不入库。网络桥接由 [`scripts/build-netbridge.sh`](scripts/build-netbridge.sh) 构建，Go、gomobile 与 Tailscale 版本锁定在 `netbridge/go.mod`。

## 反馈

仅接受 [issue](https://github.com/briqt/moke/issues) 形式的问题反馈，暂不接受 PR（详见 [CONTRIBUTING](CONTRIBUTING.zh-CN.md)）。

交流社区：[学AI，linuxdo](https://linux.do/)

## 致谢与第三方

终端内核复用 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator` / `terminal-view`（Apache-2.0，源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。SSH 传输使用 [sshj](https://github.com/hierynomus/sshj)。mosh native 基于 [mobile-shell/mosh](https://github.com/mobile-shell/mosh)（GPLv3）与 [rjyo/mosh-android](https://github.com/rjyo/mosh-android)。网络层以 [Tailscale](https://tailscale.com/)（BSD-3-Clause）用户态库运行，mosh 中继使用 [ProtonMail/go-crypto](https://github.com/ProtonMail/go-crypto)。内置中文字体为 OFL 许可。完整清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

moke 产品层（`app/`）以 **GNU General Public License v3.0 或更高版本**发布，见 [LICENSE](LICENSE) 与 [COPYRIGHT.md](COPYRIGHT.md)。vendored 的 `terminal-*` 模块为 Apache-2.0（与 GPLv3 兼容）；mosh native 组件为 GPLv3，以独立可执行文件分发。
