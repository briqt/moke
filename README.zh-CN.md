<div align="center">

# Moke · 墨客

**Android 原生 SSH / mosh 终端**（Kotlin + Jetpack Compose）

[![CI](https://github.com/briqt/moke/actions/workflows/ci.yml/badge.svg)](https://github.com/briqt/moke/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/briqt/moke?sort=semver)](https://github.com/briqt/moke/releases)
[![minSdk](https://img.shields.io/badge/minSdk-24-blue)](#)

</div>

<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

## 这是什么

Moke 是一个 Android 原生 SSH / mosh 终端。它在 app 内直接连接远程服务器——跑 shell、tmux、vim，或 Claude Code 这类全屏 TUI 工具；终端渲染复用 termux 的 `terminal-view` / `terminal-emulator`（Apache-2.0），不自研 ANSI 解析。

## 功能

- **SSH**：密码 / 私钥（PEM）认证；主机密钥 TOFU 校验；跳板机（ProxyJump）；登录后自动执行命令；窗口 resize；保活心跳。
- **mosh**：随包 native `mosh-client`，独立子进程 + PTY 运行，UDP 漫游（关屏 / 切网重连）。
- **会话与连接**：多会话常驻、跨页切换，前台服务在后台保持连接；分组 / 排序（含手动拖动）；创建副本；自定义会话标题；协议徽标；复制连接命令。
- **终端**：双排附加键 + 文本段输入；复制粘贴；捏合缩放；顶部状态条（协议 / 主机 / 延迟）；tmux 面板（附加 / 新建 / 重命名 / 关闭远端会话）。
- **外观**：实时预览；多套暗色配色；字号 / 行距 / 字间距、光标样式可调；字体管理（主字体 + 中文回退——内置思源黑体子集，可下载 Fira Code / Maple Mono / Hack 等）。
- **安全与多语言**：连接凭据经 Android Keystore（AES-GCM）加密后存储；中英双语（i18n），默认跟随系统语言，设置内可切换。

## 截图

<div align="center">
<img src="docs/screenshot-connections.png" alt="连接管理" width="200"/>&nbsp;<img src="docs/screenshot-terminal.png" alt="终端 SSH 跑 Claude Code" width="200"/>&nbsp;<img src="docs/screenshot-appearance.png" alt="外观设置" width="200"/>&nbsp;<img src="docs/screenshot-sessions.png" alt="多会话管理" width="200"/>
<br/><sub>连接管理 · 终端（SSH 跑 Claude Code）· 外观设置 · 多会话</sub>
</div>

## 安装

前往 [Releases](https://github.com/briqt/moke/releases) 下载 `moke-vX.Y.Z.apk`，允许"安装未知来源应用"后安装。发布包使用固定签名，可覆盖升级。

## 模块

| 模块 | 说明 | 许可 |
|---|---|---|
| `app` | 产品层（Compose UI / 会话编排 / 传输实现） | 见 [LICENSE](LICENSE) |
| `terminal-emulator` | 终端解析 / 状态内核（vendored；`TerminalSession` 改为传输无关） | Apache-2.0 |
| `terminal-view` | 终端渲染 View（vendored；仅为行距 / 字间距做向后兼容小改动） | Apache-2.0 |

## 构建

需要 JDK 17 + Android SDK（compileSdk 35 / build-tools 35）。在项目根创建 `local.properties` 指向 SDK：`sdk.dir=/path/to/Android/sdk`。

```bash
./gradlew assembleDebug        # 调试 APK
./gradlew testDebugUnitTest    # 单元测试
```

mosh native 产物由 [`scripts/build-mosh-native.sh`](scripts/build-mosh-native.sh) 从公开源码（mosh 1.4.0 + rjyo/mosh-android 预编译库）复现构建，需 NDK r29；GPLv3 二进制不入库。

## 反馈

仅接受 [issue](https://github.com/briqt/moke/issues) 形式的问题反馈，暂不接受 PR（详见 [CONTRIBUTING](CONTRIBUTING.zh-CN.md)）。

## 致谢与第三方

终端内核复用 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator` / `terminal-view`（Apache-2.0，源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。SSH 传输使用 [sshj](https://github.com/hierynomus/sshj)。mosh native 基于 [mobile-shell/mosh](https://github.com/mobile-shell/mosh)（GPLv3）与 [rjyo/mosh-android](https://github.com/rjyo/mosh-android)。内置中文字体为 [Noto Sans SC / 思源黑体](https://github.com/notofonts/noto-cjk)（OFL）子集。完整清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

见 [LICENSE](LICENSE)。vendored 的 `terminal-*` 模块为 Apache-2.0；mosh native 组件为 GPLv3（独立可执行、边界隔离）。
